# -*- coding: utf-8 -*-
"""中文全文检索方案实测。

目的：验证 SQLite FTS 对中文的召回行为，为 Android 端选型提供依据。
运行：python fts_probe.py
"""
import sqlite3

DOCS = [
    (1, "帮我把会议纪要整理成表格"),
    (2, "会议纪要的模板放在哪里"),
    (3, "今天天气不错，出去走走"),
    (4, "我想做一个AI对话软件，类似RikkaHub"),
    (5, "把上周的会议录音转成文字"),
]

QUERIES = ["会议", "纪要", "会议纪要", "对话", "录音", "RikkaHub", "天气"]


def is_cjk(ch):
    o = ord(ch)
    return (
        0x4E00 <= o <= 0x9FFF
        or 0x3400 <= o <= 0x4DBF
        or 0xF900 <= o <= 0xFAFF
        or 0x3040 <= o <= 0x30FF
    )


def cjk_split(s):
    """在每个 CJK 字符两侧补空格，使 unicode61 把它切成独立 token。"""
    out = []
    for ch in s:
        if is_cjk(ch):
            out.append(" ")
            out.append(ch)
            out.append(" ")
        else:
            out.append(ch)
    return " ".join("".join(out).split())


def cjk_phrase(q):
    """查询侧同样切分，并用双引号组成短语，保证字序相邻。"""
    parts = cjk_split(q).split()
    if len(parts) <= 1:
        return parts[0] if parts else '""'
    return '"' + " ".join(parts) + '"'


def run(name, ddl, idcol, insert_docs, query_fn):
    conn = sqlite3.connect(":memory:")
    try:
        conn.execute(ddl)
    except sqlite3.OperationalError as e:
        print(f"\n{name}\n  [建表失败] {e}")
        return
    for i, t in DOCS:
        conn.execute(
            f"insert into t({idcol}, body) values (?, ?)", (i, insert_docs(t))
        )
    print(f"\n{name}")
    for q in QUERIES:
        expr = query_fn(q)
        try:
            rows = conn.execute(
                "select rowid from t where t match ? order by rowid", (expr,)
            ).fetchall()
            hit = [r[0] for r in rows]
            print(f"  {q:<10} match {expr:<22} -> {hit if hit else '【无结果】'}")
        except sqlite3.OperationalError as e:
            print(f"  {q:<10} match {expr:<22} -> [报错] {e}")
    conn.close()


def like_baseline():
    conn = sqlite3.connect(":memory:")
    conn.execute("create table t(id integer primary key, body text)")
    for i, t in DOCS:
        conn.execute("insert into t values (?, ?)", (i, t))
    print("\n【基线】LIKE '%q%'（无索引，全表扫描）")
    for q in QUERIES:
        rows = conn.execute(
            "select id from t where body like ? order by id", (f"%{q}%",)
        ).fetchall()
        hit = [r[0] for r in rows]
        print(f"  {q:<10} like %{q}%{'':<12} -> {hit if hit else '【无结果】'}")
    conn.close()


def main():
    print("=" * 68)
    print(f"SQLite {sqlite3.sqlite_version}")
    conn = sqlite3.connect(":memory:")
    opts = {r[0] for r in conn.execute("pragma compile_options")}
    conn.close()
    for k in ("ENABLE_FTS3", "ENABLE_FTS4", "ENABLE_FTS5"):
        print(f"  {k}: {k in opts}")
    print("=" * 68)

    like_baseline()

    run(
        "方案 A｜FTS5 默认分词器（unicode61），原文入库",
        "create virtual table t using fts5(body)",
        "rowid",
        lambda t: t,
        lambda q: q,
    )

    run(
        "方案 B｜FTS5 默认分词器 + 汉字间插空格，查询侧组短语",
        "create virtual table t using fts5(body)",
        "rowid",
        cjk_split,
        cjk_phrase,
    )

    run(
        "方案 C｜FTS5 trigram 分词器，原文入库",
        "create virtual table t using fts5(body, tokenize='trigram')",
        "rowid",
        lambda t: t,
        lambda q: q,
    )

    run(
        "方案 D｜FTS4 默认分词器，原文入库",
        "create virtual table t using fts4(body)",
        "docid",
        lambda t: t,
        lambda q: q,
    )


if __name__ == "__main__":
    main()
