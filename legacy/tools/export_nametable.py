#!/usr/bin/env python3
"""从 probe/matrix 报告导出「版本 → 解析出的真实名字」映射表（X 3.0 风格适配数据生成器）

把结构 matcher 的解析结果固化成按版本的名字表——即 X 3.0 native 库里的那种适配数据的
自动化生成路径。用法:

  python3 tools/export_nametable.py "results/run-xxx/wechat-*.json" [--out nametable.json]
"""
import argparse
import glob
import json


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reports", nargs="+", help="报告文件 glob（可多个）")
    ap.add_argument("--out")
    a = ap.parse_args()

    table = {}
    for pat in a.reports:
        for path in sorted(glob.glob(pat)):
            with open(path) as f:
                r = json.load(f)
            entries = {}
            for t in r["targets"]:
                if t["status"] == "SUCCESS":
                    entries[t["key"]] = t.get("resolved", [])
            table[r["label"]] = {
                "apkSha256": r["apk"]["sha256"][:16],
                "targets": entries,
            }

    out = json.dumps(table, ensure_ascii=False, indent=2)
    if a.out:
        with open(a.out, "w") as f:
            f.write(out + "\n")
        print(f"[nametable] {len(table)} versions -> {a.out}")
    else:
        print(out)


if __name__ == "__main__":
    main()
