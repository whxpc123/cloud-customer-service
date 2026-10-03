/* 第十七章：只显示数据库正式消息；DOM 使用 textContent，模型内容不能注入 HTML。 */
'use strict';
const $=id=>document.getElementById(id);
const node=(tag,text='',className='')=>{const n=document.createElement(tag);n.textContent=text;n.className=className;return n;};
const routes={SMALL_TALK:'问候',KNOWLEDGE:'知识问答',ORDER_QUERY:'订单查询',AFTER_SALE_PRECHECK:'售后预检查',HUMAN_SERVICE:'人工服务',CLARIFY:'澄清 / 兜底',OUT_OF_SCOPE:'服务范围'};
const sources={RULE:'完整命令规则',MODEL:'模型分类',GUARD:'Java 兜底检查'};
const reasons={EXACT_SMALL_TALK:'纯问候、感谢或告别',EXPLICIT_HUMAN_COMMAND:'明确要求人工',MODEL_CLASSIFIED:'模型建议通过字段检查',AMBIGUOUS_INTENT:'任务尚不明确',MULTIPLE_INDEPENDENT_TASKS:'多个独立事项，需选择处理顺序',INVALID_MODEL_RESULT:'模型格式或字段无效，未执行业务',MODEL_UNAVAILABLE:'分类服务故障，未执行业务',HUMAN_SESSION_OWNED:'人工已接管，不进行机器人分类'};
const examples={hello:'你好',policy:'退货运费由谁承担？',order:'A10001 发货了吗？',sale:'A10001 的商品有质量问题，能退吗？',missing:'我要查订单',negation:'不要转人工，先告诉我退货规则。',multi:'帮我查 A10001 发货没有，另外告诉我电子发票怎么开。',human:'我要转人工',unrelated:'帮我写一篇完全无关的小说。'};

let csrf=null,csrfHeader='X-CSRF-TOKEN',account=null,conversation=null,receipt=null;
let busy=false,handoffBusy=false,refreshing=false,cursor=0,epoch=0,pending=null;
const modes={BOT:'智能客服接待',WAITING_HUMAN:'等待人工领取',HUMAN_ACTIVE:'已由客服领取',CLOSED:'本次会话已结束'};
/** 生成回答时仍允许申请人工；禁止切换会话，避免把结果挂到另一个会话。 */
function controls(){
  const ready=!!account?.authorities.includes('customer:chat'),active=ready&&!!conversation;
  for(const id of ['new-session','conversation-list','logout'])$(id).disabled=busy||handoffBusy||!ready;
  for(const id of ['send','question','example'])$(id).disabled=busy||!active||receipt?.mode==='CLOSED';
  for(const id of ['diagnose','clear'])$(id).disabled=busy||!active||receipt?.mode!=='BOT';
  $('handoff').disabled=handoffBusy||!active||receipt?.mode!=='BOT';
  $('refresh-state').disabled=!active;
  $('refresh-metrics').disabled=!ready;
  $('send').textContent=receipt&&['WAITING_HUMAN','HUMAN_ACTIVE'].includes(receipt.mode)?'保存补充消息':'发送并处理';
}
async function request(path,method='GET',body){
  const response=await fetch(path,{method,headers:{'Content-Type':'application/json',...(csrf?{[csrfHeader]:csrf}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  if(response.status===204)return null;
  const data=await response.json();
  if(!response.ok)throw new Error(data.message||`请求未完成（${response.status}），请刷新状态。`);
  return data;
}
function resetTrace(){
  document.querySelectorAll('[data-route]').forEach(n=>n.classList.remove('active'));
  $('decision').replaceChildren(node('p','发送后显示规则或模型的选择、原因与耗时。','empty'));
  $('payload').replaceChildren();$('raw-box').hidden=true;$('trace-type').textContent='等待提问';
}
function fields(rows,className='facts'){
  const dl=node('dl','',className);rows.forEach(([label,value])=>dl.append(node('dt',label),node('dd',value??'未提供')));return dl;
}
/** 来源展开显示完整正文，不从回答文本解析“引用”，也不重新检索。 */
function evidence(root,title,text,identity){const detail=node('details');detail.append(node('summary',title),node('p',text),node('p',identity,'fine'));root.append(detail);}
function renderPayload(r){
  const root=$('payload');root.replaceChildren();
  if(r.knowledge){const k=r.knowledge,card=node('article','','evidence-card');card.append(node('h3',`本轮知识来源 · ${k.references.length} 块`),fields([['业务状态',k.status],['实际查询',k.retrievalQuery||'未进入检索']]));
    if(!k.references.length)card.append(node('p','本轮没有可展示的实际来源。'));
    k.references.forEach(e=>evidence(card,`${e.sourceName} · v${e.sourceVersion} · 块 ${e.chunkIndex}`,e.content,`${e.sourceId} / ${e.documentId}`));root.append(card);
  }
  if(r.order){const o=r.order,card=node('article','','evidence-card');card.append(node('h3','本轮订单查询'),node('p',o.answer));
    if(o.lookup)card.append(fields([['结果码',o.lookup.code],['订单号',o.lookup.orderNo],['实际状态',o.lookup.status],['样例日期',o.lookup.expectedDeliveryDate]]));root.append(card);
  }
  if(r.afterSale){const a=r.afterSale,card=node('article','','evidence-card');card.append(node('h3','本轮售后预检查'),node('p','只读预检查 · 未提交申请 · 未执行退款','readonly'));
    if(!a.assessments.length)card.append(node('p','本轮未取得检查记录，请按答复补充信息。'));
    for(const result of a.assessments){card.append(node('p',result.explanation),fields([['检查状态',result.status],['用户诉求',result.claimedReason],['检查时间',result.checkedAt],['执行退款',String(result.refundExecuted)]]));
      const f=result.verifiedFacts;if(f)card.append(fields([['订单号',f.orderNo],['商品类型',f.productType],['签收时间',f.signedAt],['无理由截止',f.noReasonDeadline],['质量核验',f.qualityVerification],['适用政策',`${f.policySourceId} / ${f.policyVersion}`]]));
      result.evidence.forEach(e=>evidence(card,`${e.sourceId} · v${e.sourceVersion}`,e.text,e.documentId));
    }
    if(a.explanationFiltered)card.append(node('p','模型解释已替换为 Java 检查结果。','fine'));root.append(card);
  }
  if(r.humanStatus){const card=node('article','','evidence-card');card.append(node('h3','人工通道'),node('p',r.answer),node('p',r.humanStatus,'fine'));root.append(card);}
}
/** 诊断与真正执行使用不同标签；分类器调用数不冒充整轮模型调用数。 */
function render(r,diagnostic=false){
  const d=r.decision;
  document.querySelectorAll('[data-route]').forEach(n=>n.classList.toggle('active',n.dataset.route===d.route));
  $('trace-type').textContent=diagnostic?'仅诊断 · 未处理':'实际处理';
  $('decision').replaceChildren(fields([['流程',`${routes[d.route]} / ${d.route}`],['选择来自',sources[d.source]],['原因',reasons[d.reasonCode]||d.reasonCode],['路由耗时',`${r.routing.elapsedMs} ms`],['分类器调用',`${r.routing.classifierCalls} 次`],...(diagnostic?[]:[['业务状态',r.status]])],'decision-grid'));
  renderPayload(r);$('raw').textContent=JSON.stringify(r,null,2);$('raw-box').hidden=false;
}

/** 当前接待卡只接受本会话的新版本；历史消息的路由不代表当前接待状态。 */
function applyReceipt(r){
  if(r.conversationId!==conversation||(receipt&&r.version<receipt.version))return false;
  receipt=r;const option=Array.from($('conversation-list').options).find(o=>o.value===conversation);if(option)option.textContent=`${modes[r.mode]} · ${conversation.slice(0,8)}`;$('reception-mode').textContent=modes[r.mode];$('reception-message').textContent=r.message;
  const details=fields([['会话',r.conversationId],['受理编号',r.handoffId||'尚未申请'],['状态 / 版本',`${r.mode} / ${r.version}`],['领取客服',r.assignedAgentId||'尚未领取'],['受理时间',r.requestedAt||'—'],['领取时间',r.acceptedAt||'—'],['结束时间',r.closedAt||'—']]);
  $('receipt').replaceChildren(...details.childNodes);controls();return true;
}
function showMessage(m){
  $('messages').querySelector('.empty')?.remove();
  const item=node('article','',`turn ${m.role==='USER'?'user':m.role==='SYSTEM'?'system':'assistant'}`);
  item.append(node('strong',m.role==='USER'?'客户':m.role==='SYSTEM'?'系统记录':'云杉客服'),node('div',m.content),node('p',`#${m.id} · ${new Date(m.createdAt).toLocaleString()} · v${m.version}`,'fine'));
  if(m.payload){const button=node('button','查看这条历史消息的流程和依据');button.type='button';button.addEventListener('click',()=>render(m.payload));item.append(button);}
  $('messages').append(item);$('messages').scrollTop=$('messages').scrollHeight;
}
/** 分页读取正式记录。整页快照落后于已知版本时不推进游标，下次重读，避免网络乱序。 */
async function refresh(){
  if(!conversation||refreshing)return;
  refreshing=true;const id=conversation,token=epoch;
  try{
    let more=true;
    while(more){
      const data=await request(`/api/handoff/conversations/${id}/messages?after=${cursor}`);
      if(token!==epoch||id!==conversation)return;
      if(!applyReceipt(data.receipt))return;
      data.messages.forEach(showMessage);cursor=data.nextCursor;more=data.hasMore;
    }
  }finally{refreshing=false;}
}
async function selectConversation(id){
  epoch++;conversation=id;receipt=null;cursor=0;pending=null;
  sessionStorage.setItem('handoff-last-conversation',id);
  $('conversation-list').value=id;$('session').textContent=`账户 ${account.username} · 会话 ${id}`;
  $('messages').replaceChildren(node('p','正在读取正式记录…','empty'));resetTrace();controls();
  // 旧读取完成后才开始下一次，旧结果由 epoch 拒绝；轮询会补齐本次读取。
  await refresh();
  if(!$('messages').querySelector('.turn'))$('messages').replaceChildren(node('p','暂无正式消息，可以开始咨询。','empty'));
}
async function loadConversations(preferred){
  const list=await request('/api/handoff/conversations');
  $('conversation-list').replaceChildren(...list.map(r=>{const o=node('option',`${modes[r.mode]} · ${r.conversationId.slice(0,8)}`);o.value=r.conversationId;return o;}));
  if(!list.length){await createConversation();return;}
  await selectConversation(list.some(r=>r.conversationId===preferred)?preferred:list[0].conversationId);
}
async function createConversation(){
  const r=await request('/api/handoff/conversations','POST');
  await loadConversations(r.conversationId);$('feedback').textContent='新会话已保存。';
}
async function initialize(){
  const s=await request('/internal/handoff/session');csrf=s.csrfToken;csrfHeader=s.csrfHeader;account=s.authenticated?s:null;
  $('login-panel').hidden=!!account;$('reception').hidden=!account;
  if(account){
    $('account').textContent=`已验证账户：${s.username} · 本地教学数据`;
    if(s.authorities.includes('customer:chat'))await loadConversations(sessionStorage.getItem('handoff-last-conversation'));
    else{$('reception-message').textContent='当前为客服账户，请通过 /api/support 接口领取和结束接待。客户对话请使用 customer1001 或 customer2002 登录。';$('logout').disabled=false;}
  }
  controls();if(account&&!account.authorities.includes('customer:chat'))$('logout').disabled=false;
}
/** 一次提交保留同一 UUID；网络错误后的同正文重试不会重复保存或重复调用模型。 */
async function send(diagnostic){
  if(busy||!conversation)return;const message=$('question').value.trim();if(!message)return;
  busy=true;controls();resetTrace();$('feedback').textContent=diagnostic?'正在诊断…':'正在保存并处理；此时仍可申请人工。';
  try{
    if(diagnostic){render(await request('/internal/routing/decide','POST',{message}),true);$('feedback').textContent='诊断完成，未执行业务或保存消息。';}
    else{
      if(!pending||pending.text!==message)pending={text:message,id:crypto.randomUUID()};
      const r=await request(`/internal/routing/conversations/${conversation}/messages`,'POST',{message,clientMessageId:pending.id});
      pending=null;
      const current=applyReceipt(r.receipt);
      if(current&&r.published&&r.message?.payload)render(r.message.payload);
      $('feedback').textContent=r.published?'机器人消息已正式保存。':r.deliveryStatus==='STALE_DISCARDED'?'接待状态已改变，本轮候选回答已丢弃。':r.deliveryStatus==='REPLAY'?'这条消息已收到，请查看正式记录。':'消息或人工申请已保存，请查看接待状态。';
      await refresh();
    }
  }catch(e){$('feedback').textContent=`${e.message} 状态可能已经保存，请先刷新记录；同一正文重试会复用消息编号。`;}
  finally{busy=false;controls();}
}
$('login-form').addEventListener('submit',async e=>{
  e.preventDefault();$('login').disabled=true;
  try{
    const response=await fetch('/internal/handoff/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[csrfHeader]:csrf},body:new URLSearchParams({username:$('username').value,password:$('password').value})});
    $('password').value='';if(!response.ok)throw new Error('登录失败，请检查本地账号和密码，刷新后重试。');
    await initialize();$('login-feedback').textContent='';
  }catch(error){$('login-feedback').textContent=error.message;}finally{$('login').disabled=false;}
});
$('logout').addEventListener('click',async()=>{
  try{await request('/internal/handoff/logout','POST');epoch++;conversation=null;receipt=null;account=null;cursor=0;pending=null;
    $('messages').replaceChildren();$('receipt').replaceChildren();$('account').textContent='';$('session').textContent='请先登录。';resetTrace();await initialize();
  }catch(e){$('feedback').textContent=e.message;}
});
$('handoff').addEventListener('click',async()=>{
  if(handoffBusy||!conversation)return;handoffBusy=true;controls();
  try{applyReceipt(await request(`/api/handoff/conversations/${conversation}/handoff`,'POST'));await refresh();$('feedback').textContent='人工申请已保存，受理编号见上方。';}
  catch(e){$('feedback').textContent=`暂时无法确认申请状态：${e.message} 请刷新查询或再次申请，不会重复生成受理单。`;try{await refresh();}catch{/* 保留未知状态提示，不宣称受理失败。 */}}
  finally{handoffBusy=false;controls();}
});
$('refresh-state').addEventListener('click',()=>refresh().catch(e=>{$('feedback').textContent=e.message;}));
$('conversation-list').addEventListener('change',()=>selectConversation($('conversation-list').value).catch(e=>{$('feedback').textContent=e.message;}));
$('chat-form').addEventListener('submit',e=>{e.preventDefault();send(false);});
$('diagnose').addEventListener('click',()=>send(true));
$('example').addEventListener('change',()=>{$('question').value=examples[$('example').value];});
$('new-session').addEventListener('click',async()=>{if(busy)return;busy=true;controls();try{await createConversation();}catch(e){$('feedback').textContent=e.message;}finally{busy=false;controls();}});
$('clear').addEventListener('click',async()=>{if(busy||!conversation)return;busy=true;controls();try{applyReceipt(await request(`/internal/routing/conversations/${conversation}/memory`,'DELETE'));resetTrace();await refresh();$('feedback').textContent='上下文已重置，正式记录仍保留。';}catch(e){$('feedback').textContent=e.message;}finally{busy=false;controls();}});
$('refresh-metrics').addEventListener('click',async()=>{try{const m=await request('/internal/routing/metrics');$('metrics').replaceChildren(fields([['已分类',m.decisions],['规则命中',`${m.ruleHits} / ${m.decisions}`],['分类器调用',m.classifierCalls],['澄清 / 兜底',m.clarifications],['分类故障',m.classificationFailures],['经路由选择人工',m.humanRequests],['平均耗时',`${m.averageRoutingMs.toFixed(1)} ms`],['路由分布',Object.entries(m.routes).map(([route,n])=>`${routes[route]} ${n}`).join(' · ')||'暂无']]));}catch(e){$('feedback').textContent=e.message;}});
// 普通 HTTP 轮询；不宣称具有 SSE、实时坐席在线状态或实时消息推送。
setInterval(()=>{if(!document.hidden)refresh().catch(e=>{$('feedback').textContent=`状态刷新未完成：${e.message}`;});},3000);
controls();initialize().catch(e=>{$('login-panel').hidden=false;$('login-feedback').textContent=e.message;});
