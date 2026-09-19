"use strict";

/**
 * 验证二（修正版）：先确认渲染器对"半截语法"的真实行为，再谈抖动。
 * 上一版我假设"未闭合围栏会导致整块重排"，实测数据不支持这个假设。
 */
const { marked } = require("marked");
const render = (md) => marked.parse(md, { async: false });

const show = (label, md) => {
  console.log(`\n── ${label}`);
  console.log(`   输入: ${JSON.stringify(md)}`);
  console.log(`   输出: ${render(md).replace(/\n/g, "")}`);
};

console.log("=".repeat(78));
console.log("第一问：渲染器对半截块级语法的真实行为");
console.log("=".repeat(78));

show("未闭合的代码围栏", "前文\n\n```kotlin\nval a = 1");
show("同一段，补上闭合围栏", "前文\n\n```kotlin\nval a = 1\n```");
show("未闭合围栏后面还有正文", "```kotlin\nval a = 1\n\n## 这是标题吗");
show("只有表头行的表格", "| 参数 | 默认值 |");
show("补上分隔行之后", "| 参数 | 默认值 |\n| --- | --- |");
show("未闭合的行内代码", "这是 `一段代码");
show("补上反引号", "这是 `一段代码`");
show("未闭合的粗体", "这是 **重点");
show("补上双星号", "这是 **重点**");
show("未闭合的链接", "见 [官方文档](https://exa");
show("补上括号", "见 [官方文档](https://example.com)");

console.log("\n" + "=".repeat(78));
console.log("第二问：抖动到底出在哪几帧");
console.log("=".repeat(78));

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

function stripTrailingCloseTags(html) {
  return html.replace(/(<\/[a-z0-9]+>\s*)+$/i, "");
}

function diff(a, b) {
  const x = stripTrailingCloseTags(a);
  const y = stripTrailingCloseTags(b);
  const n = Math.min(x.length, y.length);
  let i = 0;
  while (i < n && x[i] === y[i]) i++;
  return { invalidated: x.length - i, kept: i, prev: x };
}

const frames = [];
for (let n = 0; n <= DOC.length; n++) frames.push(render(DOC.slice(0, n)));

const spikes = [];
for (let n = 1; n <= DOC.length; n++) {
  const d = diff(frames[n - 1], frames[n]);
  if (d.invalidated > 20) {
    spikes.push({ n, ...d, char: DOC[n - 1] });
  }
}

spikes.sort((a, b) => b.invalidated - a.invalidated);
console.log(`\n抖动超过 20 字符的帧：${spikes.length} / ${DOC.length}`);
console.log("\n最严重的 8 处：");
for (const s of spikes.slice(0, 8)) {
  console.log(
    `  n=${String(s.n).padStart(3)}  作废 ${String(s.invalidated).padStart(4)} 字符` +
      `  触发字符 ${JSON.stringify(s.char)}`
  );
  console.log(`       作废前的尾部: ...${s.prev.slice(-70)}`);
}

const total = spikes.reduce((a, s) => a + s.invalidated, 0);
console.log(`\n抖动合计 ${total} 字符（文档 ${DOC.length} 字符，共 ${DOC.length} 帧）`);
