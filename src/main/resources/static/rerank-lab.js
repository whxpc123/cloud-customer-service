/** 同一批真实候选的 A/B 实验；所有供应商、查询和知识文本用 textContent 渲染。 */
'use strict';
const $=id=>document.getElementById(id);
let conversationId=null,busy=false;
const statusNames={SUCCEEDED:'重排成功',DISABLED:'未启用重排',SKIPPED_EMPTY:'无候选，未调用重排',SKIPPED_SINGLE:'只有一条候选，跳过重排',FALLBACK_NOT_CONFIGURED:'未配置业务空间地址，已降级',FALLBACK_ERROR:'重排服务失败，已降级',FALLBACK_INPUT_LIMIT:'查询超出输入预算，已降级',SKIPPED_INPUT_LIMIT:'候选全部超出重排输入预算',NOT_STARTED:'未执行'};
const reasonNames={TOP_N_WITHOUT_RERANK:'未进入原排序 Top N',FALLBACK_TOP_N:'未进入降级 Top N',CANDIDATE_LIMIT:'超过 24 条候选上限',RERANK_INPUT_BUDGET:'超出重排输入预算',TOP_N:'未进入重排 Top N',TOKEN_BUDGET:'超出最终正文 Token 预算',MAX_DOCUMENTS:'超过最终 6 块上限',EMPTY_TEXT:'空正文'};
function el(tag,text,cls=''){const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;}
/** 身份固定为本地演示用户，租户及资料范围仍由服务端确定。 */
async function request(path,body){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Demo-User-Id':'1001'},...(body?{body:JSON.stringify(body)}:{})});const d=await r.json();if(!r.ok)throw new Error(d.message||`请求失败：${r.status}`);return d;}
function lock(value){busy=value;document.querySelectorAll('button,select,textarea').forEach(n=>n.disabled=value);$('result').setAttribute('aria-busy',String(value));}
/** 每次刷新或主动新建使用新会话，不把页面实验写回模型记忆。 */
async function newSession(){if(busy)return;lock(true);try{const r=await request('/internal/advisor-rag/conversations');conversationId=r.conversationId;$('session').textContent=`用户 1001 · 会话 ${conversationId}`;$('result').hidden=true;$('feedback').textContent='会话已创建，可以开始对照。';}catch(e){$('feedback').textContent='无法连接服务，请确认 18080 已启动。';}finally{lock(false);}}
/** 使用可展开全文；不把模型服务返回的正文作为新知识来源。 */
function cards(target,docs){target.replaceChildren();if(!docs.length)target.append(el('p','本轮没有可用的最终证据。','fine'));docs.forEach((d,i)=>{const n=el('details','','rank-doc');n.append(el('summary',`${i+1}. ${d.sourceName||d.sourceId} · 第 ${d.chunkIndex} 块`),el('p',`文档 ${d.documentId}\n${d.hybrid?.rrfScore != null ? "RRF" : "向量"} ${Number(d.retrievalScore??d.score).toFixed(4)} · 重排 ${d.rerankScore==null?'—':Number(d.rerankScore).toFixed(4)}`,'fine'),el('p',d.content));target.append(n);});}
/** before/after/finalDocuments 都来自同一 HTTP 响应，降级时不绘制虚假的重排分数。 */
function render(r){$('result').hidden=false;const t=r.reranking,a=r.baseline,success=t.status==='SUCCEEDED';
 $('feedback').textContent=r.status==='COMPLETED'?(statusNames[t.status]||t.status):r.status==='NEEDS_CLARIFICATION'?'缺少可靠上文，请先输入完整问题。':'检索或证据校验失败，本次对照没有完成。';
 $('notice').textContent=t.status==='FALLBACK_NOT_CONFIGURED'?'重排未调用：需要配置百炼业务空间地址。当前展示原顺序的降级结果，不能作为排序收益。':success?'重排已完成。请逐块核对最终资料是否覆盖客户的每个问题。':'本次未获得成功的重排结果。请结合状态和原始 JSON 判断，不能把降级分数当作精排分数。';
 $('stats').replaceChildren();[[t.before.length,'合并候选'],[t.inputCount,'送入重排的候选'],[t.finalDocuments.length,'最终知识块'],[t.durationMs+' ms','排序阶段耗时']].forEach(([v,k])=>{const n=el('div','','rank-stat');n.append(el('strong',String(v)),el('span',k));$('stats').append(n);});
 $('query-trace').textContent=`重排依据的完整问题：${t.query||'未执行'}\n实际检索：${r.expansion.retrievals.map(b=>b.query).join(' | ')}\nrequestId ${r.requestId} · 供应商 total_tokens ${t.totalTokens??'未提供 / 未调用'}`;
 const ranked=new Map(t.after.map((d,i)=>[d.documentId,{doc:d,rank:i+1}]));$('ranking').replaceChildren();t.before.forEach((d,i)=>{const n=el('tr'),match=ranked.get(d.documentId);n.append(el('td',String(i+1)),el('td',success&&match?String(match.rank):'—'));const cell=el('td'),detail=el('details');detail.append(el('summary',`${d.sourceName||d.sourceId} · 第 ${d.chunkIndex} 块`),el('p',d.content));cell.append(detail);n.append(cell,el('td',Number(d.retrievalScore??d.score).toFixed(4)),el('td',match?.doc.rerankScore==null?'—':Number(match.doc.rerankScore).toFixed(4)));$('ranking').append(n);});
 cards($('baseline'),a.finalDocuments);cards($('final-documents'),t.finalDocuments);$('baseline-note').textContent=`相同 Top N 和预算；正文估算 ${a.estimatedContextTokens} tokens。`;$('final-note').textContent=`${statusNames[t.status]||t.status} · 正文估算 ${t.estimatedContextTokens} / ${t.options.maxContextTokens} tokens。`;
 $('exclusions').replaceChildren();if(!t.exclusions.length)$('exclusions').append(el('p','没有记录到候选或预算淘汰。','fine'));t.exclusions.forEach(x=>$('exclusions').append(el('p',`${x.documentId} · ${x.stage} · ${reasonNames[x.reason]||x.reason}`,'fine')));$('raw').textContent=JSON.stringify(r,null,2);
}
$('compare-form').addEventListener('submit',async e=>{e.preventDefault();if(busy||!conversationId)return;const body={question:$('question').value.trim(),expansionMode:$('mode').value,perQueryTopK:Number($('candidate-k').value),topN:Number($('top-n').value),maxContextTokens:Number($('budget').value)};if(!body.question)return;lock(true);$('result').hidden=true;$('feedback').textContent='正在召回资料并比较排序，请稍候…';try{render(await request(`/internal/rerank/${encodeURIComponent(conversationId)}/compare`,body));}catch(e){$('feedback').textContent=e instanceof TypeError?'无法连接服务，请确认 18080 已启动。':e.message;}finally{lock(false);}});
$('new-session').addEventListener('click',newSession);newSession();
