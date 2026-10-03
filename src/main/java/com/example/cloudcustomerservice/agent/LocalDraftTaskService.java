package com.example.cloudcustomerservice.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.cloudcustomerservice.aftersale.*;
import com.example.cloudcustomerservice.config.PayloadLoggingChatModel;
import com.example.cloudcustomerservice.handoff.HandoffModel.Mode;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;

/** 单进程任务注册表。稳定 Agent/Saver/threadId 保留历史；每轮权限、预算和事实重新取得。 */
@Service @Profile("local & knowledge")
public class LocalDraftTaskService {
    private final ChatModel model;
    private final ReturnAssessmentService assessments;
    private final boolean logPayload;
    private final ConcurrentMap<UUID,TaskRuntime> tasks=new ConcurrentHashMap<>();
    private final Semaphore capacity=new Semaphore(2);
    private final ExecutorService runs=pool("task-run"),workers=pool("task-model-or-lookup");
    private final Duration runTimeout,callTimeout;
    private final int maxTasks,maxTurns;
    @Autowired
    public LocalDraftTaskService(ChatModel model,ReturnAssessmentService assessments,@Value("${app.ai.log-payload:false}") boolean logPayload) {
        this(model,assessments,logPayload,100,8,Duration.ofSeconds(90),Duration.ofSeconds(30));
    }
    /** 固定生产预算，包内构造器只供小规模、短超时离线验证。 */
    LocalDraftTaskService(ChatModel model,ReturnAssessmentService assessments,boolean logPayload,int maxTasks,int maxTurns,Duration runTimeout,Duration callTimeout) {
        this.model=model;this.assessments=assessments;this.logPayload=logPayload;
        this.maxTasks=maxTasks;this.maxTurns=maxTurns;this.runTimeout=runTimeout;this.callTimeout=callTimeout;
    }
    /** synchronized 只覆盖短暂创建，原子检查 100 项上限；不在此锁内等待模型或远程订单查询。 */
    public synchronized TaskSummary create(Access access,UUID conversationId,String orderNo,ReturnReason reason) {
        String order=orderNo==null?"":orderNo.strip().toUpperCase(Locale.ROOT);
        if(conversationId==null||!order.matches("A\\d{5}"))throw new IllegalArgumentException("需要有效会话与订单号");
        requireBot(access,conversationId);requireNoGlobalTools();
        if(tasks.size()>=maxTasks)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"本地任务已达上限，请清理旧任务");
        var task=new TaskRuntime(access.actor(),conversationId,order,reason==null?ReturnReason.UNKNOWN:reason);
        tasks.put(task.id,task);return task.summary();
    }
    /** 只列出当前所有者的任务元数据；不返回候选或检查点，也不启动模型。 */
    public List<TaskSummary> list(Actor actor) {
        return tasks.values().stream().filter(t->t.owner.equals(actor)).map(TaskRuntime::summary)
                .sorted(Comparator.comparing(TaskSummary::createdAt).reversed()).toList();
    }
    /** 已转人工的任务不再显示候选；运行期间不能一边写图状态一边读检查点集合。 */
    public TaskView get(Access access,UUID id) {
        TaskRuntime task=owned(access.actor(),id);
        var receipt=access.reception().apply(task.conversationId);
        if(receipt.mode()!=Mode.BOT)return new TaskView(new TaskSummary(task.id,task.conversationId,task.order,task.reason,
                Status.CLOSED,task.turnNo,task.createdAt),null,StateSummary.empty());
        if(!task.gate.tryLock())return new TaskView(task.summary(),null,StateSummary.empty());
        try{return view(task);}finally{task.gate.unlock();}
    }
    /** 同任务 tryLock 串行。框架或模型失败后可能留有部分检查点，FAILED 永远不能普通续写。 */
    public TaskView continueTask(Access access,UUID id,String message) {
        if(message==null||message.isBlank()||message.length()>2000)throw new IllegalArgumentException("补充内容须为 1～2000 字符");
        TaskRuntime task=owned(access.actor(),id);
        if(!task.gate.tryLock())throw conflict("该任务正在处理上一轮请求");
        try {
            if(task.executing.get()||task.status==Status.FAILED||task.status==Status.CLOSED)throw conflict("该任务不能继续，请查看状态或新建任务");
            if(task.turnNo>=maxTurns)throw conflict("已达八轮上限，请清理或新建任务");
            var before=requireBot(access,task.conversationId);requireNoGlobalTools();
            if(!capacity.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已有两项任务运行，请稍后手动继续");
            task.runId=UUID.randomUUID().toString();task.turnNo++;task.status=Status.RUNNING;task.lastRun=null;
            var budget=new DraftRunBudget(runTimeout);task.budget=budget;
            task.tools.beginTurn(budget,work->bounded(budget,work::get));
            task.executing.set(true);
            Future<DraftRun> future;
            try {
                future=runs.submit(()->{
                    try{return execute(task,message,budget);}
                    finally{task.executing.set(false);capacity.release();}
                });
            }catch(RejectedExecutionException ex){task.executing.set(false);capacity.release();task.status=Status.FAILED;throw conflict("任务执行器已关闭");}
            DraftRun result;
            try{result=future.get(runTimeout.toMillis(),TimeUnit.MILLISECONDS);}
            catch(InterruptedException ex){Thread.currentThread().interrupt();result=failed(task,budget);}
            catch(ExecutionException|TimeoutException ex){result=failed(task,budget);}
            finally{budget.stop();}
            // 不取消尚未开始的工作 Future：确保 finally 最终归还容量。停止标记阻止后续模型和工具。
            task.status=switch(result.status()) {
                case CANDIDATE_UNVALIDATED->Status.CANDIDATE_UNVALIDATED;
                case RUN_FAILED->Status.FAILED;
                default->Status.NEEDS_ATTENTION;
            };
            try {
                var after=access.reception().apply(task.conversationId);
                if(after.mode()!=Mode.BOT||after.version()!=before.version()) {result=result.hidden();task.status=Status.CLOSED;}
            }catch(RuntimeException ex){result=result.hidden();task.status=Status.CLOSED;}
            task.lastRun=result;
            return view(task);
        }finally{task.gate.unlock();}
    }
    /** 清理本地实验内存，与删除客服聊天/资料无关。执行尚未退出时，即使已超时也拒绝清理。 */
    public void discard(Access access,UUID id) {
        TaskRuntime task=owned(access.actor(),id);access.reception().apply(task.conversationId);
        if(!task.gate.tryLock())throw conflict("任务仍在运行，不能清理");
        try {
            if(task.executing.get())throw conflict("本轮正在停止，请稍后清理");
            task.status=Status.CLOSED;task.lastRun=null;
            try{task.saver.release(task.config());}catch(Exception ex){throw conflict("暂时无法清理检查点");}
            tasks.remove(id,task);
        }finally{task.gate.unlock();}
    }
    /** 每次业务续写只进入图一次，图内可多次选择工具；最后依赖本轮 Java 核验标记放行候选。 */
    private DraftRun execute(TaskRuntime task,String message,DraftRunBudget budget) {
        try {
            budget.remainingMillis();
            var output=task.agent.call("服务器绑定订单："+task.order+"；初始用户原因："+task.reason+"。本轮用户补充：\n"+message,
                    RunnableConfig.builder().threadId(task.threadId).addMetadata("run_id",task.runId).build());
            budget.remainingMillis();
            if(task.tools.limitExceeded())return failed(task,budget);
            String text=output==null?null:output.getText();
            if(!task.tools.canDraft()||!task.tools.templateRead())return result(task,budget,DraftRun.Status.NEEDS_ATTENTION,
                    "本轮未完成新的事实核验和模板读取，不能沿用历史结果生成候选。",null);
            if(!DraftCandidateGuard.acceptsTask(text,task.order))return result(task,budget,DraftRun.Status.NEEDS_ATTENTION,
                    "候选含危险宣称、为空或过长，已隐藏；需要人工检查。",null);
            return result(task,budget,DraftRun.Status.CANDIDATE_UNVALIDATED,"已根据本轮核验整理候选，未经审核，尚未提交。",text);
        }catch(Exception ex){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("[DRAFT TASK] taskId={} runId={} errorType={}",task.id,task.runId,ex.getClass().getSimpleName());
            return failed(task,budget);
        }
    }
    private DraftRun failed(TaskRuntime task,DraftRunBudget budget) {
        budget.stop();return result(task,budget,DraftRun.Status.RUN_FAILED,"本轮未完成，任务已停止。检查点可能不完整，请新建任务，不要直接续跑。",null);
    }
    private DraftRun result(TaskRuntime task,DraftRunBudget budget,DraftRun.Status status,String message,String text) {
        return new DraftRun(task.runId,status,message,text,task.tools.assessment(),task.tools.events(),budget.modelCalls.get(),task.tools.calls(),budget.elapsedMillis(),false,false);
    }
    private TaskView view(TaskRuntime task) {
        if(task.status==Status.CLOSED)return new TaskView(task.summary(),task.lastRun==null?null:task.lastRun.hidden(),StateSummary.empty());
        if(task.executing.get())return new TaskView(task.summary(),task.lastRun,StateSummary.empty());
        // 纯读取：MemorySaver 保存的是框架检查点，不是 task Map 或工具对象字段的自动序列化。
        var config=task.config();var checkpoint=task.saver.get(config);var messages=checkpoint.map(c->c.getState().get("messages")).orElse(List.of());
        List<?> rows=messages instanceof List<?> list?list:List.of();
        var state=new StateSummary(task.saver.list(config).size(),rows.size(),count(rows,UserMessage.class),count(rows,AssistantMessage.class),count(rows,ToolResponseMessage.class));
        return new TaskView(task.summary(),task.lastRun,state);
    }
    private static int count(List<?> rows,Class<?> type){return (int)rows.stream().filter(type::isInstance).count();}
    /** 对不存在与无权访问统一返回 404，在加载 Saver 之前拒绝跨账户、跨租户访问。 */
    private TaskRuntime owned(Actor actor,UUID id) {
        TaskRuntime task=id==null?null:tasks.get(id);
        if(task==null||!task.owner.equals(actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的本地任务；重启或清理后任务会消失");
        return task;
    }
    private com.example.cloudcustomerservice.handoff.HandoffModel.Receipt requireBot(Access access,UUID conversation) {
        var receipt=access.reception().apply(conversation);
        if(receipt.mode()!=Mode.BOT)throw conflict("当前会话已不允许机器人处理，请查看统一客服");return receipt;
    }
    /** Agent 只创建一次，装饰器在每次模型调用时获取当前轮预算与日志标识。 */
    private ChatModel boundModel(TaskRuntime task) {
        return new ChatModel() {
            @Override public ChatOptions getDefaultOptions(){return model.getDefaultOptions();}
            @Override public ChatResponse call(Prompt prompt) {
                var budget=task.budget;budget.remainingMillis();
                if(budget.modelCalls.incrementAndGet()>6){budget.stop();throw new IllegalStateException("达到模型调用上限");}
                try{return bounded(budget,()->new PayloadLoggingChatModel(model,logPayload,"draftTask:"+task.id+":"+task.runId).call(prompt));}
                catch(RuntimeException ex){budget.stop();throw ex;}
            }
        };
    }
    /** 模型和只读事实查询共用固定工作池；超时不无限重建线程，也不保证远端立即停止计费。 */
    private <T>T bounded(DraftRunBudget budget,Callable<T> action) {
        long wait=Math.min(callTimeout.toMillis(),budget.remainingMillis());
        Future<T> call=workers.submit(()->{budget.remainingMillis();return action.call();});
        try{return call.get(wait,TimeUnit.MILLISECONDS);}
        catch(InterruptedException ex){budget.stop();Thread.currentThread().interrupt();throw new IllegalStateException("本轮已中断");}
        catch(TimeoutException ex){budget.stop();throw new IllegalStateException("本轮调用超时");}
        catch(ExecutionException ex){throw new IllegalStateException("本轮调用未完成");}
        finally{call.cancel(true);}
    }
    /** 防止后续配置给共享模型添加全局业务工具，意外扩大这个实验 Agent 的能力。 */
    private void requireNoGlobalTools() {
        var options=model.getDefaultOptions();
        if(options instanceof ToolCallingChatOptions t&&(!t.getToolNames().isEmpty()||!t.getToolCallbacks().isEmpty())
                ||options instanceof DashScopeChatOptions d&&d.getTools()!=null&&!d.getTools().isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型含全局工具，无法运行任务实验");
    }
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ExecutorService pool(String name) {
        return new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    /** 进程关闭只释放内存资源；没有检查点导出或下次启动的恢复逻辑。 */
    @PreDestroy public void close(){runs.shutdownNow();workers.shutdownNow();tasks.clear();}

    /** 永远不存 HttpSession、请求对象或凭证；门锁只保护当前 JVM，不宣称能协调多实例。 */
    private final class TaskRuntime {
        final UUID id=UUID.randomUUID(),conversationId;
        final String threadId="after-sale:v1:"+UUID.randomUUID(),order;
        final Actor owner;
        final ReturnReason reason;
        final Instant createdAt=Instant.now();
        final MemorySaver saver=new MemorySaver();
        final AfterSaleDraftTools tools;
        final ReactAgent agent;
        final ReentrantLock gate=new ReentrantLock();
        final AtomicBoolean executing=new AtomicBoolean();
        volatile Status status=Status.READY;
        volatile int turnNo;
        volatile String runId;
        volatile DraftRunBudget budget=new DraftRunBudget(Duration.ZERO);
        DraftRun lastRun;
        TaskRuntime(Actor owner,UUID conversation,String order,ReturnReason reason) {
            this.owner=owner;this.conversationId=conversation;this.order=order;this.reason=reason;
            tools=new AfterSaleDraftTools(assessments,owner,order,reason,false,budget);
            agent=DraftAgentFactory.create(boundModel(this),tools,saver,true);
        }
        RunnableConfig config(){return RunnableConfig.builder().threadId(threadId).build();}
        TaskSummary summary(){return new TaskSummary(id,conversationId,order,reason,status,turnNo,createdAt);}
    }
}
