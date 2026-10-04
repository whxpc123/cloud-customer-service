/* 仅展示版本附带或用户选择的验收快照。所有外部字段通过 textContent 写入。 */
'use strict';
const $ = id => document.getElementById(id);
const labels = {PASS:'通过',FAIL:'失败',BLOCKED:'阻塞',NOT_RUN:'未执行',NOT_EXECUTED:'未执行'};
const cases = {correction:'采用最新更正，删除旧描述',negation:'保留“还没有激活”的否定词',prepare_only:'先准备，不要提交',photo_claim:'照片只是用户声明',other_order:'他人订单停止处理',confirm_not_send:'确认内容，不等于发出授权'};
const milestones = ['内容 V2 已明确确认','本地申请与 Outbox 已提交','接收端 Inbox 与申请已提交','发送方已保存核验后的回执'];
const checkNames = {
  "sender":"发送端业务包与实测版本一致",
  "receiver":"接收端业务包与实测版本一致",
  "v1_candidate": "V1 候选生成",
  "v1_semantic": "V1 保留原始描述",
  "draft_survives_jvm_restart": "草稿跨 JVM 重启恢复",
  "v2_candidate": "V2 候选生成",
  "latest_user_meaning": "更正描述与未核验事实分开",
  "stale_v1_confirmation_denied": "旧版 V1 确认被拒绝",
  "no_approval_no_submission": "没有批准不得提交",
  "other_customer_denied": "他人账户不可访问",
  "real_csrf_required": "真实 HTTP 必须携带 CSRF",
  "concurrent_submit_observed": "并发提交后读取权威回执",
  "m2_pending_without_relay": "停用投递时保留待发送状态",
  "eight_ack_losses": "八次真实成功回执被屏蔽",
  "review_survives_restart": "待核查状态跨重启保留",
  "customer_cannot_reconcile": "普通客户不能执行核查",
  "closed_task_receipt_replay": "已关闭任务回放原回执",
  "reconcile_never_creates": "核查不增加创建请求",
  "task_exactly_one": "任务恰好一份",
  "operation_exactly_one": "操作恰好一份",
  "local_application_exactly_one": "本地申请恰好一份",
  "outbox_exactly_one": "Outbox 事件恰好一份",
  "inbox_exactly_one": "Inbox 记录恰好一份",
  "remote_application_exactly_one": "远端申请恰好一份",
  "v2_exactly_one": "V2 正文恰好一份",
  "confirmation_exactly_one": "确认回执恰好一份",
  "owner_and_order": "所有者与订单范围一致",
  "closed_not_refunded": "任务关闭，两端仍待审核",
  "approved_exact_v2": "批准绑定精确 V2 与投递范围",
  "content_confirmation_scope": "内容确认没有扩权",
  "exact_immutable_body": "完整正文逐字段一致",
  "local_identity_chain": "本地身份链一致",
  "exact_event_body": "事件正文与 Inbox 一致",
  "receiver_identity_chain": "远端来源编号与用户描述一致",
  "receiver_persisted_receipt": "接收端已持久化原回执",
  "eight_real_claims": "八次真实领取记录",
  "uncertainty_is_honest": "未知结果保持待核查",
  "remote_receipt_saved": "本地保存正确远端回执",
  "audit_applied_once": "授权核查审计只应用一次",
  "draft_version_and_publish_races": "草稿版本与发布竞争",
  "approval_scope_expiry_and_submit_races": "授权期限与提交竞争",
  "application_outbox_atomicity_and_lease_fencing": "事务原子性与旧租约防护",
  "reconciliation_atomicity_permissions_and_stale_results": "核查事务、权限与旧结果防护",
  "persistent_draft_process_restart": "持久化草稿进程恢复",
  "receipt_outbox_reconciliation_process_restart": "回执、投递与核查进程恢复",
  "hitl_lab_only_timeout_and_late_result": "HITL 实验超时与迟到结果",
  "receiver_InboxIntegrationTest": "独立接收方数据库与 JWT 合同",
  "receiver_InboxProtocolTest": "接收协议校验",
  "sender_all_observed": "发送端本轮回归",
  "receiver_all_observed": "接收端本轮回归"
};
let report = null;
function node(tag, text, cls) { const e=document.createElement(tag);e.textContent=text;if(cls)e.className=cls;return e; }
function badge(value) { return node('span',labels[value] || '未执行','badge '+String(value).toLowerCase()); }
function displayTime(value) { const t=new Date(value);return Number.isNaN(t.getTime())?'未记录':t.toLocaleString('zh-CN',{hour12:false}); }
function render(data, source) {
  if(data.schemaVersion!==1 || typeof data.runId!=='string' || !data.checks || Array.isArray(data.checks)
      || typeof data.checks!=='object' || !Array.isArray(data.milestones) || !Array.isArray(data.modelCases)
      || Object.keys(data.checks).length>500 || data.modelCases.length>100 || data.milestones.length>20
      || !Object.hasOwn(labels,data.businessGate) || Object.values(data.checks).some(v=>!Object.hasOwn(labels,v))) throw new Error('报告结构不匹配');
  // 先完整校验会被渲染的行，错误报告不能半途覆盖已显示的结果。
  if(data.milestones.some(row=>!row || typeof row.name!=='string' || typeof row.at!=='string' || typeof row.detail!=='string')
      || data.modelCases.some(row=>!row || typeof row.id!=='string' || !Object.hasOwn(labels,row.status))
      || (data.limits!==undefined && (!Array.isArray(data.limits) || data.limits.some(v=>typeof v!=='string')))) throw new Error('报告行结构不匹配');
  report=data;
  $('notice').textContent=source+' · 验收时间 '+displayTime(data.finishedAt)+' · 当前服务状态请前往同步核查台查看';
  const states=Object.values(data.checks);const declared=data.engineeringGate||data.businessGate;
  const gate=states.includes('FAIL')?'FAIL':declared==='PASS'&&(!states.length||states.some(v=>v!=='PASS'))?'NOT_RUN':declared;$('gate').textContent=labels[gate]||'未执行';$('gate').style.color=gate==='PASS'?'var(--teal)':gate==='FAIL'?'#aa3444':'var(--amber)';
  $('milestones').replaceChildren(...milestones.map((title,index)=>{
    const m=data.milestones.find(v=>v.name==='M'+(index+1));const li=node('li','');
    li.append(node('span','M'+(index+1),'step'),node('strong',title),node('small',m?displayTime(m.at)+' · '+m.detail:'未观察到此里程碑'));return li;
  }));
  const expected=data.identity || data.expected || {};
  const fields=[['运行编号',data.runId],['任务 taskId',expected.task_id],['用户 / 订单 / 草稿版本',`${expected.user_id || '—'} / ${expected.order_no || '—'} / V${expected.draft_version || '—'}`],['操作 operationId',expected.operation_id],['事件 eventId',expected.event_id],['创建请求 / 屏蔽回执 / 查询请求',data.proxy?`${data.proxy.createRequests} / ${data.proxy.suppressed} / ${data.proxy.lookupRequests}`:'未记录'],['代码基线',data.manifest?.baseCommit]];
  $('identity').replaceChildren(...fields.flatMap(([label,value])=>[node('dt',label),node('dd',String(value || '未记录'))]));
  $('models').replaceChildren(...data.modelCases.map(row=>{
    const div=node('div','','model'),top=node('div','','model-top');top.append(node('span',cases[row.id]||row.id),badge(row.status));div.append(top,
      node('p','Codex 语义复核：'+(labels[row.semanticReview]||'未执行')+'。'+(row.reviewNote||'实际措辞见本轮受限模型记录。')));return div;
  }));
  $('limits').replaceChildren(...(Array.isArray(data.limits)?data.limits:[]).slice(0,20).map(v=>node('li',String(v))));
  renderChecks();
}
function renderChecks() {
  if(!report)return;
  const entries=Object.entries(report.checks).filter(([,v])=>!$('only-issues').checked||v!=='PASS');
  $('checks').replaceChildren(...entries.map(([name,value])=>{const row=node('div','','check');const parts=name.split('.');const title=(parts[0]==='review'?'核查前 · ':parts[0]==='m4'?'核查后 · ':parts[0]==='component'?'组件 · ':'')+(checkNames[parts.at(-1)]||name);row.title=name;row.append(node('span',title),badge(value));return row;}));
  if(!entries.length)$('checks').append(node('p','当前筛选没有未通过项。语义复核和发布阻塞仍需单独查看。'));
}
$('only-issues').addEventListener('change',renderChecks);
$('report-file').addEventListener('change',async event=>{
  const file=event.target.files[0];if(!file)return;
  try{if(file.size>1024*1024)throw new Error('报告超过 1 MB');render(JSON.parse(await file.text()),'本地文件 '+file.name);}
  catch(error){$('notice').textContent='无法载入：'+error.message+'。保留当前已显示的报告。';}
});
fetch('/stage-two-report.json',{cache:'no-store'}).then(r=>{if(!r.ok)throw new Error('未附带验收记录，请载入本轮 report.json');return r.json();})
  .then(data=>render(data,'随版本保存的验收记录')).catch(error=>{$('notice').textContent=error.message;});
