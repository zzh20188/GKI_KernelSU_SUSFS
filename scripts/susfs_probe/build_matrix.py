"""生成 SUSFS 原始补丁探测的构建矩阵。

从 data_source 分支（默认 dev）现取 data/<android>/<kernel>.json，按输入过滤后
输出 build.yml 需要的 include 列表。工具分支自己的 data/ 不必跟着 dev 更新。

环境变量：
  KERNEL_VERSION_FILTER  all 或某个内核版本，如 6.6
  SUB_LEVEL_MIN / SUB_LEVEL_MAX  子版本号区间（留空不限，只作用于月份分支）
  OS_PATCH_LEVELS        逗号分隔的安全补丁月份（留空不限）
  INCLUDE_LTS            true / false
  DATA_SOURCE            读取数据的分支名
  GITHUB_REPOSITORY      owner/repo（由 Actions 提供）
  GITHUB_OUTPUT / GITHUB_STEP_SUMMARY  由 Actions 提供
"""

import json
import os
import sys
import urllib.error
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))

from gki_fetch import TARGETS  # noqa: E402

# GitHub 对单个矩阵的任务数上限
MATRIX_LIMIT = 256


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def load_branch_data(android_ver: str, kernel_ver: str, repo: str, branch: str) -> dict | None:
    """优先从 raw.githubusercontent.com 读取指定分支的数据，失败时退回本地文件"""
    if repo and branch:
        url = f"https://raw.githubusercontent.com/{repo}/{branch}/data/{android_ver}/{kernel_ver}.json"
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, OSError) as e:
            print(f"  远程读取失败（{e}），退回本地数据")
    local = os.path.join(PROJECT_ROOT, "data", android_ver, f"{kernel_ver}.json")
    if not os.path.exists(local):
        return None
    with open(local, "r", encoding="utf-8") as f:
        return json.load(f)


def parse_int(value: str) -> int | None:
    return int(value) if value.isdigit() else None


def main() -> int:
    kernel_filter = env("KERNEL_VERSION_FILTER", "all")
    sub_min = parse_int(env("SUB_LEVEL_MIN"))
    sub_max = parse_int(env("SUB_LEVEL_MAX"))
    patch_levels = {p.strip() for p in env("OS_PATCH_LEVELS").split(",") if p.strip()}
    include_lts = env("INCLUDE_LTS", "true").lower() == "true"
    data_source = env("DATA_SOURCE", "dev")
    repo = env("GITHUB_REPOSITORY")

    include: list[dict] = []
    summary_rows: list[str] = []

    for (android_ver, kernel_ver) in TARGETS:
        if kernel_filter != "all" and kernel_ver != kernel_filter:
            continue
        print(f"=== {android_ver} / {kernel_ver} ===")
        data = load_branch_data(android_ver, kernel_ver, repo, data_source)
        if data is None:
            print("  没有数据，跳过")
            continue

        picked = 0
        for entry in data.get("entries", []):
            date = entry.get("date", "")
            kernel = entry.get("kernel", "")
            if not date or not kernel.startswith(f"{kernel_ver}."):
                continue
            sub_level = kernel.rsplit(".", 1)[-1]
            sub = parse_int(sub_level)
            if sub is None:
                continue
            if patch_levels and date not in patch_levels:
                continue
            if sub_min is not None and sub < sub_min:
                continue
            if sub_max is not None and sub > sub_max:
                continue
            include.append({
                "android_version": android_ver,
                "kernel_version": kernel_ver,
                "sub_level": sub_level,
                "os_patch_level": date,
            })
            picked += 1

        lts_picked = False
        if include_lts and data.get("lts"):
            include.append({
                "android_version": android_ver,
                "kernel_version": kernel_ver,
                "sub_level": "X",
                "os_patch_level": "lts",
            })
            lts_picked = True

        print(f"  选中 {picked} 个月份分支" + ("，含 LTS" if lts_picked else ""))
        summary_rows.append(f"| {android_ver} / {kernel_ver} | {picked} | {'是' if lts_picked else '否'} |")

    count = len(include)
    if count == 0:
        print("::error::过滤后没有可探测的分支，请检查输入")
        return 1
    if count > MATRIX_LIMIT:
        print(f"::error::矩阵任务数 {count} 超过上限 {MATRIX_LIMIT}，请缩小范围")
        return 1

    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write("include=" + json.dumps(include, ensure_ascii=False, separators=(",", ":")) + "\n")
        out.write(f"count={count}\n")

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(f"## 探测矩阵：{count} 个任务（数据来源 `{data_source}`）\n\n")
            summary.write("| 分支 | 月份分支数 | LTS |\n|---|---:|:-:|\n")
            summary.write("\n".join(summary_rows) + "\n")

    print(f"\n共 {count} 个任务")
    return 0


if __name__ == "__main__":
    sys.exit(main())
