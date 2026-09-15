"""汇总各构建任务的 SUSFS 原始补丁探测结论。

用法：python3 aggregate.py <download-artifact 目录> <输出目录>

输入目录下每个 SUSFS-Probe-* 子目录里有一个 result.json（由 write_result.py 写出）。
输出：
  summary.json  按 <android>-<kernel> 分块，每块的结构就是 dev 上
                data/susfs_probe/<android>-<kernel>.json 的内容，可直接拆分落盘
  summary.md    人类可读的表格（同时追加到 GITHUB_STEP_SUMMARY）
"""

import datetime
import json
import os
import sys

VERDICT_LABEL = {
    "clean": "clean（0 rej，编译成功）",
    "built_with_rej": "built_with_rej（有 rej，编译成功）",
    "failed": "failed（编译失败）",
    "error": "error（探测未完成）",
}


def load_results(root: str) -> list[dict]:
    results = []
    for dirpath, _, files in os.walk(root):
        if "result.json" not in files:
            continue
        with open(os.path.join(dirpath, "result.json"), "r", encoding="utf-8") as f:
            try:
                results.append(json.load(f))
            except ValueError:
                print(f"::warning::无法解析 {dirpath}/result.json，已跳过")
    return results


def sub_of(kernel: str) -> int:
    try:
        return int(kernel.rsplit(".", 1)[-1])
    except (ValueError, AttributeError):
        return -1


def contiguous_min(entries: list[tuple[str, dict]]) -> str:
    """按月份升序，返回「从此之后全部 clean」的最早内核版本；没有则返回空串"""
    ordered = sorted(entries, key=lambda kv: kv[0])
    start = None
    for date, r in reversed(ordered):
        if r["verdict"] == "clean":
            start = r["kernel"]
        else:
            break
    return start or ""


def main() -> int:
    if len(sys.argv) != 3:
        print("用法: aggregate.py <结果目录> <输出目录>")
        return 2
    src, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    results = load_results(src)
    if not results:
        print("::error::没有找到任何 result.json")
        return 1

    run_url = os.environ.get("RUN_URL", "")
    today = datetime.date.today().isoformat()

    branches: dict[str, dict] = {}
    for r in results:
        key = f"{r.get('android_version')}-{r.get('kernel_version')}"
        block = branches.setdefault(key, {
            "android_version": r.get("android_version", ""),
            "kernel_version": r.get("kernel_version", ""),
            "ksu_variant": r.get("ksu_variant", "") or os.environ.get("KSU_VARIANT", ""),
            "susfs_repo": r.get("susfs_repo", ""),
            "susfs_branch": r.get("susfs_branch", ""),
            "susfs_commit": r.get("susfs_commit", ""),
            "susfs_commit_date": r.get("susfs_commit_date", ""),
            "probed_at": today,
            "run_url": run_url or r.get("run_url", ""),
            "results": {},
        })
        # 同一分支各任务克隆到的 susfs 提交应一致，不一致时以最新任务为准并提示
        if r.get("susfs_commit") and block["susfs_commit"] and r["susfs_commit"] != block["susfs_commit"]:
            print(f"::warning::{key} 各任务的 susfs4ksu 提交不一致：{block['susfs_commit'][:7]} vs {r['susfs_commit'][:7]}")
        entry_key = "lts" if r.get("os_patch_level") == "lts" else r.get("os_patch_level", "")
        if not entry_key:
            continue
        block["results"][entry_key] = {
            "kernel": r.get("kernel", ""),
            "rej": r.get("rej"),
            "rej_files": r.get("rej_files", []),
            "hunks_offset": r.get("hunks_offset"),
            "hunks_fuzz": r.get("hunks_fuzz"),
            "compile": r.get("compile", ""),
            "verdict": r.get("verdict", "error"),
            "reason": r.get("reason", ""),
            "ksu_compat_applied": r.get("ksu_compat_applied", False),
        }

    # 每个分支内按月份排序（lts 放最后）
    for block in branches.values():
        items = sorted(block["results"].items(), key=lambda kv: ("~" if kv[0] == "lts" else kv[0]))
        block["results"] = dict(items)

    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(dict(sorted(branches.items())), f, indent=2, ensure_ascii=False)

    lines = [f"# SUSFS 原始补丁探测汇总（{today}）", ""]
    if run_url:
        lines.append(f"运行：{run_url}")
        lines.append("")
    for key, block in sorted(branches.items()):
        monthly = [(d, r) for d, r in block["results"].items() if d != "lts"]
        counts = {}
        for _, r in block["results"].items():
            counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
        start = contiguous_min(monthly)
        lines.append(f"## {key}")
        lines.append("")
        lines.append(f"- 变体：{block['ksu_variant']}，susfs4ksu：{block['susfs_repo']} @ {block['susfs_commit'][:7]}（{block['susfs_commit_date']}）")
        lines.append("- 结论统计：" + "，".join(f"{VERDICT_LABEL.get(k, k)} × {v}" for k, v in sorted(counts.items())))
        lines.append(f"- 连续兼容起点：{start or '无（结果不连续或全部失败）'}")
        lines.append("")
        lines.append("| 月份 | 内核 | rej | hunk 偏移/模糊 | 编译 | 结论 |")
        lines.append("|---|---|---:|---:|---|---|")
        for date, r in block["results"].items():
            rej = "-" if r["rej"] is None else r["rej"]
            hunks = f"{r.get('hunks_offset') or 0}/{r.get('hunks_fuzz') or 0}"
            note = f" ({r['reason']})" if r.get("reason") else ""
            lines.append(f"| {date} | {r['kernel']} | {rej} | {hunks} | {r['compile']} | {r['verdict']}{note} |")
        lines.append("")

    md = "\n".join(lines)
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as f:
        f.write(md)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as f:
            f.write(md + "\n")

    print(md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
