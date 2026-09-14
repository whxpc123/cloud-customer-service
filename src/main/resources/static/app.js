/**
 * 第四、五章客服工作台：原生 JavaScript 管理会话列表、草稿、消息、意图和真实订单工具轨迹。
 * 页面记录保存在当前标签页 sessionStorage；模型上下文在服务端，两者的生命周期不同。
 * 所有客户、存储和模型文本通过 textContent 渲染，不解释为 HTML。
 */
'use strict';

// 页面元素查询简写；所有 ID 都对应当前 HTML 中的固定节点。
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

/**
 * 按 activeId 查当前页面会话；不存在时返回 undefined，由调用处处理空状态。
 */
function current() { return sessions.find((session) => session.id === activeId); }
/**
 * 显示或隐藏页面级提示，使用 textContent 防止错误内容被当作 HTML 执行。
 */
function showNotice(message = '') { $('notice').textContent = message; $('notice').hidden = !message; }
/**
 * 保存当前标签页的会话和选中 ID；存储满或被禁用时给出提示，聊天仍可继续。
 * 这是浏览器页面记录，不会把历史重新同步给服务端模型。
 */
function persist() {
  try { sessionStorage.setItem(storageKey, JSON.stringify({ sessions, activeId })); }
  catch { showNotice('浏览器未能保存页面记录。当前仍可聊天，刷新后记录可能丢失。'); }
}
/**
 * 解析 sessionStorage 并过滤非法会话、消息角色和超长草稿；失效存储回到空列表。
 * 只恢复本应用的数据结构，不能假设浏览器缓存内容天然可信。
 */
function restore() {
  try {
    const stored = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
    if (!stored || !Array.isArray(stored.sessions)) return;
    // 只恢复本应用的纯文本记录，缓存和模型返回都不能按 HTML 渲染。
    sessions = stored.sessions.filter(s => s && /^[a-zA-Z0-9_-]{1,100}$/.test(s.id)
      && typeof s.title === 'string' && Array.isArray(s.turns))
      .map(s => ({ id: s.id, title: s.title.slice(0,80), demoUserId: [1001,2002].includes(s.demoUserId) ? s.demoUserId : null, createdAt: Number(s.createdAt) || Date.now(), draft: typeof s.draft === 'string' ? s.draft.slice(0,4000) : '',
        turns: s.turns.filter(t => t && typeof t.text === 'string'
          && ['user','assistant','system','error'].includes(t.role)) }));
    activeId = sessions.some(s => s.id === stored.activeId) ? stored.activeId : sessions[0]?.id;
  } catch { sessions = []; }
}
/**
 * 创建普通 DOM 节点，文本统一走 textContent；模型输出中的标签只会按文字展示。
 */
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
/**
 * 在 SVG 命名空间创建 use 引用，复用页面定义的本地图标，避免动态拼接 SVG 字符串。
 */
function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#' + name); svg.append(use); svg.setAttribute('aria-hidden', 'true'); return svg;
}
/**
 * 根据忙碌状态、会话和输入统一设置按钮可用性及 aria-busy。
 * 请求期间禁止切会话、换身份和重复发送，保证同一窗口按轮次顺序调用。
 */
function updateControls() {
  $('new-session').disabled = busy;
  $('demo-user').disabled = busy;
  $('clear-memory').disabled = busy || !current();
  $('message-input').disabled = !current();
  $('send-message').disabled = busy || !current() || !$('message-input').value.trim();
  $('char-count').textContent = `${$('message-input').value.length} / 4000`;
  document.querySelectorAll('.session-item, [data-prompt]').forEach(button => { button.disabled = busy; });
  $('chat-form').setAttribute('aria-busy', String(busy));
}
/**
 * 重建会话导航并绑定选择事件；切换前保存草稿，随后恢复目标会话的草稿和最近洞察。
 */
function renderSessions() {
  $('sessions').replaceChildren();
  $('session-count').textContent = sessions.length;
  sessions.forEach(session => {
    const button = element('button', 'session-item' + (session.id === activeId ? ' active' : ''));
    button.setAttribute('aria-current', session.id === activeId ? 'true' : 'false');
    button.title = session.title;
    button.append(icon('chat'));
    const text = element('div');
    text.append(element('strong', '', session.title), element('small', '', new Date(session.createdAt).toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'}) + ' · ' + (session.demoUserId ? '用户 ' + session.demoUserId : '访客')));
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
/**
 * 从后向前找最近一次意图结果；遇到清空记忆标记就停止，不展示旧上下文的洞察作为当前结果。
 */
function latestResult(session) {
  // 清空标记结束旧模型上下文，但此前页面记录仍可见。
  for (let i = session.turns.length - 1; i >= 0; i--) {
    if (session.turns[i].role === 'system') return null;
    if (session.turns[i].intent) return session.turns[i];
  }
  return null;
}
/**
 * 将输入框原文存入当前会话草稿，切换会话或页面离开时可以恢复未发送内容。
 */
function saveDraft() { if (current()) current().draft = $('message-input').value; }
/**
 * 重绘当前会话记录，空会话使用 template 示例；用户、助手、清空提示和错误采用不同样式。
 * 查看历史回复只更新洞察面板，不再请求模型或改写服务端记忆。
 */
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
/**
 * 展示选中回复的分类 JSON、订单号、缺失字段和置信度；非法分数不直接用于样式宽度。
 * 订单号标明来源于对话而未验证归属，置信度条也不是业务成功率。
 */
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
  renderToolResults(selectedTurn);
}
/**
 * 仅渲染后端返回的 orderLookups 轨迹；不能根据回答里“查到了”三个字推断执行过工具。
 * 查询状态和订单状态分别翻译，成功日期明确作为固定样例展示。
 */
function renderToolResults(turn) {
  const results = Array.isArray(turn?.orderLookups) ? turn.orderLookups : [];
  $('tool-summary').textContent = !turn ? '发送消息后查看是否执行查询' : results.length ? `实际执行了 ${results.length} 次查询` : '本轮没有执行订单查询';
  $('tool-results').replaceChildren();
  const codes = {FOUND:'已查询',NOT_FOUND:'未找到可访问的订单',MISSING_ORDER_NO:'需要订单号',INVALID_ORDER_NO:'订单号格式有误',AUTHENTICATION_REQUIRED:'请先选择演示用户',TEMPORARILY_UNAVAILABLE:'订单服务暂不可用'};
  const statuses = {CREATED:'已创建',PAID:'已支付',PACKING:'打包中',SHIPPED:'已发货',DELIVERED:'已送达',CANCELLED:'已取消',REFUNDING:'退款处理中',REFUNDED:'已退款'};
  results.filter(result => result && typeof result === 'object').forEach(result => {
    const card = element('div','tool-result');
    card.append(element('strong','',codes[result.code] || '查询结果'));
    if (result.code === 'FOUND') {
      card.append(element('p','',`${result.orderNo} · ${statuses[result.status] || result.status}`));
      card.append(element('small','',result.expectedDeliveryDate ? `样例预计送达：${result.expectedDeliveryDate}` : '未提供预计送达日期'));
    } else { card.append(element('p','',result.message || '请根据查询结果继续操作。')); }
    const details = element('details');
    details.append(element('summary','','查看工具返回'),element('pre','',JSON.stringify(result,null,2)));
    card.append(details); $('tool-results').append(card);
  });
}
/**
 * 统一刷新导航、消息、洞察、演示用户和输入控件，使页面各区来自同一当前会话状态。
 */
function render() {
  renderSessions(); renderMessages(); renderInsight();
  if (current()) $('demo-user').value = current().demoUserId ? String(current().demoUserId) : 'guest';
  $('session-title').textContent = current()?.title || '开始一场对话';
  $('session-meta').textContent = current() ? (current().demoUserId ? '用户 ' + current().demoUserId : '访客') + ' · 会话 ' + activeId : '点击“新建会话”开始';
  updateControls();
}
/**
 * 封装同源 JSON 请求，合并调用方演示身份头并转换 HTTP 错误。
 * 204 的清空响应没有正文，不能直接调用 response.json()。
 */
async function request(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { 'Content-Type':'application/json', ...options.headers } });
  if (!response.ok) throw new Error(response.status === 400 ? '输入不符合要求，请检查消息内容。'
    : response.status === 502 ? '模型暂时没有返回回复，请稍后继续。'
    : `服务请求失败（${response.status}），请确认 Spring Boot 已正常启动。`);
  return response.status === 204 ? null : response.json();
}
/**
 * 将 fetch 的网络 TypeError 转成启动提示；业务或格式错误保留已转换的可读说明。
 */
function errorMessage(error) {
  return error instanceof TypeError ? '无法连接服务，请确认 Spring Boot 正在运行。' : error.message || '请求失败，请稍后继续。';
}
/**
 * 请求服务器生成会话 ID，固定此会话的演示身份，再插入页面列表。
 * 失败时保留原会话，finally 始终解除忙碌状态。
 */
async function newSession() {
  if (busy) return;
  const demoUserId = $('demo-user').value === 'guest' ? null : Number($('demo-user').value);
  saveDraft(); busy = true; updateControls(); showNotice();
  try {
    const data = await request('/api/conversations', { method:'POST' });
    if (!data || !/^[A-Za-z0-9_-]{1,100}$/.test(data.conversationId)) throw new Error('服务返回了无效的会话编号。');
    const session = { id:data.conversationId, demoUserId, title:'新会话', turns:[], createdAt:Date.now() };
    sessions.unshift(session); activeId = session.id; selectedTurn = null; $('message-input').value = '';
    persist();
  } catch (error) { showNotice(errorMessage(error)); }
  finally { busy = false; render(); }
}
/**
 * 先在页面保存客户消息与等待提示，再调用一次会话 API 获取回复、意图和工具结果。
 * 失败时服务端可能已存入客户消息，因此保留错误标记并提醒避免盲目重发。
 */
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
    const data = await request(`/api/conversations/${encodeURIComponent(session.id)}/messages`, { method:'POST', headers: demoHeaders(session), body:JSON.stringify({message}) });
    if (!data || typeof data.answer !== 'string' || !data.intent || data.conversationId !== session.id) throw new Error('回复格式异常，请稍后继续。');
    const turn = { role:'assistant', text:data.answer, intent:data.intent, orderLookups:Array.isArray(data.orderLookups) ? data.orderLookups : [], time:Date.now() };
    session.turns.push(turn); selectedTurn = turn;
  } catch (error) {
    session.turns.push({ role:'error', text:errorMessage(error) + '\n本条消息可能已进入模型记忆，请避免重复发送。需要重新开始时可清空记忆。', time:Date.now() });
  } finally {
    pending.remove(); busy = false; saveDraft(); persist(); render(); $('message-input').focus({preventScroll:true});
  }
}
/**
 * 调用 DELETE 清空服务端窗口；成功后在页面追加分界提示，之前的可见消息仍保留。
 */
async function clearMemory() {
  if (busy || !current()) return;
  const session = current(); busy = true; updateControls(); showNotice();
  try {
    await request(`/api/conversations/${encodeURIComponent(session.id)}/memory`, { method:'DELETE', headers: demoHeaders(session) });
    session.turns.push({role:'system',text:'记忆已清空 · 接下来的对话从这里重新开始',time:Date.now()});
    selectedTurn = null; persist();
  } catch (error) { showNotice(errorMessage(error)); }
  finally { busy = false; render(); }
}
/**
 * 把当前会话已选的演示用户放入请求头，访客省略该头。
 * 这是本地教学身份选择，实际生产必须由可信登录机制提供身份。
 */
function demoHeaders(session) {
  // 请求头仅选择本地演示身份，不能用于生产认证。
  return session.demoUserId ? {'X-Demo-User-Id':String(session.demoUserId)} : {};
}
// 换演示用户必须新建会话，不能把已有窗口直接切给另一身份。
$('demo-user').addEventListener('change', newSession);
$('chat-form').addEventListener('submit', sendMessage);
$('message-input').addEventListener('input', () => { saveDraft(); updateControls(); });
// 中文输入法组词阶段的 Enter 不发送；Shift+Enter 保留为换行。
$('message-input').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault(); if (!busy) $('chat-form').requestSubmit();
  }
});
$('new-session').addEventListener('click', newSession);
$('clear-memory').addEventListener('click', () => {
  if (!busy && current()) {
    // 每次打开前重置结果，确保曾经确认过后再按 Escape 仍然是取消。
    $('clear-dialog').returnValue = 'cancel';
    $('clear-dialog').showModal();
  }
});
// 只有对话框显式确认才调用清空 API；取消和 Escape 都不发请求。
$('clear-dialog').addEventListener('close', () => { if ($('clear-dialog').returnValue === 'confirm') clearMemory(); });
// 离开页面前保存草稿；初始化时恢复页面记录，无会话则向服务端新建。
window.addEventListener('pagehide', () => { saveDraft(); persist(); });
if (matchMedia('(max-width:960px)').matches) $('inspector-details').open = false;
restore();
if (current()) { selectedTurn = latestResult(current()); $('message-input').value = current().draft || ''; render(); }
else { render(); newSession(); }
