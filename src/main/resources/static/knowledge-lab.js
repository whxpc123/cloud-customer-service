'use strict';
const $=id=>document.getElementById(id);
let busy=false, preview=null, shownChunks=0, activeJob=null;
function element(tag,cls,text){const e=document.createElement(tag);e.className=cls;if(text!==undefined)e.textContent=text;return e;}
function lock(value){busy=value;document.querySelectorAll('button,textarea,input,select').forEach(e=>e.disabled=value);document.querySelector('.output').setAttribute('aria-busy',String(value));if(activeJob)document.querySelectorAll('#import-form button,#import-form textarea,#import-form input,#import-form select,#preview-sample,#seed').forEach(e=>e.disabled=true);$('confirm-import').disabled=value||!preview||Boolean(activeJob);}
function clear(){ $('hits').replaceChildren();$('hits').hidden=true;$('empty-result').hidden=false;$('raw-details').hidden=true;$('raw-result').textContent='';$('result-count').textContent='等待搜索'; }
async function request(path,body,method='POST'){
 const multipart=body instanceof FormData;
 const response=await fetch('/internal/knowledge/'+path,{method,...(multipart?{body}:{headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})})});
 if(!response.ok){
  let details;try{details=await response.json();}catch{}
  const inputError=[400,404,413,429].includes(response.status)&&['INVALID_INPUT','FILE_TOO_LARGE','PREVIEW_STATE'].includes(details?.code)?details.message:null;
  const error=new Error(inputError||(response.status===404?'知识库未启用，请以 local,knowledge 环境启动。':response.status===400?'输入无效，请检查资料、问题、条数和阈值。':response.status===413?'文件不能超过 5 MB。':response.status===502?'知识库暂不可用，请检查数据库和百炼向量服务后重试。':`请求失败（${response.status}），请检查 IDEA 日志。`));error.status=response.status;throw error;
 }
 return response.json();
}
let importMode='file';
function setImportMode(mode){
 if(busy||activeJob)return;importMode=mode;
 $('file-fields').hidden=mode!=='file';$('text-fields').hidden=mode!=='text';
 $('import-file').required=mode==='file';$('import-text').required=mode==='text';
 $('file-mode').setAttribute('aria-pressed',String(mode==='file'));$('text-mode').setAttribute('aria-pressed',String(mode==='text'));
 $('import-status').textContent='';invalidatePreview();
}
$('file-mode').addEventListener('click',()=>setImportMode('file'));
$('text-mode').addEventListener('click',()=>setImportMode('text'));
$('import-file').addEventListener('change',()=>{
 const file=$('import-file').files[0];if(file)$('source-name').value=file.name;
 $('import-status').textContent='';invalidatePreview();
});
$('import-form').addEventListener('submit',async event=>{
 event.preventDefault();if(busy||activeJob)return;
 const sourceName=$('source-name').value.trim(),sourceVersion=$('source-version').value.trim(),chunkSize=Number($('chunk-size').value);let body,path;
 if(!sourceName||sourceName.length>120){$('import-status').textContent='请填写 1–120 字符的资料名称。';return;}
 if(importMode==='file'){
  const file=$('import-file').files[0];
  if(!file||!file.size||file.size>5*1024*1024||! /\.(txt|md|markdown|pdf|docx|pptx)$/i.test(file.name)){
   $('import-status').textContent='请选择非空的 TXT、Markdown、PDF、DOCX 或 PPTX，文件不能超过 5 MB。';return;
  }
  body=new FormData();body.append('file',file);body.append('sourceName',sourceName);body.append('sourceVersion',sourceVersion);body.append('chunkSize',String(chunkSize));body.append('pdfTopLines',$('pdf-top').value);body.append('pdfBottomLines',$('pdf-bottom').value);path='preview/file';
 }else{
  const text=$('import-text').value;if(!text.trim()||text.length>50000){$('import-status').textContent='正文不能为空，且不能超过 50000 字符。';return;}
  body={sourceName,sourceVersion,text,chunkSize};path='preview';
 }
 invalidatePreview();lock(true);clear();$('import-form').setAttribute('aria-busy','true');$('import-status').textContent='正在读取、清理并切分正文，预览不会调用模型…';
 try{
  const result=await request(path,body);renderPreview(result);
  $('import-status').textContent='预览已生成，请核对右侧知识块，再确认导入。';
 }catch(error){$('import-status').textContent=message(error);}
 finally{lock(false);$('import-form').setAttribute('aria-busy','false');}
});
function showPanel(kind){$('preview-panel').hidden=kind!=='preview';$('search-results').hidden=kind!=='search';$('show-preview').setAttribute('aria-pressed',String(kind==='preview'));$('show-search').setAttribute('aria-pressed',String(kind==='search'));}
$('show-preview').addEventListener('click',()=>showPanel('preview'));$('show-search').addEventListener('click',()=>showPanel('search'));
function invalidatePreview(){preview=null;$('confirm-import').disabled=true;$('preview-summary').textContent='资料或参数已改变，请重新预览。';$('chunk-previews').replaceChildren();$('preview-warnings').replaceChildren();$('more-chunks').hidden=true;}
['source-name','source-version','chunk-size','pdf-top','pdf-bottom','import-text'].forEach(id=>$(id).addEventListener('input',invalidatePreview));
function renderPreview(data){
 if(!Array.isArray(data.previews)||!data.previewId||data.chunks<1)throw new Error('预览格式异常，请重新预览。');
 preview=data;shownChunks=0;showPanel('preview');$('chunk-previews').replaceChildren();$('preview-warnings').replaceChildren();
 $('preview-summary').textContent=`${data.sourceName} · 版本 ${data.sourceVersion}\n读取 ${data.extractedDocuments} 段 → 清理后 ${data.normalizedDocuments} 段 → ${data.chunks} 个知识块\n${data.totalCharacters} 字符 · 最短 ${data.shortestChunk} / 最长 ${data.longestChunk} 字符\n${data.chunkingVersion} · 尚未写入数据库`;
 data.warnings.forEach(w=>$('preview-warnings').append(element('p','quality-warning',w)));appendChunks();$('job-status').textContent='';
}
function appendChunks(){if(!preview)return;preview.previews.slice(shownChunks,shownChunks+20).forEach(chunk=>{
 const card=element('article','preview-chunk');const m=chunk.metadata;
 card.append(element('h3','',`知识块 ${chunk.chunkIndex} · ${chunk.characters} 字符${m.page_number?' · 第 '+m.page_number+' 页':''}`),element('p','hit-content',chunk.text));
 const details=element('details','');details.append(element('summary','','来源 / Hash / 完整 Metadata'),element('pre','',JSON.stringify({documentId:chunk.documentId,...m},null,2)));card.append(details);$('chunk-previews').append(card);
 });shownChunks+=20;$('more-chunks').hidden=shownChunks>=preview.previews.length;}
$('more-chunks').addEventListener('click',appendChunks);
$('preview-sample').addEventListener('click',async()=>{if(busy||activeJob)return;invalidatePreview();lock(true);try{renderPreview(await request('files/refund-policy/preview?chunkSize='+$('chunk-size').value,undefined,'GET'));$('import-status').textContent='已预览课程售后制度；确认后才会入库。';}catch(e){$('import-status').textContent=message(e);}finally{lock(false);}});
async function pollJob(){
 $('refresh-job').hidden=true;
 try{
  for(;;){
   const job=await request('jobs/'+activeJob,undefined,'GET');$('job-status').textContent=job.message;
   if(job.status==='PUBLISHED'){$('job-status').textContent=`已导入「${job.result.sourceName}」：${job.result.importedDocuments} 个知识块，可以检索了。`;preview=null;activeJob=null;break;}
   if(job.status==='FAILED'){activeJob=null;break;}
   await new Promise(resolve=>setTimeout(resolve,1000));
  }
 }catch(e){if(e.status===404){activeJob=null;invalidatePreview();$('job-status').textContent=message(e);}else{$('job-status').textContent=message(e)+' 后台任务可能仍在执行，可重新查询状态。';$('refresh-job').hidden=false;}}
 finally{lock(false);if(activeJob)$('confirm-import').disabled=true;}
}
$('confirm-import').addEventListener('click',async()=>{
 if(busy||activeJob||!preview)return;lock(true);$('job-status').textContent='正在提交后台导入任务…';
 try{const job=await request('previews/'+preview.previewId+'/import');activeJob=job.jobId;await pollJob();}
 catch(e){$('job-status').textContent=message(e);lock(false);}
});
$('refresh-job').addEventListener('click',()=>{if(!busy&&activeJob){lock(true);pollJob();}});
function message(error){return error instanceof TypeError?'无法连接应用，请确认 IDEA 正在运行。':error.message;}
$('seed').addEventListener('click',async()=>{if(busy||activeJob)return;lock(true);clear();$('seed-status').textContent='正在生成向量并写入数据库…';$('feedback').textContent='';try{const result=await request('seed');if(result.importedDocuments!==4)throw new Error('导入响应异常，请检查数据库记录。');$('seed-status').textContent='已写入 / 更新 4 条课程知识。可以开始搜索。';}catch(error){$('seed-status').textContent=message(error);}finally{lock(false);}});
document.querySelectorAll('[data-query]').forEach(button=>button.addEventListener('click',()=>{$('query').value=button.dataset.query;clear();$('feedback').textContent='';}));
$('search-form').addEventListener('submit',async event=>{
 event.preventDefault();if(busy)return;showPanel('search');clear();
 const query=$('query').value,topK=Number($('top-k').value),threshold=Number($('threshold').value);
 if(!query.trim()||query.length>2000||!Number.isInteger(topK)||topK<1||topK>10||!Number.isFinite(threshold)||threshold<0||threshold>1){$('feedback').textContent='请填写有效问题，条数 1–10，阈值 0–1。';return;}
 lock(true);$('feedback').textContent='正在检索已发布知识…';
 try{
  const data=await request('search',{query,topK,threshold});
  if(!Array.isArray(data.hits)||data.query!==query||data.hits.some(h=>typeof h.content!=='string'||!Number.isFinite(h.score)))throw new Error('检索结果格式异常，请稍后重试。');
  $('result-count').textContent=`返回 ${data.hits.length} 条`;
  $('empty-result').hidden=true;$('hits').hidden=false;
  $('hits').append(element('p','search-context',`本次问题：${query}\n最多 ${topK} 条 · 最低相似度 ${threshold.toFixed(2)}`));
  if(!data.hits.length)$('hits').append(element('p','hit-content','没有符合当前范围和阈值的知识。首次使用请先导入资料或样例，也可调低阈值再观察。'));
  const categories={REFUND_POLICY:'退货条件',REFUND_FREIGHT:'退货运费',LOGISTICS_EXCEPTION:'物流异常',INVOICE_POLICY:'电子发票',UPLOADED_DOCUMENT:'上传资料'};
  data.hits.forEach((hit,index)=>{const card=element('article','hit');const heading=element('div','hit-heading');heading.append(element('strong','',`${index+1}. ${categories[hit.category]||hit.category}`),element('span','',hit.score.toFixed(4)));card.append(heading,element('p','hit-content',hit.content),element('p','hit-source',`${hit.sourceName||hit.sourceId} · 版本 ${hit.sourceVersion.slice(0,12)} · 知识块 ${hit.chunkIndex}\n${hit.metadata?.title||''}${hit.metadata?.page_number?' · 第 '+hit.metadata.page_number+' 页':''}\n${hit.documentId}`));$('hits').append(card);});
  $('raw-result').textContent=JSON.stringify(data,null,2);$('raw-details').hidden=false;$('feedback').textContent='检索完成。以上是资料片段，不是模型生成的答复。';
 }catch(error){clear();$('feedback').textContent=message(error);}finally{lock(false);}
});
