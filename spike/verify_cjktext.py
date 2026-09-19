# -*- coding: utf-8 -*-
"""把 CjkText.kt 的逻辑逐行移植到 Python，端到端验证查询构造是否正确。

目的：在用户编译 Kotlin 之前，先证明 forIndex / forQuery 的组合能产生
与 LIKE 基线完全一致的召回结果。逻辑等价性靠人工逐行对照保证。
"""
import re
import sqlite3

WS = re.compile(r"\s+")


def is_cjk(ch):
    c = ord(ch)
    return (
        0x4E00 <= c <= 0x9FFF
        or 0x3400 <= c <= 0x4DBF
        or 0xF900 <= c <= 0xFAFF
        or 0x3040 <= c <= 0x30FF
    )


def for_index(text):
    out = []
    for ch in text:
        if is_cjk(ch):
            out.append(" " + ch + " ")
        else:
            out.append(ch)
    return WS.sub(" ", "".join(out)).strip()


def for_query(raw, prefix_last=False):
    segs = [s for s in WS.split(raw.strip()) if s]
    if not segs:
        return ""
    return " AND ".join(
        render_segment(s, prefix_last and i == len(segs) - 1)
        for i, s in enumerate(segs)
    )


def render_segment(seg, prefix):
    if all(is_cjk(c) for c in seg):
        toks = list(seg)
        return toks[0] if len(toks) == 1 else '"' + " ".join(toks) + '"'
    if not any(is_cjk(c) for c in seg):
        return escape_latin(seg) + ("*" if prefix else "")
    parts = []
    buf = ""
    buf_cjk = is_cjk(seg[0])
    for ch in seg:
        c = is_cjk(ch)
        if c != buf_cjk:
            parts.append(render_run(buf, buf_cjk))
            buf = ""
            buf_cjk = c
        buf += ch
    if buf:
        parts.append(render_run(buf, buf_cjk))
    return " ".join(parts)


def render_run(s, cjk):
    return '"' + " ".join(s) + '"' if cjk else escape_latin(s)


def escape_latin(s):
    if all(c.isalnum() or c == "_" for c in s):
        return s.lower()
    return '"' + s.replace('"', '""') + '"'


CORPUS = [
    (1, "帮我把会议纪要整理成表格"),
    (2, "会议纪要的模板放在哪里"),
    (3, "今天天气不错，出去走走"),
    (4, "我想做一个AI对话软件，类似RikkaHub"),
    (5, "把上周的会议录音转成文字"),
]

QUERIES = ["会议", "纪要", "会议纪要", "录音", "天气", "对话", "AI",
           "RikkaHub", "rikkahub", "对话软件", "对话AI", "不存在的词"]

# 期望值由 LIKE 基线独立计算，不手写
conn = sqlite3.connect(":memory:")
conn.execute("create table raw(id integer primary key, body text)")
for i, t in CORPUS:
    conn.execute("insert into raw values (?,?)", (i, t))
expect = {
    q: sorted(r[0] for r in conn.execute(
        "select id from raw where body like ? ", (f"%{q}%",)))
    for q in QUERIES
}

fts = sqlite3.connect(":memory:")
fts.execute("create virtual table t using fts4(body)")
for i, t in CORPUS:
    fts.execute("insert into t(docid, body) values (?,?)", (i, for_index(t)))

print("=" * 74)
print("CjkText 逻辑端到端验证（FTS4，语料与真机测试一致）")
print("=" * 74)
print(f"{'查询':<12}{'构造的表达式':<26}{'实际':<14}{'期望':<14}{'结果'}")
print("-" * 74)

fails = 0
for q in QUERIES:
    expr = for_query(q)
    if not expr:
        got = []
    else:
        got = sorted(r[0] for r in fts.execute(
            "select rowid from t where t match ?", (expr,)).fetchall())
    ok = got == expect[q]
    fails += 0 if ok else 1
    print(f"{q:<12}{expr:<26}{str(got):<14}{str(expect[q]):<14}{'OK' if ok else 'FAIL'}")

print("-" * 74)
print(f"用例 {len(QUERIES)} 个，失败 {fails} 个")

print("\n前缀查询（边输边搜）：")
for q in ["Rikk", "Rikka", "会"]:
    expr = for_query(q, prefix_last=True)
    got = sorted(r[0] for r in fts.execute(
        "select rowid from t where t match ?", (expr,)).fetchall())
    print(f"  {q:<8} {expr:<14} -> {got}")

print("\n索引文本抽样：")
for i, t in CORPUS[:3] + CORPUS[3:]:
    print(f"  {i}: {for_index(t)}")
