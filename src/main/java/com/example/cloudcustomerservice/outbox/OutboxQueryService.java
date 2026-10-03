package com.example.cloudcustomerservice.outbox;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 客户只读视图：按申请/操作关联的租户和用户过滤，不提供 eventId 任意查询或重置入口。 */
@Service @Profile("local & knowledge")
@Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
public class OutboxQueryService {
    /** 本地创建、远端投递各有自己的事实；不返回租约凭证、服务令牌或事件正文。 */
    public record Delivery(String deliveryProfile,String status,UUID eventId,UUID applicationId,
            int attemptCount,OffsetDateTime nextAttemptAt,OffsetDateTime leaseUntil,
            String remoteApplicationId,OffsetDateTime deliveredAt,String lastErrorCode,boolean relayEnabled) { }
    /** 所有数字仅统计当前客户全部历史事件；平均耗时只计算已确认样本。 */
    public record Summary(long pending,long sending,long delivered,long review,Double oldestPendingSeconds,
            Double averageDeliverySeconds,Map<Integer,Long> attemptDistribution,boolean relayEnabled) { }
    private final JdbcTemplate jdbc;
    private final Environment environment;
    public OutboxQueryService(JdbcTemplate jdbc,Environment environment){this.jdbc=jdbc;this.environment=environment;}

    /** 即使尚未创建申请，也先校验操作归属；旧本地操作不会伪装成已投递。 */
    public Delivery delivery(Actor actor,UUID operationId) {
        requireActor(actor);
        var rows=jdbc.query("""
            select o.delivery_profile,a.application_id,e.* from ai.cs_submit_operation o
            join ai.cs_draft_task t on t.task_id=o.task_id
            left join ai.cs_after_sale_application a on a.operation_id=o.operation_id
                and a.tenant_id=t.tenant_id and a.user_id=t.user_id
            left join ai.cs_outbox e on e.application_id=a.application_id
            where o.operation_id=? and t.tenant_id=? and t.user_id=?
            """,(r,n)->{
                String profile=r.getString("delivery_profile"),status=r.getString("status");
                UUID app=r.getObject("application_id",UUID.class);
                if(status==null)status=app==null?"NOT_CREATED":profile.equals("LOCAL_ONLY")?"LOCAL_ONLY":"MISSING_EVENT";
                return new Delivery(profile,status,r.getObject("event_id",UUID.class),app,r.getInt("attempt_count"),
                    r.getObject("next_attempt_at",OffsetDateTime.class),r.getObject("lease_until",OffsetDateTime.class),
                    r.getString("remote_application_id"),r.getObject("delivered_at",OffsetDateTime.class),r.getString("last_error_code"),enabled());
            },operationId,actor.tenantId(),actor.userId());
        return rows.stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的提交操作"));
    }

    /** 只读监控不会扫描其他客户，也不领取、补发或修改任何记录。 */
    public Summary summary(Actor actor) {
        requireActor(actor);
        String scope=" from ai.cs_outbox e join ai.cs_after_sale_application a on a.application_id=e.application_id where a.tenant_id=? and a.user_id=?";
        var distribution=new TreeMap<Integer,Long>();
        jdbc.query("select e.attempt_count,count(*) as total"+scope+" group by e.attempt_count",r->{distribution.put(r.getInt(1),r.getLong(2));},actor.tenantId(),actor.userId());
        return jdbc.queryForObject("""
            select count(*) filter(where e.status='PENDING') as pending,
                count(*) filter(where e.status='SENDING') as sending,
                count(*) filter(where e.status='DELIVERED') as delivered,
                count(*) filter(where e.status='REVIEW') as review,
                max(extract(epoch from clock_timestamp()-e.created_at)) filter(where e.status='PENDING') as oldest,
                avg(extract(epoch from e.delivered_at-e.created_at)) filter(where e.status='DELIVERED') as duration
            """+scope,(r,n)->new Summary(r.getLong("pending"),r.getLong("sending"),r.getLong("delivered"),r.getLong("review"),
                r.getObject("oldest")==null?null:r.getDouble("oldest"),r.getObject("duration")==null?null:r.getDouble("duration"),Map.copyOf(distribution),enabled()),actor.tenantId(),actor.userId());
    }
    private boolean enabled(){return environment.matchesProfiles("outbox-delivery");}
    private static void requireActor(Actor actor){if(actor==null||actor.userId()<=0)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}
}
