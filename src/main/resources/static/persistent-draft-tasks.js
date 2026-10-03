/** 任务入口只发送本轮输入，不从浏览器重放历史；检查点与权限始终由服务器管理。 */
const $=id=>document.getElementById(id);
const base='/internal/draft-tasks/tasks';
const labels={READY:'待开始',RUNNING:'运行中',CANDIDATE_UNVALIDATED:'候选 · 未经审核',NEEDS_ATTENTION:'需要检查',RECOVERY_REQUIRED:'需要恢复核查',CLOSED:'已关闭'};
let csrf='',csrfHeader='X-CSRF-TOKEN',allowed=false,busy=false,selected='',current=null,poll=null;
async function api(path,method='GET',body){
  const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',
    headers:method==='GET'?{}:{'Content-Type':'application/json',[csrfHeader]:csrf},
    ...(body===undefined?{}:{body:JSON.stringify(body)})});
  const data=await response.json().catch(()=>({}));
  if(!response.ok){const error=new Error(data.message||`请求失败（HTTP ${response.status}），请检查登录与接待状态。`);error.status=response.status;throw error;}
  return data;
}
/** 全页互斥防止本页重复发送；服务器通过数据库版本 CAS 处理其他标签页或其他实例的同任务并发。 */
function controls(){
  $('create-inputs').disabled=busy||!allowed;$('refresh').disabled=busy;
  const blocked=!current||['RECOVERY_REQUIRED','CLOSED','RUNNING'].includes(current.task.status)||current.task.turnNo>=8||!current.task.compatible;
  $('turn-inputs').disabled=busy||!allowed||blocked;
  $('discard').disabled=busy||!allowed||!current||['RUNNING','RECOVERY_REQUIRED','CLOSED'].includes(current.task.status);
  document.querySelectorAll('.task-choice').forEach(el=>{el.disabled=busy;});
  $('result').setAttribute('aria-busy',String(busy));
}
function clearResult(){
  current=null;$('candidate-box').hidden=true;$('candidate').textContent='';$('assessment-box').hidden=true;$('assessment').textContent='';$('run-box').hidden=true;$('steps').replaceChildren();$('raw').textContent='尚无响应';
  for(const id of ['turn-count','checkpoint-count','message-count'])$(id).textContent='—';
  $('task-meta').textContent='从左侧新建或选择任务。';$('state-detail').textContent='摘要只读，不会再次调用模型。';$('status').textContent='未选择任务';controls();
}
function render(view){
  clearResult();current=view;const {task,state,lastRun:run}=view;
  $('status').textContent=labels[task.status]||task.status;
  $('task-meta').textContent=`订单 ${task.orderNo} · taskId ${task.taskId} · 版本 ${task.version} · 会话 ${task.conversationId}`;
  $('turn-count').textContent=`${task.turnNo} / 8`;$('checkpoint-count').textContent=state.checkpointCount;$('message-count').textContent=state.messageCount;
  $('state-detail').textContent=`最近完成第 ${view.lastCompletedTurn} 轮：用户 ${state.userMessages} · 助手 ${state.assistantMessages} · 工具 ${state.toolMessages}。本次仅读取已存快照。`;
  const pending=['RUNNING','RECOVERY_REQUIRED'].includes(task.status);
  $('message').textContent=!task.compatible?'Agent 契约版本不兼容，不能直接续写，请核查版本。':pending?'本轮尚未确认完成。下方如有内容，仅是上一正常完成轮次的快照；请先刷新查询，不要自动重跑。':run?.message||(task.status==='CLOSED'?'任务已结束或接待状态变化，候选已隐藏。':'任务已保存，发送第一条描述开始。');

  if(run){
    if(run.candidateText){$('candidate-box').hidden=false;$('candidate').textContent=run.candidateText;}
    if(run.assessment){$('assessment-box').hidden=false;$('assessment-message').textContent=`${run.assessment.status} · ${run.assessment.explanation}`;$('assessment').textContent=JSON.stringify(run.assessment,null,2);}
    $('run-box').hidden=false;$('run-detail').textContent=`模型 ${run.modelCalls}/6 · 工具 ${run.toolCalls}/8 · ${(run.elapsedMs/1000).toFixed(1)} 秒 · runId ${run.runId}`;
    for(const text of run.executedSteps){const li=document.createElement('li');li.textContent=text;$('steps').append(li);}
  }
  $('raw').textContent=JSON.stringify(view,null,2);controls();
}
/** 切换任务时清空编辑框，避免把另一任务或初始样例的旧输入意外重复提交。 */
async function list(){
  const rows=await api(base);$('task-list').replaceChildren();
  if(!rows.length){const p=document.createElement('p');p.className='fine';p.textContent='暂无任务。创建一个任务，再逐轮补充描述。';$('task-list').append(p);}
  for(const row of rows){const b=document.createElement('button');b.type='button';b.className='task-choice';b.setAttribute('aria-current',String(row.taskId===selected));
    const title=document.createElement('span');title.textContent=`${row.orderNo} · ${labels[row.status]} · ${row.turnNo}/8 · v${row.version}`;
    const detail=document.createElement('small');detail.textContent=row.taskId;b.append(title,detail);
    b.addEventListener('click',()=>operate(async()=>{selected=row.taskId;$('message-input').value='';history.replaceState(null,'',`#${selected}`);await load();await list();}));$('task-list').append(b);
  }
}
/** GET 仅获取业务快照，RUNNING 没有自动过期租约；用户通过显式刷新查看最新状态。 */
async function load(){
  clearTimeout(poll);if(!selected){clearResult();return;}
  try{render(await api(`${base}/${selected}`));}
  catch(error){clearResult();if(error.status===404){selected='';history.replaceState(null,'',location.pathname);}throw error;}
  // RUNNING 可能是上次进程留下的记录，保持显式刷新，不自动解锁、重跑或无限轮询。
}
async function refresh(conversation){
  clearTimeout(poll);const session=await api('/internal/handoff/session');csrf=session.csrfToken;csrfHeader=session.csrfHeader;
  allowed=session.authenticated&&session.authorities?.includes('customer:chat');
  $('account-status').textContent=allowed?`当前账户：${session.username} · 显示当前账户最近 100 项数据库任务。`:'请到“统一客服 / 登录”使用客户账户登录，再返回刷新。';
  if(!allowed){selected='';clearResult();$('task-list').replaceChildren();return;}
  const conversations=await api('/api/handoff/conversations');$('conversation').replaceChildren();
  for(const row of conversations.filter(r=>r.mode==='BOT')){const option=document.createElement('option');option.value=row.conversationId;option.textContent=`BOT · ${row.conversationId.slice(0,8)}`;$('conversation').append(option);}
  if(conversation)$('conversation').value=conversation;
  if(!$('conversation').options.length){const option=document.createElement('option');option.value='';option.textContent='请先新建会话';$('conversation').append(option);}
  await list();await load();
  // 刷新已有任务时清空示例输入，防止把最初的旧描述误当作新的更正再次发送。
  if(current?.task.turnNo>0)$('message-input').value='';
}
/** 失败不自动重试写操作。网络断开时用户可以刷新查询数据库里的实际状态。 */
async function operate(action){
  if(busy)return;busy=true;controls();
  try{await action();}
  catch(e){clearResult();$('message').textContent=`${e.message} 未自动重试；可刷新任务查看实际状态。`;}
  finally{busy=false;controls();}
}
$('refresh').addEventListener('click',()=>operate(()=>refresh($('conversation').value)));
$('new-conversation').addEventListener('click',()=>operate(async()=>{const row=await api('/api/handoff/conversations','POST',{});await refresh(row.conversationId);}));
$('create-form').addEventListener('submit',event=>{event.preventDefault();operate(async()=>{
  const row=await api(base,'POST',{conversationId:$('conversation').value,orderNo:$('order').value.trim(),reason:$('reason').value});selected=row.taskId;$('message-input').value='';history.replaceState(null,'',`#${selected}`);await load();await list();
});});
$('continue-form').addEventListener('submit',event=>{event.preventDefault();operate(async()=>{
  const id=selected;const expectedVersion=current.task.version;const message=$('message-input').value.trim();clearTimeout(poll);clearResult();$('task-meta').textContent=`正在处理任务 ${id}`;$('status').textContent='运行中';$('message').textContent='正在续写，等待本轮核验与模型结果。';
  render(await api(`${base}/${id}/turns`,'POST',{expectedVersion,message}));$('message-input').value='';await list();
});});
$('discard').addEventListener('click',()=>{if(!busy)$('discard-dialog').showModal();});
$('discard-dialog').addEventListener('close',()=>{if($('discard-dialog').returnValue==='confirm')operate(async()=>{
  render(await api(`${base}/${selected}/close`,'POST',{expectedVersion:current.task.version}));$('message').textContent='任务已结束，持久化历史与正式客服记录保留。';await list();
});});
// URL 中仅保存外部业务任务编号，服务端逐次授权；不在浏览器保存或回传完整消息历史。
if(/^#[0-9a-f-]{36}$/i.test(location.hash))selected=location.hash.slice(1);
operate(()=>refresh());
