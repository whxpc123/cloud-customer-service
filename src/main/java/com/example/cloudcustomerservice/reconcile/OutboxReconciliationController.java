package com.example.cloudcustomerservice.reconcile;

import com.example.cloudcustomerservice.security.HandoffIdentity;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 同一账户会话、CSRF 和独立核查权限；租户/操作人取服务器身份，客户端只能指定原事件。 */
@RestController @Profile("local & knowledge")
public class OutboxReconciliationController {
    private final OutboxReconciliationService service;
    private final ReconciliationQueryService queries;
    public OutboxReconciliationController(OutboxReconciliationService service,ReconciliationQueryService queries){this.service=service;this.queries=queries;}
    public record Request(){@JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("核查不接受强制状态或身份参数");}}
    @GetMapping(value="/internal/outbox-reconciliation",produces="text/html;charset=UTF-8")
    public Resource page(){return new ClassPathResource("reconciliation/index.html");}
    @GetMapping("/api/support/outbox") @PreAuthorize("hasAuthority('support:reconcile')")
    public ResponseEntity<ReconciliationQueryService.Page> list(@RequestParam(defaultValue="REVIEW") String status,@RequestParam(defaultValue="1") int page){return safe(queries.list(HandoffIdentity.actor(),status,page));}
    @GetMapping("/api/support/outbox/summary") @PreAuthorize("hasAuthority('support:reconcile')")
    public ResponseEntity<ReconciliationQueryService.Summary> summary(){return safe(queries.summary(HandoffIdentity.actor()));}
    @GetMapping("/api/support/outbox/{eventId}") @PreAuthorize("hasAuthority('support:reconcile')")
    public ResponseEntity<ReconciliationQueryService.Detail> detail(@PathVariable UUID eventId){return safe(queries.detail(HandoffIdentity.actor(),eventId));}
    @PostMapping("/api/support/outbox/{eventId}/reconcile") @PreAuthorize("hasAuthority('support:reconcile')")
    public ResponseEntity<OutboxReconciliationStore.Result> reconcile(@PathVariable UUID eventId,@RequestBody Request ignored)throws Exception {
        if(ignored==null)throw new IllegalArgumentException("需要空对象请求");
        return safe(service.reconcile(HandoffIdentity.actor(),eventId));
    }
    private <T> ResponseEntity<T> safe(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
