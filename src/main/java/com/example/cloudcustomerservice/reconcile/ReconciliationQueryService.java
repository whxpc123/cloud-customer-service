package com.example.cloudcustomerservice.reconcile;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import com.example.cloudcustomerservice.security.HandoffIdentity;
import com.example.cloudcustomerservice.outbox.RemoteAfterSaleClient;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 核查人员的租户视图：仅返回标识、结论和时间，不返回原事件正文或服务凭证。 */
@Service @Profile("local & knowledge")
@PreAuthorize("hasAuthority('support:reconcile')")
@Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=3)
public class ReconciliationQueryService {
    public record Event(UUID eventId, UUID applicationId, UUID operationId, String orderNo, String status,
            int attemptCount, long reconcileVersion, String remoteApplicationId, String lastErrorCode,
            OffsetDateTime createdAt, OffsetDateTime deliveredAt) { }
    public record Audit(UUID checkId, UUID eventId, long requestedBy, long expectedVersion, String status,
            String finding, String code, String remoteId, String stateBefore, String stateAfter, boolean repaired,
            OffsetDateTime startedAt, OffsetDateTime completedAt) { }
    public record Detail(Event event, List<Audit> audits, boolean lookupConfigured) { }
    public record Page(List<Event> events, long total, int page, int pageSize, boolean lookupConfigured) { }
    public record Summary(long review, Double oldestEventSeconds, long repaired, long started,
            long longStarted, Map<String,Long> findings) { }
    private final JdbcTemplate jdbc;
    private final ObjectProvider<RemoteAfterSaleClient> remote;
    public ReconciliationQueryService(JdbcTemplate jdbc,ObjectProvider<RemoteAfterSaleClient> remote){this.jdbc=jdbc;this.remote=remote;}
    private static final String COLUMNS="e.*,a.order_no";
    private static final String JOIN=" FROM ai.cs_outbox e JOIN ai.cs_after_sale_application a ON a.application_id=e.application_id AND a.tenant_id=e.tenant_id";

    /** 按当前租户分页，每页最多25项；查询不会领取、投递或修改 REVIEW。 */
    public Page list(Actor actor,String status,int page) {
        HandoffIdentity.requireSame(actor);
        if(!Set.of("REVIEW","DELIVERED","PENDING","SENDING","ALL").contains(status)||page<1||page>100000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"筛选条件不合法");
        String where=" WHERE e.tenant_id=? AND e.destination='AFTER_SALE_V1'"+(status.equals("ALL")?"":" AND e.status=?");
        var params=new ArrayList<Object>();params.add(actor.tenantId());if(!status.equals("ALL"))params.add(status);
        long total=jdbc.queryForObject("SELECT count(*)"+JOIN+where,Long.class,params.toArray());
        params.add((page-1)*25);
        var rows=jdbc.query("SELECT "+COLUMNS+JOIN+where+" ORDER BY e.created_at DESC,e.event_id LIMIT 25 OFFSET ?",this::event,params.toArray());
        return new Page(rows,total,page,25,remote.getIfAvailable()!=null);
    }

    /** 详情按租户再次过滤；完成记录与未完成 STARTED 均可查，刷新不会重新执行核查。 */
    public Detail detail(Actor actor,UUID eventId) {
        HandoffIdentity.requireSame(actor);
        var events=jdbc.query("SELECT "+COLUMNS+JOIN+" WHERE e.tenant_id=? AND e.event_id=? AND e.destination='AFTER_SALE_V1'",this::event,actor.tenantId(),eventId);
        if(events.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的事件");
        var audits=jdbc.query("""
            SELECT *,observation_json->>'code' AS code,observation_json->>'remoteId' AS remote_id
            FROM ai.cs_outbox_reconciliation WHERE tenant_id=? AND event_id=?
            ORDER BY started_at DESC,check_id DESC LIMIT 50
            """,(r,n)->new Audit(r.getObject("check_id",UUID.class),r.getObject("event_id",UUID.class),
                r.getLong("requested_by"),r.getLong("expected_version"),r.getString("status"),r.getString("finding"),
                r.getString("code"),r.getString("remote_id"),r.getString("state_before"),r.getString("state_after"),r.getBoolean("repaired"),
                r.getObject("started_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class)),actor.tenantId(),eventId);
        return new Detail(events.get(0),audits,remote.getIfAvailable()!=null);
    }

    /** 基础观察指标按当前租户全部历史统计；等待年龄以原事件创建时间计，并非 REVIEW 转入时间。 */
    public Summary summary(Actor actor) {
        HandoffIdentity.requireSame(actor);
        var counts=jdbc.queryForMap("""
            SELECT count(*) AS review,max(extract(epoch FROM clock_timestamp()-created_at)) AS age
            FROM ai.cs_outbox WHERE tenant_id=? AND status='REVIEW'
            """,actor.tenantId());
        var audits=jdbc.queryForMap("""
            SELECT count(*) FILTER(WHERE repaired) AS repaired,count(*) FILTER(WHERE status='STARTED') AS started,
              count(*) FILTER(WHERE status='STARTED' AND started_at<clock_timestamp()-interval '5 minutes') AS old
            FROM ai.cs_outbox_reconciliation WHERE tenant_id=?
            """,actor.tenantId());
        var findings=new TreeMap<String,Long>();
        jdbc.query("SELECT coalesce(finding,'STARTED') AS finding,count(*) FROM ai.cs_outbox_reconciliation WHERE tenant_id=? GROUP BY finding",
                r->{findings.put(r.getString(1),r.getLong(2));},actor.tenantId());
        long stale=jdbc.queryForObject("SELECT count(*) FROM ai.cs_outbox_reconciliation WHERE tenant_id=? AND status='STALE'",Long.class,actor.tenantId());
        findings.put("STALE",stale);
        return new Summary(((Number)counts.get("review")).longValue(),counts.get("age")==null?null:((Number)counts.get("age")).doubleValue(),
                ((Number)audits.get("repaired")).longValue(),((Number)audits.get("started")).longValue(),((Number)audits.get("old")).longValue(),findings);
    }
    private Event event(java.sql.ResultSet r,int n)throws java.sql.SQLException {
        return new Event(r.getObject("event_id",UUID.class),r.getObject("application_id",UUID.class),r.getObject("operation_id",UUID.class),
                r.getString("order_no"),r.getString("status"),r.getInt("attempt_count"),r.getLong("reconcile_version"),
                r.getString("remote_application_id"),r.getString("last_error_code"),r.getObject("created_at",OffsetDateTime.class),r.getObject("delivered_at",OffsetDateTime.class));
    }
}
