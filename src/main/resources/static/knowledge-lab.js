'use strict';
const $=id=>document.getElementById(id);
let busy=false;
function element(tag,cls,text){const e=document.createElement(tag);e.className=cls;if(text!==undefined)e.textContent=text;return e;}
function lock(value){busy=value;document.querySelectorAll('button,textarea,input,select').forEach(e=>e.disabled=value);document.querySelector('.output').setAttribute('aria-busy',String(value));}
function clear(){ $('hits').replaceChildren();$('hits').hidden=true;$('empty-result').hidden=false;$('raw-details').hidden=true;$('raw-result').textContent='';$('result-count').textContent='等待搜索'; }
async function request(path,body){
 const response=await fetch('/internal/knowledge/'+path,{method:'POST',headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
 if(!response.ok)throw new Error(response.status===404?'知识库未启用，请以 local,knowledge 环境启动。':response.status===400?'输入无效，请检查问题、条数和阈值。':response.status===502?'知识库暂不可用，请检查数据库和百炼向量服务后重试。':`请求失败（${response.status}），请检查 IDEA 日志。`);
 return response.json();
}
function message(error){return error instanceof TypeError?'无法连接应用，请确认 IDEA 正在运行。':error.message;}
$('seed').addEventListener('click',async()=>{if(busy)return;lock(true);clear();$('seed-status').textContent='正在生成向量并写入数据库…';$('feedback').textContent='';try{const result=await request('seed');if(result.importedDocuments!==4)throw new Error('导入响应异常，请检查数据库记录。');$('seed-status').textContent='已写入 / 更新 4 条课程知识。可以开始搜索。';}catch(error){$('seed-status').textContent=message(error);}finally{lock(false);}});
document.querySelectorAll('[data-query]').forEach(button=>button.addEventListener('click',()=>{$('query').value=button.dataset.query;clear();$('feedback').textContent='';}));
$('search-form').addEventListener('submit',async event=>{
 event.preventDefault();if(busy)return;clear();
 const query=$('query').value,topK=Number($('top-k').value),threshold=Number($('threshold').value);
 if(!query.trim()||query.length>2000||!Number.isInteger(topK)||topK<1||topK>10||!Number.isFinite(threshold)||threshold<0||threshold>1){$('feedback').textContent='请填写有效问题，条数 1–10，阈值 0–1。';return;}
 lock(true);$('feedback').textContent='正在检索已发布知识…';
 try{
  const data=await request('search',{query,topK,threshold});
  if(!Array.isArray(data.hits)||data.query!==query||data.hits.some(h=>typeof h.content!=='string'||!Number.isFinite(h.score)))throw new Error('检索结果格式异常，请稍后重试。');
  $('result-count').textContent=`返回 ${data.hits.length} 条`;
  $('empty-result').hidden=true;$('hits').hidden=false;
  $('hits').append(element('p','search-context',`本次问题：${query}\n最多 ${topK} 条 · 最低相似度 ${threshold.toFixed(2)}`));
  if(!data.hits.length)$('hits').append(element('p','hit-content','没有符合当前范围和阈值的知识。首次使用请先导入样例，也可调低阈值再观察。'));
  const categories={REFUND_POLICY:'退货条件',REFUND_FREIGHT:'退货运费',LOGISTICS_EXCEPTION:'物流异常',INVOICE_POLICY:'电子发票'};
  data.hits.forEach((hit,index)=>{const card=element('article','hit');const heading=element('div','hit-heading');heading.append(element('strong','',`${index+1}. ${categories[hit.category]||hit.category}`),element('span','',hit.score.toFixed(4)));card.append(heading,element('p','hit-content',hit.content),element('p','hit-source',`${hit.sourceId} · 版本 ${hit.sourceVersion} · 知识块 ${hit.chunkIndex}\n${hit.documentId}`));$('hits').append(card);});
  $('raw-result').textContent=JSON.stringify(data,null,2);$('raw-details').hidden=false;$('feedback').textContent='检索完成。以上是资料片段，不是模型生成的答复。';
 }catch(error){clear();$('feedback').textContent=message(error);}finally{lock(false);}
});
