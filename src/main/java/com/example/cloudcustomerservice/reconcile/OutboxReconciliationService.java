package com.example.cloudcustomerservice.reconcile;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import com.example.cloudcustomerservice.outbox.RemoteAfterSaleClient;

import static com.example.cloudcustomerservice.reconcile.ReconcileModel.*;

/** 人工发起一次核查的编排器：开始留痕、无事务网络查询、带版本原子落库。 */
@Service
@Profile("local & knowledge")
public class OutboxReconciliationService {

    private final OutboxReconciliationStore store;
    private final org.springframework.beans.factory.ObjectProvider<RemoteAfterSaleClient> clients;

    public OutboxReconciliationService(
            OutboxReconciliationStore store,
            org.springframework.beans.factory.ObjectProvider<RemoteAfterSaleClient> clients) {

        this.store = store;
        this.clients = clients;
    }

    /** NEVER 禁止调用方把整个流程包进事务；权限与租户身份必须同时验证。 */
    @PreAuthorize("hasAuthority('support:reconcile')")
    @Transactional(propagation = Propagation.NEVER)
    public OutboxReconciliationStore.Result reconcile(
            Actor operator,
            UUID eventId) throws JsonProcessingException {

        com.example.cloudcustomerservice.security.HandoffIdentity.requireSame(operator);
        var work = store.begin(operator, eventId);

        Evidence evidence;

        try {
            RemoteAfterSaleClient remote = clients.getIfAvailable();
            if (remote == null) throw new RemoteAfterSaleClient.DeliveryFailure("LOOKUP_NOT_CONFIGURED", false);
            Reply reply = remote.lookup(
                    work.eventId(),
                    work.payload()
            );

            evidence = ReconcileModel.inspect(
                    work.eventId(),
                    work.applicationId(),
                    reply
            );
        }
        catch (RemoteAfterSaleClient.DeliveryFailure ex) {
            evidence = new Evidence(
                    Finding.QUERY_UNAVAILABLE,
                    null,
                    ex.code()
            );
        }
        catch (RuntimeException ex) {
            evidence = new Evidence(
                    Finding.QUERY_UNAVAILABLE,
                    null,
                    "UNEXPECTED_LOOKUP_ERROR"
            );
        }

        /*
         * 放在远端调用的 try/catch 之外。
         * 本地保存失败，不能误报成“远端查询失败”。
         */
        return store.record(work, evidence);
    }
}
