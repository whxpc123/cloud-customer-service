/** 只读售后页面：身份固定由服务器提供；CSRF 只保存在当前页面内存，不接受演示身份头。 */
'use strict';
const $=id=>document.getElementById(id),base='/internal/after-sale';
const node=(tag,text,cls='')=>{const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;};
const statuses={NEED_ORDER_NO:'需要明确订单号',NOT_ACCESSIBLE:'订单不可访问',NO_EVIDENCE:'适用政策依据不足',NEED_MORE_INFORMATION:'需要补充信息',NEED_QUALITY_VERIFICATION:'需要质量核验',NEED_MANUAL_REVIEW:'仍需人工审核',NO_REASON_WINDOW_EXPIRED:'无理由期限已过',TEMPORARILY_UNAVAILABLE:'检查服务暂不可用'};
const reasons={QUALITY_ISSUE:'用户反馈质量问题（待核验诉求）',CHANGE_OF_MIND:'用户提出个人原因退货',UNKNOWN:'原因未明确'};
const quality={UNVERIFIED:'尚未核验',CONFIRMED:'系统记录已确认',REJECTED:'系统记录未通过核验'};
const examples={quality:'A10001 的商品有质量问题，我能退吗？',mind:'A10001 我不喜欢了，想无理由退货，还在期限内吗？',foreign:'A10002 的商品有质量问题，我能退吗？',missing:'A10005 的商品有质量问题，可以退吗？',confirmed:'A10004 商品质量问题的售后现在能直接退款吗？'};
let csrf=null,conversation=null,busy=false;
function lock(value){busy=value;document.querySelectorAll('button,select,textarea').forEach(n=>n.disabled=value);}
async function request(path,method='GET',body){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(csrf?{'X-AfterSale-CSRF':csrf}:{})},...(body?{body:JSON.stringify(body)}:{})});if(r.status===204)return null;const data=await r.json();if(!r.ok)throw new Error(data.message||`请求失败 ${r.status}，请刷新会话后重试。`);return data;}
function resetEvidence(){ $('assessments').replaceChildren(node('p','还没有本轮检查记录。'));$('raw-box').hidden=true; }
async function newSession(){if(busy)return;lock(true);try{const session=await request('/session');csrf=session.csrfToken;const created=await request('/conversations','POST');conversation=created.conversationId;$('session').textContent=`演示账户 1001 · 会话 ${conversation}`;$('messages').replaceChildren();resetEvidence();$('feedback').textContent='会话已建立，可以开始只读检查。';}catch(e){$('feedback').textContent=e.message;}finally{lock(false);}}
/** 不从模型文字推断执行状态；全部事实/来源卡只使用 assessments。 */
function render(r){
 const root=$('assessments');root.replaceChildren();if(!r.assessments.length)root.append(node('p','本轮未取得实际检查记录，不能据此判断退货资格。'));
 for(const a of r.assessments){const box=node('article','','sale-assessment');box.append(node('h3',statuses[a.status]||a.status),node('p',a.explanation));const dl=node('dl');
 const rows=[['用户诉求',reasons[a.claimedReason]||a.claimedReason],['检查时间',a.checkedAt]];
 if(a.verifiedFacts){const f=a.verifiedFacts;rows.push(['订单',f.orderNo],['商品类型',f.productType],['签收时间',f.signedAt||'缺失'],['无理由截止',f.noReasonDeadline||'缺失'],['质量核验',quality[f.qualityVerification]||'缺失'],['适用政策',`${f.policySourceId||'缺失'} / ${f.policyVersion||'缺失'}`]);}
 rows.forEach(([label,value])=>dl.append(node('dt',label),node('dd',value)));box.append(dl,node('p','只读检查 · 未提交申请 · 未执行退款','sale-readonly'));
 if(!a.evidence.length)box.append(node('p','本次未取得政策证据。','fine'));
 a.evidence.forEach(e=>{const details=node('details');details.append(node('summary',`${e.sourceId} · v${e.sourceVersion}`),node('p',e.text),node('p',`documentId ${e.documentId}`,'fine'));box.append(details);});root.append(box);}
 $('raw').textContent=JSON.stringify(r,null,2);$('raw-box').hidden=false;
}
function turn(who,text){const n=node('article','','sale-turn');n.append(node('strong',who),node('div',text));$('messages').append(n);n.scrollIntoView({block:'nearest'});}
$('chat-form').addEventListener('submit',async e=>{e.preventDefault();if(busy||!conversation)return;const question=$('question').value.trim();if(!question)return;lock(true);resetEvidence();turn('客户',question);$('feedback').textContent='正在调用只读工具查询事实和政策…';try{const r=await request(`/conversations/${encodeURIComponent(conversation)}/messages`,'POST',{message:question});turn('售后助手 · 解释不代表审批',r.answer);render(r);$('feedback').textContent=`本轮 ${r.status} · requestId ${r.requestId}`+(r.explanationFiltered?' · 模型解释已替换为确定性结果。':'');}catch(error){$('feedback').textContent=error.message;}finally{lock(false);}});
$('clear').addEventListener('click',async()=>{if(busy||!conversation)return;lock(true);try{await request(`/conversations/${encodeURIComponent(conversation)}/memory`,'DELETE');$('messages').replaceChildren();resetEvidence();$('feedback').textContent='本会话记忆已清空。';}catch(e){$('feedback').textContent=e.message;}finally{lock(false);}});
$('example').addEventListener('change',()=>{$('question').value=examples[$('example').value];});$('new-session').addEventListener('click',newSession);newSession();
