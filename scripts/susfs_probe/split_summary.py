"""把 aggregate.py 的 summary.json 拆成 dev 上的 data/susfs_probe/<android>-<kernel>.json。

用法：python3 split_summary.py <summary.json> <输出目录> [--force]

默认拒绝含 error 条目的分支块（这些月份没有内核结论，应先重跑）；--force 则忽略 errors 照常写出。
写出的文件只保留网页需要的字段，errors 不写入。
"""

from __future__ import annotations

import json
import os
import sys

BLOCK_FIELDS = ("android_version", "kernel_version", "ksu_variant", "susfs_repo", "susfs_branch",
                "susfs_commit", "susfs_commit_date", "probed_at", "run_url", "partial", "results")


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    force = "--force" in sys.argv
    if len(args) != 2:
        print("用法: split_summary.py <summary.json> <输出目录> [--force]")
        return 2
    summary_path, out_dir = args
    with open(summary_path, "r", encoding="utf-8") as f:
        summary = json.load(f)
    os.makedirs(out_dir, exist_ok=True)

    written = 0
    for key, block in sorted(summary.items()):
        errors = block.get("errors") or {}
        if errors and not force:
            print(f"跳过 {key}：{len(errors)} 个月份为 error（{', '.join(errors)}），先重跑或加 --force")
            continue
        out = {k: block[k] for k in BLOCK_FIELDS if k in block}
        path = os.path.join(out_dir, f"{key}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(out, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(f"写出 {path}（{len(out.get('results', {}))} 条）")
        written += 1
    print(f"共写出 {written} 个文件")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
