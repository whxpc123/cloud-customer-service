/**
 * 第十章单会话知识页面：一次提交只触发一条 Advisor 链，答复与来源来自同一响应。
 * 不把 Context、模型输出或来源当 HTML；刷新时新建会话，演示身份切换也另建会话。
 */
'use strict';
const $ = id => document.getElementById(id);
const labels = { ANSWERED: '模型已返回 · 请核对依据', NO_EVIDENCE: '无证据 · 未调用回答模型', NEEDS_CLARIFICATION: '需要补充场景', TEMPORARILY_UNAVAILABLE: '服务暂不可用' };
let busy = false, conversationId = null, sessionUser = '1001';

/** 创建纯文本节点，避免资料或回答中的标签被解释为代码。 */
function node(tag, text, cls = '') {
  const el = document.createElement(tag); el.className = cls;
  if (text !== undefined) el.textContent = text;
  return el;
}
/** 锁住同一会话中的所有状态变更，避免发送、清空与切换身份交错。 */
function lock(value) {
  busy = value;
  document.querySelectorAll('button, textarea, select').forEach(el => { el.disabled = value; });
  $('question').disabled = value || !conversationId;
  $('ask').disabled = value || !conversationId;
  $('clear-memory').disabled = value || !conversationId;
  $('result-panel').setAttribute('aria-busy', String(value));
}
/** 请求路径固定在同源实验入口；204 无正文，其他成功响应须为 JSON。 */
async function request(path, options = {}) {
  const response = await fetch('/internal/advisor-rag/' + path, options);
  if (!response.ok) {
    let data; try { data = await response.json(); } catch { /* 容器错误可能没有 JSON，使用下方状态提示。 */ }
    throw new Error(response.status === 404 ? '接口未启用或会话路径错误，请以 local,knowledge 环境启动。'
      : response.status === 400 ? data?.message || '输入格式不符合要求。' : `请求失败（${response.status}），请检查 IDEA 日志。`);
  }
  return response.status === 204 ? null : response.json();
}
/** 演示请求头取自本会话固定身份，而不是正在变更的选择框。 */
function headers() { return { 'Content-Type': 'application/json', ...(sessionUser === 'guest' ? {} : { 'X-Demo-User-Id': sessionUser }) }; }
/** 网络故障转换为启动提示，不把它误认为本次无证据。 */
function message(error) { return error instanceof TypeError ? '无法连接应用，请检查 IDEA 是否已启动。' : error.message; }
/** 清掉所选一轮的来源与原始 JSON，避免新请求误用旧证据。 */
function clearEvidence() {
  $('references').replaceChildren(); $('raw-details').hidden = true; $('raw-details').open = false;
  $('raw-result').textContent = ''; $('request-id').textContent = ''; $('result-status').textContent = '等待提问';
  $('retrieval-query').textContent = '发送后查看实际用于搜索的问题。';
}
/** 创建成功后才切换身份和会话；失败保留旧状态，避免旧会话被错误配给新用户。 */
async function newSession() {
  if (busy) return;
  const desiredUser = $('demo-user').value; lock(true); $('feedback').textContent = '正在创建知识会话…';
  try {
    const data = await request('conversations', { method: 'POST' });
    if (!data || !/^[a-zA-Z0-9_-]{1,100}$/.test(data.conversationId)) throw new Error('服务返回的会话 ID 无效。');
    conversationId = data.conversationId; sessionUser = desiredUser;
    $('session-id').textContent = `会话 ${conversationId} · ${sessionUser === 'guest' ? '访客' : '用户 ' + sessionUser}`;
    $('messages').replaceChildren(node('p', '会话已创建，先提出完整问题，再尝试追问。', 'fine'));
    $('question').value = ''; clearEvidence(); $('feedback').textContent = '';
  } catch (error) { $('demo-user').value = sessionUser; $('feedback').textContent = message(error); }
  finally { lock(false); }
}
/** 显示实际检索问题及 Context 返回的来源；查看历史只是切换展示，不重新调用模型。 */
function showEvidence(data) {
  clearEvidence(); $('result-status').textContent = labels[data.status];
  $('retrieval-query').textContent = '首路实际检索：' + (data.retrievalQuery ?? '未执行检索');
  if (data.transformation) $('retrieval-query').textContent += '\n原始问题：' + data.transformation.originalQuery + '\n转换：' + data.transformation.stages.map(s => `${s.name} ${s.status} (${s.durationMs} ms)`).join(' → ');
  if (data.expansion) $('retrieval-query').textContent += '\n扩展：' + data.expansion.status + '\n实际各路：' + data.expansion.retrievals.map(b => b.query).join(' | ') + `\n候选 ${data.expansion.rawDocumentCount} → ${data.expansion.joinedDocumentCount} 块（去重 ${data.expansion.duplicateDocumentCount}），检索 ${data.expansion.retrievalStatus}`;
  $('request-id').textContent = '审计 requestId：' + data.requestId;
  for (const ref of data.references) {
    const card = node('details', undefined, 'evidence-card');
    card.append(node('summary', `${ref.sourceName || ref.sourceId} · v${ref.sourceVersion} · 第 ${ref.chunkIndex} 块`),
      node('pre', `相似度 ${Number(ref.score).toFixed(4)}\ndocumentId: ${ref.documentId}\nsourceId: ${ref.sourceId}`), node('p', ref.content));
    $('references').append(card);
  }
  $('raw-result').textContent = JSON.stringify(data, null, 2); $('raw-details').hidden = false;
}
/** 追加可见记录；只有带后端响应的助手记录才允许展开该轮真实依据。 */
function appendTurn(role, text, response) {
  const card = node('article', undefined, 'knowledge-turn ' + role);
  card.append(node('strong', role === 'user' ? '我' : role === 'system' ? '会话提示' : labels[response?.status] || '云杉知识客服'), node('p', text));
  if (response) { const button = node('button', '查看这轮依据 ↗'); button.type = 'button'; button.addEventListener('click', () => { if (!busy) showEvidence(response); }); card.append(button); }
  $('messages').append(card); $('messages').scrollTop = $('messages').scrollHeight;
}
/** 提交一次终结请求；无证据与暂不可用也作为明确结果展示，不自动重试收费调用。 */
async function send(event) {
  event.preventDefault(); if (busy || !conversationId) return;
  const question = $('question').value.trim();
  if (!question || question.length > 2000) { $('feedback').textContent = '请输入 1 至 2000 个字符。'; return; }
  lock(true); clearEvidence(); appendTurn('user', question); $('question').value = '';
  $('result-status').textContent = '处理中'; $('feedback').textContent = '正在加载历史、检索资料并检查证据…';
  try {
    const data = await request(`conversations/${encodeURIComponent(conversationId)}/messages`, { method: 'POST', headers: headers(), body: JSON.stringify({ question, expansionMode: $('expansion-mode').value }) });
    if (!data || data.conversationId !== conversationId || data.transformation?.originalQuery !== question || !Object.hasOwn(labels, data.status)
      || typeof data.requestId !== 'string' || typeof data.answer !== 'string' || !Array.isArray(data.references)
      || data.references.some(r => !r || typeof r.content !== 'string' || !Number.isFinite(r.score))) throw new Error('本次响应格式异常，请检查服务。');
    appendTurn('assistant', data.answer, data); showEvidence(data); $('feedback').textContent = labels[data.status];
  } catch (error) {
    clearEvidence(); $('result-status').textContent = '请求失败';
    const text = message(error) + ' 本轮问题可能已写入模型记忆，请避免重复发送；也可以清空后重新开始。';
    appendTurn('system', text); $('feedback').textContent = text;
  } finally { lock(false); $('question').focus(); }
}
/** 成功删除服务端上下文后追加可见分界，页面旧答复与来源仍可手动回看。 */
async function clearMemory() {
  if (busy || !conversationId) return; lock(true);
  try {
    await request(`conversations/${encodeURIComponent(conversationId)}/memory`, { method: 'DELETE', headers: headers() });
    appendTurn('system', '模型记忆已清空，之后的问题从这里重新开始。'); clearEvidence(); $('feedback').textContent = '记忆已清空。';
  } catch (error) { $('feedback').textContent = message(error); }
  finally { lock(false); }
}
// 样例只填表，不自动发送；所有后端身份与范围均由服务器决定。
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => { if (!busy) { $('question').value = button.dataset.question; $('question').focus(); } }));
$('advisor-form').addEventListener('submit', send);
$('new-session').addEventListener('click', newSession);
$('demo-user').addEventListener('change', newSession);
$('clear-memory').addEventListener('click', () => { if (!busy && conversationId) { $('clear-dialog').returnValue = 'cancel'; $('clear-dialog').showModal(); } });
$('clear-dialog').addEventListener('close', () => { if ($('clear-dialog').returnValue === 'confirm') clearMemory(); });
newSession();
