'use strict';
const $ = (id) => document.getElementById(id);
const labels = { ANSWERED: '模型已返回 · 请核对依据', NO_EVIDENCE: '无证据 · 未调用聊天模型', TEMPORARILY_UNAVAILABLE: '服务暂不可用' };
let busy = false;
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => {
  if (!busy) { $('question').value = button.dataset.question; $('question').focus(); }
}));
function clearResult() {
  $('references').replaceChildren();
  $('raw-details').hidden = true;
  $('raw-details').open = false;
  $('evidence-panel').hidden = true;
  $('raw-result').textContent = '';
  $('answer-status').removeAttribute('data-status');
}
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
  } finally {
    busy = false;
    $('ask').disabled = false;
    $('rag-output').setAttribute('aria-busy', 'false');
  }
});
