/** 每次提交只运行一次。所有不可信文本使用 textContent；不解释 HTML，也不展示模型思维链。 */
const $=id=>document.getElementById(id);
let csrf='',csrfHeader='X-CSRF-TOKEN',busy=false;
async function api(path,body){
  const response=await fetch(path,{credentials:'same-origin',cache:'no-store',
    ...(body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json',[csrfHeader]:csrf},body:JSON.stringify(body)})});
  const data=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(data.message||`请求失败（HTTP ${response.status}），请重新检查登录和接待状态。`);
  return data;
}
/** 会话只提供归属，不把正式聊天历史悄悄拼入每次独立的 Agent 运行。 */
async function refresh(selected){
  const session=await api('/internal/handoff/session');csrf=session.csrfToken;csrfHeader=session.csrfHeader;
  const allowed=session.authenticated&&session.authorities?.includes('customer:chat');
  $('inputs').disabled=busy||!allowed;
  $('account-status').textContent=allowed?`当前账户：${session.username} · 只有 BOT 会话可以开始新运行。`:'请到“统一客服 / 登录”使用 customer1001 或 customer2002 登录，再返回刷新。';
  if(!allowed)return;
  const rows=await api('/api/handoff/conversations');
  const options=rows.filter(r=>r.mode==='BOT').map(row=>{
    const option=document.createElement('option');option.value=row.conversationId;option.textContent=`BOT · ${row.conversationId.slice(0,8)}`;return option;
  });
  $('conversation').replaceChildren(...options);
  if(selected&&options.some(o=>o.value===selected))$('conversation').value=selected;
  if(!options.length){const option=document.createElement('option');option.value='';option.textContent='请新建机器人会话';$('conversation').append(option);}
}
function clearResult(){
  $('candidate-box').hidden=true;$('candidate').textContent='';$('assessment-box').hidden=true;$('assessment').textContent='';
  $('raw').textContent='尚无响应';$('steps').replaceChildren();for(const id of ['model-count','tool-count','elapsed'])$(id).textContent='—';
}
$('new-conversation').addEventListener('click',async()=>{
  if(busy)return;busy=true;$('inputs').disabled=true;
  try{const row=await api('/api/handoff/conversations',{});busy=false;await refresh(row.conversationId);}
  catch(e){$('account-status').textContent=e.message;$('inputs').disabled=true;}
  finally{busy=false;}
});
$('draft-form').addEventListener('submit',async event=>{
  event.preventDefault();if(busy)return;
  const request={conversationId:$('conversation').value,orderNo:$('order').value.trim(),reason:$('reason').value,task:$('task').value.trim(),inspectionOnly:$('inspection-only').checked};
  if(!request.conversationId)return;
  busy=true;$('inputs').disabled=true;$('result').setAttribute('aria-busy','true');clearResult();
  $('status').textContent='运行中';$('message').textContent='任务正在执行，等待实际结果。没有提交任何售后申请。';
  try{
    const run=await api('/internal/draft-agent/runs',request);
    const labels={CANDIDATE_UNVALIDATED:'候选 · 未经审核',INSPECTED:'仅检查完成',NEEDS_ATTENTION:'需要人工检查',RUN_FAILED:'本轮未完成',STATE_CHANGED:'接待状态已变化'};
    $('status').textContent=labels[run.status]||run.status;$('message').textContent=run.message;
    $('model-count').textContent=`${run.modelCalls} / 6`;$('tool-count').textContent=`${run.toolCalls} / 8`;$('elapsed').textContent=`${(run.elapsedMs/1000).toFixed(1)} 秒`;
    if(run.candidateText){$('candidate-box').hidden=false;$('candidate').textContent=run.candidateText;}
    if(run.assessment){$('assessment-box').hidden=false;$('assessment-status').textContent=run.assessment.status;$('assessment-message').textContent=run.assessment.explanation;$('assessment').textContent=JSON.stringify(run.assessment,null,2);}
    for(const step of run.executedSteps){const li=document.createElement('li');li.textContent=step;$('steps').append(li);}
    if(!run.executedSteps.length){const li=document.createElement('li');li.textContent='本轮没有可展示的工具记录';$('steps').append(li);}
    $('raw').textContent=JSON.stringify(run,null,2);
  }catch(e){$('status').textContent='未取得结果';$('message').textContent=`${e.message} 没有自动重跑请求。`;}
  finally{busy=false;$('result').setAttribute('aria-busy','false');await refresh(request.conversationId).catch(e=>{$('account-status').textContent=e.message;$('inputs').disabled=true;});}
});
refresh().catch(e=>{$('account-status').textContent=e.message;$('inputs').disabled=true;});
