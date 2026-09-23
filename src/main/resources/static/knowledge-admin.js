/**
 * 云杉知识管理：来源目录、真实原件、导入预览、多会话问答和可复现的基础评测。
 * 动态数据全部用 textContent/DOM 节点渲染；只有本文件的固定模板使用 innerHTML。
 * routeVersion 防止慢请求把结果写入已切换的页面；导航不会取消服务器正在执行的任务。
 */
"use strict";
const app = document.getElementById("app"),
  base = "/internal/knowledge-admin";
let routeVersion = 0,
  toastTimer,
  chatBusy = false,
  evalDraft = null;
const $ = (id) => document.getElementById(id);
const statuses = {
  PUBLISHED: "已发布",
  ARCHIVED: "回收站",
  OTHER: "其他状态",
  QUEUED: "排队中",
  RUNNING: "运行中",
  COMPLETED: "已完成",
  COMPLETED_WITH_ERRORS: "完成 · 有失败题",
  INTERRUPTED: "已中断",
  ANSWERED: "模型已回答 · 请核对来源",
  NEEDS_CLARIFICATION: "需要补充场景",
  NO_EVIDENCE: "无证据 · 已拦截",
  TEMPORARILY_UNAVAILABLE: "服务暂不可用",
};
/** 创建文本节点；资料、模型文本和缓存内容都不解释为 HTML。 */
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
/** 图标和链接由固定应用路由构造，来源 ID 使用 URL 编码。 */
function link(text, href, cls) {
  const a = el("a", text, cls);
  a.href = href;
  return a;
}
function button(text, action, cls) {
  const b = el("button", text, cls);
  b.type = "button";
  b.addEventListener("click", action);
  return b;
}
function badge(status) {
  return el(
    "span",
    statuses[status] || status,
    "badge " +
      (["PUBLISHED", "COMPLETED"].includes(status)
        ? "good"
        : [
              "OTHER",
              "INTERRUPTED",
              "TEMPORARILY_UNAVAILABLE",
              "COMPLETED_WITH_ERRORS",
            ].includes(status)
          ? "warn"
          : ""),
  );
}
function time(value) {
  if (!value) return "未记录";
  const d = new Date(value);
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleString("zh-CN", { hour12: false });
}
function size(value) {
  return value == null
    ? "未保存"
    : value < 1024
      ? value + " B"
      : value < 1048576
        ? (value / 1024).toFixed(1) + " KB"
        : (value / 1048576).toFixed(2) + " MB";
}
/** 统一可操作错误，不把无证据业务状态当网络成功提示。 */
async function api(path, options = {}) {
  const r = await fetch(path, { cache: "no-store", ...options });
  let data = null;
  if (r.status !== 204) {
    try {
      data = await r.json();
    } catch {
      if (r.ok) throw new Error("服务返回格式异常，请检查 IDEA 日志。");
    }
  }
  if (!r.ok)
    throw new Error(
      data?.message ||
        data?.detail ||
        (r.status === 404
          ? "接口未启用，请使用 local,knowledge 配置启动。"
          : `请求失败（${r.status}），请检查服务后重试。`),
    );
  return data;
}
function jsonOptions(method, data) {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  };
}
function notify(message) {
  clearTimeout(toastTimer);
  $("toast").textContent = message;
  $("toast").hidden = false;
  toastTimer = setTimeout(() => ($("toast").hidden = true), 5000);
}
function errorPanel(error) {
  app.replaceChildren(
    el("div", error.message || "无法连接应用，请检查 IDEA 与数据库。", "error"),
    button("重新加载", route),
  );
}
/** 原生 dialog 支持键盘取消，只有显式确认才返回 true。 */
function confirmAction(title, text) {
  $("confirm-title").textContent = title;
  $("confirm-text").textContent = text;
  const d = $("confirm-dialog");
  d.returnValue = "cancel";
  d.showModal();
  return new Promise((resolve) =>
    d.addEventListener("close", () => resolve(d.returnValue === "confirm"), {
      once: true,
    }),
  );
}
function heading(title, description, actions) {
  const h = el("div", undefined, "heading"),
    text = el("div");
  text.append(el("h1", title), el("p", description));
  h.append(text);
  if (actions) h.append(actions);
  return h;
}
function stat(label, value, note) {
  const n = el("div", undefined, "stat");
  n.append(el("small", label), el("strong", value), el("p", note || ""));
  return n;
}
function empty(parent, title, description) {
  const n = el("div", undefined, "empty");
  n.append(el("strong", title), el("p", description));
  parent.append(n);
}
/** 安全文本 Markdown 子集：标题、表格、列表、代码块与粗体；不执行 HTML/图片/外部链接。 */
function inline(parent, text) {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  for (const part of parts) {
    if (part.startsWith("**") && part.endsWith("**"))
      parent.append(el("strong", part.slice(2, -2)));
    else if (part.startsWith("`") && part.endsWith("`"))
      parent.append(el("code", part.slice(1, -1)));
    else parent.append(document.createTextNode(part));
  }
}
function markdown(text) {
  const root = el("div", undefined, "prose"),
    lines = String(text || "")
      .replace(/\r\n/g, "\n")
      .split("\n");
  let i = 0;
  const cells = (l) =>
    l
      .trim()
      .replace(/^\|/, "")
      .replace(/\|$/, "")
      .split("|")
      .map((x) => x.trim());
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      i++;
      continue;
    }
    if (line.startsWith("```")) {
      const code = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```"))
        code.push(lines[i++]);
      root.append(el("pre", code.join("\n")));
      i++;
      continue;
    }
    if (
      i + 1 < lines.length &&
      line.includes("|") &&
      /^\s*\|?\s*:?-{3,}/.test(lines[i + 1]) &&
      cells(lines[i + 1]).every((c) => /^:?-+:?$/.test(c))
    ) {
      const table = el("table"),
        tr = el("tr");
      cells(line).forEach((c) => {
        const th = el("th");
        inline(th, c);
        tr.append(th);
      });
      const head = el("thead");
      head.append(tr);
      table.append(head);
      i += 2;
      const body = el("tbody");
      while (i < lines.length && lines[i].includes("|") && lines[i].trim()) {
        const r = el("tr");
        cells(lines[i++]).forEach((c) => {
          const td = el("td");
          inline(td, c);
          r.append(td);
        });
        body.append(r);
      }
      table.append(body);
      root.append(table);
      continue;
    }
    const match = line.match(/^(#{1,6})\s+(.*)/);
    if (match) {
      const h = el("h" + Math.min(match[1].length, 4));
      inline(h, match[2]);
      root.append(h);
      i++;
      continue;
    }
    if (/^\s*([-*+] |\d+\. )/.test(line)) {
      const list = el(/^\s*\d/.test(line) ? "ol" : "ul");
      while (i < lines.length && /^\s*([-*+] |\d+\. )/.test(lines[i])) {
        const li = el("li");
        inline(li, lines[i++].replace(/^\s*([-*+] |\d+\. )/, ""));
        list.append(li);
      }
      root.append(list);
      continue;
    }
    const p = el("p");
    inline(p, line);
    root.append(p);
    i++;
  }
  return root;
}
/** hash 路由统一修改选中导航；所有读取失败都可在当前页面重试。 */
async function route() {
  const token = ++routeVersion;
  const [rawName, id] = location.hash.slice(1).split("/");
  const name = rawName || "home";
  const group =
    name === "doc" || name === "import"
      ? "documents"
      : name === "run"
        ? "evaluations"
        : name;
  document
    .querySelectorAll("[data-nav]")
    .forEach((n) => n.classList.toggle("active", n.dataset.nav === group));
  $("location").textContent =
    {
      home: "概览",
      documents: "文档管理",
      chat: "知识问答",
      evaluations: "评测分析",
    }[group] || "知识库管理";
  app.innerHTML = '<div class="loading">正在读取数据…</div>';
  try {
    if (name === "documents") await documentsPage(token);
    else if (name === "doc" && id)
      await detailPage(decodeURIComponent(id), token);
    else if (name === "import") importPage(token);
    else if (name === "chat") await chatPage(token);
    else if (name === "evaluations") await evaluationsPage(token);
    else if (name === "run" && id) await runPage(id, token);
    else await homePage(token);
  } catch (error) {
    if (token === routeVersion) errorPanel(error);
  }
}
/** 概览只显示数据库真实计数；历史样例也是文档来源，不当作虚构业务数据。 */
async function homePage(token) {
  const [all, published, archived] = await Promise.all(
    ["ALL", "PUBLISHED", "ARCHIVED"].map((s) =>
      api(base + "/documents?pageSize=5&status=" + s),
    ),
  );
  if (token !== routeVersion) return;
  app.replaceChildren(
    heading(
      "让每份资料，都有来处。",
      "管理资料、追踪回答依据，并用自己的测试题检查知识问答。",
      link("＋ 导入文档", "#import", "primary-link"),
    ),
  );
  const stats = el("div", undefined, "stats");
  stats.append(
    stat("文档来源", all.total, "按来源计数，同名更新不新增来源"),
    stat("已发布", published.total, "可被当前知识库检索"),
    stat("回收站", archived.total, "保留文件和切片，可恢复"),
  );
  app.append(stats);
  const links = el("div", undefined, "help-grid");
  for (const [title, desc, href] of [
    ["文档管理", "从原件到切片，查看资料是否完整。", "#documents"],
    ["知识问答", "连续提问，展开每轮实际检索来源。", "#chat"],
    ["评测分析", "运行自定义题集，查找未命中与拒答差异。", "#evaluations"],
  ]) {
    const a = link("", href);
    a.append(el("strong", title + " ↗"), el("p", desc));
    links.append(a);
  }
  app.append(links);
  const p = el("section", undefined, "panel");
  p.style.marginTop = "22px";
  p.append(el("h2", "最近更新的文档"));
  renderSourceTable(p, all.items);
  app.append(p);
}
/** 文档列表分页在服务器执行，筛选变更总是回到第一页。 */
async function documentsPage(token) {
  app.innerHTML =
    '<div id="doc-heading"></div><section class="panel"><form id="filters" class="toolbar"><label>搜索文档<input id="doc-query" type="search" maxlength="120" placeholder="文档名称或来源 ID"></label><label>状态<select id="doc-status"><option value="PUBLISHED">已发布</option><option value="ARCHIVED">回收站</option><option value="ALL">全部</option><option value="OTHER">其他状态</option></select></label><label>类型<select id="doc-type"><option value="ALL">全部格式</option><option value="MARKDOWN">Markdown</option><option value="TEXT">TXT / 粘贴正文</option><option value="PDF">PDF</option><option value="DOCX">Word</option><option value="PPTX">PowerPoint</option><option value="LEGACY">历史资料</option></select></label><button class="primary">查询</button></form><div id="source-table"></div><div id="source-pager" class="pager"></div></section>';
  $("doc-heading").append(
    heading(
      "文档管理",
      "新文件可下载原件；历史资料可查看和导出已保存的全部知识块。",
      link("＋ 导入文档", "#import", "primary-link"),
    ),
  );
  let page = 1,
    serial = 0;
  async function load() {
    const sequence = ++serial;
    const params = new URLSearchParams({
      query: $("doc-query").value,
      status: $("doc-status").value,
      type: $("doc-type").value,
      page,
      pageSize: 15,
    });
    $("source-table").textContent = "正在查询…";
    try {
      const data = await api(base + "/documents?" + params);
      if (token !== routeVersion || sequence !== serial) return;
      $("source-table").replaceChildren();
      renderSourceTable($("source-table"), data.items);
      const p = $("source-pager");
      p.replaceChildren(
        el(
          "span",
          `共 ${data.total} 份 · 第 ${page} / ${Math.max(1, Math.ceil(data.total / 15))} 页`,
        ),
      );
      const prev = button("上一页", () => {
          page--;
          load();
        }),
        next = button("下一页", () => {
          page++;
          load();
        });
      prev.disabled = page <= 1;
      next.disabled = page * 15 >= data.total;
      p.append(prev, next);
    } catch (e) {
      if (token === routeVersion && sequence === serial)
        $("source-table").replaceChildren(el("p", e.message, "error"));
    }
  }
  $("filters").onsubmit = (e) => {
    e.preventDefault();
    page = 1;
    load();
  };
  await load();
}
function renderSourceTable(parent, items) {
  if (!items.length) {
    empty(
      parent,
      "这里还没有文档",
      "可以导入 TXT、Markdown、PDF、Word、PowerPoint，或调整筛选条件。",
    );
    return;
  }
  const wrap = el("div", undefined, "table-wrap"),
    table = el("table");
  table.innerHTML =
    "<thead><tr><th>文档名称 / 来源</th><th>状态</th><th>切片数</th><th>原件大小</th><th>更新时间</th><th>操作</th></tr></thead>";
  const body = el("tbody");
  for (const s of items) {
    const row = el("tr"),
      title = el("td"),
      a = link(s.name, "#doc/" + encodeURIComponent(s.sourceId), "file-title");
    title.append(
      el("span", "▤", "file-icon"),
      a,
      el(
        "span",
        `${s.fileType} · v${s.version || "未知"} · ${s.sourceId}`,
        "sub",
      ),
    );
    const state = el("td");
    state.append(badge(s.status));
    const action = el("td");
    action.append(link("查看详情", "#doc/" + encodeURIComponent(s.sourceId)));
    row.append(
      title,
      state,
      el("td", String(s.chunks)),
      el("td", size(s.bytes)),
      el("td", time(s.updatedAt)),
      action,
    );
    body.append(row);
  }
  table.append(body);
  wrap.append(table);
  parent.append(wrap);
}
/** 文档详情展示完整来源与切片，回收站操作只更新当前来源，不影响其他文档。 */
async function detailPage(id, token) {
  const d = await api(base + "/documents/" + encodeURIComponent(id));
  if (token !== routeVersion) return;
  const s = d.source,
    actions = el("div", undefined, "row");
  actions.append(
    link("← 返回列表", "#documents"),
    link(
      "导出知识块",
      base + "/documents/" + encodeURIComponent(id) + "/export",
    ),
  );
  if (s.hasOriginal)
    actions.append(
      link(
        "下载原文件",
        base + "/documents/" + encodeURIComponent(id) + "/download",
        "primary-link",
      ),
    );
  if (["PUBLISHED", "ARCHIVED"].includes(s.status))
    actions.append(
      button(
        s.status === "ARCHIVED" ? "恢复发布" : "移入回收站",
        async () => {
          if (
            !(await confirmAction(
              s.status === "ARCHIVED" ? "恢复这份文档？" : "移入回收站？",
              s.status === "ARCHIVED"
                ? "恢复后，文档将重新参与知识检索。"
                : "该文档将退出知识检索，原文件和切片保留，可在回收站恢复。已有问答记录不会被改写。",
            ))
          )
            return;
          try {
            await api(
              base + "/documents/" + encodeURIComponent(id) + "/archive",
              jsonOptions("PATCH", { archived: s.status !== "ARCHIVED" }),
            );
            if (token === routeVersion) route();
            notify("文档状态已更新");
          } catch (e) {
            notify(e.message);
          }
        },
        s.status === "ARCHIVED" ? "" : "danger",
      ),
    );
  app.replaceChildren(
    heading(s.name, "查看原件、来源信息与实际入库切片。", actions),
  );
  const info = el("section", undefined, "panel"),
    dl = el("dl", undefined, "metadata");
  const pairs = [
    ["状态", statuses[s.status] || s.status],
    ["来源 ID", s.sourceId],
    ["版本", s.version],
    ["文件格式", s.fileType],
    ["文件名", d.fileName || "旧资料未保存原文件"],
    ["SHA-256", d.fileHash || "未保存原件，无法计算文件哈希"],
    ["原件大小", size(s.bytes)],
    ["首次上传", time(s.createdAt)],
    ["版本更新", time(s.updatedAt)],
  ];
  for (const [k, v] of pairs) dl.append(el("dt", k), el("dd", v || "未记录"));
  info.append(dl);
  app.append(info);
  const preview = el("section", undefined, "panel"),
    head = el("div", undefined, "heading"),
    previewBody = el("div", undefined, "preview");
  const label = {
    ORIGINAL_TEXT: "原文预览",
    EXTRACTED_TEXT: "提取正文预览",
    CHUNKS: "历史知识块合并预览",
  }[d.previewKind];
  head.append(el("h2", label));
  let raw = false;
  head.append(
    button("切换纯文本 / 排版", () => {
      raw = !raw;
      previewBody.replaceChildren(
        raw ? el("pre", d.preview, "raw") : markdown(d.preview),
      );
    }),
  );
  preview.append(head);
  if (d.previewKind !== "ORIGINAL_TEXT")
    preview.append(
      el(
        "p",
        d.previewKind === "CHUNKS"
          ? "这份旧资料仅保存了知识块，以下按块序合并，不能还原原件排版。重新导入后可保存原文件。"
          : "以下是文档 Reader 提取的文本，版式或表格可能变化，请下载原文件核对。",
        "notice",
      ),
    );
  previewBody.append(markdown(d.preview));
  preview.append(previewBody);
  app.append(preview);
  const chunks = el("section", undefined, "panel");
  chunks.append(el("h2", "切分结果"));
  const stats = el("div", undefined, "stats");
  stats.append(
    stat("切片总数", s.chunks),
    stat("平均字数", d.averageChunk.toFixed(0)),
    stat("最短切片", d.shortestChunk),
    stat("最长切片", d.longestChunk),
  );
  chunks.append(stats);
  const list = el("div"),
    pager = el("div", undefined, "pager");
  chunks.append(list, pager);
  let page = 1;
  function show() {
    list.replaceChildren();
    for (const c of d.chunks.slice((page - 1) * 10, page * 10)) {
      const det = el("details", undefined, "chunk"),
        sum = el("summary");
      sum.append(
        el("span", "#" + c.metadata.chunkIndex),
        el("span", Array.from(c.content).length + " 字"),
        el(
          "span",
          (c.metadata.chunkHash || c.documentId).slice(0, 12),
          "muted",
        ),
      );
      const body = el("div", undefined, "chunk-content");
      body.append(el("p", c.content));
      const metadata = el("details");
      metadata.append(
        el("summary", "查看元数据"),
        el("pre", JSON.stringify(c.metadata, null, 2), "raw"),
      );
      body.append(metadata);
      det.append(sum, body);
      list.append(det);
    }
    pager.replaceChildren(
      el("span", `第 ${page} / ${Math.ceil(d.chunks.length / 10)} 页`),
    );
    const prev = button("上一页", () => {
        page--;
        show();
      }),
      next = button("下一页", () => {
        page++;
        show();
      });
    prev.disabled = page === 1;
    next.disabled = page * 10 >= d.chunks.length;
    pager.append(prev, next);
  }
  show();
  app.append(chunks);
}
/** 导入复用第八章预览和后台任务 API：只有确认当前预览才生成向量、发布原件与知识块。 */
function importPage(token) {
  app.innerHTML =
    '<div id="import-heading"></div><section class="panel"><form id="import-form"><div class="grid-two"><div><label class="field">资料名称<input id="import-name" maxlength="120" required placeholder="例如：售后服务制度"></label><label class="field">版本<input id="import-version" maxlength="60" value="1.0"></label></div><div><label class="field">切分档位<select id="import-size"><option>200</option><option selected>500</option><option>1000</option></select> Token</label><p class="muted">同名资料确认导入后整体替换旧版，并恢复发布；向量生成失败时保留原版。</p></div></div><label class="field">导入方式<select id="import-mode"><option value="file">上传文件</option><option value="text">粘贴正文</option></select></label><label class="field" id="upload-field">TXT / Markdown / PDF / DOCX / PPTX，最多 5 MB<input id="import-file" type="file" accept=".txt,.md,.markdown,.pdf,.docx,.pptx"></label><label class="field" id="paste-field" hidden>完整正文<textarea id="import-text" rows="10" maxlength="50000" placeholder="粘贴需要导入的完整资料"></textarea></label><div class="actions"><button class="primary" id="preview-button">解析并预览切片</button></div></form><p id="import-feedback" role="status"></p></section><section class="panel" id="import-preview" hidden></section>';
  $("import-heading").append(
    heading(
      "导入文档",
      "先核对提取与切分，再确认入库。原文件随成功发布一起保存。",
      link("← 返回文档", "#documents"),
    ),
  );
  let preview = null,
    revision = 0,
    busy = false;
  const clear = () => {
    revision++;
    preview = null;
    $("import-preview").hidden = true;
  };
  $("import-form").addEventListener("input", clear);
  $("import-form").addEventListener("change", clear);
  $("import-mode").onchange = () => {
    $("upload-field").hidden = $("import-mode").value === "text";
    $("paste-field").hidden = $("import-mode").value !== "text";
  };
  $("import-file").onchange = () => {
    const f = $("import-file").files[0];
    if (f && !$("import-name").value) $("import-name").value = f.name;
  };
  function lock(v) {
    busy = v;
    $("import-form")
      .querySelectorAll("input,textarea,select,button")
      .forEach((n) => (n.disabled = v));
  }
  $("import-form").onsubmit = async (e) => {
    e.preventDefault();
    if (busy) return;
    clear();
    const current = revision;
    lock(true);
    $("import-feedback").textContent = "正在提取正文与切分…";
    try {
      let result;
      if ($("import-mode").value === "text")
        result = await api(
          "/internal/knowledge/preview",
          jsonOptions("POST", {
            sourceName: $("import-name").value,
            sourceVersion: $("import-version").value,
            text: $("import-text").value,
            chunkSize: Number($("import-size").value),
          }),
        );
      else {
        const file = $("import-file").files[0];
        if (!file || file.size > 5 * 1024 * 1024)
          throw new Error("请选择不超过 5 MB 的文件。");
        const data = new FormData();
        data.append("file", file);
        data.append("sourceName", $("import-name").value);
        data.append("sourceVersion", $("import-version").value);
        data.append("chunkSize", $("import-size").value);
        result = await api("/internal/knowledge/preview/file", {
          method: "POST",
          body: data,
        });
      }
      if (token !== routeVersion || current !== revision) return;
      preview = result;
      const p = $("import-preview");
      p.replaceChildren(el("h2", "核对预览"));
      p.hidden = false;
      p.append(
        el(
          "p",
          `${result.chunks} 个切片 · ${result.totalCharacters} 字符 · 预览有效至 ${time(result.expiresAt)}`,
          "muted",
        ),
      );
      result.warnings.forEach((w) => p.append(el("p", w, "notice")));
      for (const c of result.previews) {
        const d = el("details", undefined, "chunk");
        d.append(
          el("summary", `#${c.chunkIndex} · ${c.characters} 字符`),
          el("pre", c.text, "raw"),
        );
        p.append(d);
      }
      p.append(
        button(
          "确认这些切片并导入",
          async (event) => {
            if (busy || !preview) return;
            const confirmed = preview,
              commitButton = event.currentTarget;
            lock(true);
            commitButton.disabled = true;
            $("import-feedback").textContent = "已确认，正在提交后台任务…";
            try {
              let job = await api(
                "/internal/knowledge/previews/" +
                  encodeURIComponent(confirmed.previewId) +
                  "/import",
                { method: "POST" },
              );
              while (
                token === routeVersion &&
                ["QUEUED", "EMBEDDING"].includes(job.status)
              ) {
                $("import-feedback").textContent = job.message;
                await new Promise((r) => setTimeout(r, 1500));
                if (token !== routeVersion) return;
                job = await api(
                  "/internal/knowledge/jobs/" + encodeURIComponent(job.jobId),
                );
              }
              if (token !== routeVersion) return;
              if (job.status !== "PUBLISHED") throw new Error(job.message);
              notify("文档已导入，原件和切片已保存。");
              location.hash = "doc/" + encodeURIComponent(job.result.sourceId);
            } catch (error) {
              if (token === routeVersion) {
                $("import-feedback").textContent = error.message;
                lock(false);
                commitButton.disabled = false;
              }
            }
          },
          "primary",
        ),
      );
      $("import-feedback").textContent = "预览完成，尚未调用向量模型。";
    } catch (error) {
      if (token === routeVersion)
        $("import-feedback").textContent = error.message;
    } finally {
      if (token === routeVersion) lock(false);
    }
  };
}
/** 会话列表只缓存在当前浏览器标签页，后端仍为内存窗口；不假装跨设备持久化聊天。 */
const chatKey = "yunshan-knowledge-admin-chat-v1";
let chats = [],
  activeChat = null;
try {
  const cached = JSON.parse(sessionStorage.getItem(chatKey) || "[]");
  if (Array.isArray(cached))
    chats = cached
      .filter(
        (c) =>
          c &&
          typeof c.id === "string" &&
          /^[a-zA-Z0-9_-]{1,100}$/.test(c.id) &&
          ["1001", "2002", "guest"].includes(c.user) &&
          Array.isArray(c.turns),
      )
      .slice(0, 20);
} catch {
  /* 缓存损坏时从空列表开始，不把缓存当后端状态。 */
}
function saveChats() {
  try {
    sessionStorage.setItem(chatKey, JSON.stringify(chats));
  } catch {
    notify("页面记录较大，本次未能保存到浏览器；服务端记忆不受影响。");
  }
}
function chatHeaders(user) {
  return {
    "Content-Type": "application/json",
    ...(user === "guest" ? {} : { "X-Demo-User-Id": user }),
  };
}
/** 同一时间只发送一个问题，锁定身份、切换和清空，避免对同会话并行调用。 */
async function chatPage(token) {
  app.innerHTML =
    '<div id="chat-heading"></div><div class="chat-layout"><aside class="chat-sessions"><button id="new-chat" class="primary">＋ 新建对话</button><div id="chat-list"></div><p class="muted">记录仅保留在当前标签页。应用重启会清空模型记忆，页面记录不会自动补送。</p></aside><section class="chat-main"><div class="chat-head"><span id="chat-title">知识问答</span><label>演示身份 <select id="chat-user"><option value="1001">用户 1001</option><option value="2002">用户 2002</option><option value="guest">访客</option></select></label><button id="chat-clear">清空模型记忆</button></div><div id="chat-history" class="chat-history" role="log"></div><form id="chat-form" class="chat-compose"><textarea id="chat-question" rows="3" maxlength="2000" required placeholder="输入问题，Enter 发送，Shift + Enter 换行"></textarea><div class="actions"><small>结合历史补全追问后检索；来源可核对，回答未逐句验证。</small><button id="chat-send" class="primary">发送问题</button></div></form></section></div>';
  $("chat-heading").append(
    heading("知识问答", "带着上文继续提问，展开每轮实际检索的资料。"),
  );
  if (!chats.some((c) => c.id === activeChat))
    activeChat = chats[0]?.id || null;
  function current() {
    return chats.find((c) => c.id === activeChat);
  }
  function lock() {
    if (token !== routeVersion) return;
    app
      .querySelectorAll("button,select,textarea")
      .forEach((n) => (n.disabled = chatBusy));
    $("chat-send").disabled = chatBusy || !current();
    $("chat-clear").disabled = chatBusy || !current();
  }
  function display() {
    if (token !== routeVersion) return;
    const list = $("chat-list");
    list.replaceChildren();
    for (const c of chats) {
      const item = el(
          "div",
          undefined,
          "session-item" + (c.id === activeChat ? " active" : ""),
        ),
        b = button("", () => {
          if (!chatBusy) {
            activeChat = c.id;
            display();
          }
        });
      b.append(
        el("strong", c.title || "新对话"),
        el(
          "small",
          `${c.turns.filter((t) => t.role === "assistant").length} 轮 · ${c.user === "guest" ? "访客" : "用户 " + c.user}`,
        ),
      );
      item.append(b);
      list.append(item);
    }
    const c = current(),
      history = $("chat-history");
    history.replaceChildren();
    if (!c)
      empty(
        history,
        "从一个问题开始",
        "新建对话后，选择资料相关问题进行提问。",
      );
    else {
      $("chat-user").value = c.user;
      $("chat-title").textContent = c.title || "新对话";
      if (!c.turns.length)
        empty(
          history,
          "有什么资料需要核对？",
          "例如：因个人原因退货，运费由谁承担？",
        );
      c.turns.forEach((t) => renderTurn(history, t));
    }
    history.scrollTop = history.scrollHeight;
    lock();
  }
  async function create() {
    if (chatBusy) return;
    const user = $("chat-user").value;
    chatBusy = true;
    lock();
    try {
      const data = await api("/internal/advisor-rag/conversations", {
        method: "POST",
      });
      chats.unshift({
        id: data.conversationId,
        user,
        title: "新对话",
        turns: [],
      });
      chats = chats.slice(0, 20);
      activeChat = data.conversationId;
      saveChats();
    } catch (e) {
      notify(e.message);
      if (current() && token === routeVersion)
        $("chat-user").value = current().user;
    } finally {
      chatBusy = false;
      display();
      if (token !== routeVersion && location.hash === "#chat") route();
    }
  }
  $("new-chat").onclick = create;
  $("chat-user").onchange = create;
  $("chat-clear").onclick = async () => {
    if (chatBusy || !current()) return;
    const c = current();
    if (
      !(await confirmAction(
        "清空当前模型记忆？",
        "清空后新问题不再使用之前的上下文，当前页面记录仍保留。",
      ))
    )
      return;
    if (chatBusy || c !== current()) return;
    chatBusy = true;
    lock();
    try {
      await api(
        "/internal/advisor-rag/conversations/" +
          encodeURIComponent(c.id) +
          "/memory",
        { method: "DELETE", headers: chatHeaders(c.user) },
      );
      c.turns.push({
        role: "system",
        text: "模型记忆已清空，之后的问题从这里重新开始。",
      });
      saveChats();
    } catch (e) {
      notify(e.message);
    } finally {
      chatBusy = false;
      display();
      if (token !== routeVersion && location.hash === "#chat") route();
    }
  };
  $("chat-form").onsubmit = async (e) => {
    e.preventDefault();
    const c = current(),
      question = $("chat-question").value.trim();
    if (chatBusy || !c || !question) return;
    chatBusy = true;
    c.turns.push({ role: "user", text: question });
    if (c.title === "新对话") c.title = question.slice(0, 30);
    $("chat-question").value = "";
    display();
    const pending = el("p", "正在检索知识并生成回答…", "muted");
    $("chat-history").append(pending);
    try {
      const response = await api(
        "/internal/advisor-rag/conversations/" +
          encodeURIComponent(c.id) +
          "/messages",
        {
          method: "POST",
          headers: chatHeaders(c.user),
          body: JSON.stringify({ question }),
        },
      );
      c.turns.push({ role: "assistant", response });
    } catch (e) {
      c.turns.push({
        role: "system",
        text: e.message + " 本轮问题可能已进入模型记忆，可清空后重试。",
      });
    } finally {
      chatBusy = false;
      saveChats();
      display();
      if (token !== routeVersion && location.hash === "#chat") route();
      if (token === routeVersion) $("chat-question").focus();
    }
  };
  $("chat-question").onkeydown = (e) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      $("chat-form").requestSubmit();
    }
  };
  display();
  if (!chats.length && !chatBusy) await create();
}
/** 每个来源卡片对应该轮 response.references；没有伪造 rerank、校验状态或外部 Trace 链接。 */
function renderTurn(parent, turn) {
  const n = el("article", undefined, "message " + turn.role);
  if (turn.role !== "assistant") {
    n.textContent = turn.text;
    parent.append(n);
    return;
  }
  const r = turn.response;
  if (!r || !Array.isArray(r.references)) {
    n.textContent = "该条页面记录格式不完整，请新建对话。";
    parent.append(n);
    return;
  }
  n.append(badge(r.status), markdown(r.answer));
  // 新轨迹可空以兼容旧评测记录，展示实际分路而不把计划查询冒充已执行。
  if (r.expansion) n.append(el("p", `多路状态：${r.expansion.status} · 检索 ${r.expansion.retrievalStatus}\n${r.expansion.retrievals.map(b => `${b.query}（${b.documents.length} 块）`).join("\n")}\n候选 ${r.expansion.rawDocumentCount} → ${r.expansion.joinedDocumentCount}，重复 ${r.expansion.duplicateDocumentCount}`, "trace"));
  n.append(
    el(
      "p",
      `requestId: ${r.requestId}\n首路搜索：${r.retrievalQuery ?? "未执行检索"}\n原始问题：${r.transformation?.originalQuery ?? "旧记录未保存"}\n转换：${r.transformation?.stages.map(s => `${s.name} ${s.status} (${s.durationMs} ms)`).join(" → ") ?? "旧记录未保存"}`,
      "trace",
    ),
  );
  r.references.forEach((ref, i) => {
    const d = el("details", undefined, "citation"),
      summary = el("summary");
    summary.textContent = `[${i + 1}] ${ref.sourceName || ref.sourceId} · v${ref.sourceVersion} · 第 ${ref.chunkIndex} 块 · 向量相似度 ${Number(ref.score).toFixed(4)}`;
    const body = el("div");
    body.append(
      markdown(ref.content),
      link("查看文档详情 ↗", "#doc/" + encodeURIComponent(ref.sourceId)),
    );
    d.append(summary, body);
    n.append(d);
  });
  const raw = el("details");
  raw.append(
    el("summary", "查看接口 JSON"),
    el("pre", JSON.stringify(r, null, 2), "raw"),
  );
  n.append(raw);
  parent.append(n);
}
/** 自建题集用可增删行编辑；只有点击开始才触发真实模型调用。 */
async function evaluationsPage(token) {
  const runs = await api(base + "/evaluations");
  if (token !== routeVersion) return;
  app.innerHTML =
    '<div id="eval-heading"></div><section class="panel"><h2>新建基础评测</h2><label class="field">运行名称<input id="eval-name" maxlength="80" placeholder="例如：售后资料更新回归"></label><p class="notice">每题独立会话，按顺序执行真实问答，会产生模型调用费用。引用命中表示召回至少一个期望来源；拒答检查只比较 NO_EVIDENCE 状态，不评价自然语言是否正确。</p><div class="table-wrap"><table class="eval-cases"><thead><tr><th>问题</th><th>应无证据拒答</th><th>期望来源 ID（逗号分隔，可空）</th><th></th></tr></thead><tbody id="eval-rows"></tbody></table></div><div class="actions"><button id="eval-add">＋ 添加测试题</button><button id="eval-start" class="primary">开始评测</button></div><p id="eval-feedback" role="status"></p></section><section class="panel"><h2>最近 50 次评测</h2><div id="eval-runs"></div></section>';
  $("eval-heading").append(
    heading("评测分析", "保存每题实际回答与证据，定位引用未命中和拒答差异。"),
  );
  let cases = evalDraft?.cases || [
    { question: "", shouldRefuse: false, expectedSources: [] },
  ];
  $("eval-name").value = evalDraft?.name || "";
  evalDraft = null;
  function rows() {
    const root = $("eval-rows");
    root.replaceChildren();
    cases.forEach((c, i) => {
      const row = el("tr"),
        q = el("textarea"),
        ref = el("input"),
        select = el("select");
      q.value = c.question;
      q.maxLength = 2000;
      q.rows = 2;
      q.setAttribute("aria-label", "第 " + (i + 1) + " 题问题");
      q.oninput = () => (c.question = q.value);
      select.innerHTML =
        '<option value="false">否</option><option value="true">是</option>';
      select.value = String(c.shouldRefuse);
      select.setAttribute("aria-label", "第 " + (i + 1) + " 题应拒答");
      select.onchange = () => (c.shouldRefuse = select.value === "true");
      ref.value = c.expectedSources.join(", ");
      ref.placeholder = "文档详情中的来源 ID";
      ref.setAttribute("aria-label", "第 " + (i + 1) + " 题期望来源");
      ref.oninput = () =>
        (c.expectedSources = ref.value
          .split(/[,，]/)
          .map((s) => s.trim())
          .filter(Boolean));
      for (const x of [
        q,
        select,
        ref,
        button(
          "移除",
          () => {
            cases.splice(i, 1);
            rows();
          },
          "danger",
        ),
      ]) {
        const td = el("td");
        td.append(x);
        row.append(td);
      }
      root.append(row);
    });
    $("eval-add").disabled = cases.length >= 20;
  }
  $("eval-add").onclick = () => {
    if (cases.length < 20) {
      cases.push({ question: "", shouldRefuse: false, expectedSources: [] });
      rows();
    }
  };
  $("eval-start").onclick = async () => {
    const name = $("eval-name").value.trim();
    if (!name || !cases.length || cases.some((c) => !c.question.trim())) {
      $("eval-feedback").textContent = "请填写运行名称和每题问题。";
      return;
    }
    const request = { name, cases };
    app
      .querySelectorAll("input,select,textarea,button")
      .forEach((n) => (n.disabled = true));
    $("eval-feedback").textContent = "正在保存题集并提交运行…";
    try {
      const run = await api(
        base + "/evaluations",
        jsonOptions("POST", request),
      );
      if (token === routeVersion) location.hash = "run/" + run.id;
      else notify("评测已开始，可到评测分析查看。");
    } catch (e) {
      if (token === routeVersion) {
        $("eval-feedback").textContent = e.message;
        app
          .querySelectorAll("input,select,textarea,button")
          .forEach((n) => (n.disabled = false));
      }
    }
  };
  rows();
  const root = $("eval-runs");
  if (!runs.length)
    empty(
      root,
      "还没有评测记录",
      "填写第一组测试题，建立资料更新前后的比较依据。",
    );
  else {
    const table = el("table");
    table.innerHTML =
      "<thead><tr><th>评测名称</th><th>状态</th><th>已完成 / 题数</th><th>创建时间</th></tr></thead>";
    const body = el("tbody");
    runs.forEach((r) => {
      const row = el("tr"),
        name = el("td"),
        status = el("td");
      name.append(link(r.name, "#run/" + r.id));
      status.append(badge(r.status));
      row.append(
        name,
        status,
        el("td", `${r.completed} / ${r.cases}`),
        el("td", time(r.createdAt)),
      );
      body.append(row);
    });
    table.append(body);
    const wrap = el("div", undefined, "table-wrap");
    wrap.append(table);
    root.append(wrap);
  }
}
/** 运行详情增量轮询，不自动重跑。不可用题从比较分母排除，另列失败数与覆盖题数。 */
async function runPage(id, token) {
  const r = await api(base + "/evaluations/" + encodeURIComponent(id));
  if (token !== routeVersion) return;
  const actions = el("div", undefined, "row");
  actions.append(
    link("← 返回列表", "#evaluations"),
    button("复制题集", () => {
      evalDraft = {
        name: r.name + " · 复测",
        cases: JSON.parse(JSON.stringify(r.cases)),
      };
      location.hash = "evaluations";
    }),
  );
  app.replaceChildren(
    heading(
      r.name,
      `${statuses[r.status] || r.status} · 已保存 ${r.results.length} / ${r.cases.length} 题 · ${time(r.createdAt)}`,
      actions,
    ),
  );
  const stats = el("div", undefined, "stats"),
    valid = r.results.filter((x) => !x.error),
    ref = valid.filter((x) => x.sourceHit !== null),
    match = valid.filter((x) => x.refusalMatched !== null);
  const rate = (a, key) =>
    a.length
      ? ((a.filter((x) => x[key]).length / a.length) * 100).toFixed(1) + "%"
      : "—";
  stats.append(
    stat(
      "期望来源命中率",
      rate(ref, "sourceHit"),
      `${ref.length} 题配置了期望来源且执行成功`,
    ),
    stat(
      "无证据拒答匹配率",
      rate(match, "refusalMatched"),
      `${match.length} 题有可比较结果`,
    ),
    stat(
      "平均整轮耗时",
      valid.length
        ? (valid.reduce((a, b) => a + b.durationMs, 0) / valid.length).toFixed(
            0,
          ) + " ms"
        : "—",
      "仅成功执行题；包含检索与生成",
    ),
    stat(
      "执行失败",
      r.results.filter((x) => x.error).length,
      "失败不计作正确拒答",
    ),
  );
  app.append(stats);
  app.append(
    el(
      "p",
      "本次基础评测未接入忠实度、回答相关性、上下文精确率/召回率及首 Token 延迟；当前结果不能替代人工事实核验。",
      "notice",
    ),
  );
  const panel = el("section", undefined, "panel"),
    filter = el("label", undefined, "row"),
    check = el("input");
  check.type = "checkbox";
  filter.append(check, document.createTextNode("仅看不匹配或执行失败的题目"));
  panel.append(filter);
  const list = el("div");
  panel.append(list);
  app.append(panel);
  function show() {
    list.replaceChildren();
    const results = r.results.filter(
      (x) =>
        !check.checked ||
        x.error ||
        x.refusalMatched === false ||
        x.sourceHit === false,
    );
    if (!results.length) {
      empty(
        list,
        "暂无符合条件的题目",
        ["QUEUED", "RUNNING"].includes(r.status)
          ? "评测运行中，完成一题后会显示结果。"
          : "可以取消筛选查看全部题目。",
      );
      return;
    }
    for (const result of results) {
      const d = el("details", undefined, "case-details chunk"),
        title = el("summary");
      const outcome = result.error
        ? "执行失败"
        : result.refusalMatched === false || result.sourceHit === false
          ? "需检查"
          : "匹配";
      title.textContent = `#${result.index} ${result.testCase.question} · ${outcome} · ${result.durationMs} ms`;
      const body = el("div", undefined, "chunk-content");
      body.append(
        el(
          "p",
          `期望无证据拒答：${result.testCase.shouldRefuse ? "是" : "否"}；拒答匹配：${result.refusalMatched == null ? "未计分" : result.refusalMatched ? "是" : "否"}；来源命中：${result.sourceHit == null ? "未配置 / 未计分" : result.sourceHit ? "是" : "否"}`,
          "muted",
        ),
        el(
          "p",
          "期望来源：" +
            (result.testCase.expectedSources.join(", ") || "未配置"),
          "muted",
        ),
      );
      if (result.error) body.append(el("p", result.error, "error"));
      if (result.response)
        renderTurn(body, { role: "assistant", response: result.response });
      d.append(title, body);
      list.append(d);
    }
  }
  check.onchange = show;
  show();
  if (["QUEUED", "RUNNING"].includes(r.status))
    setTimeout(() => {
      if (token === routeVersion)
        runPage(id, token).catch((e) => {
          if (token === routeVersion) errorPanel(e);
        });
    }, 2000);
}
window.addEventListener("hashchange", route);
route();
