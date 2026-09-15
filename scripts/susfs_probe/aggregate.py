"""汇总各构建任务的 SUSFS 原始补丁探测结论。

用法：python3 aggregate.py <download-artifact 目录> <输出目录>

输入目录下每个 SUSFS-Probe-* 子目录里有一个 result.json（由 write_result.py 写出）。
环境变量（均可选）：
  MATRIX_INCLUDE     build_matrix.py 输出的 include JSON，用来对账：矩阵里有、结果里没有的
                     任务记为 error / missing_result
  MERGE_FROM_BRANCH  合并该分支上已有的 data/susfs_probe/<key>.json（部分重跑时保留其它月份）
  GITHUB_REPOSITORY  owner/repo，读取上面的分支时用
  PROBE_PARTIAL      true 表示本次带了子版本 / 月份过滤
  RUN_URL / KSU_VARIANT / GITHUB_STEP_SUMMARY

输出：
  summary.json      按 <android>-<kernel> 分块，已与 MERGE_FROM_BRANCH 上的旧结果合并，
                    每块的 results 只含 clean / built_with_rej / failed；error 单独放在 errors，
                    可用 split_summary.py 拆成 data/susfs_probe/<key>.json
  summary.run.json  只含本次运行的原始条目（含 error），便于排查
  summary.md        人类可读的表格（同时追加到 GITHUB_STEP_SUMMARY）
"""

from __future__ import annotations

import collections
import copy
import datetime
import json
import os
import sys
import urllib.error
import urllib.request

FINAL_VERDICTS = ("clean", "built_with_rej", "failed")
VERDICT_LABEL = {
    "clean": "clean（0 rej，编译成功）",
    "built_with_rej": "built_with_rej（有 rej，编译成功）",
    "failed": "failed（编译失败）",
    "error": "error（探测未完成）",
}
ENTRY_FIELDS = ("kernel", "rej", "rej_files", "hunks_ignored", "hunks_offset", "hunks_fuzz", "compile",
                "verdict", "reason", "kernel_ref", "kernel_commit", "ksu_commit", "susfs_commit",
                "ksu_compat_applied", "susfs_side_fixes_applied")


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


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


def entry_key(os_patch_level: str) -> str:
    return "lts" if os_patch_level == "lts" else os_patch_level


def fetch_existing(key: str) -> dict | None:
    """读取 MERGE_FROM_BRANCH 分支上已有的探测文件，没有则返回 None"""
    repo, branch = env("GITHUB_REPOSITORY"), env("MERGE_FROM_BRANCH")
    if not repo or not branch:
        return None
    url = f"https://raw.githubusercontent.com/{repo}/{branch}/data/susfs_probe/{key}.json"
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code != 404:
            print(f"::warning::读取已有探测文件 {url} 失败：{e}")
        return None
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as e:
        print(f"::warning::读取已有探测文件 {url} 失败：{e}")
        return None


def contiguous_min(results: dict) -> tuple[str, str]:
    """与网页同一规则：只有「从某个月份起到最新全部 clean、且更早的月份没有 clean」才画线。
    返回 (起点内核版本, 说明)；不画线时起点为空串。"""
    monthly = sorted((d, r) for d, r in results.items() if d != "lts")
    if not monthly:
        return "", "没有月份结果"
    i = len(monthly)
    while i > 0 and monthly[i - 1][1].get("verdict") == "clean":
        i -= 1
    if i == len(monthly):
        return "", "最新月份不是 clean"
    if any(r.get("verdict") == "clean" for _, r in monthly[:i]):
        return "", "结果不连续（更早的月份也有 clean），网页只逐行标记"
    return monthly[i][1].get("kernel", ""), ""


def main() -> int:
    if len(sys.argv) != 3:
        print("用法: aggregate.py <结果目录> <输出目录>")
        return 2
    src, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    results = load_results(src)
    run_url = env("RUN_URL")
    today = datetime.date.today().isoformat()
    partial = env("PROBE_PARTIAL").lower() == "true"

    # ---- 与矩阵对账：矩阵里有、结果里没有的任务记为 error / missing_result ----
    matrix_raw = env("MATRIX_INCLUDE")
    expected: list[dict] = []
    if matrix_raw:
        try:
            expected = json.loads(matrix_raw)
        except ValueError:
            print("::warning::MATRIX_INCLUDE 不是合法 JSON，跳过对账")
    got = {(r.get("android_version"), r.get("kernel_version"), r.get("os_patch_level")) for r in results}
    missing = [m for m in expected if (m["android_version"], m["kernel_version"], m["os_patch_level"]) not in got]
    for m in missing:
        results.append({
            "android_version": m["android_version"], "kernel_version": m["kernel_version"],
            "os_patch_level": m["os_patch_level"], "kernel": f"{m['kernel_version']}.{m['sub_level']}",
            "verdict": "error", "reason": "missing_result", "compile": "",
        })
    if not results:
        print("::error::没有找到任何 result.json")
        return 1
    if missing:
        print(f"::warning::{len(missing)} 个矩阵任务没有产出探测结论（missing_result）")

    # ---- 按分支分块 ----
    blocks: dict[str, dict] = {}
    for r in results:
        key = f"{r.get('android_version')}-{r.get('kernel_version')}"
        block = blocks.setdefault(key, {
            "android_version": r.get("android_version", ""),
            "kernel_version": r.get("kernel_version", ""),
            "ksu_variant": "",
            "susfs_repo": "",
            "susfs_branch": "",
            "susfs_commit": "",
            "susfs_commit_date": "",
            "probed_at": today,
            "run_url": run_url or r.get("run_url", ""),
            "partial": partial,
            "results": {},
            "errors": {},
            "_commits": collections.Counter(),
            "_meta": {},
        })
        ek = entry_key(r.get("os_patch_level", ""))
        if not ek:
            continue
        entry = {k: r.get(k) for k in ENTRY_FIELDS if k in r}
        entry.setdefault("kernel", "")
        entry.setdefault("verdict", "error")
        if entry["verdict"] in FINAL_VERDICTS:
            block["results"][ek] = entry
        else:
            block["errors"][ek] = entry
        if r.get("susfs_commit"):
            block["_commits"][r["susfs_commit"]] += 1
            block["_meta"].setdefault(r["susfs_commit"], (r.get("susfs_repo", ""), r.get("susfs_branch", ""), r.get("susfs_commit_date", "")))
        if r.get("ksu_variant") and not block["ksu_variant"]:
            block["ksu_variant"] = r["ksu_variant"]

    warnings: list[str] = []
    run_only = {}
    for key, block in blocks.items():
        block["ksu_variant"] = block["ksu_variant"] or env("KSU_VARIANT")
        # 块级 susfs 提交取出现次数最多的；不一致时记录警告（各条目自带 susfs_commit）
        if block["_commits"]:
            commit, _ = block["_commits"].most_common(1)[0]
            block["susfs_commit"] = commit
            block["susfs_repo"], block["susfs_branch"], block["susfs_commit_date"] = block["_meta"][commit]
            if len(block["_commits"]) > 1:
                shorts = ", ".join(f"{c[:7]}×{n}" for c, n in block["_commits"].most_common())
                warnings.append(f"{key} 各任务的 susfs4ksu 提交不一致：{shorts}，块级取出现最多的 {commit[:7]}")
        run_only[key] = copy.deepcopy({k: v for k, v in block.items() if not k.startswith("_")})
        del block["_commits"], block["_meta"]

    # ---- 与已有文件合并：保留旧结果里本次没跑到的月份 ----
    merged_from = env("MERGE_FROM_BRANCH")
    for key, block in blocks.items():
        existing = fetch_existing(key) if merged_from else None
        if not existing or not isinstance(existing.get("results"), dict):
            continue
        kept = 0
        for ek, old in existing["results"].items():
            if ek in block["results"] or ek in block["errors"]:
                continue
            if isinstance(old, dict) and old.get("verdict") in FINAL_VERDICTS:
                block["results"][ek] = old
                kept += 1
        if kept:
            warnings.append(f"{key} 合并了 {merged_from} 上已有文件的 {kept} 条旧结论（本次未探测的月份）")
            if existing.get("susfs_commit") and existing.get("susfs_commit") != block["susfs_commit"]:
                warnings.append(f"{key} 旧结论来自 susfs4ksu {existing['susfs_commit'][:7]}，本次为 {block['susfs_commit'][:7]}，各条目以自身 susfs_commit 为准")

    # 每个分支内按月份排序（lts 放最后）
    for block in blocks.values():
        for field in ("results", "errors"):
            items = sorted(block[field].items(), key=lambda kv: ("~" if kv[0] == "lts" else kv[0]))
            block[field] = dict(items)

    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(dict(sorted(blocks.items())), f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "summary.run.json"), "w", encoding="utf-8") as f:
        json.dump(dict(sorted(run_only.items())), f, indent=2, ensure_ascii=False)

    # ---- Markdown ----
    lines = [f"# SUSFS 原始补丁探测汇总（{today}）", ""]
    if run_url:
        lines += [f"运行：{run_url}", ""]
    if partial:
        lines += ["> 本次带了子版本 / 月份过滤，summary.json 已与来源分支上的旧结论合并；未探测的月份沿用旧结论。", ""]
    if warnings:
        lines += ["## 注意", ""] + [f"- {w}" for w in warnings] + [""]
    total_errors = sum(len(b["errors"]) for b in blocks.values())
    if total_errors:
        lines += [f"> **{total_errors} 个任务没有得到内核结论（error）**，落盘前请用 os_patch_levels 只重跑这些月份。", ""]
        print(f"::warning::{total_errors} 个任务为 error，落盘前需要重跑")

    for key, block in sorted(blocks.items()):
        counts = collections.Counter(r["verdict"] for r in block["results"].values())
        counts["error"] = len(block["errors"])
        start, note = contiguous_min(block["results"])
        lines.append(f"## {key}")
        lines.append("")
        lines.append(f"- 变体：{block['ksu_variant']}，susfs4ksu：{block['susfs_repo']} @ {block['susfs_commit'][:7]}（{block['susfs_commit_date']}）")
        lines.append("- 结论统计：" + "，".join(f"{VERDICT_LABEL.get(k, k)} × {v}" for k, v in sorted(counts.items()) if v))
        lines.append(f"- 网页会画的兼容线起点：{start or '无'}" + (f"（{note}）" if note else ""))
        lines.append("")
        lines.append("| 月份 | 内核 | rej | 忽略 | 偏移/模糊 | 编译 | 结论 | 备注 |")
        lines.append("|---|---|---:|---:|---:|---|---|---|")
        for date, r in block["results"].items():
            rej = "-" if r.get("rej") is None else r.get("rej")
            hunks = f"{r.get('hunks_offset') or 0}/{r.get('hunks_fuzz') or 0}"
            notes = []
            if r.get("reason"):
                notes.append(r["reason"])
            if r.get("verdict") == "failed" and (r.get("rej") or 0) == 0 and (r.get("hunks_ignored") or 0) == 0:
                notes.append("需复核：补丁干净打入仍编译失败，多半是 SUSFS/KSU 侧或环境问题")
            if r.get("susfs_side_fixes_applied"):
                notes.append("含 SUSFS 侧修复 " + "+".join(r["susfs_side_fixes_applied"]))
            if date == "lts":
                notes.append(f"LTS 探测时的实际版本 {r.get('kernel', '')}，落盘前核对 data 里的 lts")
            lines.append(f"| {date} | {r.get('kernel', '')} | {rej} | {r.get('hunks_ignored') if r.get('hunks_ignored') is not None else '-'} | {hunks} | {r.get('compile', '')} | {r.get('verdict')} | {'；'.join(notes)} |")
        for date, r in block["errors"].items():
            lines.append(f"| {date} | {r.get('kernel', '')} | - | - | - | {r.get('compile', '')} | error | {r.get('reason', '')} |")
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
