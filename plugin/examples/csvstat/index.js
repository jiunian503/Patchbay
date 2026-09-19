"use strict";

/**
 * 脚本插件入口。
 *
 * 宿主约定：
 *   - 必须导出 async run(input, host)，返回值需可 JSON 序列化。
 *   - input 是模型按 parameters 填好的参数对象。
 *   - host 是受权限约束的宿主能力集合，插件拿不到清单里没声明的东西：
 *       host.http(request)      受 permissions.network 白名单约束
 *       host.fs.readText(p)     受 permissions.filesystem 约束，且只能读插件工作区
 *       host.settings           用户在安装时填的配置
 *       host.log(msg)           写入插件日志，便于排查
 *
 * 铁律：本文件必须是纯 JS。
 * Android 上运行时下载的 .so 无法 dlopen（targetSdk≥29 被 SELinux 拦），
 * 所以任何带原生扩展的 npm 包（node-pty、sharp、better-sqlite3…）都用不了。
 */

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;

  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        field += c;
      }
    } else if (c === '"') {
      quoted = true;
    } else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else if (c !== "\r") {
      field += c;
    }
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

const round = (x) => Math.round(x * 1000) / 1000;

function toNumber(v) {
  const n = Number(String(v).replace(/[,\s¥$%]/g, ""));
  return Number.isFinite(n) ? n : null;
}

function stats(values) {
  const s = values.slice().sort((a, b) => a - b);
  const n = s.length;
  const sum = s.reduce((a, b) => a + b, 0);
  const q = (p) => {
    const pos = (n - 1) * p;
    const lo = Math.floor(pos);
    const hi = Math.ceil(pos);
    return lo === hi ? s[lo] : s[lo] + (s[hi] - s[lo]) * (pos - lo);
  };
  return {
    count: n,
    sum: round(sum),
    mean: round(sum / n),
    min: s[0],
    p25: round(q(0.25)),
    median: round(q(0.5)),
    p75: round(q(0.75)),
    max: s[n - 1],
  };
}

module.exports = {
  async run(input, host) {
    let text = input.csv;
    if (!text && input.path) {
      text = await host.fs.readText(input.path);
    }
    if (!text) {
      throw new Error("必须提供 csv 文本或 path 之一");
    }

    const rows = parseCsv(text).filter(
      (r) => r.length > 1 || (r[0] ?? "").trim() !== ""
    );
    if (rows.length < 2) {
      throw new Error("CSV 至少需要表头行和一行数据");
    }

    const header = rows[0].map((h) => h.trim());
    const body = rows.slice(1);

    const result = {
      rowCount: body.length,
      columnCount: header.length,
      columns: [],
    };

    header.forEach((name, idx) => {
      const raw = body.map((r) => (r[idx] ?? "").trim());
      const nums = raw.map(toNumber).filter((v) => v !== null);
      const col = { name, nonEmpty: raw.filter((v) => v !== "").length };

      // 数值占比超过八成才当数值列处理。
      // 否则订单号、日期、电话这类全数字的文本列会被算出一堆无意义的均值。
      if (nums.length > 0 && nums.length >= raw.length * 0.8) {
        Object.assign(col, { type: "number" }, stats(nums));
      } else {
        const freq = new Map();
        for (const v of raw) {
          freq.set(v, (freq.get(v) ?? 0) + 1);
        }
        col.type = "text";
        col.distinct = freq.size;
        col.top = [...freq.entries()]
          .sort((a, b) => b[1] - a[1])
          .slice(0, 5)
          .map(([value, count]) => ({ value, count }));
      }

      result.columns.push(col);
    });

    host.log(`csv_stats: ${result.rowCount} 行 × ${result.columnCount} 列`);
    return result;
  },
};
