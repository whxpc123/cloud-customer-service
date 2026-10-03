package com.example.cloudcustomerservice.agent;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.cloudcustomerservice.agent.persistence.*;
import com.example.cloudcustomerservice.agent.persistence.DraftTaskRepository.TaskRow;
import com.example.cloudcustomerservice.aftersale.*;
import com.example.cloudcustomerservice.config.PayloadLoggingChatModel;
import com.example.cloudcustomerservice.handoff.HandoffModel.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;

/** 任务与检查点都落库；每轮重建 Agent，不保留 taskId 到运行对象的 JVM 映射。 */
@Service @Profile("local & knowledge")
@Transactional(propagation=Propagation.NEVER)
public class PersistentDraftTaskService {
    private final DraftTaskRepository repository;
    private final PostgresSaverFactory savers;
    private final ReturnAssessmentService assessments;
    private final ChatModel model;
    private final ObjectMapper json;
    private final boolean logPayload;
    private final Duration runTimeout,callTimeout;
    private final Semaphore capacity=new Semaphore(2);
    private final ExecutorService runs=pool("persistent-task"),workers=pool("persistent-call");
    /** 外部只返回稳定业务信息和版本，不暴露内部 threadId、checkpointId 或凭证。 */
    public record Summary(UUID taskId,UUID conversationId,String orderNo,ReturnReason reason,String status,
            long version,int turnNo,String agentProfile,boolean compatible,Instant createdAt,Instant updatedAt) { }
    public record View(Summary task,int lastCompletedTurn,DraftRun lastRun,StateSummary state) { }
    /** 保存上一正常完成轮次的业务结果与摘要；不会把 RUNNING 中的部分检查点解释成完成结果。 */
    public record Completed(int turnNo,DraftRun run,StateSummary state) { }
    private record Execution(UUID checkpointId,Completed result) { }
    @Autowired
    public PersistentDraftTaskService(DraftTaskRepository repository,PostgresSaverFactory savers,ReturnAssessmentService assessments,
            ChatModel model,ObjectMapper json,@Value("${app.ai.log-payload:false}") boolean logPayload) {
        this(repository,savers,assessments,model,json,logPayload,Duration.ofSeconds(90),Duration.ofSeconds(30));
    }
    PersistentDraftTaskService(DraftTaskRepository repository,PostgresSaverFactory savers,ReturnAssessmentService assessments,
            ChatModel model,ObjectMapper json,boolean logPayload,Duration runTimeout,Duration callTimeout) {
        this.repository=repository;this.savers=savers;this.assessments=assessments;this.model=model;this.json=json;
        this.logPayload=logPayload;this.runTimeout=runTimeout;this.callTimeout=callTimeout;
    }
    /** 创建任务无需检查点或模型；未开始的 READY 记录也可跨重启恢复。 */
    public Summary create(Access access,UUID conversation,String orderNo,ReturnReason reason) {
        String order=orderNo==null?"":orderNo.strip().toUpperCase(Locale.ROOT);
        if(conversation==null||!order.matches("A\\d{5}"))throw new IllegalArgumentException("需要有效会话与订单号");
        requireBot(access,conversation);requireNoGlobalTools();
        return summary(repository.create(access.actor(),conversation,order,reason==null?ReturnReason.UNKNOWN:reason));
    }
    public List<Summary> list(Actor actor){return repository.list(actor).stream().map(this::summary).toList();}
    /** 查询只读业务快照；不新建 Saver、不反序列化图、不查订单、不调用模型。 */
    public View get(Access access,UUID id) {
        var task=repository.owned(access.actor(),id);
        boolean show=access.reception().apply(task.conversationId()).mode()==Mode.BOT;
        return view(task,show);
    }
    /** 结束普通任务只改变业务状态，保留持久化历史，不把 Saver.release 当成关闭数据库连接。 */
    public View close(Access access,UUID id,Long version) {
        checkVersion(version);var task=repository.owned(access.actor(),id);access.reception().apply(task.conversationId());
        repository.close(access.actor(),id,version);return view(repository.owned(access.actor(),id),false);
    }
    /** claim 和 finish 均由独立 Repository 代理短事务执行；模型等待绝不能处于数据库事务内。 */
    public View continueTask(Access access,UUID id,Long expectedVersion,String message) {
        checkVersion(expectedVersion);
        if(message==null||message.isBlank()||message.length()>2000)throw new IllegalArgumentException("补充内容须为 1～2000 字符");
        var original=repository.owned(access.actor(),id);var before=requireBot(access,original.conversationId());requireNoGlobalTools();
        if(!capacity.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"本实例已有两个任务运行，请稍后查询状态");
        TaskRow task;
        try{task=repository.claim(access.actor(),id,expectedVersion,before.version(),UUID.randomUUID());}
        catch(RuntimeException ex){capacity.release();throw ex;}
        var budget=new DraftRunBudget(runTimeout);
        try {
            Future<Execution> future;
            try{future=runs.submit(()->{try{return execute(task,access.actor(),message,budget);}finally{capacity.release();}});}
            catch(RejectedExecutionException ex){capacity.release();throw ex;}
            // 不取消整个运行 Future，确保即使排队时已超时，工作 finally 仍会归还实例容量。
            var executed=future.get(runTimeout.toMillis(),TimeUnit.MILLISECONDS);budget.remainingMillis();
            try {
                var after=access.reception().apply(task.conversationId());
                if(after.mode()!=Mode.BOT||after.version()!=before.version())return hideLate(task);
            }catch(RuntimeException ex){return hideLate(task);}
            repository.finish(task,executed.result().run().status().name(),executed.checkpointId(),json.writeValueAsString(executed.result()));
            return get(access,id);
        }catch(Exception ex){
            if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
            // CAS 标记不会覆盖已经提交的成功结果；若连标记都失败，原 RUNNING 本身仍禁止自动再运行。
            try{repository.requireRecovery(task);}catch(RuntimeException ignored){log("RECOVERY_MARK_UNCONFIRMED",task);}
            log("COMPLETION_UNCONFIRMED",task);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"本轮未确认完成，请先查询任务状态；需要核查时请新建任务，不要自动重跑");
        }finally{budget.stop();}
    }
    /** 异步运行只写框架检查点；业务 finish 在等待成功后由请求线程执行，迟到线程不能偷偷提交完成结果。 */
    private Execution execute(TaskRow task,Actor actor,String message,DraftRunBudget budget) throws Exception {
        budget.remainingMillis();var saver=savers.create();
        var config=RunnableConfig.builder().threadId(task.threadId()).addMetadata("run_id",task.runId().toString()).build();
        var previous=saver.get(config);String previousId=previous.map(Checkpoint::getId).orElse(null);
        String expected=task.lastCheckpointId()==null?null:task.lastCheckpointId().toString();
        if(!Objects.equals(previousId,expected)||(task.turnNo()>1&&previousId==null))
            throw new IllegalStateException("任务与检查点不一致");
        if(previous.isPresent()&&!StateGraph.END.equals(previous.get().getNextNodeId()))throw new IllegalStateException("旧检查点未正常结束");
        var tools=new AfterSaleDraftTools(assessments,actor,task.orderNo(),task.reason(),false,budget);
        tools.beginTurn(budget,work->bounded(budget,work::get));
        var agent=DraftAgentFactory.create(boundModel(task,budget),tools,saver,true);
        var output=agent.call("服务器绑定订单："+task.orderNo()+"；初始用户原因："+task.reason()+"。本轮用户补充：\n"+message,config);
        budget.remainingMillis();if(tools.limitExceeded())throw new IllegalStateException("本轮达到工具上限");
        // 强制用另一个无缓存实例读数据库，检测“仅内存里有新检查点”或数据库写入未确认的情况。
        var fresh=savers.create();var stored=fresh.get(config).orElseThrow(()->new IllegalStateException("未确认检查点落库"));
        String runtimeId=saver.get(config).map(Checkpoint::getId).orElse(null);
        if(!stored.getId().equals(runtimeId)||stored.getId().equals(previousId)||!StateGraph.END.equals(stored.getNextNodeId()))
            throw new IllegalStateException("检查点尚未确认正常结束");
        var state=state(stored,fresh.list(config).size());
        if(state.userMessages()!=task.turnNo())throw new IllegalStateException("恢复后的用户轮次不匹配");
        String text=output==null?null:output.getText();
        boolean ready=tools.canDraft()&&tools.templateRead()&&DraftCandidateGuard.acceptsTask(text,task.orderNo());
        var run=new DraftRun(task.runId().toString(),ready?DraftRun.Status.CANDIDATE_UNVALIDATED:DraftRun.Status.NEEDS_ATTENTION,
                ready?"本轮结果与检查点已确认保存；候选未经审核，尚未提交。":"本轮已结束，但核验、模板或候选检查未满足要求，请查看实际工具记录。",
                ready?text:null,tools.assessment(),tools.events(),budget.modelCalls.get(),tools.calls(),budget.elapsedMillis(),false,false);
        budget.remainingMillis();return new Execution(UUID.fromString(stored.getId()),new Completed(task.turnNo(),run,state));
    }
    private View hideLate(TaskRow task){repository.closeRun(task);return view(repository.owned(new Actor(task.tenantId(),task.userId()),task.taskId()),false);}
    /** 展示时不把旧的完成快照当成正在运行轮次的结果；前端同时收到 lastCompletedTurn。 */
    private View view(TaskRow task,boolean show) {
        Summary summary=summary(task);
        if(!show||task.status().equals("CLOSED")) {
            summary=new Summary(summary.taskId(),summary.conversationId(),summary.orderNo(),summary.reason(),"CLOSED",summary.version(),summary.turnNo(),summary.agentProfile(),summary.compatible(),summary.createdAt(),summary.updatedAt());
            return new View(summary,0,null,StateSummary.empty());
        }
        if(task.lastResultJson()==null)return new View(summary,0,null,StateSummary.empty());
        try{var saved=json.readValue(task.lastResultJson(),Completed.class);return new View(summary,saved.turnNo(),saved.run(),saved.state());}
        catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"已存结果无法读取，请核查数据，未调用模型重建");}
    }
    private Summary summary(TaskRow row){return new Summary(row.taskId(),row.conversationId(),row.orderNo(),row.reason(),row.status(),row.version(),row.turnNo(),row.agentProfile(),DraftTaskRepository.PROFILE.equals(row.agentProfile()),row.createdAt(),row.updatedAt());}
    private StateSummary state(Checkpoint checkpoint,int count){
        Object value=checkpoint.getState().get("messages");if(!(value instanceof List<?> messages))throw new IllegalStateException("检查点消息结构不兼容");
        return new StateSummary(count,messages.size(),count(messages,UserMessage.class),count(messages,AssistantMessage.class),count(messages,ToolResponseMessage.class));
    }
    private static int count(List<?> rows,Class<?> type){return (int)rows.stream().filter(type::isInstance).count();}
    private static void checkVersion(Long value){if(value==null||value<0)throw new IllegalArgumentException("需要当前 expectedVersion");}
    private com.example.cloudcustomerservice.handoff.HandoffModel.Receipt requireBot(Access access,UUID id){
        var receipt=access.reception().apply(id);if(receipt.mode()!=Mode.BOT)throw new ResponseStatusException(HttpStatus.CONFLICT,"会话已不允许机器人处理");return receipt;
    }
    /** 模型仍只有核验与模板两个工具；不从历史消息恢复身份，不引入全局写工具。 */
    private void requireNoGlobalTools(){var options=model.getDefaultOptions();
        if(options instanceof ToolCallingChatOptions t&&(!t.getToolNames().isEmpty()||!t.getToolCallbacks().isEmpty())
                ||options instanceof DashScopeChatOptions d&&d.getTools()!=null&&!d.getTools().isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型含全局工具，不能运行此实验");
    }
    private ChatModel boundModel(TaskRow task,DraftRunBudget budget){return new ChatModel(){
        @Override public ChatOptions getDefaultOptions(){return model.getDefaultOptions();}
        @Override public ChatResponse call(Prompt prompt){budget.remainingMillis();
            if(budget.modelCalls.incrementAndGet()>6){budget.stop();throw new IllegalStateException("达到模型上限");}
            try{return bounded(budget,()->new PayloadLoggingChatModel(model,logPayload,"persistentDraft:"+task.taskId()+":"+task.runId()).call(prompt));}
            catch(RuntimeException ex){budget.stop();throw ex;}
        }
    };}
    /** 停止本地等待不保证远程请求立即结束；固定线程池不因超时无限扩容。 */
    private <T>T bounded(DraftRunBudget budget,Callable<T> action){
        long wait=Math.min(callTimeout.toMillis(),budget.remainingMillis());Future<T> future=workers.submit(()->{budget.remainingMillis();return action.call();});
        try{return future.get(wait,TimeUnit.MILLISECONDS);}
        catch(InterruptedException ex){budget.stop();Thread.currentThread().interrupt();throw new IllegalStateException("已中断");}
        catch(TimeoutException ex){budget.stop();throw new IllegalStateException("调用超时");}
        catch(ExecutionException ex){throw new IllegalStateException("调用未完成");}
        finally{future.cancel(true);}
    }
    private static ExecutorService pool(String name){return new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());}
    private static void log(String event,TaskRow task){org.slf4j.LoggerFactory.getLogger(PersistentDraftTaskService.class).warn("[PERSISTENT TASK] event={} taskId={} runId={}",event,task.taskId(),task.runId());}
    /** 不 release 数据库线程，不重置 RUNNING；重启后保留可观察的未完成状态。 */
    @PreDestroy public void shutdown(){runs.shutdownNow();workers.shutdownNow();}
}
