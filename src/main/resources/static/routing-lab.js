/* 第十六章：所有动态文本通过 textContent 展示，模型与文档不能注入 HTML。Cookie 会话和 CSRF 由同源 API 建立。 */
'use strict';
const base='/internal/routing';
const $=id=>document.getElementById(id);
const node=(tag,text='',className='')=>{const n=document.createElement(tag);n.textContent=text;n.className=className;return n;};
const routes={SMALL_TALK:'问候',KNOWLEDGE:'知识问答',ORDER_QUERY:'订单查询',AFTER_SALE_PRECHECK:'售后预检查',HUMAN_SERVICE:'人工服务',CLARIFY:'澄清 / 兜底',OUT_OF_SCOPE:'服务范围'};
const sources={RULE:'完整命令规则',MODEL:'模型分类',GUARD:'Java 兜底检查'};
const reasons={EXACT_SMALL_TALK:'纯问候、感谢或告别',EXPLICIT_HUMAN_COMMAND:'明确要求人工',MODEL_CLASSIFIED:'模型建议通过字段检查',AMBIGUOUS_INTENT:'任务尚不明确',MULTIPLE_INDEPENDENT_TASKS:'多个独立事项，需选择处理顺序',INVALID_MODEL_RESULT:'模型格式或字段无效，未执行业务',MODEL_UNAVAILABLE:'分类服务故障，未执行业务',HUMAN_SESSION_OWNED:'人工已接管，不进行机器人分类'};
const examples={hello:'你好',policy:'退货运费由谁承担？',order:'A10001 发货了吗？',sale:'A10001 的商品有质量问题，能退吗？',missing:'我要查订单',negation:'不要转人工，先告诉我退货规则。',multi:'帮我查 A10001 发货没有，另外告诉我电子发票怎么开。',human:'我要转人工',unrelated:'帮我写一篇完全无关的小说。'};
let csrf=null,conversation=null,busy=false;
/** 全页面串行操作，避免新会话、清空与尚未完成的回答发生交错。 */
function lock(value){busy=value;document.querySelectorAll('button,select,textarea').forEach(n=>n.disabled=value);}
async function request(path,method='GET',body){
  const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(csrf?{'X-Routing-CSRF':csrf}:{})},...(body?{body:JSON.stringify(body)}:{})});
  if(response.status===204)return null;
  const data=await response.json();
  if(!response.ok)throw new Error(data.message||`请求失败 ${response.status}，请刷新页面后重试。`);
  return data;
}
function resetTrace(){
  document.querySelectorAll('[data-route]').forEach(n=>n.classList.remove('active'));
  $('decision').replaceChildren(node('p','发送后显示规则或模型的选择、原因与耗时。','empty'));
  $('payload').replaceChildren();$('raw-box').hidden=true;$('trace-type').textContent='等待提问';
}
async function newSession(){
  if(busy)return;lock(true);
  try{const session=await request('/session');csrf=session.csrfToken;const created=await request('/conversations','POST');conversation=created.conversationId;
    $('session').textContent=`本地演示 · 会话 ${conversation}`;$('messages').replaceChildren(node('p','可以先说“你好”，或直接提问。','empty'));resetTrace();$('feedback').textContent='会话已建立。仅诊断不会写入历史。';
  }catch(e){$('feedback').textContent=e.message;}finally{lock(false);}
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
function turn(who,text,r){
  $('messages').querySelector('.empty')?.remove();const item=node('article','',`turn ${who==='客户'?'user':'assistant'}`);item.append(node('strong',who),node('div',text));
  if(r){const button=node('button','查看这一轮的流程和依据');button.type='button';button.addEventListener('click',()=>render(r));item.append(button);}
  $('messages').append(item);while($('messages').children.length>20)$('messages').firstElementChild.remove();$('messages').scrollTop=$('messages').scrollHeight;
}
async function send(diagnostic){
  if(busy||!conversation)return;const message=$('question').value.trim();if(!message)return;lock(true);resetTrace();
  $('feedback').textContent=diagnostic?'正在诊断当前这一句…':'正在选择流程并处理问题…';
  try{const r=await request(diagnostic?'/decide':`/conversations/${encodeURIComponent(conversation)}/messages`,'POST',{message});
    if(!diagnostic){turn('客户',message);turn(`云杉客服 · ${routes[r.decision.route]}`,r.answer,r);}
    render(r,diagnostic);$('feedback').textContent=diagnostic?'诊断完成，未写入历史，也未调用业务处理器。':`处理完成 · ${r.requestId}`;
  }catch(e){$('feedback').textContent=e.message;}finally{lock(false);}
}
$('chat-form').addEventListener('submit',e=>{e.preventDefault();send(false);});
$('diagnose').addEventListener('click',()=>send(true));
$('example').addEventListener('change',()=>{$('question').value=examples[$('example').value];});
$('new-session').addEventListener('click',newSession);
$('clear').addEventListener('click',async()=>{if(busy||!conversation)return;lock(true);try{await request(`/conversations/${encodeURIComponent(conversation)}/memory`,'DELETE');$('messages').replaceChildren(node('p','会话已清空，可以开始新的问题。','empty'));resetTrace();$('feedback').textContent='本会话记忆已清空。';}catch(e){$('feedback').textContent=e.message;}finally{lock(false);}});
$('refresh-metrics').addEventListener('click',async()=>{if(busy)return;lock(true);try{const m=await request('/metrics');$('metrics').replaceChildren(fields([['已分类',m.decisions],['规则命中',`${m.ruleHits} / ${m.decisions}`],['分类器调用',m.classifierCalls],['澄清 / 兜底',m.clarifications],['分类故障',m.classificationFailures],['人工请求',m.humanRequests],['平均耗时',`${m.averageRoutingMs.toFixed(1)} ms`],['路由分布',Object.entries(m.routes).map(([route,n])=>`${routes[route]} ${n}`).join(' · ')||'暂无']]));}catch(e){$('feedback').textContent=e.message;}finally{lock(false);}});
newSession();
