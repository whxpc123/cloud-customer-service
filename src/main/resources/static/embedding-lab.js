'use strict';
const $ = id => document.getElementById(id);
const examples = {
  related:['我要申请退货','东西买错了，我不想要了'],
  unrelated:['我要申请退货','今天天气怎么样'],
  opposite:['商品支持七日无理由退货','商品不支持七日无理由退货'],
  identifier:['订单 A10001','订单 A10002']
};
let busy = false;
function node(tag, className, text) { const e=document.createElement(tag); if(className)e.className=className; if(text!==undefined)e.textContent=text; return e; }
function clearResult() { $('result').replaceChildren(); $('result').hidden=true; $('raw-details').hidden=true; $('raw-result').textContent=''; $('empty-result').hidden=false; $('result-kind').textContent='等待实验'; }
function mode(name) {
  if(busy)return;
  for(const key of ['compare','rank']) { $('mode-'+key).setAttribute('aria-pressed',String(name===key)); $(key+'-form').hidden=key!==name; }
  $('feedback').textContent=''; clearResult();
}
for(const name of ['compare','rank']) $('mode-'+name).addEventListener('click',()=>mode(name));
document.querySelectorAll('[data-example]').forEach(button=>button.addEventListener('click',()=>{
  const [left,right]=examples[button.dataset.example]; $('left-text').value=left; $('right-text').value=right; clearResult(); $('feedback').textContent='';
}));
function validate(text) { if(!text.trim() || text.length>2000)throw new Error('每段文本需要包含 1–2000 个字符。'); }
function render(data, kind, payload) {
  if(!Number.isInteger(data.dimensions)||data.dimensions<1)throw new Error('服务返回了无效维度。');
  const validScore = score => typeof score==='number' && Number.isFinite(score) && score>=-1 && score<=1;
  if(kind==='compare' ? !validScore(data.score) : !Array.isArray(data.matches)||data.matches.length!==payload.candidates.length||data.matches.some(m=>typeof m.content!=='string'||!validScore(m.score))) throw new Error('服务返回的分数格式不正确。');
  const result=$('result'); result.replaceChildren();
  $('result-kind').textContent=kind==='compare'?'余弦相似度':'全部候选 · 降序';
  result.append(node('p','dimension',`实际 ${data.dimensions} 维 · 本次服务返回`));
  if(kind==='compare') {
    result.append(node('div','score',data.score.toFixed(4)),node('div','score-caption','范围 −1 到 1 · 不是正确率'));
    const scale=node('div','score-scale'); const dot=node('span','score-dot'); dot.style.left=((data.score+1)*50)+'%'; scale.append(dot);result.append(scale);
    const labels=node('div','scale-labels'); for(const t of ['−1','0','1'])labels.append(node('span','',t));result.append(labels);
    for(const [label,text] of [['句子 A',payload.left],['句子 B',payload.right]]){const p=node('p','result-text');p.append(node('small','',label),node('span','',text));result.append(p);}
  } else {
    result.append(node('p','result-text',payload.query));
    const list=node('ol','rank-list');data.matches.forEach((match,i)=>{const li=node('li');const top=node('div','rank-top');top.append(node('span','',String(i+1).padStart(2,'0')),node('strong','',match.score.toFixed(4)));li.append(top,node('p','',match.content));list.append(li);});result.append(list);
  }
  $('raw-result').textContent=JSON.stringify(data,null,2);$('raw-details').hidden=false;result.hidden=false;$('empty-result').hidden=true;
}
async function run(event,kind) {
  event.preventDefault(); if(busy)return;
  try {
    const payload=kind==='compare'?{left:$('left-text').value,right:$('right-text').value}:{query:$('query-text').value,candidates:$('candidates-text').value.split(/\r?\n/).filter(t=>t.trim())};
    if(kind==='compare'){validate(payload.left);validate(payload.right);}else{validate(payload.query);if(payload.candidates.length<1||payload.candidates.length>20)throw new Error('请输入 1–20 条候选文本，每行一条。');payload.candidates.forEach(validate);}
    clearResult();busy=true;document.querySelectorAll('button,textarea').forEach(e=>e.disabled=true);document.querySelector('.output').setAttribute('aria-busy','true');$('feedback').textContent='正在生成向量并计算相似度…';
    const response=await fetch('/internal/embedding-lab/'+kind,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    if(!response.ok)throw new Error(response.status===404?'实验接口未启用，请使用 local 环境启动 IDEA。':response.status===400?'输入不符合要求，请检查文本长度和候选数量。':response.status===502?'向量服务暂不可用，请检查 IDEA 日志和百炼模型权限后重试。':`请求失败（${response.status}），请稍后重试。`);
    render(await response.json(),kind,payload);$('feedback').textContent='计算完成。分数来自本次真实向量调用。';
  } catch(error) {clearResult();$('feedback').textContent=error instanceof TypeError?'无法连接服务，请确认 IDEA 应用正在运行。':error.message;}
  finally{busy=false;document.querySelectorAll('button,textarea').forEach(e=>e.disabled=false);document.querySelector('.output').setAttribute('aria-busy','false');}
}
$('compare-form').addEventListener('submit',event=>run(event,'compare'));$('rank-form').addEventListener('submit',event=>run(event,'rank'));
