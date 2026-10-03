/** 审批页不保存图状态、凭证或正文到浏览器存储；每次刷新从授权接口读取。 */
const $=id=>document.getElementById(id),base='/internal/draft-tasks',apiBase=base+'/hitl/executions';
const phases={NEW:'尚未开始',RUNNING:'正在提出操作',WAITING_APPROVAL:'等待人工审批',RESUMING:'正在恢复执行',REJECTED:'已拒绝 · 未执行',EXPIRED:'审批已过期',STALE:'草稿或状态已变化',SIMULATION_COMPLETED:'模拟执行完成',NO_EXECUTION:'没有执行工具',NEW_APPROVAL_REQUIRED:'出现新请求 · 未放行',RECOVERY_REQUIRED:'需要核查'};
let csrf='',csrfHeader='',busy=false,allowed=false,preview=null,current=null,selected='';
const params=new URLSearchParams(location.search);let wantedTask=params.get('taskId'),wantedDraft=params.get('draftVersion');
if(/^#[0-9a-f-]{36}$/i.test(location.hash))selected=location.hash.slice(1);
async function api(path,method='GET',body){
  const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:method==='GET'?{}:{'Content-Type':'application/json',[csrfHeader]:csrf},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const data=await r.json().catch(()=>({}));if(!r.ok){const e=new Error(data.message||`请求未完成（HTTP ${r.status}）`);e.status=r.status;throw e;}return data;
}
function controls(){
  $('start-inputs').disabled=busy||!allowed;$('start').disabled=busy||!allowed||!preview?.current||!preview.confirmationEffective;
  $('refresh').disabled=busy;document.querySelectorAll('.task-choice').forEach(b=>b.disabled=busy);
  const waiting=allowed&&!busy&&current?.phase==='WAITING_APPROVAL'&&Date.now()<Date.parse(current.card.expiresAt);
  $('approve').disabled=!waiting;$('reject').disabled=!waiting;
  $('discard').disabled=busy||!allowed||!current||['RUNNING','RESUMING'].includes(current.phase);
  $('result').setAttribute('aria-busy',String(busy));
}
/** 所有异步动作串行；写操作失败后只允许显式 GET 查询，不自动重放批准。 */
async function operate(work){if(busy)return;busy=true;controls();try{await work();}catch(e){
  if(e.status===401||e.status===403)clearIdentity();
  current=null;$('card').hidden=true;$('receipt').hidden=true;$('raw').textContent='请刷新查看实际状态';$('phase').textContent='需要刷新';$('count').textContent='—';
  $('notice').textContent=e.message+'。未自动重试，可刷新实验查询实际状态。';
}finally{busy=false;controls();}}
/** 登录失效时同时清除左右两栏，避免仍展示上一位用户的草稿预览。 */
function clearIdentity(){
  allowed=false;preview=null;current=null;csrf='';csrfHeader='';
  $('task-select').replaceChildren();$('draft-select').replaceChildren();$('draft-preview').replaceChildren();
  $('card').hidden=true;$('receipt').hidden=true;$('experiments').replaceChildren();
  $('raw').textContent='尚未登录';$('count').textContent='—';$('phase').textContent='需要登录';
}
async function readDraft(){
  preview=null;$('draft-preview').replaceChildren();if(!$('task-select').value||!$('draft-select').value)return;
  preview=await api(`${base}/tasks/${$('task-select').value}/drafts/${$('draft-select').value}`);
  const h=document.createElement('strong');h.textContent=`${preview.body.orderNo} · 草稿 V${preview.draftVersion}`;
  const p=document.createElement('p');p.textContent=preview.body.userStatement.userDescription;
  const s=document.createElement('p');s.className='fine';s.textContent=preview.current&&preview.confirmationEffective?'当前内容已确认；尚未授权本次操作。':'这一版当前不可启动审批，请回草稿页重新核对。';
  $('draft-preview').append(h,p,s);controls();
}
async function loadVersions(){
  preview=null;$('draft-select').replaceChildren();if(!$('task-select').value){$('draft-preview').textContent='暂无已确认草稿，请先到草稿页准备。';return;}
  const rows=await api(`${base}/tasks/${$('task-select').value}/drafts`);
  for(const row of rows.filter(r=>r.current&&r.confirmationEffective)){const o=document.createElement('option');o.value=row.draftVersion;o.textContent=`V${row.draftVersion} · 内容已确认`;$('draft-select').append(o);}
  if(wantedDraft&&[...$('draft-select').options].some(o=>o.value===wantedDraft))$('draft-select').value=wantedDraft;
  wantedDraft=null;
  if(!$('draft-select').options.length){$('draft-preview').textContent='这项任务没有当前有效的内容确认，请先到草稿页核对。';return;}
  await readDraft();
}
async function list(){
  const rows=await api(apiBase);$('experiments').replaceChildren();
  if(!rows.length){$('experiments').textContent='暂无本地实验。重启会清空此列表。';return;}
  for(const row of rows){const b=document.createElement('button');b.type='button';b.className='task-choice';b.setAttribute('aria-current',String(row.executionId===selected));
    const title=document.createElement('span');title.textContent=`V${row.draftVersion} · ${phases[row.phase]} · 执行 ${row.simulatedExecutions} 次`;
    const sub=document.createElement('small');sub.textContent=row.executionId;b.append(title,sub);
    b.addEventListener('click',()=>operate(async()=>{selected=row.executionId;history.replaceState(null,'',`${location.pathname}${location.search}#${selected}`);render(await api(`${apiBase}/${selected}`));await list();}));$('experiments').append(b);}
}
/** 显示的是服务端冻结的卡片及真实计数，不让模型再生成审批摘要或成功话术。 */
function render(view){
  current=view;$('phase').textContent=phases[view.phase];$('count').textContent=view.simulatedExecutions;$('raw').textContent=JSON.stringify(view,null,2);
  $('step-proposed').classList.toggle('active',view.phase==='RUNNING');$('step-waiting').classList.toggle('active',view.phase==='WAITING_APPROVAL');$('step-result').classList.toggle('active',!!view.decision);
  $('notice').textContent=view.phase==='WAITING_APPROVAL'?'工具已经在执行前暂停。请核对这一笔操作，再批准或拒绝。':view.phase==='SIMULATION_COMPLETED'?'受保护的模拟工具已执行一次；没有创建正式售后申请或执行退款。':view.phase==='RECOVERY_REQUIRED'?'本段运行未确认正常完成。审批与执行结果需分别查看，不能自动重试。':view.phase==='NEW_APPROVAL_REQUIRED'?'Agent 又提出了新的请求。本场到此停止，新请求没有获得许可。':phases[view.phase]+'；没有真实提交或退款。';
  const c=view.card;$('card').hidden=!c;
  if(c){const draft=c.reviewedDraft;$('card-meta').textContent=`审批编号 ${c.approvalId} · 实验 ${view.executionId}`;
    $('draft-heading').textContent=`${draft.body.orderNo} · 固定草稿 V${draft.draftVersion}`;$('description').textContent=draft.body.userStatement.userDescription;$('handling').textContent=draft.body.userStatement.requestedHandling;
    $('facts').textContent=`${draft.body.checkedSnapshot.explanation} 事实快照时间：${new Date(draft.body.checkedSnapshot.checkedAt).toLocaleString()}。这是启动时的审阅对象，执行前仍由服务器复核。`;
    $('expiry').textContent=`审批截止：${new Date(c.expiresAt).toLocaleString()} · 以服务器校验为准。`;
    $('tool-call').textContent=JSON.stringify({toolCallId:c.toolCallId,toolName:c.toolName,arguments:JSON.parse(c.arguments),scope:c.scope},null,2);}
  $('receipt').hidden=!view.decision;
  if(view.decision){const d=view.decision;$('receipt-text').textContent=`决策：${d.choice==='APPROVE'?'批准本次模拟操作':'拒绝本次操作'}\n决策人：${d.decidedBy} · ${new Date(d.decidedAt).toLocaleString()}\n执行结果：${phases[view.phase]} · 模拟工具执行 ${view.simulatedExecutions} 次\n真实提交：否；退款执行：否。`;}
  controls();
}
async function refresh(){
  const session=await api('/internal/handoff/session');csrf=session.csrfToken;csrfHeader=session.csrfHeader;allowed=session.authenticated&&session.authorities?.includes('customer:chat');
  $('account').textContent=allowed?`当前账户：${session.username} · 仅可审批自己的任务`:'请在统一客服页使用客户账号登录，再返回刷新。';
  if(!allowed){clearIdentity();$('notice').textContent='请登录后刷新，重新读取可访问的实验。';return;}
  const previous=$('task-select').value,rows=await api(base+'/tasks');$('task-select').replaceChildren();
  for(const row of rows.filter(r=>r.compatible&&r.status==='CANDIDATE_UNVALIDATED'&&r.draftVersion>0)){const o=document.createElement('option');o.value=row.taskId;o.textContent=`${row.orderNo} · ${row.taskId.slice(0,8)} · 草稿 V${row.draftVersion}`;$('task-select').append(o);}
  const wanted=wantedTask||previous;if([...$('task-select').options].some(o=>o.value===wanted))$('task-select').value=wanted;wantedTask=null;
  await loadVersions();await list();
  if(selected){try{render(await api(`${apiBase}/${selected}`));}catch(e){if(e.status===404){selected='';history.replaceState(null,'',location.pathname+location.search);}throw e;}}
}
$('refresh').addEventListener('click',()=>operate(refresh));$('task-select').addEventListener('change',()=>operate(loadVersions));$('draft-select').addEventListener('change',()=>operate(readDraft));
$('start-form').addEventListener('submit',event=>{event.preventDefault();if(!preview)return;const chosen=preview;operate(async()=>{
  current=null;$('card').hidden=true;$('receipt').hidden=true;$('count').textContent='0';$('phase').textContent='正在提出操作';$('notice').textContent='正在等待 Agent 提出具体操作，尚未批准…';
  const view=await api(apiBase,'POST',{taskId:chosen.taskId,draftVersion:chosen.draftVersion,expectedTaskVersion:chosen.taskVersion});selected=view.executionId;history.replaceState(null,'',`${location.pathname}${location.search}#${selected}`);render(view);await list();});});
for(const [button,decision] of [['approve','APPROVE'],['reject','REJECT']])$(button).addEventListener('click',()=>{if(!current?.card)return;const seen=current;operate(async()=>{
  $('notice').textContent='正在处理本次决策，请等待服务器结果…';render(await api(`${apiBase}/${seen.executionId}/decision`,'POST',{approvalId:seen.card.approvalId,expectedVersion:seen.version,decision}));await list();});});
$('discard').addEventListener('click',()=>{if(!busy&&current)$('discard-dialog').showModal();});$('discard-dialog').addEventListener('close',()=>{if($('discard-dialog').returnValue==='confirm')operate(async()=>{
  await api(`${apiBase}/${selected}`,'DELETE');selected='';current=null;history.replaceState(null,'',location.pathname+location.search);$('card').hidden=true;$('receipt').hidden=true;$('phase').textContent='已清理';$('notice').textContent='本地实验已清理，数据库草稿保留。';$('count').textContent='—';$('raw').textContent='实验已清理';await list();});});
// 只更新本地按钮禁用状态，不轮询模型或自动消费过期审批。
setInterval(controls,1000);operate(refresh);
