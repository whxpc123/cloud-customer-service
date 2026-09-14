/**
 * 第九章无状态知识问答：每次提交完整问题，展示答复与 Java 返回的实际证据。
 * 不使用客服会话历史或订单工具；ANSWERED 只表示模型返回，用户仍需展开证据核对。
 */
'use strict';
// 页面元素查询简写；所有 ID 都对应当前 HTML 中的固定节点。
const $ = (id) => document.getElementById(id);
// 执行状态只描述本次流程：ANSWERED 也可能是模型拒答，不能显示为“已验证正确”。
const labels = { ANSWERED: '模型已返回 · 请核对依据', NO_EVIDENCE: '无证据 · 未调用聊天模型', TEMPORARILY_UNAVAILABLE: '服务暂不可用' };
let busy = false;
// 示例只填入问题，点击提交后才发起检索与生成。
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => {
  if (!busy) { $('question').value = button.dataset.question; $('question').focus(); }
}));
/**
 * 删除旧证据、原始 JSON 和执行状态，避免请求失败后残留上一次成功的来源。
 */
function clearResult() {
  $('references').replaceChildren();
  $('raw-details').hidden = true;
  $('raw-details').open = false;
  $('evidence-panel').hidden = true;
  $('raw-result').textContent = '';
  $('answer-status').removeAttribute('data-status');
}
/**
 * 验证执行状态、答复文本和来源数组，再按纯文本生成可折叠证据卡。
 * 来源由后端确定，页面不从模型答复中解析或推断引用。
 */
function render(data) {
  if (!Object.hasOwn(labels, data.status) || typeof data.answer !== 'string' || !Array.isArray(data.references)) throw new Error('服务返回的格式不正确，请重试。');
  $('answer-status').textContent = labels[data.status];
  $('answer-status').dataset.status = data.status;
  $('answer').textContent = data.answer;
  $('evidence-panel').hidden = false;
  $('evidence-count').textContent = `（${data.references.length} 块）`;
  for (const ref of data.references) {
    const card = document.createElement('details');
    card.className = 'evidence-card';
    const title = document.createElement('summary');
    title.textContent = `${ref.sourceName || ref.sourceId} · v${ref.sourceVersion} · 第 ${ref.chunkIndex} 块`;
    const metadata = document.createElement('pre');
    metadata.textContent = `documentId: ${ref.documentId}\nsourceId: ${ref.sourceId}\ncategory: ${ref.category}\n相似度: ${Number(ref.score).toFixed(4)}`;
    const body = document.createElement('p');
    body.textContent = ref.content;
    card.append(title, metadata, body);
    $('references').append(card);
  }
  $('raw-result').textContent = JSON.stringify(data, null, 2);
  $('raw-details').hidden = false;
}
// 单次提交锁住发送按钮；保存本次问题快照，避免编辑输入后混淆它与当前显示的答案。
$('rag-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (busy) return;
  const question = $('question').value.trim();
  if (!question) { $('feedback').textContent = '请先输入一个完整的问题。'; return; }
  busy = true;
  $('ask').disabled = true;
  $('rag-output').setAttribute('aria-busy', 'true');
  clearResult();
  $('answered-question').hidden = false;
  $('answered-question').textContent = `本次问题：${question}`;
  $('answer').textContent = '正在检索资料；有证据后会生成回答…';
  $('answer-status').textContent = '处理中';
  $('feedback').textContent = '正在查找相关知识，请稍候。';
  try {
    const response = await fetch('/internal/rag/answer', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question })
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || `请求失败（${response.status}），请检查服务后重试。`);
    render(data);
    $('feedback').textContent = data.status === 'ANSWERED' ? '回答已返回，请展开证据核对条件与例外。' : data.answer;
  } catch (error) {
    clearResult();
    $('answer-status').textContent = '请求失败';
    $('answer').textContent = '本次未取得可用结果，请稍后重试。';
    $('feedback').textContent = error.message || '网络请求失败，请稍后重试。';
  // 无论 HTTP、网络或响应格式是否失败，都恢复按钮和无障碍忙碌状态。
  } finally {
    busy = false;
    $('ask').disabled = false;
    $('rag-output').setAttribute('aria-busy', 'false');
  }
});
