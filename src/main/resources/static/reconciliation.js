// 第29章：所有远端核查由明确点击触发；页面加载、选择、刷新只读取本地持久化记录。
const $ = id => document.getElementById(id);
const apiRoot = '/api/support/outbox';
const stateNames = {REVIEW:'结果待核查',DELIVERED:'已确认送达',PENDING:'等待投递',SENDING:'投递中'};
const findingNames = {PERSISTED:'原结果已持久化',NOT_OBSERVED:'尚未观察到原结果',PAYLOAD_CONFLICT:'原事件正文冲突',RECEIVER_INCONSISTENT:'接收方记录不一致',QUERY_UNAVAILABLE:'本次无法完成查询',INVALID_RESPONSE:'查询回执无法验证',STARTED:'核查未记录完成',STALE:'过期观察'};
const findingNotes = {
  PERSISTED:'原事件与持久化回执匹配。远端已创建待审核申请，这不表示售后审核通过。',
  NOT_OBSERVED:'这次查询没有观察到已提交记录。远端事务可能尚未完成，不能据此再次创建申请。',
  PAYLOAD_CONFLICT:'同一事件编号对应的正文不同。保留原事件，由核查人员检查来源与正文。',
  RECEIVER_INCONSISTENT:'接收方的事件、申请或回执未能相互对应，需要进一步检查权威数据库。',
  QUERY_UNAVAILABLE:'本次查询未获得可验证结果。请排查连接或服务授权，不能把查询异常当作业务失败。',
  INVALID_RESPONSE:'返回内容不符合原事件的回执合同。保留待核查，不采用该回执。'
};
let session, busy=false, page=1, total=0, selected=null, requestVersion=0, refreshQueued=false;
const time = value => value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '尚未记录';
const node = (tag,text,cls) => {const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(cls)el.className=cls;return el;};
const authorized = () => session?.authorities?.includes('support:reconcile');
function notice(text,error=false){$('notice').textContent=text;$('notice').dataset.error=String(error);}
function lock(value){
  busy=value;$('case').setAttribute('aria-busy',String(value));
  for(const id of ['refresh','filter','logout','prev','next'])$(id).disabled=value;
  $('prev').disabled=value||page<=1;$('next').disabled=value||page*25>=total;
  for(const el of document.querySelectorAll('.event,#login button,#login input,#login select'))el.disabled=value;
  $('reconcile').disabled=value||!authorized()||selected?.event.status!=='REVIEW'||!selected?.lookupConfigured;
  $('reconcile').textContent=value?'处理中…':'核查原事件';
}
// 只读取 JSON；远端响应或数据库字段只作为文本展示，不拼接为 HTML。
async function call(path,method='GET',body){
  const headers={'Content-Type':'application/json'};
  if(method!=='GET'&&session?.csrfToken)headers[session.csrfHeader||'X-CSRF-TOKEN']=session.csrfToken;
  const response=await fetch(path,{method,headers,credentials:'same-origin',cache:'no-store',body:body===undefined?undefined:JSON.stringify(body)});
  const data=response.status===204?null:await response.json();
  if(!response.ok){
    if(response.status===401||response.status===403){$('workspace').hidden=true;selected=null;}
    throw new Error(data?.message||'请求未完成，请刷新登录与记录后检查。');
  }
  return data;
}
async function readSession(){
  session=await call('/internal/handoff/session');
  $('login').hidden=Boolean(session.authenticated);$('logout').hidden=!session.authenticated;
  $('account').textContent=session.authenticated?`当前账户 ${session.username} · ${authorized()?'已授予核查权限':'无核查权限'}`:'尚未登录 · 使用已配置的本地账户';
  $('workspace').hidden=!authorized();
  if(!authorized())notice(session.authenticated?'当前账户没有核查权限。可退出后使用已授权的核查员账户登录。':'登录核查员账户后，查看当前租户的同步事件。');
  return authorized();
}
function renderSummary(data){
  $('review-count').textContent=data.review;$('repair-count').textContent=data.repaired;$('started-count').textContent=data.started;
  $('long-started').textContent=`其中 ${data.longStarted} 次超过 5 分钟`;
  const seconds=data.oldestEventSeconds;
  $('oldest').textContent=seconds==null?'—':seconds<60?'不足 1 分钟':seconds<3600?`${Math.floor(seconds/60)} 分钟`:seconds<86400?`${Math.floor(seconds/3600)} 小时`:`${Math.floor(seconds/86400)} 天`;
  $('findings').replaceChildren(...Object.entries(data.findings).map(([key,count])=>node('span',`${findingNames[key]||key} · ${count}`)));
}
function renderList(data){
  total=data.total;$('total').textContent=`${total} 条`;$('page').textContent=`${page} / ${Math.max(1,Math.ceil(total/25))}`;
  $('events').replaceChildren();
  if(!data.events.length)$('events').append(node('p','当前筛选下没有事件。可切换状态或稍后刷新。','empty-note'));
  for(const event of data.events){
    const button=node('button',undefined,'event');button.type='button';button.dataset.eventId=event.eventId;
    button.append(node('span',`${event.orderNo} · ${stateNames[event.status]||event.status}`),node('small',event.eventId),node('small',`${time(event.createdAt)} · 投递 ${event.attemptCount} 次`));
    button.addEventListener('click',()=>{if(!busy){location.hash=event.eventId;}});
    $('events').append(button);
  }
}
function renderDetail(data){
  selected=data;$('empty').hidden=true;$('detail').hidden=false;$('outcome').hidden=true;
  const e=data.event;$('order').textContent=`订单 ${e.orderNo}`;$('state').textContent=stateNames[e.status]||e.status;$('state').dataset.state=e.status;
  for(const [id,value] of Object.entries({'event-id':e.eventId,'application-id':e.applicationId,'operation-id':e.operationId,'created':time(e.createdAt),'attempt':`${e.attemptCount} 次 / v${e.reconcileVersion}`,'remote-id':e.remoteApplicationId||'尚无匹配回执','delivered':time(e.deliveredAt),'error-code':e.lastErrorCode||'无'}))$(id).textContent=value;
  $('action-hint').textContent=e.status!=='REVIEW'?'此事件当前无需核查。可以查看已经保存的核查记录。':!data.lookupConfigured?'尚未配置查询服务。请由维护人员完成服务端连接与授权后再核查。':'查询原事件并保存本次观察；只有匹配的持久化回执才能补记送达。不会重新投递，不会创建新申请。';
  $('audits').replaceChildren();
  if(!data.audits.length)$('audits').append(node('p','尚无核查记录。不会因打开本页而自动发起核查。','empty-note'));
  for(const audit of data.audits){
    const card=node('article',undefined,'audit');card.dataset.repaired=String(audit.repaired);
    const disposition=audit.status==='STARTED'?'尚未记录完成':audit.status==='STALE'?'过期观察 · 未应用':audit.repaired?'已补记送达':'已记录 · 保留待核查';
    card.append(node('h4',`${findingNames[audit.finding]||'核查已开始'} · ${disposition}`),node('p',`核查员 ${audit.requestedBy} · ${time(audit.startedAt)} · 基于 v${audit.expectedVersion}`));
    card.append(node('p',audit.status==='STARTED'?'开始记录已保存，但尚无完成记录；可能仍在执行或进程已中断。刷新只读取记录，必要时可重新查询原事件。':audit.status==='STALE'?'保存时原状态或核查版本已变化，本次观察只留作审计，没有覆盖当前状态。':findingNotes[audit.finding]||'查看记录详情。'));
    const details=node('details');details.append(node('summary','查看记录编号与证据'));
    details.append(node('pre',`核查编号 ${audit.checkId}\n原事件 ${audit.eventId}\n审计状态 ${audit.status}\n状态 ${audit.stateBefore} → ${audit.stateAfter||'尚未保存'}\n观察代码 ${audit.code||'尚无'}\n远端编号 ${audit.remoteId||'尚无'}\n完成时间 ${time(audit.completedAt)}`));
    card.append(details);$('audits').append(card);
  }
  for(const button of document.querySelectorAll('.event'))button.setAttribute('aria-current',String(button.dataset.eventId===e.eventId));
}
// 版本号防止晚到的只读请求写回已经切换的详情；核查期间则锁定选择和按钮。
async function refresh(){
  if(busy)return;const version=++requestVersion;lock(true);
  try{
    if(!await readSession())return;
    const [list,summary]=await Promise.all([call(`${apiRoot}?status=${$('filter').value}&page=${page}`),call(`${apiRoot}/summary`)]);
    if(version!==requestVersion)return;renderSummary(summary);renderList(list);
    const hash=location.hash.slice(1);const id=/^[a-f0-9-]{36}$/i.test(hash)?hash:list.events[0]?.eventId;
    if(id){if(!hash)history.replaceState(null,'',`#${id}`);renderDetail(await call(`${apiRoot}/${id}`));}
    else{selected=null;$('empty').hidden=false;$('detail').hidden=true;}
    notice('已读取保存的记录。本次刷新未发起远端核查。');
  }catch(error){notice(error.message,true);selected=null;$('detail').hidden=true;$('empty').hidden=false;}
  finally{lock(false);if(refreshQueued){refreshQueued=false;refresh();}}
}
$('reconcile').addEventListener('click',async()=>{
  if(busy||!selected||selected.event.status!=='REVIEW')return;
  const eventId=selected.event.eventId;lock(true);notice('正在查询原事件。查询结束后保存观察与核查记录，请勿重新提交申请。');
  try{
    const result=await call(`${apiRoot}/${eventId}/reconcile`,'POST',{});
    // POST 未收到确认时绝不自动再 POST；成功后这三个请求也全为本地只读查询。
    const [detail,list,summary]=await Promise.all([call(`${apiRoot}/${eventId}`),call(`${apiRoot}?status=${$('filter').value}&page=${page}`),call(`${apiRoot}/summary`)]);
    renderList(list);renderSummary(summary);renderDetail(detail);
    $('outcome').hidden=false;$('outcome').dataset.repaired=String(result.repaired);
    $('outcome').textContent=result.auditStatus==='STALE'?'本次观察已过期，仅保存审计记录，未改变同步状态。请查看最新记录。':result.repaired?'已核实原回执并补记送达。原申请与投递次数保持不变，售后仍等待审核。':`${findingNames[result.finding]||result.finding}。${findingNotes[result.finding]||''}`;
    notice(`核查记录已保存：${result.checkId}`);
  }catch(error){notice(`${error.message} 未自动再次核查或投递。请先刷新保存的记录。`,true);}
  finally{lock(false);if(refreshQueued||location.hash.slice(1)!==eventId){refreshQueued=false;refresh();}}
});
$('refresh').addEventListener('click',refresh);
$('filter').addEventListener('change',()=>{page=1;history.replaceState(null,'',location.pathname);refresh();});
$('prev').addEventListener('click',()=>{if(page>1){page--;history.replaceState(null,'',location.pathname);refresh();}});
$('next').addEventListener('click',()=>{if(page*25<total){page++;history.replaceState(null,'',location.pathname);refresh();}});
window.addEventListener('hashchange',()=>{if(busy)refreshQueued=true;else refresh();});
$('login').addEventListener('submit',async event=>{
  event.preventDefault();if(busy)return;lock(true);
  try{
    await readSession();const body=new URLSearchParams({username:$('username').value,password:$('password').value});
    const response=await fetch('/internal/handoff/login',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded',[session.csrfHeader||'X-CSRF-TOKEN']:session.csrfToken},body});
    $('password').value='';if(!response.ok)throw new Error('账户或密码不正确，请检查本地账户配置。');
    selected=null;
  }catch(error){notice(error.message,true);lock(false);return;}
  lock(false);refresh();
});
$('logout').addEventListener('click',async()=>{
  if(busy)return;lock(true);
  try{await call('/internal/handoff/logout','POST');$('workspace').hidden=true;selected=null;}
  catch(error){notice(error.message,true);lock(false);return;}
  lock(false);refresh();
});
refresh();
