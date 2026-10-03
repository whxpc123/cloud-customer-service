/** 真正读取 fetch 响应流；不使用 response.text()、假进度条或拆字动画。 */
import {consumeSse,createAnswerTracker} from './sse-client.mjs';
const $=id=>document.getElementById(id);
let csrf=null,csrfHeader='X-CSRF-TOKEN',logged=false,controller=null;
async function session(){
  const response=await fetch('/internal/handoff/session');
  if(!response.ok)throw new Error('无法读取登录状态，请到统一客服页面登录。');
  const state=await response.json();csrf=state.csrfToken;csrfHeader=state.csrfHeader;logged=state.authenticated;
  $('account-status').textContent=logged?`已登录：${state.username} · 同一次提交只发起一次模型请求。`:'请先点击上方“统一客服 / 登录”，登录后返回本页。';
  $('start').disabled=!logged||!!controller;
}
$('stream-form').addEventListener('submit',async event=>{
  event.preventDefault();if(controller||!logged)return;
  const question=$('question').value.trim();if(!question)return;
  controller=new AbortController();const active=controller,start=performance.now();let first=null;
  $('start').disabled=true;$('cancel').disabled=false;$('question').disabled=true;
  $('answer-text').textContent='';$('events').replaceChildren();$('answer-status').textContent='正在连接模型流…';$('output-kind').textContent='生成中草稿';
  $('fragment-count').textContent='0';$('first-fragment').textContent='—';$('elapsed').textContent='—';
  const tracker=createAnswerTracker(text=>{
    if(first===null){first=performance.now()-start;$('first-fragment').textContent=`${Math.round(first)} ms`;}
    $('answer-text').appendChild(document.createTextNode(text));
  },(name,packet)=>{
    const state=tracker.snapshot();$('fragment-count').textContent=String(state.deltas);$('elapsed').textContent=`${Math.round(performance.now()-start)} ms`;
    const row=document.createElement('li');row.textContent=`${packet.sequence} · ${name} · ${packet.turnId}`;$('events').append(row);
    while($('events').children.length>80)$('events').firstElementChild.remove();
    if(name==='turn.started')$('answer-status').textContent=packet.text;
    if(name==='turn.completed'){$('answer-status').textContent=packet.text;$('output-kind').textContent='实验文本已结束';}
    if(name==='turn.failed'){$('answer-status').textContent=packet.text;$('output-kind').textContent='未完成草稿';}
  });
  try{
    const response=await fetch('/internal/stream-lab/answer',{method:'POST',credentials:'same-origin',signal:active.signal,
      headers:{'Content-Type':'application/json','Accept':'text/event-stream',[csrfHeader]:csrf},body:JSON.stringify({question})});
    if(!response.ok)throw new Error(`请求未开始：HTTP ${response.status}，请检查登录或稍后手动重试。`);
    if(!response.headers.get('Content-Type')?.startsWith('text/event-stream'))throw new Error('服务器没有返回 SSE');
    await consumeSse(response.body,tracker.accept);
    if(!tracker.snapshot().terminal)throw new Error('连接中断，本轮尚未确认完成。');
  }catch(error){
    $('answer-status').textContent=active.signal.aborted?'已停止接收，本轮尚未确认完成。':`${error.message} 没有自动重发请求。`;
    $('output-kind').textContent='未完成草稿';
  }finally{
    controller=null;$('cancel').disabled=true;$('question').disabled=false;$('elapsed').textContent=`${Math.round(performance.now()-start)} ms`;
    await session().catch(e=>{$('account-status').textContent=e.message;$('start').disabled=true;});
  }
});
$('cancel').addEventListener('click',()=>controller?.abort());
window.addEventListener('pagehide',()=>controller?.abort());
session().catch(e=>{$('account-status').textContent=e.message;});
