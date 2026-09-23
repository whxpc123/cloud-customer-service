/** 第十一章实验：真实会话上文→只读转换→可选向量对比，所有返回内容作为纯文本呈现。 */
'use strict';
const $ = id => document.getElementById(id);
let conversationId = null, user = '1001', busy = false;
const stageNames = {COMPRESSION:'补全上下文', REWRITE:'优化检索表达'};
const statuses = {TRANSFORMED:'已转换',UNCHANGED:'保持原问题',SKIPPED_NO_HISTORY:'无历史，跳过模型',NEEDS_CLARIFICATION:'需要补充场景',FALLBACK_ERROR:'模型失败，回退输入',FALLBACK_INVALID:'输出校验未通过，回退输入',SKIPPED_CLARIFICATION:'等待澄清，跳过'};
function node(tag,text,cls='') {const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;}
function lock(value) {busy=value;document.querySelectorAll('button,textarea,select,input').forEach(n=>n.disabled=value);$('result').setAttribute('aria-busy',String(value));}
function headers() {return {'Content-Type':'application/json',...(user==='guest'?{}:{'X-Demo-User-Id':user})};}
async function request(path,options={}) {
  const r=await fetch(path,options);let data;try{data=await r.json();}catch{throw new Error('服务未返回 JSON，请确认已启用 local,knowledge。');}
  if(!r.ok)throw new Error(data.message||`请求失败（${r.status}），请检查服务日志。`);return data;
}
function fail(e) {$('feedback').textContent=e instanceof TypeError?'无法连接应用，请检查 IDEA 是否正在运行。':e.message;}
/** 每次新会话清掉旧轨迹，身份切换失败则恢复旧选择，避免混淆数据归属。 */
async function newSession() {
  if(busy)return;const desired=$('demo-user').value;lock(true);
  try {
    const data=await request('/internal/advisor-rag/conversations',{method:'POST'});
    conversationId=data.conversationId;user=desired;$('session-id').textContent=`会话 ${conversationId} · ${user==='guest'?'访客':'用户 '+user}`;
    $('history-log').replaceChildren(node('p','空历史：可先发送完整问题，也可直接测试含糊追问。','fine'));
    clearResult();$('feedback').textContent='新会话已创建。';
  }catch(e){$('demo-user').value=user;fail(e);}finally{lock(false);}
}
function clearResult() {
  $('original-query').textContent='等待转换';$('transformed-query').textContent='先建立上文，再观察这一句如何补全。';
  $('stages').replaceChildren();$('trace-summary').textContent='';$('raw-details').hidden=true;$('raw-result').textContent='';$('comparison').hidden=true;
}
/** 建立历史走真实问答接口，即使无证据也由服务端记录原始客户问题。 */
$('history-form').addEventListener('submit',async e=>{
  e.preventDefault();if(busy||!conversationId)return;const question=$('history-question').value.trim();if(!question)return;
  lock(true);clearResult();$('feedback').textContent='正在发送上文并检索知识…';
  try{
    const data=await request(`/internal/advisor-rag/conversations/${encodeURIComponent(conversationId)}/messages`,{method:'POST',headers:headers(),body:JSON.stringify({question})});
    $('history-log').append(node('article','我：'+question),node('article',`云杉客服（${data.status}）：\n${data.answer}`));
    $('feedback').textContent='本轮已写入会话。现在可以观察追问转换。';
  }catch(e){fail(e);}finally{lock(false);}
});
/** 展示完整块正文，不把标签或脚本内容解释成 HTML。 */
function hits(id,title,search) {
  const target=$(id);target.replaceChildren(node('h3',title));
  if(!search){target.append(node('p','本次未完成这一侧检索。','fine'));return;}
  target.append(node('p',search.query,'fine'));
  if(!search.hits.length)target.append(node('p','当前阈值下没有命中资料。','fine'));
  search.hits.forEach((h,i)=>{const d=node('details','', 'hit');d.append(node('summary',`${i+1}. ${h.sourceName||h.sourceId} · ${Number(h.score).toFixed(4)}`),node('p',h.content));target.append(d);});
}
$('transform-form').addEventListener('submit',async e=>{
  e.preventDefault();if(busy||!conversationId)return;const question=$('question').value.trim();if(!question)return;
  const body={question,rewrite:$('rewrite').checked,compare:$('compare').checked};lock(true);clearResult();$('feedback').textContent='正在生成独立查询…';
  try{
    const data=await request(`/internal/query-transformation/${encodeURIComponent(conversationId)}/compress`,{method:'POST',headers:headers(),body:JSON.stringify(body)});
    const t=data.transformation;$('original-query').textContent=t.originalQuery;$('transformed-query').textContent=t.transformedQuery;
    $('trace-summary').textContent=`历史 ${t.historyMessageCount} 条 · ${t.clarificationRequired?'需要补充场景，未执行检索':'可继续核对检索结果'} · requestId ${data.requestId}`;
    t.stages.forEach(s=>$('stages').append(node('p',`${stageNames[s.name]||s.name} / ${statuses[s.status]||s.status} / ${s.durationMs} ms`)));
    $('raw-result').textContent=JSON.stringify(data,null,2);$('raw-details').hidden=false;
    if(body.compare){$('comparison').hidden=false;hits('original-hits','原始问题',data.originalSearch);hits('transformed-hits','转换问题',data.transformedSearch);}
    $('feedback').textContent=t.clarificationRequired?'缺少可靠指代依据，请补充具体商品和场景。':data.comparisonStatus==='FAILED'?'转换已完成，但对比检索失败，请检查数据库或向量服务。':'转换实验完成。请检查对象、数字、否定词与条件是否保留。';
  }catch(e){fail(e);}finally{lock(false);}
});
document.querySelectorAll('[data-sample]').forEach(b=>b.addEventListener('click',()=>{if(!busy)$('history-question').value=b.dataset.sample;}));
$('new-session').addEventListener('click',newSession);$('demo-user').addEventListener('change',newSession);newSession();
