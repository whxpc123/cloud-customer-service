package com.example.cloudcustomerservice.hitl;

import com.example.cloudcustomerservice.agent.DraftTaskModel.Access;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.handoff.HandoffModel.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.hitl.HitlLabSession.*;

/** 本地注册表只保留内存实验；源草稿仍从 PostgreSQL 读取，原准备任务不会被领取或修改。 */
@Service @Profile("local & knowledge") @Transactional(propagation = Propagation.NEVER)
public class HitlLabService {
    private record Entry(HitlLabSession session, UUID conversationId, long receptionVersion) { }
    public record Summary(UUID executionId, UUID taskId, long draftVersion, Phase phase, int simulatedExecutions) { }
    private final ConcurrentMap<UUID,Entry> sessions = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(2);
    private final ExecutorService workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),
            r -> { var t = new Thread(r,"hitl-lab"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    private final ChatModel model;
    private final ObjectMapper json;
    private final DraftApplicationService drafts;
    private final DraftVersionStore store;
    private final boolean logPayload;
    private final Clock clock;
    private final Duration timeout,ttl;
    @Autowired
    public HitlLabService(ChatModel model,ObjectMapper json,DraftApplicationService drafts,DraftVersionStore store,
            @Value("${app.ai.log-payload:false}") boolean logPayload) {
        this(model,json,drafts,store,logPayload,Clock.systemUTC(),Duration.ofSeconds(45),Duration.ofMinutes(10));
    }
    HitlLabService(ChatModel model,ObjectMapper json,DraftApplicationService drafts,DraftVersionStore store,
            boolean logPayload,Clock clock,Duration timeout,Duration ttl) {
        this.model=model; this.json=json; this.drafts=drafts; this.store=store; this.logPayload=logPayload;
        this.clock=clock; this.timeout=timeout; this.ttl=ttl;
    }
    /** 开始时要求用户明确选择已确认草稿，浏览器不能提交正文、工具参数或审批者。 */
    public View start(Access access,UUID taskId,Long draftVersion,Long expectedTaskVersion) {
        var frozen = drafts.read(access,taskId,draftVersion);
        if (expectedTaskVersion == null || expectedTaskVersion < 0) throw new IllegalArgumentException("需要当前任务版本");
        if (!frozen.current() || !frozen.confirmationEffective() || frozen.taskVersion()!=expectedTaskVersion)
            throw conflict("草稿不是当前已确认版本，请重新核对");
        var source = store.source(access.actor(),taskId);
        var reception = access.reception().apply(source.conversationId());
        if (reception.mode()!=Mode.BOT) throw conflict("接待状态不允许审批实验");
        var entry = register(access,frozen,source.conversationId(),reception.version());
        try {
            var result = entry.session.start(access.actor(),() -> verify(access,entry),this::run);
            // 即使模型迟到时 Session 已注销，也不能把包含正文的旧卡片返回给该请求。
            readable(access,entry); return result;
        } catch (RuntimeException ex) { if (entry.session.snapshot(access.actor()).phase()==Phase.NEW) sessions.remove(entry.session.id,entry); throw ex; }
    }
    private synchronized Entry register(Access access,DraftModels.View frozen,UUID conversation,long receptionVersion) {
        if (sessions.size()>=100) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已达 100 场本地实验，请清理旧实验");
        var session=new HitlLabSession(model,json,access.actor(),frozen,logPayload,clock,ttl);
        var entry=new Entry(session,conversation,receptionVersion); sessions.put(session.id,entry); return entry;
    }
    public List<Summary> list(Access access) {
        return sessions.values().stream().filter(e->e.session.owner.equals(access.actor())).map(e->{var v=e.session.snapshot(access.actor());
            return new Summary(v.executionId(),v.taskId(),v.draftVersion(),v.phase(),v.simulatedExecutions());}).toList();
    }
    /** GET 不恢复 Agent；仅读取当前认证下的草稿和这台进程的实验快照。 */
    public View get(Access access,UUID id) { var entry=owned(access,id); readable(access,entry); return entry.session.snapshot(access.actor()); }
    public View decide(Access access,UUID id,UUID approvalId,Long expectedVersion,Choice choice) {
        var entry=owned(access,id); readable(access,entry);
        var result=entry.session.decide(access.actor(),approvalId,expectedVersion,choice,() -> verify(access,entry),this::run);
        readable(access,entry); return result;
    }
    public void discard(Access access,UUID id) { var entry=owned(access,id); readable(access,entry); entry.session.discard(access.actor()); sessions.remove(id,entry); }
    private Entry owned(Access access,UUID id) {
        var entry=id==null?null:sessions.get(id);
        if (entry==null||!entry.session.owner.equals(access.actor())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的审批实验；服务重启后本地实验会消失");
        return entry;
    }
    private DraftModels.View readable(Access access,Entry entry) { return drafts.read(access,entry.session.frozen.taskId(),entry.session.frozen.draftVersion()); }
    /** 草稿与确认编号、准备任务版本和接待版本都必须仍匹配；每次请求重新传入身份回调。 */
    private void verify(Access access,Entry entry) {
        var current=readable(access,entry); var frozen=entry.session.frozen;
        var receipt=access.reception().apply(entry.conversationId);
        if (!current.current()||!current.confirmationEffective()||current.taskVersion()!=frozen.taskVersion()
                ||current.confirmation()==null||!current.confirmation().confirmationId().equals(frozen.confirmation().confirmationId())
                ||receipt.mode()!=Mode.BOT||receipt.version()!=entry.receptionVersion)
            throw conflict("草稿、内容确认或接待状态已经变化，本次操作不能执行");
    }
    /** 最多两段图执行同时占用池，45 秒停止等待；迟到图不能继续调用模型或增加模拟计数。 */
    private Optional<com.alibaba.cloud.ai.graph.NodeOutput> run(Callable<Optional<com.alibaba.cloud.ai.graph.NodeOutput>> action) throws Exception {
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已有两场实验运行，请稍后新建实验");
        Future<Optional<com.alibaba.cloud.ai.graph.NodeOutput>> future;
        try { future=workers.submit(()->{try{return action.call();}finally{capacity.release();}}); }
        catch (RejectedExecutionException ex) { capacity.release(); throw ex; }
        // 不取消尚未开始的 Future；容量在真实工作退出后归还，远程慢请求不会制造无限线程。
        try { return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
    }
    @PreDestroy public void close() { workers.shutdownNow(); sessions.clear(); }
}
