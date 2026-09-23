package com.example.cloudcustomerservice.aftersale;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 明确的本地教学适配器，不是真实订单系统。固定日期不代表当前订单状态；不会改写第五章数据。 */
@Component
@Profile("local & knowledge")
public class LocalOrderFactsReader implements OrderFactsReader {
    private record Owned(String tenant,long user,OrderFacts facts) { }
    private final Map<String,Owned> samples=Map.of(
        "A10001",new Owned("tenant-yunshan",1001,facts("A10001","ORDINARY",QualityVerification.UNVERIFIED,"3.2")),
        "A10002",new Owned("tenant-yunshan",2002,facts("A10002","ORDINARY",QualityVerification.UNVERIFIED,"3.2")),
        "A10003",new Owned("tenant-yunshan",1001,facts("A10003","ACTIVATED_SOFTWARE",QualityVerification.UNVERIFIED,"software-not-loaded")),
        "A10004",new Owned("tenant-yunshan",1001,facts("A10004","ORDINARY",QualityVerification.CONFIRMED,"3.2")),
        "A10005",new Owned("tenant-yunshan",1001,facts("A10005","ORDINARY",QualityVerification.UNVERIFIED,"not-loaded")));
    /** 三个字段一起匹配，不暴露“不存在”和“属于他人”的差异。 */
    @Override public Optional<OrderFacts> findOwned(Actor actor,String orderNo){
        var row=samples.get(orderNo);return row!=null&&row.tenant().equals(actor.tenantId())&&row.user()==actor.userId()?Optional.of(row.facts()):Optional.empty();
    }
    private static OrderFacts facts(String id,String type,QualityVerification quality,String version){return new OrderFacts(id,type,
        OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),OffsetDateTime.parse("2026-08-27T23:59:59+08:00"),quality,"refund-policy",version);}
}
