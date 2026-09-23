/** 第十二章页面：生成真实扩展查询，并按用户选择调用单路基线和多路检索。 */
'use strict';
const $ = id => document.getElementById(id);
let conversationId = null, user = '1001', busy = false;
const labels = {EXPANDED:'已扩展',PARTIAL:'部分变体通过校验',FALLBACK_INVALID:'扩展无效，已回退完整问题',FALLBACK_ERROR:'扩展失败，已回退完整问题',SKIPPED_CLARIFICATION:'需要补充场景',SKIPPED_SIMPLE:'单问题，跳过扩展',DISABLED:'已关闭扩展'};

/** 所有模型文字、资料正文和来源都使用 textContent，禁止解释成 HTML。 */
function node(tag, text, cls='') { const n=document.createElement(tag); n.textContent=text; n.className=cls; return n; }
/** 锁定当前身份与实验参数，避免同一会话发生交错收费请求。 */
function lock(value) { busy=value; document.querySelectorAll('button,select,input,textarea').forEach(n=>n.disabled=value); $('results').setAttribute('aria-busy',String(value)); }
/** 演示身份沿用知识会话协议；租户和过滤器由后端固定。 */
function headers() { return {'Content-Type':'application/json',...(user==='guest'?{}:{'X-Demo-User-Id':user})}; }
/** 网络失败不自动重试，避免页面重复产生模型费用。 */
async function request(path, options={}) {
  const response=await fetch(path,options);
  let data; try { data=await response.json(); } catch { throw new Error('服务未返回 JSON，请确认 local,knowledge 已启用。'); }
  if(!response.ok) throw new Error(data.message||`请求失败（${response.status}），请检查服务日志。`);
  return data;
}
/** 错误信息使用明确的下一步，服务端不暴露模型供应商原始异常。 */
function fail(error) { $('feedback').textContent=error instanceof TypeError?'无法连接应用，请确认 18080 服务已启动。':error.message; }
/** 切换输入前清除旧结果，避免让旧检索数据冒充本次结果。 */
function clearResult() {
  $('pipeline').replaceChildren(node('p','等待本次查询结果。','fine'));
  $('metrics').replaceChildren(); $('trace').textContent=''; $('raw-details').hidden=true; $('retrieval').hidden=true;
}
/** 新会话只分配 ID，不自动请求模型；刷新也创建新会话。 */
async function newSession() {
  if(busy)return;
  const selected=$('demo-user').value; lock(true);
  try {
    const result=await request('/internal/advisor-rag/conversations',{method:'POST'});
    conversationId=result.conversationId; user=selected;
    $('session-id').textContent=`会话 ${conversationId} · ${user==='guest'?'访客':'用户 '+user}`;
    $('history-log').textContent=''; clearResult(); $('feedback').textContent='新会话已创建，可以直接测试完整的多问题。';
  } catch(error) { $('demo-user').value=user; fail(error); } finally { lock(false); }
}
/** 估算请求上限而非实际计费，实际调用与候选数量在响应里核对。 */
function costNote() {
  const routes=Number($('query-count').value)+Number($('include-original').checked);
  $('cost-note').textContent=$('compare').checked
    ? `最多 ${routes+1} 路真实检索（含单路基线），每路生成查询向量；另有一次扩展模型调用，有历史时还会补全问题。`
    : '只生成查询：一次扩展模型调用，有历史时还会先补全。不执行检索，也不生成客服回答。';
}
/** 候选卡保留文档 ID 和原始分数，新增只表示不在本次基线结果里，不表示一定正确。 */
function documentCards(target, documents, baselineIds=null) {
  if(!documents.length) target.append(node('p','本次没有可展示的命中资料。','fine'));
  documents.forEach(d=>{
    const fresh=baselineIds && !baselineIds.has(d.documentId);
    const card=node('details','',fresh?'candidate new':'candidate');
    card.append(node('summary',`${fresh?'新增候选 · ':''}${d.sourceName||d.sourceId} · 第 ${d.chunkIndex} 块 · ${Number(d.score).toFixed(4)}`),
      node('p',`documentId: ${d.documentId}\nsourceId: ${d.sourceId} · v${d.sourceVersion}`,'fine'),node('p',d.content));
    target.append(card);
  });
}
/** 用本次真实计划和实际命中呈现路径，未检索时不显示伪造的零候选指标。 */
function render(data) {
  const e=data.expansion;
  $('pipeline').replaceChildren();
  const complete=node('article','', 'query-route original');
  complete.append(node('strong','补全后的完整问题'),node('p',e.transformedQuery)); $('pipeline').append(complete);
  e.queries.forEach((query,index)=>{
    const route=node('article','', 'query-route');
    route.append(node('strong',`Q${index} · ${query===e.transformedQuery?'保留完整问题':'扩展变体'}`),node('p',query)); $('pipeline').append(route);
  });
  $('feedback').textContent=`${labels[e.status]||e.status}。${data.comparisonStatus==='FAILED'?'检索对比失败，不能将部分候选当作完整依据。':data.comparisonStatus==='SKIPPED_CLARIFICATION'?'缺少可靠上文，本次未检索。':'请检查每条查询是否保留了原问题的条件。'}`;
  $('trace').textContent=`原始问题：${e.originalQuery}\n历史 ${data.transformation.historyMessageCount} 条 · 扩展 ${e.expansionDurationMs} ms · 丢弃 ${e.rejectedVariants} 条变体 · requestId ${data.requestId}`;
  if(data.comparisonStatus!=='NOT_REQUESTED') renderRetrieval(data);
  $('raw-result').textContent=JSON.stringify(data,null,2); $('raw-details').hidden=false;
}
/** 合并统计和两侧文档对应同一次实验；失败时合并数量不标成召回率。 */
function renderRetrieval(data) {
  const e=data.expansion; $('retrieval').hidden=false;
  $('metrics').replaceChildren();
  if(e.retrievalStatus==='COMPLETED') {
    [[e.rawDocumentCount,'原始候选'],[e.duplicateDocumentCount,'重复 ID'],[e.joinedDocumentCount,'合并候选'],[e.contextCharacters,'上下文字符']].forEach(([value,label])=>{
      const n=node('div','', 'metric'); n.append(node('strong',String(value)),node('span',label)); $('metrics').append(n);
    });
  }
  $('route-settings').textContent=`每路 Top ${e.perQueryTopK} · 阈值 0.60`;
  $('retrieval-note').textContent=`实际执行 ${e.retrievals.length} 路 · 每路 Top ${e.perQueryTopK} · ${e.retrievalStatus} · Token 启发式估算 ${e.contextTokensEstimated}，不是账单用量。`;
  $('branches').replaceChildren();
  e.retrievals.forEach(b=>{
    const row=node('details','', 'branch');
    row.append(node('summary',`Q${b.index} · ${b.documents.length} 块 · ${b.durationMs} ms · ${b.status}\n${b.query}`));
    documentCards(row,b.documents); $('branches').append(row);
  });
  $('baseline').replaceChildren(node('h3','单路基线 · Top 5'));
  $('joined').replaceChildren(node('h3','多路合并 · 按 ID 去重'));
  const baseline=data.baseline;
  if(baseline) { $('baseline').append(node('p',`${baseline.status} · ${baseline.durationMs} ms`,'fine')); documentCards($('baseline'),baseline.documents); }
  else $('baseline').append(node('p','本次未执行基线检索。','fine'));
  const ids=baseline?.status==='COMPLETED'?new Set(baseline.documents.map(d=>d.documentId)):null;
  documentCards($('joined'),e.joinedDocuments,ids);
  $('difference').textContent=ids&&e.retrievalStatus==='COMPLETED'
    ? `相对本次单路 Top 5，合并结果新增 ${e.joinedDocuments.filter(d=>!ids.has(d.documentId)).length} 个文档 ID。这不是正确知识覆盖率，需要逐块核对。`
    : '对比未完整执行，不计算新增候选差异。';
}
/** 一次实验只提交一次模型请求；后端负责限量、过滤和回退。 */
$('expand-form').addEventListener('submit',async event=>{
  event.preventDefault(); if(busy||!conversationId)return;
  const question=$('question').value.trim(); if(!question)return;
  const input={question,numberOfQueries:Number($('query-count').value),includeOriginal:$('include-original').checked,compare:$('compare').checked};
  lock(true); clearResult(); $('feedback').textContent='正在补全问题并扩展检索视角…';
  try { render(await request(`/internal/query-expansion/${encodeURIComponent(conversationId)}/expand`,{method:'POST',headers:headers(),body:JSON.stringify(input)})); }
  catch(error) { fail(error); } finally { lock(false); }
});
/** 上文走真实会话接口且显式单路；输出只用于给后续追问提供语境。 */
$('history-form').addEventListener('submit',async event=>{
  event.preventDefault(); if(busy||!conversationId)return;
  const question=$('history-question').value.trim(); if(!question)return;
  lock(true); clearResult(); $('feedback').textContent='正在建立真实会话历史…';
  try {
    const r=await request(`/internal/advisor-rag/conversations/${encodeURIComponent(conversationId)}/messages`,{method:'POST',headers:headers(),body:JSON.stringify({question,expansionMode:'OFF'})});
    $('history-log').append(node('p',`我：${question}\n客服（${r.status}）：${r.answer}`)); $('feedback').textContent='上文已发送，可以继续测试追问。';
  } catch(error) { fail(error); } finally { lock(false); }
});
// 样例只填充输入，不触发收费；身份变化新建会话，防止误用上一身份历史。
document.querySelectorAll('[data-question]').forEach(b=>b.addEventListener('click',()=>{if(!busy){$('question').value=b.dataset.question;clearResult();}}));
['query-count','include-original','compare'].forEach(id=>$(id).addEventListener('change',costNote));
$('new-session').addEventListener('click',newSession); $('demo-user').addEventListener('change',newSession);
costNote(); newSession();
