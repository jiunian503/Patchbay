"use strict";

/**
 * 验证二（结论版）：尾部剥离方案。
 *
 * 实测发现：marked 对未闭合围栏的处理天然正确（零抖动），
 * 表格只有一次性小重排；唯一持续抖动源是未完成的行内语法——
 * 尤其 [文本](url 会被 autolink，URL 每增长一字符就重建一次 <a>。
 *
 * 方案：把"最后一个未闭合语法点"到末尾的那一段剥出来，以纯文本渲染。
 * 闭合后它自然回到正常渲染路径，只产生一次可接受的重排。
 */
const { marked } = require("marked");
const render = (md) => marked.parse(md, { async: false });
const escapeHtml = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** 是否处于未闭合的代码围栏内。围栏内交给渲染器，它处理得对。 */
function insideFence(text) {
  let open = false;
  let ch = null;
  let len = 0;
  for (const line of text.split("\n")) {
    const m = /^\s{0,3}(`{3,}|~{3,})/.exec(line);
    if (!m) continue;
    const c = m[1][0];
    const l = m[1].length;
    if (!open) {
      open = true;
      ch = c;
      len = l;
    } else if (c === ch && l >= len) {
      open = false;
      ch = null;
    }
  }
  return open;
}

/** 找出最后一个"未闭合语法点"的位置，没有则返回 -1。 */
function unstableStart(text) {
  const hits = [];

  // [文本](url —— 有 ]( 但没有配对的 )
  const linkOpen = text.lastIndexOf("](");
  if (linkOpen !== -1 && text.indexOf(")", linkOpen) === -1) {
    const bracket = text.lastIndexOf("[", linkOpen);
    if (bracket !== -1) hits.push(bracket);
  }

  // $$ 数学块
  const dollars = text.split("$$").length - 1;
  if (dollars % 2 === 1) hits.push(text.lastIndexOf("$$"));

  // 单反引号行内代码（排除围栏）
  const backticks = text.split("`").length - 1;
  if (backticks % 2 === 1) hits.push(text.lastIndexOf("`"));

  // ** 粗体
  const bolds = text.split("**").length - 1;
  if (bolds % 2 === 1) hits.push(text.lastIndexOf("**"));

  // 表格头行：仅当表格尚未开始（前一行也不是分隔行）时才剥离。
  // 注意表格正文行同样含 |，若不排除会把整个表格期间每行都误剥成纯文本。
  const lines = text.split("\n");
  const last = lines[lines.length - 1];
  const prev = lines.length >= 2 ? lines[lines.length - 2] : "";
  const isDelim = (s) => s.includes("-") && /^\s*\|?[\s:|-]+\|?\s*$/.test(s);
  if (last.includes("|") && !isDelim(last) && !isDelim(prev)) {
    hits.push(text.length - last.length);
  }

  return hits.length ? Math.max(...hits) : -1;
}

function renderStreaming(text) {
  if (insideFence(text)) return render(text);
  const s = unstableStart(text);
  if (s === -1) return render(text);
  return (
    render(text.slice(0, s)) +
    '<span class="streaming">' +
    escapeHtml(text.slice(s)) +
    "</span>"
  );
}

const DOC = [
  "好的，我先看一下这段代码。",
  "",
  "核心逻辑是这样的：",
  "",
  "```kotlin",
  "fun stream(prompt: String) {",
  "    val call = provider.chat(prompt)",
  "    call.collect { emit(it) }",
  "}",
  "```",
  "",
  "参数对照如下：",
  "",
  "| 参数 | 默认值 | 说明 |",
  "| --- | --- | --- |",
  "| temperature | 0.7 | 随机性 |",
  "| top_p | 1.0 | 核采样 |",
  "",
  "参考 [官方文档](https://example.com/docs) 了解更多。",
].join("\n");

function stripTrailingCloseTags(h) {
  return h.replace(/(<\/[a-z0-9]+>\s*)+$/i, "");
}

function measure(transform, label) {
  const frames = [];
  for (let n = 0; n <= DOC.length; n++) frames.push(transform(DOC.slice(0, n)));

  let total = 0;
  const spikes = [];
  for (let n = 1; n <= DOC.length; n++) {
    const a = stripTrailingCloseTags(frames[n - 1]);
    const b = stripTrailingCloseTags(frames[n]);
    const m = Math.min(a.length, b.length);
    let i = 0;
    while (i < m && a[i] === b[i]) i++;
    const bad = a.length - i;
    total += bad;
    if (bad > 20) spikes.push({ n, bad, char: DOC[n - 1], tail: a.slice(-95) });
  }
  spikes.sort((x, y) => y.bad - x.bad);

  console.log(`\n${label}`);
  console.log(`  累计作废字符 ${total}`);
  console.log(`  单帧作废超过 20 字符的帧数 ${spikes.length} / ${DOC.length}`);
  for (const s of spikes) {
    console.log(
      `    n=${String(s.n).padStart(3)} 作废 ${String(s.bad).padStart(3)}` +
        `  触发 ${JSON.stringify(s.char)}`
    );
    console.log(`         前一帧尾部: ...${s.tail.replace(/\n/g, "\\n")}`);
  }
  return total;
}

console.log("=".repeat(78));
console.log("验证二 结论：尾部剥离方案实测");
console.log("=".repeat(78));

const naive = measure((s) => render(s), "【朴素】每个增量直接整段重渲染");
const fixed = measure(renderStreaming, "【尾部剥离】未闭合语法以纯文本渲染");

console.log("\n" + "-".repeat(78));
console.log(`累计作废字符 ${naive} → ${fixed}`);
console.log(`降幅 ${(100 * (1 - fixed / naive)).toFixed(1)}%`);

console.log("\n尾部剥离行为抽样：");
for (const n of [70, 130, 182, 268, 275, 278]) {
  const t = DOC.slice(0, n);
  const s = unstableStart(t);
  const fence = insideFence(t);
  console.log(
    `  n=${String(n).padStart(3)}  ` +
      (fence
        ? "在围栏内 → 交给渲染器"
        : s === -1
        ? "无未闭合语法 → 正常渲染"
        : `剥离末尾 ${t.length - s} 字符: ${JSON.stringify(t.slice(s))}`)
  );
}
