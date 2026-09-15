"""把单次构建的 SUSFS 原始补丁探测结论写成 result.json。

由 build.yml 的「写入 SUSFS 探测结果」步骤调用（always()），综合三处信息：
  1. apply.sh 写出的 apply.json（原始补丁的 .rej / 忽略 hunk 统计、KSU 侧补丁成败）
  2. 编译步骤的 outcome（success / failure / skipped / cancelled）
  3. 实际使用的内核 ref、KernelSU 提交、susfs4ksu 仓库与提交

结论 verdict：
  clean          0 个 .rej、0 个被忽略的 hunk，且编译成功，即「可直接应用」
  built_with_rej 有 .rej（或有被忽略的 hunk）但编译成功，功能可能缺失，需要人工判断
  failed         编译失败
  error          探测流程本身没跑完或失败原因不在内核侧（同步失败、补丁步骤未执行、
                 KSU 侧补丁冲突、exec hook 适配失败等），不是内核结论
"""

from __future__ import annotations

import json
import os
import subprocess
import sys


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def git_out(repo: str, *args: str) -> str:
    if not repo or not os.path.isdir(repo):
        return ""
    try:
        return subprocess.check_output(["git", "-C", repo, *args], text=True, stderr=subprocess.DEVNULL).strip()
    except (subprocess.CalledProcessError, OSError):
        return ""


def kernel_ref(branch: str) -> str:
    """按 build.yml 的同步结果推出实际使用的内核 ref"""
    tag = env("TAG_FALLBACK")
    if tag:
        return f"refs/tags/{tag}"
    remote = env("REMOTE_BRANCH")
    if "deprecated/" in remote:
        return f"refs/heads/deprecated/{branch}"
    if remote:
        return f"refs/heads/{branch}"
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

    compile_outcome = env("COMPILE_OUTCOME") or "skipped"
    compile_label = {"success": "ok", "failure": "failed"}.get(compile_outcome, compile_outcome)

    rej = apply_info.get("rej", 0) if apply_info else None
    ignored = apply_info.get("hunks_ignored", 0) if apply_info else None
    ksu_rej = apply_info.get("ksu_rej", 0) if apply_info else 0

    if apply_info is None:
        verdict, reason = "error", ("apply_aborted" if os.path.exists(os.path.join(probe_dir, "patch.log")) else "susfs_not_applied")
    elif apply_info.get("ksu_compat_failed"):
        verdict, reason = "error", "ksu_compat_failed"
    elif compile_outcome == "success":
        verdict = "clean" if (rej == 0 and ignored == 0) else "built_with_rej"
        reason = "" if verdict == "clean" else ("hunks_ignored" if rej == 0 else "")
    elif compile_outcome == "failure":
        if ksu_rej:
            verdict, reason = "error", "ksu_patch_failed"
        else:
            verdict, reason = "failed", ""
    else:
        verdict, reason = "error", f"compile_{compile_outcome}"

    android = env("ANDROID_VERSION")
    kernel_version = env("KERNEL_VERSION")
    actual_sublevel = env("ACTUAL_SUBLEVEL") or env("SUB_LEVEL")
    branch = f"{android}-{kernel_version}-{env('OS_PATCH_LEVEL')}"
    kernel_root = env("KERNEL_ROOT")
    susfs_repo_dir = env("SUSFS4KSU")

    result = {
        "android_version": android,
        "kernel_version": kernel_version,
        "sub_level": env("SUB_LEVEL"),
        "actual_sublevel": actual_sublevel,
        "kernel": f"{kernel_version}.{actual_sublevel}" if actual_sublevel else "",
        "os_patch_level": env("OS_PATCH_LEVEL"),
        "ksu_variant": env("KSU_VARIANT"),
        # 复现所需：内核、KSU、susfs4ksu 的实际来源
        "kernel_ref": kernel_ref(branch),
        "kernel_commit": git_out(os.path.join(kernel_root, "common"), "rev-parse", "HEAD") if kernel_root else "",
        "ksu_branch": env("BRANCH"),
        "ksu_commit": git_out(os.path.join(kernel_root, "KernelSU"), "rev-parse", "HEAD") if kernel_root else "",
        "susfs_repo": git_out(susfs_repo_dir, "remote", "get-url", "origin").removesuffix(".git"),
        "susfs_branch": f"gki-{android}-{kernel_version}",
        "susfs_commit": git_out(susfs_repo_dir, "rev-parse", "HEAD"),
        "susfs_commit_date": git_out(susfs_repo_dir, "log", "-1", "--format=%cs"),
        "compile": compile_label,
        "rej": rej,
        "rej_files": apply_info.get("rej_files", []) if apply_info else [],
        "hunks_failed": apply_info.get("hunks_failed") if apply_info else None,
        "hunks_ignored": ignored,
        "hunks_offset": apply_info.get("hunks_offset") if apply_info else None,
        "hunks_fuzz": apply_info.get("hunks_fuzz") if apply_info else None,
        "patch_exit": apply_info.get("patch_exit") if apply_info else None,
        "ksu_rej": ksu_rej,
        "ksu_compat_applied": apply_info.get("ksu_compat_applied", False) if apply_info else False,
        "susfs_side_fixes_applied": apply_info.get("susfs_side_fixes_applied", []) if apply_info else [],
        "rej_total": int(env("REJ_COUNT", "0") or 0),
        "verdict": verdict,
        "reason": reason,
        "run_url": env("RUN_URL"),
        "workflow_sha": env("GITHUB_SHA"),
    }

    with open(os.path.join(probe_dir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    print(f"探测结论: {result['kernel'] or result['sub_level']} / {result['os_patch_level']} -> {verdict}"
          + (f"（{reason}）" if reason else "") + f"，rej={rej}，ignored={ignored}，compile={compile_label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
