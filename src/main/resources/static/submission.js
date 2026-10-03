/** 第26～27章：浏览器保存选中的操作编号，不生成幂等编号、不保存批准状态、不自动重放写请求。 */
const $=id=>document.getElementById(id),base='/internal/draft-tasks/submission/operations';
const statuses={PENDING_APPROVAL:'等待提交批准',APPROVED:'已批准 · 待执行',REJECTED:'已拒绝',SUCCEEDED:'已创建待审核申请'};
let allowed=false,busy=false,csrf='',csrfHeader='',selected='',detail=null,priorReceipt=null;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
if(uuid.test(location.hash.slice(1)))selected=location.hash.slice(1);
const params=new URLSearchParams(location.search);
if(uuid.test(params.get('taskId')||''))$('task-id').value=params.get('taskId');
if(/^[1-9][0-9]*$/.test(params.get('draftVersion')||''))$('draft-version').value=params.get('draftVersion');
const time=value=>value?new Date(value).toLocaleString():'尚无决定';
/** 网络错误不换号、不重试；每次写入只发送固定 DTO，所有动态展示均使用 textContent。 */
async function api(path,method='GET',body){
  const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:method==='GET'?{}:{'Content-Type':'application/json',[csrfHeader]:csrf},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const data=await response.json().catch(()=>({}));
  if(!response.ok){const error=new Error(data.message||`请求未完成（HTTP ${response.status}）`);error.status=response.status;throw error;}return data;
}
function controls(){
  $('prepare-fields').disabled=busy||!allowed;$('refresh').disabled=busy;
  const op=detail?.operation, pending=op?.status==='PENDING_APPROVAL';
  $('accepted').disabled=busy||!allowed||!pending||!detail.firstExecutionEligible;
  $('approve').disabled=busy||!allowed||!pending||!detail.firstExecutionEligible||!$('accepted').checked;
  $('reject').disabled=busy||!allowed||!pending;
  $('execute').disabled=busy||!allowed||!op||!(op.status==='SUCCEEDED'||op.status==='APPROVED'&&detail.firstExecutionEligible);
  $('query').disabled=busy||!allowed||!selected;
  $('refresh-delivery').disabled=busy||!allowed||!selected;
  $('execute').textContent=op?.status==='SUCCEEDED'?'再次执行原操作 · 回放回执':'执行同一笔操作';
  document.querySelectorAll('.choice').forEach(b=>b.disabled=busy);$('content').setAttribute('aria-busy',String(busy));
}
function clear(){detail=null;priorReceipt=null;$('accepted').checked=false;$('operation-detail').hidden=true;$('receipt').hidden=true;$('delivery').hidden=true;$('operation-title').textContent='选择或准备一笔操作';$('operation-status').textContent='尚未选择';$('graph-status').textContent='';$('trace').replaceChildren();}
function receipt(value){
  $('receipt').hidden=!value;if(!value)return;
  $('application-id').textContent=value.applicationId;$('created-at').textContent=time(value.createdAt);$('receipt-version').textContent=`V${value.draftVersion}`;
  $('receipt-json').textContent=JSON.stringify(value,null,2);
  $('replay-note').textContent=priorReceipt&&priorReceipt.applicationId===value.applicationId&&priorReceipt.createdAt===value.createdAt?'与上次读取一致：申请编号和创建时间均未改变。':'回执已持久化，可在刷新或重新登录后用原操作编号查询。';priorReceipt=value;
}
function render(view){
  detail=view;const op=view.operation;$('accepted').checked=false;$('operation-detail').hidden=false;
  $('operation-title').textContent=`订单 ${op.orderNo} · 草稿 V${op.draftVersion}`;$('operation-status').textContent=statuses[op.status];
  $('operation-id').textContent=op.operationId;$('bound-task').textContent=op.taskId;$('expires').textContent=`${time(op.expiresAt)}${op.expired?' · 首次执行期限已过':''}`;
  $('decision-meta').textContent=op.decidedBy?`账户 ${op.decidedBy} · ${time(op.decidedAt)}`:'未批准，不能创建申请';
  $('body-title').textContent=`绑定的草稿 V${op.draftVersion}`;$('description').textContent=view.body.userStatement?.userDescription||'未记录';$('handling').textContent=view.body.userStatement?.requestedHandling||'未记录';$('body-json').textContent=JSON.stringify(view.body,null,2);
  const external=op.deliveryProfile==='AFTER_SALE_V1';
  $('scope-description').textContent=external?'本系统创建 + 同步到售后平台 V1（AFTER_SALE_V1）':'仅本系统创建（LOCAL_ONLY），不会生成外部发送任务。';
  $('scope-fields').textContent=external?'发送固定字段：事件/申请/操作编号、租户、订单号、草稿版本、用户问题描述和申请诉求、创建时间。不发送聊天历史、完整订单或 Agent 状态。':'旧操作保持原授权范围。重复准备或升级程序不会自动扩大范围。';
  $('accept-label').textContent=`我允许将订单 ${op.orderNo} 的草稿 V${op.draftVersion} 创建为本系统待审核售后申请${external?"，并将上述字段同步至售后平台 V1":"，仅在本系统保存"}；这不代表审核通过或退款。`;
  $('message').textContent=view.receipt?'已找到数据库创建回执。再次执行原操作会回放这份结果。':op.status==='REJECTED'?'本笔操作已拒绝，不能用原编号改为批准。':!view.firstExecutionEligible?'首次执行条件已失效：可能是期限、任务、草稿或接待状态变化。可以查询原回执；不会替您生成新操作。':op.status==='APPROVED'?'批准已保存。点击“执行同一笔操作”才会创建本地待审核申请。':'请核对这份固定正文，再决定批准或拒绝。';
  receipt(view.receipt);controls();
}
async function load(){if(!selected){clear();return;}render(await api(`${base}/${selected}`));await delivery();}
async function list(){
  const rows=await api(base);$('operation-list').replaceChildren();
  if(!rows.length){const p=document.createElement('p');p.className='fine';p.textContent='暂无提交操作。可以从下方准备一笔，或从草稿页进入。';$('operation-list').append(p);}
  for(const op of rows){const b=document.createElement('button');b.type='button';b.className='choice';b.setAttribute('aria-current',String(op.operationId===selected));const title=document.createElement('span'),id=document.createElement('small');title.textContent=`${op.orderNo} · V${op.draftVersion} · ${statuses[op.status]}`;id.textContent=op.operationId;b.append(title,id);
    b.addEventListener('click',()=>operate(async()=>{clear();selected=op.operationId;history.replaceState(null,'',`${location.pathname}#${selected}`);await load();await list();}));$('operation-list').append(b);}
  await summary();
}
/** 出错时去除可能过时的操作卡，但保留 URL 中的编号供刷新/重登录后核查；不宣称一定失败。 */
async function operate(work){if(busy)return;busy=true;controls();try{await work();}catch(e){clear();if(e.status===401||e.status===403){allowed=false;$('operation-list').replaceChildren();$('queue-summary').textContent='登录已失效，请重新登录。';$('queue-timing').textContent='';$('queue-distribution').textContent='尚未查询';}$('message').textContent=`${e.message} 未自动重试。${selected?'原操作编号已保留，刷新查询结果。':'重复准备同一任务同一版本可找回原操作。'}`;}finally{busy=false;controls();}}
async function refresh(){
  clear();const session=await api('/internal/handoff/session');csrf=session.csrfToken;csrfHeader=session.csrfHeader;allowed=session.authenticated&&session.authorities?.includes('customer:chat');
  $('account').textContent=allowed?`当前账户：${session.username} · 回执保存在数据库中`:'请在统一客服页使用客户账号登录，再返回刷新。';
  if(!allowed){$('operation-list').replaceChildren();$('queue-summary').textContent='请先使用客户账号登录。';$('queue-timing').textContent='';$('queue-distribution').textContent='尚未查询';return;}await list();await load();
}
$('refresh').addEventListener('click',()=>operate(refresh));$('accepted').addEventListener('change',controls);
$('prepare-form').addEventListener('submit',e=>{e.preventDefault();operate(async()=>{
  const id=$('task-id').value.trim(),version=Number($('draft-version').value);if(!uuid.test(id)||!Number.isSafeInteger(version)||version<=0)throw new Error('请输入有效任务编号与正数版本');
  const op=await api(base,'POST',{taskId:id,draftVersion:version,deliveryProfile:$('delivery-profile').value});clear();selected=op.operationId;history.replaceState(null,'',`${location.pathname}#${selected}`);await load();await list();
});});
for(const [button,decision] of [['approve','APPROVE'],['reject','REJECT']])$(button).addEventListener('click',()=>operate(async()=>{
  const id=selected;if(decision==='APPROVE'&&!$('accepted').checked)return;
  await api(`${base}/${id}/decision`,'POST',{decision,accepted:decision==='APPROVE'});await load();await list();
}));
$('execute').addEventListener('click',()=>operate(async()=>{
  const id=selected;$('graph-status').textContent='正在执行，请保留原操作编号…';$('trace').replaceChildren();
  const run=await api(`${base}/${id}/execute`,'POST',{});await load();await list();
  $('graph-status').textContent=`${run.status} · ${run.message}`;
  for(const node of run.trace){const li=document.createElement('li');li.textContent=node;$('trace').append(li);}
  if(run.receipt)receipt(run.receipt);
}));
$('query').addEventListener('click',()=>operate(async()=>{
  const query=await api(`${base}/${selected}/result`);await load();await list();
  // 查询和详情读取之间可能刚好有事务提交；以后一次已经观察到的回执为准。
  const observed=detail?.receipt||query.receipt;
  $('graph-status').textContent=observed?'只读查询已找到创建回执，没有执行图。':'当前尚未观察到已提交的回执，不代表执行已失败。';$('trace').replaceChildren();if(observed)receipt(observed);
}));
/** 本地回执与同步结果分别渲染；缺记录、超时或 REVIEW 都不能被当成远端未创建。 */
const deliveryStates={NOT_CREATED:['尚未创建','当前还没有本地申请，不存在可投递事件。'],LOCAL_ONLY:['仅本地','这笔操作只授权本地创建，没有外部发送任务。'],PENDING:['等待投递','本地已受理，正在等待同步至售后平台。'],SENDING:['同步处理中','尚未确认外部结果；领取租约到期后可能使用同一事件重投。'],DELIVERED:['已取得持久化回执','接收方已按约定返回并保存回执；不代表售后审核通过。'],REVIEW:['需要核查','自动处理已停止。远端可能已经创建，请保留原事件编号核查。'],MISSING_EVENT:['记录异常待核查','已有本地申请，但未找到应有事件，请联系维护人员，切勿换号重建。']};
async function delivery(){
  if(!selected)return;const d=await api(`${base}/${selected}/delivery`),state=deliveryStates[d.status]||['未知状态','请保留原编号核查。'];
  $('delivery').hidden=false;$('delivery').dataset.state=d.status;$('delivery-status').textContent=state[0];$('delivery-message').textContent=state[1];
  $('relay-mode').textContent=d.relayEnabled?'后台投递已配置启用；此标识不代表远端健康或已收到。':'后台投递未启用，待发记录会保留；需由维护人员配置并启动投递服务。';
  $('event-id').textContent=d.eventId||'无';$('remote-id').textContent=d.remoteApplicationId||'尚无确认';
  $('delivery-attempt').textContent=d.eventId?`${d.attemptCount} 次领取（不等于发送次数） / ${d.status==='PENDING'?time(d.nextAttemptAt):'—'}`:'无';
  $('delivery-time').textContent=`${d.deliveredAt?time(d.deliveredAt):'尚未确认'} / ${d.lastErrorCode||'无错误码'}`;$('delivery-json').textContent=JSON.stringify(d,null,2);
}
async function summary(){
  const s=await api('/internal/draft-tasks/submission/delivery-summary');
  $('queue-summary').textContent=`等待 ${s.pending} · 处理中 ${s.sending} · 已确认 ${s.delivered} · 需核查 ${s.review}`;
  $('queue-timing').textContent=`最老待发等待：${s.oldestPendingSeconds==null?'无待发':Math.round(s.oldestPendingSeconds)+' 秒'}；已确认平均耗时：${s.averageDeliverySeconds==null?'暂无样本':s.averageDeliverySeconds.toFixed(1)+' 秒'}（${s.delivered} 条）。`;
  $('queue-distribution').textContent=Object.entries(s.attemptDistribution).sort((a,b)=>Number(a[0])-Number(b[0])).map(([n,total])=>`${n} 次领取：${total} 条事件`).join('\n')||'尚无事件';
}
$('refresh-delivery').addEventListener('click',()=>operate(async()=>{await delivery();await summary();}));
operate(refresh);
