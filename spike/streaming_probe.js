"use strict";

/**
 * 验证二：流式 Markdown 渲染的抖动实测。
 *
 * 场景：模型逐字吐出一段 Markdown，UI 每收到一个增量就重新渲染整条消息。
 * 问题：某些块级结构在"未闭合"和"已闭合"两种状态下，渲染结果完全不同，
 *       于是闭合的那一瞬间整块内容重排——用户看到屏幕猛地跳一下。
 *
 * 度量：相邻两帧（prefix n 与 prefix n+1）之间，上一帧有多少字符被作废。
 *       这个数字就是"抖动幅度"，单位是 HTML 字符数。
 *
 * 运行：NODE_PATH=<workspace>/node_modules node streaming_probe.js
 */
const { marked } = require("marked");

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
  "| max_tokens | 4096 | 上限 |",
  "",
  "最后有两点要注意：",
  "",
  "- 第一点是超时处理",
  "- 第二点是重试策略",
  "",
  "参考 [官方文档](https://example.com/docs) 了解更多。",
].join("\n");

const render = (md) => marked.parse(md, { async: false });

function stripTrailingCloseTags(html) {
  return html.replace(/(<\/[a-z0-9]+>\s*)+$/i, "");
}

function invalidatedChars(a, b) {
  const x = stripTrailingCloseTags(a);
  const y = stripTrailingCloseTags(b);
  const n = Math.min(x.length, y.length);
  let i = 0;
  while (i < n && x[i] === y[i]) i++;
  return x.length - i;
}

/**
 * 补全器：把"流式中的半截文本"补成渲染结果与闭合后一致的形式。
 * 只处理块级分隔结构，因为只有它们会造成整块重排。
 */
function completeForStreaming(text) {
  const lines = text.split("\n");

  let fenceChar = null;
  let fenceLen = 0;
  let inFence = false;
  for (const line of lines) {
    const m = /^\s{0,3}(`{3,}|~{3,})/.exec(line);
    if (!m) continue;
    const ch = m[1][0];
    const len = m[1].length;
    if (!inFence) {
      inFence = true;
      fenceChar = ch;
      fenceLen = len;
    } else if (ch === fenceChar && len >= fenceLen) {
      inFence = false;
      fenceChar = null;
    }
  }

  if (inFence) {
    return { text: text + "\n" + fenceChar.repeat(fenceLen), streaming: "code" };
  }

  const dollars = (text.match(/\$\$/g) || []).length;
  if (dollars % 2 === 1) {
    return { text: text + "$$", streaming: "math" };
  }

  const lastIdx = lines.length - 1;
  const last = lines[lastIdx];
  const isDelimiterRow = /^\s*\|?[\s:|-]+\|?\s*$/.test(last);
  if (last.includes("|") && !isDelimiterRow) {
    const cells = last.split("|").map((s) => s.trim());
    while (cells.length && cells[0] === "") cells.shift();
    while (cells.length && cells[cells.length - 1] === "") cells.pop();
    if (cells.length >= 2) {
      const synth = "| " + cells.map(() => "---").join(" | ") + " |";
      return { text: text + "\n" + synth, streaming: "table" };
    }
  }

  return { text, streaming: null };
}

function analyze(transform, label) {
  const frames = [];
  for (let n = 1; n <= DOC.length; n++) {
    frames.push(render(transform(DOC.slice(0, n)).text));
  }

  const spikes = [];
  let total = 0;
  for (let n = 1; n < DOC.length; n++) {
    const bad = invalidatedChars(frames[n - 1], frames[n]);
    total += bad;
    if (bad > 200) {
      spikes.push({ n, bad, added: DOC[n - 1], frame: frames[n - 1] });
    }
  }

  const worst = [...spikes].sort((a, b) => b.bad - a.bad).slice(0, 6);
  console.log(`\n${label}`);
  console.log(`  累计作废字符 ${total}`);
  console.log(`  抖动超过 200 字符的帧数 ${spikes.length} / ${DOC.length - 1}`);
  if (worst.length) {
    console.log("  最严重的几处：");
    for (const s of worst) {
      const at = JSON.stringify(DOC.slice(Math.max(0, s.n - 14), s.n));
      console.log(
        `    n=${String(s.n).padStart(3)} 作废 ${String(s.bad).padStart(5)} 字符` +
          `  刚输入 ${JSON.stringify(s.added)}  尾部: ...${at.slice(-40)}`
      );
    }
  }
  return total;
}

console.log("=".repeat(78));
console.log("验证二：流式 Markdown 渲染抖动实测");
console.log(`文档 ${DOC.length} 字符 / marked 渲染`);
console.log("=".repeat(78));

const naive = analyze((s) => ({ text: s }), "【朴素方案】每个增量直接重新渲染");
const fixed = analyze(completeForStreaming, "【补全器方案】先补全块级分隔结构再渲染");

console.log("\n" + "-".repeat(78));
console.log(`累计作废字符：朴素 ${naive} → 补全器 ${fixed}`);
if (fixed > 0) {
  console.log(`降幅 ${(100 * (1 - fixed / naive)).toFixed(1)}%`);
}

console.log("\n补全器行为抽样：");
for (const n of [55, 60, 95, 100, 105]) {
  const c = completeForStreaming(DOC.slice(0, n));
  const tail = c.text.slice(n).replace(/\n/g, "\\n");
  console.log(
    `  n=${String(n).padStart(3)}  ${c.streaming ? "补全 " + c.streaming : "无需补全"}` +
      (tail ? `  → 追加 "${tail}"` : "")
  );
}
