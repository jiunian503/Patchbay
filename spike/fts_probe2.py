# -*- coding: utf-8 -*-
"""补充验证：FTS4 是否同样可用（Room 只原生支持 @Fts4）、前缀查询、排序、索引开销。"""
import sqlite3
from fts_probe import DOCS, cjk_split, cjk_phrase, QUERIES


def build(ddl, idcol):
    conn = sqlite3.connect(":memory:")
    conn.execute(ddl)
    for i, t in DOCS:
        conn.execute(f"insert into t({idcol}, body) values (?, ?)", (i, cjk_split(t)))
    return conn


def probe(name, conn, queries):
    print(f"\n{name}")
    for q in queries:
        expr = cjk_phrase(q)
        try:
            rows = conn.execute(
                "select rowid from t where t match ? order by rowid", (expr,)
            ).fetchall()
            hit = [r[0] for r in rows]
            print(f"  {q:<10} {expr:<22} -> {hit if hit else '【无结果】'}")
        except sqlite3.OperationalError as e:
            print(f"  {q:<10} {expr:<22} -> [报错] {e}")


print("=" * 68)
print("补充验证")
print("=" * 68)

probe(
    "方案 E｜FTS4 + 汉字间插空格 + 短语查询（Room 可直接用的路线）",
    build("create virtual table t using fts4(body)", "docid"),
    QUERIES,
)

conn5 = build("create virtual table t using fts5(body)", "rowid")
print("\n方案 F｜FTS5 + 插空格，前缀查询与前缀匹配")
for expr, label in [
    ('rikka*', '拉丁词前缀 rikka*'),
    ('"会 议"', '中文短语'),
    ('"会 议" OR "录 音"', '多词 OR'),
    ('"会 议" AND "纪 要"', '多词 AND'),
    ('NEAR("会 议" "纪 要", 4)', 'NEAR 邻近'),
]:
    try:
        rows = conn5.execute(
            "select rowid from t where t match ? order by rowid", (expr,)
        ).fetchall()
        print(f"  {label:<20} {expr:<26} -> {[r[0] for r in rows] or '【无结果】'}")
    except sqlite3.OperationalError as e:
        print(f"  {label:<20} {expr:<26} -> [报错] {e}")

print("\n方案 G｜bm25 相关性排序（查「会议」）")
rows = conn5.execute(
    "select rowid, bm25(t) as score from t where t match ? order by score",
    (cjk_phrase("会议"),),
).fetchall()
for r in rows:
    print(f"  doc {r[0]}  bm25={r[1]:.4f}")

print("\n方案 H｜索引开销对比")
raw = sum(len(t) for _, t in DOCS)
split = sum(len(cjk_split(t)) for _, t in DOCS)
print(f"  原文合计字符数      : {raw}")
print(f"  插空格后字符数      : {split}  ({split / raw:.2f}x)")
print(f"  FTS5 索引体积(字节) : ", end="")
page = conn5.execute("pragma page_count").fetchone()[0]
psize = conn5.execute("pragma page_size").fetchone()[0]
print(f"{page * psize}（含表与索引，{len(DOCS)} 条短文档）")

conn4 = build("create virtual table t using fts4(body)", "docid")
p4 = conn4.execute("pragma page_count").fetchone()[0]
s4 = conn4.execute("pragma page_size").fetchone()[0]
print(f"  FTS4 索引体积(字节) : {p4 * s4}")
