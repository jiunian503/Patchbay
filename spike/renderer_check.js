"use strict";

/**
 * 验证 StreamingMarkdownRenderer 的块缓存是否安全。
 *
 * 核心问题：把文档按"稳定块边界"切开、逐块解析后拼接，
 * 结果是否等于整篇一次性解析？CommonMark 里有些块级结构会跨越空行
 * （列表项之间、引用块、setext 标题、HTML 块），切块可能改变语义。
 *
 * 运行：NODE_PATH=<workspace>/node_modules node renderer_check.js
 */
const { marked } = require("marked");
const render = (md) => marked.parse(md, { async: false });

function matchFence(line) {
  let k = 0;
  while (k < line.length && k < 3 && line[k] === " ") k++;
  if (k >= line.length) return null;
  const ch = line[k];
  if (ch !== "`" && ch !== "~") return null;
  let n = 0;
  while (k + n < line.length && line[k + n] === ch) n++;
  return n >= 3 ? [ch, n] : null;
}

/**
 * 与 Kotlin 版 stableBoundary 逐行等价。
 *
 * 列表上下文追踪：CommonMark 里列表项之间可以有空行（松散列表），
 * 空行并不结束列表。若在列表内部封块，`- 甲\n\n- 乙` 会被解析成两个
 * 独立的 <ul>，第二个还会丢掉 <p> 包裹 —— 与整篇解析结果不一致。
 * 所以列表内一律不封块。
 */
function stableBoundary(text) {
  let boundary = 0;
  let inFence = false;
  let fenceChar = "\u0000";
  let fenceLen = 0;
  let listIndent = -1;
  let blankPending = false;
  let i = 0;

  while (i < text.length) {
    const nl = text.indexOf("\n", i);
    if (nl === -1) break;
    const line = text.slice(i, nl);
    const after = nl + 1;

    const fence = matchFence(line);
    if (fence) {
      const [ch, len] = fence;
      if (!inFence) {
        inFence = true;
        fenceChar = ch;
        fenceLen = len;
      } else if (ch === fenceChar && len >= fenceLen) {
        inFence = false;
        boundary = after;
      }
      blankPending = false;
      i = after;
      continue;
    }

    if (inFence) {
      i = after;
      continue;
    }

    const trimmed = line.trimStart();
    const indent = line.length - trimmed.length;
    const isBlank = trimmed === "";
    const isListItem = /^([-*+]|\d+[.)])\s+/.test(trimmed);

    if (isBlank) {
      blankPending = true;
      // 只有不在列表里，空行才是安全的块边界
      if (listIndent === -1) boundary = after;
    } else {
      if (isListItem) {
        if (listIndent === -1 || indent <= listIndent) listIndent = indent;
      } else if (listIndent !== -1 && blankPending && indent <= listIndent) {
        // 空行之后的非列表行，且缩进不深于列表 —— 列表结束
        listIndent = -1;
      }
      blankPending = false;
    }

    i = after;
  }
  return Math.max(0, Math.min(boundary, text.length));
}

function renderStreaming(full) {
  let sealed = "";
  let upTo = 0;
  let parserCalls = 0;
  let parserChars = 0;

  const boundary = stableBoundary(full);
  if (boundary > upTo) {
    sealed += render(full.slice(upTo, boundary));
    parserCalls++;
    parserChars += boundary - upTo;
    upTo = boundary;
  }
  const tail = full.slice(upTo);
  if (tail) {
    sealed += render(tail);
    parserCalls++;
    parserChars += tail.length;
  }
  return { html: sealed, parserCalls, parserChars };
}

const CASES = {
  "A 纯段落": "第一段。\n\n第二段。\n\n第三段。\n",
  "B 列表项之间有空行": "- 第一项\n\n- 第二项\n\n- 第三项\n",
  "C 引用块跨空行": "> 第一行引用\n\n> 第二行引用\n",
  "D 段落 + setext 下划线": "这是一个标题\n\n---\n\n正文。\n",
  "E 有序列表 + 松散项": "1. 甲\n\n2. 乙\n\n3. 丙\n",
  "F 围栏内含空行": "前文\n\n```js\nconst a = 1;\n\nconst b = 2;\n```\n\n后文\n",
  "G 嵌套列表": "- 甲\n  - 甲一\n  - 甲二\n\n- 乙\n",
  "H 完整流式文档": [
    "好的，我先看一下这段代码。",
    "",
    "核心逻辑是这样的：",
    "",
    "```kotlin",
    "fun stream(prompt: String) {",
    "    val call = provider.chat(prompt)",
    "}",
    "```",
    "",
    "参数对照如下：",
    "",
    "| 参数 | 默认值 |",
    "| --- | --- |",
    "| temperature | 0.7 |",
    "",
    "参考 [官方文档](https://example.com/docs) 了解更多。",
  ].join("\n"),
  "I 列表后接段落（列表结束判定）": "- 甲\n- 乙\n\n这是一段普通正文。\n\n又一段。\n",
  "J HTML 块": "<div>\n\n内容\n\n</div>\n\n正文。\n",
  "K 引用块内用 > 续空行": "> 第一行\n>\n> 第二行\n\n正文。\n",
  "L 围栏后紧跟列表": "```js\nconst a = 1;\n```\n\n- 甲\n- 乙\n\n正文。\n",
  "M 有序列表嵌套 + 松散": "1. 甲\n\n   说明段\n\n2. 乙\n\n3. 丙\n",
};

console.log("=".repeat(78));
console.log("块缓存正确性检查：逐块解析拼接 vs 整篇解析");
console.log("=".repeat(78));

let fails = 0;
for (const [name, doc] of Object.entries(CASES)) {
  const whole = render(doc);
  const { html } = renderStreaming(doc);
  const ok = whole === html;
  if (!ok) fails++;
  console.log(`\n${ok ? "一致  " : "不一致"} ${name}`);
  if (!ok) {
    console.log(`   整篇: ${whole.replace(/\n/g, "")}`);
    console.log(`   分块: ${html.replace(/\n/g, "")}`);
    console.log(`   边界: ${stableBoundary(doc)}`);
  }
}
console.log(`\n${"-".repeat(78)}\n用例 ${Object.keys(CASES).length} 个，不一致 ${fails} 个`);

console.log("\n" + "=".repeat(78));
console.log("性能收益：分块缓存 vs 每帧全量重解析");
console.log("=".repeat(78));

const DOC = CASES["H 完整流式文档"];
let naiveChars = 0;
for (let n = 1; n <= DOC.length; n++) naiveChars += n;

let cachedChars = 0;
let cachedCalls = 0;
let sealedUpTo = 0;
for (let n = 1; n <= DOC.length; n++) {
  const b = stableBoundary(DOC.slice(0, n));
  if (b > sealedUpTo) {
    cachedChars += b - sealedUpTo;
    cachedCalls++;
    sealedUpTo = b;
  }
  cachedChars += n - sealedUpTo;
  cachedCalls++;
}

console.log(`  朴素（每帧解析全文）  : ${naiveChars} 字符 / ${DOC.length} 次解析`);
console.log(`  分块缓存             : ${cachedChars} 字符 / ${cachedCalls} 次解析`);
console.log(`  字符量降至 ${(100 * cachedChars / naiveChars).toFixed(1)}%`);
