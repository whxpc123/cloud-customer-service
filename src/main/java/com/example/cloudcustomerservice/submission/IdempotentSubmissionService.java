package com.example.cloudcustomerservice.submission;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.agent.persistence.DraftTaskRepository;
import com.fasterxml.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 同库短事务中的提交服务。必须通过 Spring 代理调用，不能在工具中自行 new。
 * 不调用模型、订单微服务或外部售后平台；这里只登记一份等待审核的本地申请。
 */
@Service @Profile("local & knowledge")
@Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=5,rollbackFor=Exception.class)
public class IdempotentSubmissionService {
    public enum Decision { APPROVE, REJECT }
    /** 操作身份始终稳定；expired 仅约束首次执行，不影响历史成功回放。 */
    public record Operation(UUID operationId,UUID taskId,long draftVersion,String action,String status,
            long receptionVersion,Long decidedBy,OffsetDateTime decidedAt,OffsetDateTime expiresAt,
            OffsetDateTime createdAt,boolean expired,String orderNo) { }
    /** 回执来自真实 INSERT 的数据库时间，不代表后续审核通过或资金退款。 */
    public record Receipt(UUID operationId,UUID applicationId,long draftVersion,OffsetDateTime createdAt,
            String result,boolean refundExecutedByThisOperation) { }
    public record Detail(Operation operation,JsonNode body,Receipt receipt,boolean firstExecutionEligible) { }
    public record ResultQuery(String observation,Receipt receipt) { }
    private record Task(UUID id,UUID conversationId,String tenantId,long userId,String orderNo,String status,
            String profile,long draftVersion,UUID runId) { }
    private record Locked(Task task,String mode,long receptionVersion) { }
    private static final RowMapper<Task> TASK=(r,n)->new Task(r.getObject("task_id",UUID.class),r.getObject("conversation_id",UUID.class),
        r.getString("tenant_id"),r.getLong("user_id"),r.getString("order_no"),r.getString("status"),r.getString("agent_profile"),r.getLong("draft_version"),r.getObject("run_id",UUID.class));
    private static final RowMapper<Operation> OPERATION=(r,n)->new Operation(r.getObject("operation_id",UUID.class),r.getObject("task_id",UUID.class),
        r.getLong("draft_version"),r.getString("action"),r.getString("status"),r.getLong("reception_version"),
        (Long)r.getObject("decided_by"),r.getObject("decided_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class),
        r.getObject("created_at",OffsetDateTime.class),r.getBoolean("expired"),r.getString("order_no"));
    private static final RowMapper<Receipt> RECEIPT=(r,n)->new Receipt(r.getObject("operation_id",UUID.class),r.getObject("application_id",UUID.class),
        r.getLong("draft_version"),r.getObject("created_at",OffsetDateTime.class),"APPLICATION_CREATED_PENDING_REVIEW",false);
    private static final String OP_SELECT="select o.*, t.order_no, o.expires_at <= clock_timestamp() as expired from ai.cs_submit_operation o join ai.cs_draft_task t on t.task_id=o.task_id";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public IdempotentSubmissionService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    /** 同任务同版重复准备复用原编号，不能借重试重新计时、换号或批准。 */
    public Operation prepare(Actor actor,UUID taskId,long draftVersion) {
        if(draftVersion<=0)throw new IllegalArgumentException("需要具体的正数草稿版本");
        var locked=lockOwnedTask(actor,taskId);
        var ids=jdbc.queryForList("select operation_id from ai.cs_submit_operation where task_id=? and draft_version=?",UUID.class,taskId,draftVersion);
        if(!ids.isEmpty())return ownedOperation(actor,ids.get(0),false);
        requireBot(locked,null);requireNoApplication(taskId);currentConfirmedBody(locked.task(),draftVersion);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into ai.cs_submit_operation(operation_id,task_id,draft_version,reception_version,expires_at)
            values(?,?,?,?,clock_timestamp()+interval '15 minutes')
            """,id,taskId,draftVersion,locked.receptionVersion());
        return ownedOperation(actor,id,false);
    }

    /** 明确用户决策持久化；同一决定幂等，相反决定不能覆盖，不暴露为 Agent 工具。 */
    public Operation decide(Actor actor,UUID id,Decision decision) {
        if(decision==null)throw new IllegalArgumentException("请选择批准或拒绝");
        var initial=ownedOperation(actor,id,false);var locked=lockOwnedTask(actor,initial.taskId());var op=ownedOperation(actor,id,true);
        boolean approve=decision==Decision.APPROVE;
        if(approve&&Set.of("APPROVED","SUCCEEDED").contains(op.status())||!approve&&op.status().equals("REJECTED"))return op;
        if(!op.status().equals("PENDING_APPROVAL"))throw conflict("本操作已经作出决策，不能改写");
        if(approve){requireBot(locked,op);requireNotExpired(id);currentConfirmedBody(locked.task(),op.draftVersion());requireNoApplication(op.taskId());}
        requireOne(jdbc.update("update ai.cs_submit_operation set status=?,decided_by=?,decided_at=clock_timestamp() where operation_id=? and status='PENDING_APPROVAL'",
            approve?"APPROVED":"REJECTED",actor.userId(),id));
        return ownedOperation(actor,id,false);
    }

    /**
     * 始终先校验当前归属，再查历史成功。成功回放不再检查已关闭任务或旧授权期限。
     * INSERT、消费批准、关闭任务共享同一连接和事务；异常向代理传播以整体回滚。
     */
    public Receipt submit(Actor actor,UUID id) {
        var initial=ownedOperation(actor,id,false);var locked=lockOwnedTask(actor,initial.taskId());var op=ownedOperation(actor,id,true);
        var previous=receipts(id);if(!previous.isEmpty())return previous.get(0);
        if(op.status().equals("SUCCEEDED"))throw new IllegalStateException("成功操作缺少回执，需核查");
        if(!op.status().equals("APPROVED"))throw conflict("本次提交操作尚未获得批准");
        requireBot(locked,op);requireNotExpired(id);var task=locked.task();
        String body=currentConfirmedBody(task,op.draftVersion());requireNoApplication(task.id());
        var created=jdbc.query("""
            insert into ai.cs_after_sale_application(application_id,operation_id,task_id,draft_version,tenant_id,user_id,order_no,body_snapshot)
            values(?,?,?,?,?,?,?,cast(? as jsonb)) returning operation_id,application_id,draft_version,created_at
            """,RECEIPT,UUID.randomUUID(),id,task.id(),op.draftVersion(),task.tenantId(),task.userId(),task.orderNo(),body);
        requireOne(jdbc.update("update ai.cs_submit_operation set status='SUCCEEDED' where operation_id=? and status='APPROVED'",id));
        requireOne(jdbc.update("update ai.cs_draft_task set status='CLOSED',version=version+1,updated_at=clock_timestamp() where task_id=? and status='CANDIDATE_UNVALIDATED'",task.id()));
        return created.get(0);
    }

    /** 查询权威写库，只报告当前观察到的成功。未查到不能被翻译成执行失败。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=5)
    public ResultQuery result(Actor actor,UUID id) {
        ownedOperation(actor,id,false);var rows=receipts(id);
        return new ResultQuery(rows.isEmpty()?"NOT_OBSERVED":"APPLICATION_CREATED_PENDING_REVIEW",rows.isEmpty()?null:rows.get(0));
    }

    /** 所有者历史目录用于重启/刷新后找回编号，不会准备操作或触发提交。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=5)
    public List<Operation> list(Actor actor) {
        requireActor(actor);return jdbc.query(OP_SELECT+" where t.tenant_id=? and t.user_id=? order by o.created_at desc,o.operation_id limit 100",OPERATION,actor.tenantId(),actor.userId());
    }

    /** 锁定同一任务以提供一致的展示快照；历史正文不随当前草稿变动，读取不调用 Graph。 */
    public Detail detail(Actor actor,UUID id) {
        var initial=ownedOperation(actor,id,false);var locked=lockOwnedTask(actor,initial.taskId());var op=ownedOperation(actor,id,true);
        String body=jdbc.queryForObject("select body_json::text from ai.cs_draft_revision where task_id=? and draft_version=?",String.class,op.taskId(),op.draftVersion());
        var receipts=receipts(id);boolean eligible=false;
        if(receipts.isEmpty()&&!op.expired()&&Set.of("PENDING_APPROVAL","APPROVED").contains(op.status())){
            try{requireBot(locked,op);currentConfirmedBody(locked.task(),op.draftVersion());requireNoApplication(op.taskId());eligible=true;}
            catch(ResponseStatusException e){if(e.getStatusCode().value()!=409)throw e;}
        }
        try{return new Detail(op,json.readTree(body),receipts.isEmpty()?null:receipts.get(0),eligible);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("草稿快照不可读",e);}
    }

    /** 与前章一致：会话→任务→操作。先做归属过滤再加锁，避免泄露他人标识。 */
    private Locked lockOwnedTask(Actor actor,UUID taskId) {
        jdbc.execute("set local lock_timeout = '3s'");
        var source=ownedTask(actor,taskId,false);
        var rows=jdbc.query("select mode,version from ai.cs_conversation where id=? and tenant_id=? and user_id=? for update",
            (r,n)->Map.entry(r.getString("mode"),r.getLong("version")),source.conversationId(),actor.tenantId(),actor.userId());
        if(rows.isEmpty())throw notFound();var c=rows.get(0);return new Locked(ownedTask(actor,taskId,true),c.getKey(),c.getValue());
    }
    private Task ownedTask(Actor actor,UUID id,boolean lock) {
        requireActor(actor);Objects.requireNonNull(id);
        return jdbc.query("select * from ai.cs_draft_task where task_id=? and tenant_id=? and user_id=?"+(lock?" for update":""),TASK,id,actor.tenantId(),actor.userId())
            .stream().findFirst().orElseThrow(IdempotentSubmissionService::notFound);
    }
    private Operation ownedOperation(Actor actor,UUID id,boolean lock) {
        requireActor(actor);Objects.requireNonNull(id);
        return jdbc.query(OP_SELECT+" where o.operation_id=? and t.tenant_id=? and t.user_id=?"+(lock?" for update of o":""),OPERATION,id,actor.tenantId(),actor.userId())
            .stream().findFirst().orElseThrow(IdempotentSubmissionService::notFound);
    }
    /** 只在首次执行/批准时要求接待仍是准备时的 BOT；回执回放仅核对当前访问归属。 */
    private void requireBot(Locked locked,Operation op) {
        if(!locked.mode().equals("BOT")||(op!=null&&locked.receptionVersion()!=op.receptionVersion()))throw conflict("接待状态已变化，本次首次执行授权不可用");
    }
    private String currentConfirmedBody(Task task,long version) {
        if(!task.status().equals("CANDIDATE_UNVALIDATED")||!DraftTaskRepository.PROFILE.equals(task.profile())||task.draftVersion()!=version||task.runId()==null)
            throw conflict("任务或草稿已变化，不能首次执行这次提交");
        var rows=jdbc.queryForList("""
            select r.body_json::text from ai.cs_draft_revision r join ai.cs_draft_confirmation c using(task_id,draft_version)
            where r.task_id=? and r.draft_version=? and r.basis_run_id=? and c.confirmed_by=? and c.scope='DRAFT_CONTENT_ONLY'
            """,String.class,task.id(),version,task.runId(),task.userId());
        if(rows.size()!=1)throw conflict("没有当前有效的草稿内容确认");return rows.get(0);
    }
    private List<Receipt> receipts(UUID id){return jdbc.query("select operation_id,application_id,draft_version,created_at from ai.cs_after_sale_application where operation_id=?",RECEIPT,id);}
    private void requireNotExpired(UUID id){if(!Boolean.TRUE.equals(jdbc.queryForObject("select expires_at>clock_timestamp() from ai.cs_submit_operation where operation_id=?",Boolean.class,id)))throw conflict("本次提交授权已过期，不能首次执行");}
    private void requireNoApplication(UUID id){if(jdbc.queryForObject("select count(*) from ai.cs_after_sale_application where task_id=?",Long.class,id)>0)throw conflict("该任务已创建申请，不能另建第二份");}
    private static void requireActor(Actor actor){if(actor==null||actor.userId()<=0)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}
    private static void requireOne(int count){if(count!=1)throw new IllegalStateException("数据库状态变化与预期不符");}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的提交操作或任务");}
}
