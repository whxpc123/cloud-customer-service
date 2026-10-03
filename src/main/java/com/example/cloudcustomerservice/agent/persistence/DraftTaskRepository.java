package com.example.cloudcustomerservice.agent.persistence;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 所有写操作都是三秒短事务；运行资格依赖数据库 CAS，不依赖某个 JVM 的锁。 */
@Repository @Profile("local & knowledge")
@Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
public class DraftTaskRepository {
    public static final String PROFILE="after-sale-draft-v1-saa1.1.2.2";
    /** 仅内部使用：包含恢复关联和完成快照，控制器不得直接序列化这条记录。 */
    public record TaskRow(UUID taskId,String tenantId,long userId,UUID conversationId,String threadId,
            String orderNo,ReturnReason reason,String agentProfile,String status,long version,int turnNo,
            UUID runId,UUID lastCheckpointId,String lastResultJson,Instant createdAt,Instant updatedAt) { }
    private static final RowMapper<TaskRow> ROW=(rs,n)->new TaskRow(rs.getObject("task_id",UUID.class),rs.getString("tenant_id"),
            rs.getLong("user_id"),rs.getObject("conversation_id",UUID.class),rs.getString("thread_id"),rs.getString("order_no"),
            ReturnReason.valueOf(rs.getString("reason")),rs.getString("agent_profile"),rs.getString("status"),rs.getLong("version"),
            rs.getInt("turn_no"),rs.getObject("run_id",UUID.class),rs.getObject("last_checkpoint_id",UUID.class),
            rs.getString("last_result_json"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    public DraftTaskRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    /** 全局活动任务上限 100；事务级锁只保护数量与创建，不覆盖任何模型等待。 */
    public TaskRow create(Actor actor,UUID conversation,String order,ReturnReason reason) {
        jdbc.queryForObject("select pg_advisory_xact_lock(2210022)",Object.class);
        if(jdbc.queryForObject("select count(*) from ai.cs_draft_task where status <> 'CLOSED'",Long.class)>=100)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"活动任务已达 100 项，请先结束可结束的任务");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into ai.cs_draft_task(task_id,tenant_id,user_id,conversation_id,thread_id,order_no,reason,agent_profile)
                values(?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.userId(),conversation,"after-sale:persistent:v1:"+UUID.randomUUID(),order,reason.name(),PROFILE);
        return owned(actor,id);
    }
    /** 所有权必须先于检查点读取；404 不透露他人任务是否存在。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=3)
    public TaskRow owned(Actor actor,UUID id) {
        return jdbc.query("select * from ai.cs_draft_task where task_id=? and tenant_id=? and user_id=?",ROW,id,actor.tenantId(),actor.userId())
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的持久化任务"));
    }
    /** 有界列表，包含已结束的历史；只向服务层交付，外部响应会移除内部 threadId 与完整快照。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=3)
    public List<TaskRow> list(Actor actor) {
        return jdbc.query("select * from ai.cs_draft_task where tenant_id=? and user_id=? order by created_at desc,task_id limit 100",ROW,actor.tenantId(),actor.userId());
    }
    /** 版本、契约、任务状态与会话状态一次条件更新；两个旧版本请求只能有一个领取成功。 */
    public TaskRow claim(Actor actor,UUID id,long expectedVersion,long conversationVersion,UUID runId) {
        owned(actor,id);
        var rows=jdbc.query("""
                update ai.cs_draft_task t set status='RUNNING',run_id=?,turn_no=turn_no+1,version=version+1,updated_at=current_timestamp
                where task_id=? and tenant_id=? and user_id=? and version=? and agent_profile=? and turn_no<8
                and status in ('READY','CANDIDATE_UNVALIDATED','NEEDS_ATTENTION')
                and exists(select 1 from ai.cs_conversation c where c.id=t.conversation_id and c.tenant_id=t.tenant_id
                    and c.user_id=t.user_id and c.mode='BOT' and c.version=?) returning t.*
                """,ROW,runId,id,actor.tenantId(),actor.userId(),expectedVersion,PROFILE,conversationVersion);
        if(rows.size()!=1)throw conflict();return rows.get(0);
    }
    /** 只在当前运行仍拥有同一版本时提交成功；检查点使用独立连接，必须由调用方先确认已落库。 */
    public void finish(TaskRow task,String outcome,UUID checkpoint,String json) {
        if(!Set.of("CANDIDATE_UNVALIDATED","NEEDS_ATTENTION").contains(outcome))throw new IllegalArgumentException("无效完成状态");
        int n=jdbc.update("""
                update ai.cs_draft_task set status=?,last_checkpoint_id=?,last_result_json=cast(? as jsonb),version=version+1,updated_at=current_timestamp
                where task_id=? and tenant_id=? and user_id=? and status='RUNNING' and version=? and run_id=?
                """,outcome,checkpoint,json,task.taskId(),task.tenantId(),task.userId(),task.version(),task.runId());
        if(n!=1)throw conflict();
    }
    /** 保留最近正常完成的结果，不把部分图状态冒充新结果；数据库失败时 RUNNING 也不允许继续。 */
    public void requireRecovery(TaskRow task){transitionRun(task,"RECOVERY_REQUIRED");}
    /** 会话交接或注销期间的迟到结果不能发布，业务任务关闭；保留历史便于排查。 */
    public void closeRun(TaskRow task){transitionRun(task,"CLOSED");}
    private void transitionRun(TaskRow task,String status){
        jdbc.update("""
                update ai.cs_draft_task set status=?,version=version+1,updated_at=current_timestamp
                where task_id=? and tenant_id=? and user_id=? and status='RUNNING' and version=? and run_id=?
                """,status,task.taskId(),task.tenantId(),task.userId(),task.version(),task.runId());
    }
    /** 显式结束正常任务，保留数据库历史。RUNNING/RECOVERY_REQUIRED 不提供自动解锁或释放线程入口。 */
    public void close(Actor actor,UUID id,long version) {
        owned(actor,id);
        int n=jdbc.update("""
                update ai.cs_draft_task set status='CLOSED',version=version+1,updated_at=current_timestamp
                where task_id=? and tenant_id=? and user_id=? and version=?
                and status in ('READY','CANDIDATE_UNVALIDATED','NEEDS_ATTENTION')
                """,id,actor.tenantId(),actor.userId(),version);
        if(n!=1)throw conflict();
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"任务版本、契约或状态不允许操作，请先刷新；未确认完成的运行需要核查，不能自动重跑");}
}
