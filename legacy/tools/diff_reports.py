#!/usr/bin/env python3
"""对比两份 probe 报告：状态变化 + 解析结果漂移（版本适配的核心视图）

用法:
  python3 tools/diff_reports.py old.json new.json [--out diff.txt]
"""
import argparse
import json


def load(p):
    with open(p) as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("--out")
    a = ap.parse_args()
    old, new = load(a.old), load(a.new)
    o = {t["key"]: t for t in old["targets"]}
    n = {t["key"]: t for t in new["targets"]}

    lines = [f"# {old['label']} → {new['label']}",
             f"  (sha256 {old['apk']['sha256'][:12]} → {new['apk']['sha256'][:12]})"]
    changed = False
    for key in sorted(set(o) | set(n)):
        t1, t2 = o.get(key), n.get(key)
        if t1 is None:
            lines.append(f"+ [新增目标] {key}")
            changed = True
            continue
        if t2 is None:
            lines.append(f"- [移除目标] {key}")
            changed = True
            continue
        if t1["status"] != t2["status"]:
            lines.append(f"! [{key}] 状态: {t1['status']} → {t2['status']}")
            changed = True
        r1, r2 = t1.get("resolved", []), t2.get("resolved", [])
        if r1 != r2:
            lines.append(f"~ [{key}] 解析漂移 ({t1['hits']}→{t2['hits']} hits):")
            for r in r1[:5]:
                lines.append(f"    - {r}")
            for r in r2[:5]:
                lines.append(f"    + {r}")
            changed = True
    if not changed:
        lines.append("(无差异)")

    text = "\n".join(lines)
    print(text)
    if a.out:
        with open(a.out, "w") as f:
            f.write(text + "\n")
        print(f"\n[diff] -> {a.out}")


if __name__ == "__main__":
    main()
