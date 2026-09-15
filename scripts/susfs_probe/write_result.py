"""把单次构建的 SUSFS 原始补丁探测结论写成 result.json。

由 build.yml 的「写入 SUSFS 探测结果」步骤调用（always()），综合三处信息：
  1. apply.sh 写出的 apply.json（原始补丁的 .rej 数与 hunk 统计）
  2. 编译步骤的 outcome（success / failure / skipped / cancelled）
  3. 实际克隆的 susfs4ksu 仓库、分支与提交

结论 verdict：
  clean          0 个 .rej 且编译成功，即「可直接应用」
  built_with_rej 有 .rej 但编译成功，功能可能缺失，需要人工判断
  failed         编译失败
  error          探测流程本身没跑完（同步失败、补丁步骤未执行等），不是内核结论
"""

import json
import os
import subprocess
import sys


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def git_out(repo: str, *args: str) -> str:
    try:
        return subprocess.check_output(["git", "-C", repo, *args], text=True, stderr=subprocess.DEVNULL).strip()
    except (subprocess.CalledProcessError, OSError):
        return ""


def main() -> int:
    probe_dir = env("SUSFS_PROBE_DIR")
    if not probe_dir:
        print("::error::缺少 SUSFS_PROBE_DIR")
        return 1
    os.makedirs(probe_dir, exist_ok=True)

    apply_path = os.path.join(probe_dir, "apply.json")
    apply_info = None
    if os.path.exists(apply_path):
        with open(apply_path, "r", encoding="utf-8") as f:
            apply_info = json.load(f)

    compile_outcome = env("COMPILE_OUTCOME", "skipped")
    if apply_info is None:
        verdict = "error"
        reason = "susfs_not_applied"
    elif compile_outcome == "success":
        verdict = "clean" if apply_info.get("rej", 0) == 0 else "built_with_rej"
        reason = ""
    elif compile_outcome == "failure":
        verdict = "failed"
        reason = ""
    else:
        verdict = "error"
        reason = f"compile_{compile_outcome}"

    susfs_repo_dir = env("SUSFS4KSU")
    susfs_remote = git_out(susfs_repo_dir, "remote", "get-url", "origin").removesuffix(".git") if susfs_repo_dir else ""
    susfs_commit = git_out(susfs_repo_dir, "rev-parse", "HEAD") if susfs_repo_dir else ""
    susfs_date = git_out(susfs_repo_dir, "log", "-1", "--format=%cs") if susfs_repo_dir else ""

    kernel_version = env("KERNEL_VERSION")
    actual_sublevel = env("ACTUAL_SUBLEVEL") or env("SUB_LEVEL")

    result = {
        "android_version": env("ANDROID_VERSION"),
        "kernel_version": kernel_version,
        "sub_level": env("SUB_LEVEL"),
        "actual_sublevel": actual_sublevel,
        "kernel": f"{kernel_version}.{actual_sublevel}" if actual_sublevel else "",
        "os_patch_level": env("OS_PATCH_LEVEL"),
        "ksu_variant": env("KSU_VARIANT"),
        "susfs_repo": susfs_remote,
        "susfs_branch": f"gki-{env('ANDROID_VERSION')}-{kernel_version}",
        "susfs_commit": susfs_commit,
        "susfs_commit_date": susfs_date,
        "compile": {"success": "ok", "failure": "failed"}.get(compile_outcome, compile_outcome),
        "rej": apply_info.get("rej", 0) if apply_info else None,
        "rej_files": apply_info.get("rej_files", []) if apply_info else [],
        "hunks_failed": apply_info.get("hunks_failed", 0) if apply_info else None,
        "hunks_offset": apply_info.get("hunks_offset", 0) if apply_info else None,
        "hunks_fuzz": apply_info.get("hunks_fuzz", 0) if apply_info else None,
        "ksu_compat_applied": apply_info.get("ksu_compat_applied", False) if apply_info else False,
        "rej_total": int(env("REJ_COUNT", "0") or 0),
        "verdict": verdict,
        "reason": reason,
        "run_url": env("RUN_URL"),
        "workflow_sha": env("GITHUB_SHA"),
    }

    with open(os.path.join(probe_dir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    print(f"探测结论: {result['kernel'] or result['sub_level']} / {result['os_patch_level']} -> {verdict}"
          + (f"（{reason}）" if reason else "") + f"，rej={result['rej']}，compile={result['compile']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
