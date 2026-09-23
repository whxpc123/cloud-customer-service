/** 第十四章只渲染一次真实接口结果；正文、编码及错误均使用 textContent，禁止把资料作为 HTML 执行。 */
'use strict';
const $=id=>document.getElementById(id);
const el=(tag,text,cls='')=>{const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;};
let busy=false;
const score=value=>value==null?'—':Number(value).toFixed(6);
/** 排名按当前列表展示；原始向量、关键词、RRF、模型分数分别标注，互不冒充。 */
function cards(id,docs,kind){
 const root=$(id);root.replaceChildren();
 if(!docs.length){root.append(el('p','本路没有命中。','fine'));return;}
 docs.forEach((d,i)=>{const h=d.hybrid||{},box=el('details','','rank-doc');
 box.append(el('summary',`${i+1}. ${d.sourceName||d.sourceId} · 第 ${d.chunkIndex} 块`));
 box.append(el('p',`来源 ${d.sourceId} · 版本 ${d.sourceVersion||'未记录'}\n`+(kind==='vector'?`向量 ${score(d.score)}`:kind==='keyword'?`关键词 ${score(d.score)}`:kind==='exact'?'编码完整值命中（不评分）':`召回 ${(h.retrievalSources||[]).join(' + ')} · 精确优先 ${h.exactMatch?'是':'否'}\n向量名次 ${h.vectorRank??'—'} / 分数 ${score(h.vectorScore)} · 关键词名次 ${h.keywordRank??'—'} / 分数 ${score(h.keywordScore)}\nRRF ${score(h.rrfScore)} · 模型重排 ${score(d.rerankScore)}`),'fine'));
 box.append(el('p',d.content));root.append(box);});
}
$('hybrid-form').addEventListener('submit',async event=>{
 event.preventDefault();if(busy)return;busy=true;document.querySelectorAll('button,select,textarea').forEach(n=>n.disabled=true);
 $('result').hidden=true;$('feedback').textContent='正在查询三路候选…';
 try{
 const response=await fetch('/internal/hybrid-search/compare',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({question:$('question').value.trim(),topK:Number($('top-k').value),rerankEnabled:$('rerank').value==='true'})});
 const r=await response.json();if(!response.ok)throw new Error(response.status===429?'请求过于频繁，请稍后再试。':r.message||'检索失败，无法展示完整对照。');
 $('feedback').textContent=r.message;if(r.status!=='COMPLETED')return;
 $('result').hidden=false;$('stats').replaceChildren();[['vector','向量命中'],['keyword','关键词命中'],['exact','精确命中'],['fused','融合候选']].forEach(([key,label])=>{const n=el('div','','rank-stat');n.append(el('strong',String(r[key].length)),el('span',label));$('stats').append(n);cards(key,r[key],key);});
 $('query-trace').textContent=`识别编码：${r.identifiers.join('、')||'无'} · 实际关键词查询：${r.keywordQuery} · requestId ${r.requestId}`;
 const t=r.reranking;cards('final',t.finalDocuments,'final');$('final-status').textContent=`重排状态 ${t.status} · 最终 ${t.finalDocuments.length} 块 · 正文估算 ${t.estimatedContextTokens} tokens。`+(t.status==='FALLBACK_NOT_CONFIGURED'?'未配置百炼业务空间地址，本次未调用重排，保留融合顺序。':'')+(t.status==='SUCCEEDED'?'精确命中按业务优先级置前，展示的模型分数保持原值。':'');$('raw').textContent=JSON.stringify(r,null,2);
 }catch(error){$('feedback').textContent=error instanceof TypeError?'无法连接服务，请检查 18080。':error.message;}
 finally{busy=false;document.querySelectorAll('button,select,textarea').forEach(n=>n.disabled=false);}
});
