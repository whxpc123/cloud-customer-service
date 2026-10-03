package com.example.cloudcustomerservice.draft;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import com.example.cloudcustomerservice.agent.persistence.DraftTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.draft.DraftModels.*;

/**
 * 草稿发布和确认共用父任务行锁；先锁会话再锁任务，和接待操作的锁顺序一致。
 * 所有方法都是短事务，只访问数据库，不等待模型或重新核验远程订单。
 */
@Repository @Profile("local & knowledge")
@Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3,rollbackFor=Exception.class)
public class DraftVersionStore {
    /** 服务内部的源快照，完整 resultJson 不作为 API 返回；用于模型前后比较任务版本。 */
    public record Basis(UUID taskId,UUID conversationId,long taskVersion,long draftVersion,UUID runId,
            String orderNo,ReturnReason reason,String status,String agentProfile,String resultJson) { }
    private record Revision(long version,UUID basisRunId,String bodyJson,OffsetDateTime createdAt) { }
    private static final RowMapper<Basis> BASIS=(r,n)->new Basis(r.getObject("task_id",UUID.class),r.getObject("conversation_id",UUID.class),
            r.getLong("version"),r.getLong("draft_version"),r.getObject("run_id",UUID.class),r.getString("order_no"),
            ReturnReason.valueOf(r.getString("reason")),r.getString("status"),r.getString("agent_profile"),r.getString("last_result_json"));
    private static final RowMapper<Revision> REVISION=(r,n)->new Revision(r.getLong("draft_version"),r.getObject("basis_run_id",UUID.class),r.getString("body_json"),r.getObject("created_at",OffsetDateTime.class));
    private static final RowMapper<Confirmation> CONFIRMATION=(r,n)->new Confirmation(r.getObject("confirmation_id",UUID.class),
            r.getObject("task_id",UUID.class),r.getLong("draft_version"),r.getLong("confirmed_by"),r.getObject("confirmed_at",OffsetDateTime.class),r.getString("scope"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public DraftVersionStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    /** 先限定租户与用户，才读取结果；这一层不加载任何 Graph 检查点。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=3)
    public Basis source(Actor actor,UUID id){return owned(actor,id,false);}

    /** 再次锁行比较，模型期间确认、续写、结束、发布或接待变化均不能被旧结果覆盖。 */
    public View publish(Actor actor,Basis expected,long receptionVersion,Body body) {
        var task=locked(actor,expected.taskId(),receptionVersion);
        requireCandidate(task);
        if(task.taskVersion()!=expected.taskVersion()||!Objects.equals(task.runId(),expected.runId()))
            throw conflict("任务已变化，请刷新后根据新的完成结果重新整理；未保存旧结果");
        if(body==null||!task.orderNo().equals(body.orderNo()))throw new IllegalArgumentException("草稿订单与任务不一致");
        if(task.draftVersion()>=100)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"本任务已保存 100 版，请结束后新建任务");
        long next=Math.addExact(task.draftVersion(),1);
        jdbc.update("insert into ai.cs_draft_revision(task_id,draft_version,basis_run_id,body_json) values(?,?,?,cast(? as jsonb))",
                task.taskId(),next,task.runId(),write(body));
        jdbc.update("update ai.cs_draft_task set draft_version=?,version=version+1,updated_at=current_timestamp where task_id=?",next,task.taskId());
        return view(owned(actor,task.taskId(),false),revision(task.taskId(),next),null);
    }
    /** 读取也短暂锁父行，确保“正文 / 当前有效性 / 确认记录”来自同一稳定业务状态。 */
    public View read(Actor actor,UUID id,long version,long receptionVersion) {
        positive(version);var task=locked(actor,id,receptionVersion);return view(task,revision(id,version),confirmation(id,version));
    }
    /** 不把当前版本偷偷返回给确认操作；目录仅供用户明确选择一版重新查看。 */
    public List<RevisionSummary> list(Actor actor,UUID id,long receptionVersion) {
        var task=locked(actor,id,receptionVersion);
        var rows=jdbc.query("select * from ai.cs_draft_revision where task_id=? order by draft_version desc limit 100",REVISION,id);
        var confirmations=new HashMap<Long,Confirmation>();
        jdbc.query("select * from ai.cs_draft_confirmation where task_id=?",CONFIRMATION,id).forEach(c->confirmations.put(c.draftVersion(),c));
        return rows.stream().map(r->{var c=confirmations.get(r.version());boolean current=isCurrent(task,r);
            return new RevisionSummary(r.version(),r.basisRunId(),r.createdAt(),current,current&&c!=null,c);}).toList();
    }
    /**
     * accepted 必须显式为 true；先检查当前效力，再处理重复确认。
     * 父行锁串行化发布/确认/任务领取，同版重复请求复用真实回执且不再增加任务版本。
     */
    public Confirmation confirm(Actor actor,UUID id,long seenVersion,boolean accepted,long receptionVersion) {
        positive(seenVersion);if(!accepted)throw new IllegalArgumentException("需要明确接受本版问题描述和申请诉求");
        var task=locked(actor,id,receptionVersion);var revision=revision(id,seenVersion);
        if(!isCurrent(task,revision))throw conflict("您查看的草稿或任务已变化，请重新加载并核对；没有确认其他版本");
        var existing=confirmation(id,seenVersion);if(existing!=null)return existing;
        UUID receipt=UUID.randomUUID();
        jdbc.update("insert into ai.cs_draft_confirmation(confirmation_id,task_id,draft_version,confirmed_by,scope) values(?,?,?,?,'DRAFT_CONTENT_ONLY')",receipt,id,seenVersion,actor.userId());
        jdbc.update("update ai.cs_draft_task set version=version+1,updated_at=current_timestamp where task_id=?",id);
        return Objects.requireNonNull(confirmation(id,seenVersion));
    }
    /** 查询主体始终带租户和用户；不存在与跨用户请求统一 404，不能先泄露草稿正文。 */
    private Basis owned(Actor actor,UUID id,boolean lock) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jdbc.query("select * from ai.cs_draft_task where task_id=? and tenant_id=? and user_id=?"+(lock?" for update":""),BASIS,id,actor.tenantId(),actor.userId())
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的任务"));
    }
    /** 写入/读取时与人工接待争用会话行；模型前捕获的接待版本不能跨越一次状态变更。 */
    private Basis locked(Actor actor,UUID id,long receptionVersion) {
        var source=owned(actor,id,false);
        var reception=jdbc.query("select mode,version from ai.cs_conversation where id=? and tenant_id=? and user_id=? for update",
                (r,n)->Map.entry(r.getString("mode"),r.getLong("version")),source.conversationId(),actor.tenantId(),actor.userId());
        if(reception.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的会话");
        var receipt=reception.get(0);
        if(!receipt.getKey().equals("BOT")||receipt.getValue()!=receptionVersion)throw conflict("接待状态已变化，不能继续草稿操作，请刷新");
        return owned(actor,id,true);
    }
    private Revision revision(UUID id,long version) {
        return jdbc.query("select * from ai.cs_draft_revision where task_id=? and draft_version=?",REVISION,id,version).stream()
                .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"草稿版本不存在"));
    }
    private Confirmation confirmation(UUID id,long version){return jdbc.query("select * from ai.cs_draft_confirmation where task_id=? and draft_version=?",CONFIRMATION,id,version).stream().findFirst().orElse(null);}
    private View view(Basis task,Revision revision,Confirmation confirmation) {
        try {boolean current=isCurrent(task,revision);return new View(task.taskId(),revision.version(),task.taskVersion(),revision.basisRunId(),revision.createdAt(),
                json.readValue(revision.bodyJson(),Body.class),current,current&&confirmation!=null,confirmation);}
        catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"已存草稿无法读取，请核查数据，未重新生成");}
    }
    /** 旧确认永远保留，但新运行一开始，状态/runId 已变化，当前效力立即为 false。 */
    private boolean isCurrent(Basis task,Revision revision){return "CANDIDATE_UNVALIDATED".equals(task.status())
            &&DraftTaskRepository.PROFILE.equals(task.agentProfile())&&task.draftVersion()==revision.version()&&Objects.equals(task.runId(),revision.basisRunId());}
    public static void requireCandidate(Basis task){if(!"CANDIDATE_UNVALIDATED".equals(task.status())||task.runId()==null||task.resultJson()==null
            ||!DraftTaskRepository.PROFILE.equals(task.agentProfile()))throw conflict("当前没有可整理的兼容且正常完成的候选");}
    private String write(Body body){try{return json.writeValueAsString(body);}catch(Exception ex){throw new IllegalStateException("草稿序列化失败");}}
    private static void positive(long version){if(version<=0)throw new IllegalArgumentException("需要具体的正数草稿版本");}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
