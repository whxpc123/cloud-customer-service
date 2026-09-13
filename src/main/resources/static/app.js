'use strict';

const $ = (id) => document.getElementById(id);
const storageKey = 'yunshan-conversations-v1';
const intentLabels = {
  PRODUCT_CONSULTATION: ['商品咨询', '客户希望了解商品参数、规格或使用方式。'],
  ORDER_QUERY: ['订单查询', '客户希望了解订单或支付等相关信息。'],
  LOGISTICS_QUERY: ['物流咨询', '客户正在关注发货、运输或签收进度。'],
  REFUND_REQUEST: ['退货退款', '客户表达了售后需求，具体办理仍需业务确认。'],
  HUMAN_SERVICE: ['人工服务', '客户希望由人工客服继续提供帮助。'],
  OTHER: ['其他话题', '当前消息暂未归入商城业务类型。'],
  UNKNOWN: ['待进一步确认', '信息不足、识别失败或结果未通过校验，请结合原文判断。']
};
let sessions = [];
let activeId = null;
let busy = false;
let selectedTurn = null;

function current() { return sessions.find((session) => session.id === activeId); }
function showNotice(message = '') { $('notice').textContent = message; $('notice').hidden = !message; }
function persist() {
  try { sessionStorage.setItem(storageKey, JSON.stringify({ sessions, activeId })); }
  catch { showNotice('浏览器未能保存页面记录。当前仍可聊天，刷新后记录可能丢失。'); }
}
function restore() {
  try {
    const stored = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
    if (!stored || !Array.isArray(stored.sessions)) return;
    // Only restore this application's plain-text records. Never render storage or model text as HTML.
    sessions = stored.sessions.filter(s => s && /^[a-zA-Z0-9_-]{1,100}$/.test(s.id)
      && typeof s.title === 'string' && Array.isArray(s.turns))
      .map(s => ({ id: s.id, title: s.title.slice(0,80), createdAt: Number(s.createdAt) || Date.now(), draft: typeof s.draft === 'string' ? s.draft.slice(0,4000) : '',
        turns: s.turns.filter(t => t && typeof t.text === 'string'
          && ['user','assistant','system','error'].includes(t.role)) }));
    activeId = sessions.some(s => s.id === stored.activeId) ? stored.activeId : sessions[0]?.id;
  } catch { sessions = []; }
}
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#' + name); svg.append(use); svg.setAttribute('aria-hidden', 'true'); return svg;
}
function updateControls() {
  $('new-session').disabled = busy;
  $('clear-memory').disabled = busy || !current();
  $('message-input').disabled = !current();
  $('send-message').disabled = busy || !current() || !$('message-input').value.trim();
  $('char-count').textContent = `${$('message-input').value.length} / 4000`;
  document.querySelectorAll('.session-item, [data-prompt]').forEach(button => { button.disabled = busy; });
  $('chat-form').setAttribute('aria-busy', String(busy));
}
function renderSessions() {
  $('sessions').replaceChildren();
  $('session-count').textContent = sessions.length;
  sessions.forEach(session => {
    const button = element('button', 'session-item' + (session.id === activeId ? ' active' : ''));
    button.setAttribute('aria-current', session.id === activeId ? 'true' : 'false');
    button.title = session.title;
    button.append(icon('chat'));
    const text = element('div');
    text.append(element('strong', '', session.title), element('small', '', new Date(session.createdAt).toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'}) + ' · 独立会话'));
    button.append(text);
    button.addEventListener('click', () => {
      if (busy || activeId === session.id) return;
      saveDraft(); activeId = session.id; selectedTurn = latestResult(session); showNotice();
      $('message-input').value = session.draft || '';
      persist(); render();
    });
    $('sessions').append(button);
  });
}
function latestResult(session) {
  // A reset marker ends the previous model context, while earlier page records stay visible.
  for (let i = session.turns.length - 1; i >= 0; i--) {
    if (session.turns[i].role === 'system') return null;
    if (session.turns[i].intent) return session.turns[i];
  }
  return null;
}
function saveDraft() { if (current()) current().draft = $('message-input').value; }
function renderMessages() {
  const list = $('messages'); list.replaceChildren();
  const session = current();
  if (!session?.turns.length) {
    list.append($('empty-state').content.cloneNode(true));
    list.querySelectorAll('[data-prompt]').forEach(button => button.addEventListener('click', () => {
      $('message-input').value = button.dataset.prompt; updateControls(); $('message-input').focus();
    }));
  } else {
    session.turns.forEach(turn => {
      const row = element('article', 'message ' + turn.role);
      if (turn.role === 'system') { row.append(element('span', '', turn.text)); list.append(row); return; }
      const avatar = element('div', 'avatar'); avatar.setAttribute('aria-hidden', 'true');
      if (turn.role === 'user') avatar.textContent = '我'; else avatar.append(icon('tree'));
      const content = element('div', 'message-content');
      const label = element('div', 'message-label', turn.role === 'user' ? '我' : '云杉客服');
      const timestamp = Number(turn.time);
      if (Number.isFinite(timestamp)) label.append(element('time', '', new Date(timestamp).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})));
      content.append(label, element('div', 'bubble', turn.text));
      if (turn.intent && typeof turn.intent === 'object') {
        const inspect = element('button', 'inspect-turn', '查看这条回复的意图识别 ↗');
        inspect.setAttribute('aria-pressed', String(selectedTurn === turn));
        inspect.addEventListener('click', () => {
          selectedTurn = turn; renderInsight();
          list.querySelectorAll('.inspect-turn').forEach(button => button.setAttribute('aria-pressed','false'));
          inspect.setAttribute('aria-pressed','true');
          $('inspector-details').open = true;
          if (matchMedia('(max-width:960px)').matches) $('inspector-details').scrollIntoView({block:'start',behavior:'smooth'});
        });
        content.append(inspect);
      }
      row.append(avatar, content); list.append(row);
    });
  }
  list.scrollTop = session?.turns.length ? list.scrollHeight : 0;
}
function renderInsight() {
  const result = selectedTurn?.intent;
  const valid = result && typeof result === 'object';
  const label = valid ? (intentLabels[result.intent] || intentLabels.UNKNOWN) : ['从一句话开始', '发送消息后，这里会显示识别出的需求和订单信息。'];
  $('insight-status').textContent = valid ? '已识别 · 可选择历史回复查看' : '等待第一条消息';
  $('intent-name').textContent = label[0]; $('intent-description').textContent = label[1];
  $('order-number').textContent = valid && typeof result.orderNo === 'string' ? result.orderNo : '—';
  $('order-hint').textContent = valid ? (result.orderNo ? '来自对话，尚未验证订单归属' : '本条消息未识别出订单号') : '当前尚无识别结果';
  const confidence = valid && typeof result.confidence === 'number' && Number.isFinite(result.confidence)
    ? Math.max(0, Math.min(1, result.confidence)) : null;
  $('confidence').textContent = confidence === null ? '—' : `${Math.round(confidence * 100)}%`;
  $('confidence-bar').style.width = `${(confidence || 0) * 100}%`;
  const fields = valid && Array.isArray(result.missingFields) ? result.missingFields : [];
  $('missing-fields').textContent = !valid ? '等待识别' : fields.length ? fields.map(f => f === 'orderNo' ? '请补充订单号' : String(f)).join('、') : '暂无待补充字段';
  $('missing-fields').classList.toggle('needs-info', fields.length > 0);
  $('raw-json').textContent = valid ? JSON.stringify(result, null, 2) : '发送消息后查看';
}
function render() {
  renderSessions(); renderMessages(); renderInsight();
  $('session-title').textContent = current()?.title || '开始一场对话';
  $('session-meta').textContent = current() ? '会话 ' + activeId : '点击“新建会话”开始';
  updateControls();
}
async function request(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { 'Content-Type':'application/json', ...options.headers } });
  if (!response.ok) throw new Error(response.status === 400 ? '输入不符合要求，请检查消息内容。'
    : response.status === 502 ? '模型暂时没有返回回复，请稍后继续。'
    : `服务请求失败（${response.status}），请确认 Spring Boot 已正常启动。`);
  return response.status === 204 ? null : response.json();
}
function errorMessage(error) {
  return error instanceof TypeError ? '无法连接服务，请确认 Spring Boot 正在运行。' : error.message || '请求失败，请稍后继续。';
}
async function newSession() {
  if (busy) return;
  saveDraft(); busy = true; updateControls(); showNotice();
  try {
    const data = await request('/api/conversations', { method:'POST' });
    if (!data || !/^[A-Za-z0-9_-]{1,100}$/.test(data.conversationId)) throw new Error('服务返回了无效的会话编号。');
    const session = { id:data.conversationId, title:'新会话', turns:[], createdAt:Date.now() };
    sessions.unshift(session); activeId = session.id; selectedTurn = null; $('message-input').value = '';
    persist();
  } catch (error) { showNotice(errorMessage(error)); }
  finally { busy = false; render(); }
}
async function sendMessage(event) {
  event.preventDefault();
  const session = current(); const message = $('message-input').value.trim();
  if (busy || !session || !message) return;
  if (message.length > 4000) { showNotice('每条消息最多 4000 个字符。'); return; }
  busy = true; showNotice();
  if (!session.turns.some(t => t.role === 'user')) session.title = message.length > 18 ? message.slice(0,18) + '…' : message;
  session.turns.push({ role:'user', text:message, time:Date.now() });
  $('message-input').value = ''; session.draft = ''; persist(); render();
  const pending = element('article', 'message pending');
  pending.append(element('div','avatar','杉'), element('div','bubble','正在理解上下文并回复…'));
  $('messages').append(pending); $('messages').scrollTop = $('messages').scrollHeight;
  try {
    const data = await request(`/api/conversations/${encodeURIComponent(session.id)}/messages`, { method:'POST', body:JSON.stringify({message}) });
    if (!data || typeof data.answer !== 'string' || !data.intent || data.conversationId !== session.id) throw new Error('回复格式异常，请稍后继续。');
    const turn = { role:'assistant', text:data.answer, intent:data.intent, time:Date.now() };
    session.turns.push(turn); selectedTurn = turn;
  } catch (error) {
    session.turns.push({ role:'error', text:errorMessage(error) + '\n本条消息可能已进入模型记忆，请避免重复发送。需要重新开始时可清空记忆。', time:Date.now() });
  } finally {
    pending.remove(); busy = false; saveDraft(); persist(); render(); $('message-input').focus({preventScroll:true});
  }
}
async function clearMemory() {
  if (busy || !current()) return;
  const session = current(); busy = true; updateControls(); showNotice();
  try {
    await request(`/api/conversations/${encodeURIComponent(session.id)}/memory`, { method:'DELETE' });
    session.turns.push({role:'system',text:'记忆已清空 · 接下来的对话从这里重新开始',time:Date.now()});
    selectedTurn = null; persist();
  } catch (error) { showNotice(errorMessage(error)); }
  finally { busy = false; render(); }
}
$('chat-form').addEventListener('submit', sendMessage);
$('message-input').addEventListener('input', () => { saveDraft(); updateControls(); });
$('message-input').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault(); if (!busy) $('chat-form').requestSubmit();
  }
});
$('new-session').addEventListener('click', newSession);
$('clear-memory').addEventListener('click', () => {
  if (!busy && current()) {
    // Escape must cancel even after an earlier dialog was confirmed.
    $('clear-dialog').returnValue = 'cancel';
    $('clear-dialog').showModal();
  }
});
$('clear-dialog').addEventListener('close', () => { if ($('clear-dialog').returnValue === 'confirm') clearMemory(); });
window.addEventListener('pagehide', () => { saveDraft(); persist(); });
if (matchMedia('(max-width:960px)').matches) $('inspector-details').open = false;
restore();
if (current()) { selectedTurn = latestResult(current()); $('message-input').value = current().draft || ''; render(); }
else { render(); newSession(); }
