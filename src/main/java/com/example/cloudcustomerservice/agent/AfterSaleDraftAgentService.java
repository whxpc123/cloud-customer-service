package com.example.cloudcustomerservice.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.cloudcustomerservice.aftersale.*;
import com.example.cloudcustomerservice.config.PayloadLoggingChatModel;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftRun.Status.*;

/** 第二十章只在本次调用内完成观察、工具执行和候选整理；不共享 Agent、Saver、工具或历史。 */
@Service @Profile("local & knowledge")
public class AfterSaleDraftAgentService {
    private final ChatModel model;
    private final ReturnAssessmentService assessments;
    private final boolean logPayload;
    // 零排队的固定线程池：卡住的供应商调用仍占用自己的线程，不靠无限建线程掩盖故障。
    private final ExecutorService runs=pool("draft-run"), modelWorkers=workPool();
    private final Duration runTimeout, callTimeout;
    @org.springframework.beans.factory.annotation.Autowired
    public AfterSaleDraftAgentService(ChatModel model, ReturnAssessmentService assessments,
            @Value("${app.ai.log-payload:false}") boolean logPayload) {
        this(model,assessments,logPayload,Duration.ofSeconds(90),Duration.ofSeconds(30));
    }
    /** 包内构造器用于毫秒级超时验证，生产时不能由用户请求放大预算。 */
    AfterSaleDraftAgentService(ChatModel model,ReturnAssessmentService assessments,boolean logPayload,
            Duration runTimeout,Duration callTimeout) {
        this.model=model;this.assessments=assessments;this.logPayload=logPayload;
        this.runTimeout=runTimeout;this.callTimeout=callTimeout;
    }
    public DraftRun prepare(Actor actor,String orderNo,ReturnReason reason,String task,boolean inspectionOnly) {
        Objects.requireNonNull(actor,"必须绑定服务器身份");
        String order=orderNo==null?"":orderNo.strip().toUpperCase(Locale.ROOT);
        if(!order.matches("A\\d{5}")||task==null||task.isBlank()||task.length()>2000)
            throw new IllegalArgumentException("请指定有效订单和 1～2000 字任务");
        requireNoGlobalTools();
        String runId=UUID.randomUUID().toString();
        var budget=new DraftRunBudget(runTimeout);
        var tools=new AfterSaleDraftTools(assessments,actor,order,reason==null?ReturnReason.UNKNOWN:reason,inspectionOnly,budget,
                work->boundedCall(budget,work::get));
        Future<DraftRun> running;
        try { running=runs.submit(()->execute(runId,order,task,inspectionOnly,budget,tools)); }
        catch(RejectedExecutionException ex) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已有两次草稿运行，请稍后手动重试"); }
        try { return running.get(runTimeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); return failed(runId,budget,tools); }
        catch(ExecutionException|TimeoutException ex) { return failed(runId,budget,tools); }
        finally { budget.stop(); running.cancel(true); }
    }
    private DraftRun execute(String id,String order,String task,boolean inspectionOnly,DraftRunBudget budget,AfterSaleDraftTools tools) {
        try {
            var agent=DraftAgentFactory.create(
                    new PayloadLoggingChatModel(boundedModel(budget),logPayload,"draftAgent:"+id),
                    tools,new MemorySaver(),false);
            var output=agent.call("服务器指定订单："+order+"；只检查模式："+inspectionOnly+"。\n用户任务："+task,
                    RunnableConfig.builder().threadId(id).build());
            budget.remainingMillis();
            String text=output==null?null:output.getText();
            if(tools.limitExceeded()) return failed(id,budget,tools);
            // 只检查时返回确定性事实，不把模型的自由文本伪装成业务核验结论。
            if(inspectionOnly && tools.assessment()!=null
                    && tools.assessment().status()!=AssessmentStatus.TEMPORARILY_UNAVAILABLE)
                return result(id,INSPECTED,tools.assessment().explanation(),null,budget,tools);
            if(!tools.templateRead()||!tools.canDraft())
                return result(id,NEEDS_ATTENTION,"本轮未取得完整核验依据和模板，未生成可审阅草稿。",null,budget,tools);
            if(!DraftCandidateGuard.accepts(text))
                return result(id,NEEDS_ATTENTION,"模型文本为空、过长或含越权操作宣称，已隐藏；请人工检查事实。",null,budget,tools);
            return result(id,CANDIDATE_UNVALIDATED,"候选草稿未经事实与语义审核，尚未提交。",text,budget,tools);
        } catch(Exception ex) {
            // 只记录关联号和异常类型，既不泄漏供应商响应，也不伪造业务完成状态。
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("[DRAFT AGENT] runId={} errorType={}",id,ex.getClass().getSimpleName());
            return failed(id,budget,tools);
        }
    }
    /** 框架同步调用由专门的有界线程池执行；30 秒超时只终止本地等待，不能保证远端立即停止。 */
    private ChatModel boundedModel(DraftRunBudget budget) {
        return new ChatModel() {
            @Override public ChatOptions getDefaultOptions() { return model.getDefaultOptions(); }
            @Override public ChatResponse call(Prompt prompt) {
                budget.remainingMillis();
                if(budget.modelCalls.incrementAndGet()>6) throw new IllegalStateException("模型调用次数已达上限");
                try { return boundedCall(budget,()->model.call(prompt)); }
                catch(RuntimeException ex) { budget.stop();throw ex; }
            }
        };
    }
    /** 模型与只读业务查询共用两个底层工作槽，超过 30 秒不再等待；无界阻塞不会制造更多线程。 */
    private <T> T boundedCall(DraftRunBudget budget,Callable<T> work) {
        long wait=Math.min(callTimeout.toMillis(),budget.remainingMillis());
        Future<T> call=modelWorkers.submit(()->{budget.remainingMillis();return work.call();});
        try { return call.get(wait,TimeUnit.MILLISECONDS); }
        catch(InterruptedException ex) { budget.stop();Thread.currentThread().interrupt();throw new IllegalStateException("本轮已中断"); }
        catch(TimeoutException ex) { budget.stop();throw new IllegalStateException("本轮等待超时"); }
        catch(ExecutionException ex) { throw new IllegalStateException("本轮调用未完成"); }
        finally { call.cancel(true); }
    }
    /** 防止未来给共享 ChatModel 注册全局写工具后，被默认选项合并进本实验。 */
    private void requireNoGlobalTools() {
        var options=model.getDefaultOptions();
        if(options instanceof ToolCallingChatOptions t && (!t.getToolCallbacks().isEmpty()||!t.getToolNames().isEmpty())
                || options instanceof DashScopeChatOptions d && d.getTools()!=null&&!d.getTools().isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型含全局工具，无法安全运行草稿实验");
    }
    private DraftRun failed(String id,DraftRunBudget budget,AfterSaleDraftTools tools) {
        budget.stop();
        return result(id,RUN_FAILED,"本轮未完成，可能达到调用或等待上限；不会自动重跑，也没有提交申请。",null,budget,tools);
    }
    private DraftRun result(String id,DraftRun.Status status,String message,String candidate,DraftRunBudget budget,AfterSaleDraftTools tools) {
        return new DraftRun(id,status,message,candidate,tools.assessment(),tools.events(),budget.modelCalls.get(),
                tools.calls(),budget.elapsedMillis(),false,false);
    }
    private static ExecutorService pool(String name) {
        return new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{
            var thread=new Thread(task,name);thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    private static ExecutorService workPool() {
        // 两个运行交替提交模型/查询工作时，允许两项短暂交接排队；不因线程刚返回尚未空闲误拒绝。
        return new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),task->{
            var thread=new Thread(task,"draft-model-or-lookup");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @PreDestroy public void close() { runs.shutdownNow();modelWorkers.shutdownNow(); }
}
