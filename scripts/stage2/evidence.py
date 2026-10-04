"""第二阶段只读证据断言。缺失、重复、错身份、错正文均失败；不能以空集合算通过。"""
PASS, FAIL, BLOCKED, NOT_RUN = 'PASS', 'FAIL', 'BLOCKED', 'NOT_RUN'


def verify(expected, sender, receiver, milestone):
    """expected 必须在执行前冻结；本函数不从事后数据库反推应该是什么。"""
    checks = {}
    def check(name, condition):
        checks[name] = PASS if condition else FAIL
    def one(rows, label):
        check(label + '_exactly_one', isinstance(rows, list) and len(rows) == 1)
        return rows[0] if isinstance(rows, list) and len(rows) == 1 else {}
    task = one(sender.get('tasks'), 'task')
    op = one(sender.get('operations'), 'operation')
    app = one(sender.get('applications'), 'local_application')
    event = one(sender.get('outbox'), 'outbox')
    inbox = one(receiver.get('inbox'), 'inbox')
    remote = one(receiver.get('applications'), 'remote_application')
    revision = one([r for r in sender.get('revisions', []) if r.get('draft_version') == 2], 'v2')
    confirm = one(sender.get('confirmations'), 'confirmation')
    check('owner_and_order', all(task.get(k) == expected[k] for k in ['task_id','tenant_id','user_id','order_no']))
    check('closed_not_refunded', task.get('status') == 'CLOSED' and app.get('status') == remote.get('status') == 'PENDING_REVIEW')
    check('approved_exact_v2', op.get('task_id') == expected['task_id'] and op.get('draft_version') == 2
          and op.get('status') == 'SUCCEEDED' and op.get('decided_by') == expected['user_id']
          and op.get('delivery_profile') == 'AFTER_SALE_V1')
    check('content_confirmation_scope', confirm.get('task_id') == expected['task_id'] and confirm.get('draft_version') == 2
          and confirm.get('confirmed_by') == expected['user_id'] and confirm.get('scope') == 'DRAFT_CONTENT_ONLY')
    check('exact_immutable_body', revision.get('body_json') == app.get('body_snapshot') == expected['body'])
    check('local_identity_chain', app.get('operation_id') == expected['operation_id'] == op.get('operation_id')
          and app.get('task_id') == expected['task_id'] and app.get('draft_version') == 2
          and all(app.get(k) == expected[k] for k in ['tenant_id','user_id','order_no'])
          and app.get('application_id') == expected['application_id'] == event.get('application_id')
          and event.get('operation_id') == expected['operation_id'] and event.get('event_id') == expected['event_id'])
    payload = event.get('payload') or {}
    check('exact_event_body', payload == inbox.get('payload') and payload.get('userStatement') == expected['body']['userStatement']
          and payload.get('eventId') == expected['event_id'] and payload.get('applicationId') == expected['application_id']
          and payload.get('operationId') == expected['operation_id'] and payload.get('tenantId') == expected['tenant_id']
          and payload.get('draftVersion') == 2 and payload.get('orderNo') == expected['order_no'])
    check('receiver_identity_chain', remote.get('source_event_id') == inbox.get('event_id') == expected['event_id']
          and remote.get('source_application_id') == expected['application_id'] and remote.get('source_operation_id') == expected['operation_id']
          and remote.get('draft_version') == 2 and remote.get('order_no') == expected['order_no']
          and remote.get('tenant_id') == inbox.get('tenant_id') == expected['tenant_id']
          and remote.get('producer_id') == inbox.get('producer_id') == expected['producer_id']
          and remote.get('user_statement') == expected['body']['userStatement'])
    receipt = inbox.get('receipt') or {}
    check('receiver_persisted_receipt', inbox.get('status') == 'PROCESSED' and receipt.get('status') == 'PERSISTED'
          and receipt.get('eventId') == expected['event_id'] and receipt.get('applicationId') == expected['application_id']
          and bool(remote.get('remote_application_id')) and receipt.get('remoteApplicationId') == remote.get('remote_application_id'))
    check('eight_real_claims', event.get('attempt_count') == 8)
    if milestone == 'REVIEW':
        check('uncertainty_is_honest', event.get('status') == 'REVIEW' and event.get('remote_application_id') is None
              and event.get('last_error_code') == 'HTTP_503')
    elif milestone == 'M4':
        check('remote_receipt_saved', event.get('status') == 'DELIVERED'
              and event.get('remote_application_id') == remote.get('remote_application_id') and bool(event.get('delivered_at')))
        check('audit_applied_once', len(sender.get('checks', [])) == 1 and sender['checks'][0].get('finding') == 'PERSISTED'
              and sender['checks'][0].get('status') == 'RECORDED' and sender['checks'][0].get('repaired') is True and sender['checks'][0].get('requested_by') == 9001)
    else:
        raise ValueError('未知里程碑')
    return checks


def verdict(checks):
    """任何硬失败优先；未执行/阻塞不能通过，被调用方必须提供非空集合。"""
    values = list(checks.values())
    return FAIL if FAIL in values else BLOCKED if BLOCKED in values else NOT_RUN if not values or any(v != PASS for v in values) else PASS
