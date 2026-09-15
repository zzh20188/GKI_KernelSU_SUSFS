#!/usr/bin/env bash
# 工具分支 susfs-probe 专用：以「原始模式」应用 SUSFS 补丁
#
# 与 dev 分支的 apply.sh 不同，这里不做任何内核上下文调整、也不做任何版本修复，
# 只把 susfs4ksu 的文件拷进内核树并直接 patch -p1，然后由编译结果说明
# 原始补丁在这个子版本上到底能不能用。结论写入 $SUSFS_PROBE_DIR/apply.json，
# 供 build.yml 的「写入 SUSFS 探测结果」步骤汇总。
#
# 保留 / 可选的适配（都与内核子版本无关，是否触发都记入 apply.json）：
#   1. KSU 侧：SukiSU/ReSukiSU 尚未提供 su 会话 FD 接口时恢复旧版 exec hook（总是启用，
#      对 SukiSU 是链接必需，对 ReSukiSU 一般不触发）
#   2. SUSFS 侧（SUSFS_PROBE_SIDE_FIXES=1 时才启用，默认关闭）：上游 5.10 补丁自身的两处
#      编译缺陷（statfs.c 声明晚于使用、susfs.c 缺 linux/security.h）。它们只取决于内核
#      大版本，不修的话 5.10 整条线都会 failed，探测不出子版本边界
#
# 依赖环境变量：
#   ANDROID_VERSION KERNEL_VERSION KSU_VARIANT OS_PATCH_LEVEL SUB_LEVEL
#   KERNEL_ROOT SUSFS4KSU
# 可选：SUSFS_PROBE_DIR（结论输出目录，默认 $KERNEL_ROOT/susfs-probe）
#       SUSFS_PROBE_SIDE_FIXES=1（启用 SUSFS 侧修复）
# 调用前必须将工作目录设为 $KERNEL_ROOT
set -eo pipefail

: "${KERNEL_ROOT:?需要 KERNEL_ROOT}"
: "${SUSFS4KSU:?需要 SUSFS4KSU}"
: "${ANDROID_VERSION:?需要 ANDROID_VERSION}"
: "${KERNEL_VERSION:?需要 KERNEL_VERSION}"

PROBE_DIR="${SUSFS_PROBE_DIR:-$KERNEL_ROOT/susfs-probe}"
mkdir -p "$PROBE_DIR"
PATCH_LOG="$PROBE_DIR/patch.log"
: > "$PATCH_LOG"

SUSFS_PATCH="50_add_susfs_in_gki-$ANDROID_VERSION-$KERNEL_VERSION.patch"
PATCH_EXIT=""
KSU_PATCH_EXIT=""
KSU_REJ_COUNT=0
KSU_COMPAT_APPLIED=0
KSU_COMPAT_FAILED=0
SIDE_FIXES_APPLIED=""
REJ_FILES=()

# 把当前统计写成 apply.json（正常结束与中途失败都调用）
write_apply_json() {
  SUSFS_PATCH="$SUSFS_PATCH" PATCH_LOG="$PATCH_LOG" PROBE_DIR="$PROBE_DIR" \
  PATCH_EXIT="$PATCH_EXIT" KSU_PATCH_EXIT="$KSU_PATCH_EXIT" KSU_REJ_COUNT="$KSU_REJ_COUNT" \
  KSU_COMPAT_APPLIED="$KSU_COMPAT_APPLIED" KSU_COMPAT_FAILED="$KSU_COMPAT_FAILED" \
  SIDE_FIXES_APPLIED="$SIDE_FIXES_APPLIED" \
  python3 - "${REJ_FILES[@]}" <<'PY'
import json
import os
import re
import sys

log = ""
if os.path.exists(os.environ["PATCH_LOG"]):
    log = open(os.environ["PATCH_LOG"], encoding="utf-8", errors="replace").read()

def to_int(value):
    return int(value) if value not in ("", None) else None

# patch 对找不到目标文件、疑似已应用的 hunk 只会「ignored」，不产生 .rej，必须单独计数
ignored = sum(int(m) for m in re.findall(r"^(\d+) out of \d+ hunks? ignored", log, re.M))
result = {
    "patch": os.environ["SUSFS_PATCH"],
    "patch_exit": to_int(os.environ["PATCH_EXIT"]),
    "rej": len(sys.argv[1:]),
    "rej_files": sys.argv[1:],
    "hunks_failed": len(re.findall(r"^Hunk #\d+ FAILED", log, re.M)),
    "hunks_ignored": ignored,
    "files_missing": len(re.findall(r"can't find file to patch", log)),
    "hunks_offset": len(re.findall(r"^Hunk #\d+ succeeded at \d+( with fuzz \d+)? \(offset", log, re.M)),
    "hunks_fuzz": len(re.findall(r"with fuzz \d+", log)),
    "ksu_patch_exit": to_int(os.environ["KSU_PATCH_EXIT"]),
    "ksu_rej": int(os.environ["KSU_REJ_COUNT"] or 0),
    "ksu_compat_applied": os.environ["KSU_COMPAT_APPLIED"] == "1",
    "ksu_compat_failed": os.environ["KSU_COMPAT_FAILED"] == "1",
    "susfs_side_fixes_applied": [f for f in os.environ["SIDE_FIXES_APPLIED"].split(",") if f],
}
with open(os.path.join(os.environ["PROBE_DIR"], "apply.json"), "w", encoding="utf-8") as f:
    json.dump(result, f, indent=2, ensure_ascii=False)
print("apply.json:", json.dumps(result, ensure_ascii=False))
PY
}

echo "以原始模式应用 SUSFS 补丁（不做上下文调整与版本修复）..."

cp "$SUSFS4KSU/kernel_patches/$SUSFS_PATCH" ./common/
cp "$SUSFS4KSU"/kernel_patches/fs/* ./common/fs/
cp "$SUSFS4KSU"/kernel_patches/include/linux/* ./common/include/linux/

# KSU 侧补丁：只与 KernelSU 变体有关，与内核子版本无关；成败单独记录
case "$KSU_VARIANT" in
  "Official")
    cd ./KernelSU
    cp "$SUSFS4KSU"/kernel_patches/KernelSU/10_enable_susfs_for_ksu.patch ./
    set +e
    patch -p1 --forward < 10_enable_susfs_for_ksu.patch
    KSU_PATCH_EXIT=$?
    set -e
    KSU_REJ_COUNT=$(find . -name '*.rej' -type f | wc -l)
    [ "$KSU_REJ_COUNT" -gt 0 ] && echo "::warning title=KSU 侧补丁冲突::10_enable_susfs_for_ksu.patch 产生了 ${KSU_REJ_COUNT} 个 .rej"
    cd ..
    ;;
  *)
    echo "$KSU_VARIANT 使用内置 SUSFS 支持"
    ;;
esac

cd "$KERNEL_ROOT/common"

# 主补丁：不做任何预处理，直接打入；输出留档以便统计偏移 / 模糊 / 忽略的 hunk
set +e
patch -p1 < "$SUSFS_PATCH" 2>&1 | tee "$PATCH_LOG"
PATCH_EXIT=${PIPESTATUS[0]}
set -e
echo "patch 退出码: $PATCH_EXIT"

# 可选的 SUSFS 侧修复：上游 5.10 补丁自身的编译缺陷，与子版本无关
if [[ "${SUSFS_PROBE_SIDE_FIXES:-}" == "1" ]]; then
  if [[ -f fs/statfs.c ]] && grep -qF 'susfs_sus_kstat_spoof_vfs_statfs(' fs/statfs.c; then
    STATFS_USE=$(grep -n 'if (!susfs_sus_kstat_spoof_vfs_statfs(' fs/statfs.c | head -1 | cut -d: -f1)
    STATFS_DECL=$(grep -n '^extern int susfs_sus_kstat_spoof_vfs_statfs(' fs/statfs.c | head -1 | cut -d: -f1)
    if [[ -n "$STATFS_USE" && -n "$STATFS_DECL" && "$STATFS_DECL" -gt "$STATFS_USE" ]] \
      && grep -q '^static int susfs_statfs_by_dentry(' fs/statfs.c; then
      echo "SUSFS 侧修复：前移 statfs.c 中 susfs_sus_kstat_spoof_vfs_statfs 的声明"
      sed -i '/^static int susfs_statfs_by_dentry(/i extern int susfs_sus_kstat_spoof_vfs_statfs(struct inode *inode, struct kstatfs *buf, bool *is_fuse);' fs/statfs.c
      SIDE_FIXES_APPLIED="${SIDE_FIXES_APPLIED:+$SIDE_FIXES_APPLIED,}statfs_decl"
    fi
  fi
  if [[ -f fs/susfs.c ]] && grep -qF 'security_sb_statfs(' fs/susfs.c \
    && ! grep -qF '#include <linux/security.h>' fs/susfs.c; then
    echo "SUSFS 侧修复：为 susfs.c 补充 linux/security.h"
    sed -i '0,/^#include <linux\/fs.h>$/s//#include <linux\/fs.h>\n#include <linux\/security.h>/' fs/susfs.c
    SIDE_FIXES_APPLIED="${SIDE_FIXES_APPLIED:+$SIDE_FIXES_APPLIED,}susfs_security_h"
  fi
fi

# 统计主补丁留下的 .rej（只看 common/，此时还没有别的补丁介入；
# common/drivers/kernelsu 是符号链接，find 默认不跟随，KSU 侧 .rej 已在上面单独统计）
mapfile -t REJ_FILES < <(find . -name '*.rej' -type f | sed 's|^\./||' | sort)
if [ "${#REJ_FILES[@]}" -gt 0 ]; then
  echo "::warning title=SUSFS 原始补丁冲突::原始补丁产生了 ${#REJ_FILES[@]} 个 .rej 文件"
  printf '  %s\n' "${REJ_FILES[@]}"
else
  echo "原始补丁干净打入，0 个 .rej"
fi

# KSU 侧适配：SukiSU/ReSukiSU 尚未提供 su 会话 FD 接口时恢复旧版 exec hook
EXEC_HELPER=""
if [[ "$KSU_VARIANT" == SukiSU* || "$KSU_VARIANT" == "ReSukiSU" ]]; then
  if grep -qF 'ksu_install_su_fd();' fs/exec.c; then
    EXEC_HELPER="ksu_install_su_fd"
  elif grep -qF 'ksu_handle_post_execveat_sucompat(' fs/exec.c; then
    EXEC_HELPER="ksu_handle_post_execveat_sucompat"
  fi
fi
if [[ -n "$EXEC_HELPER" ]] \
  && ! grep -RqsE --include='*.c' "^[[:space:]]*int[[:space:]]+${EXEC_HELPER}[[:space:]]*\(" "$KERNEL_ROOT/KernelSU/kernel"; then
  echo "$KSU_VARIANT 尚未提供 $EXEC_HELPER，恢复旧版 exec hook（KSU 侧适配，与内核子版本无关）"
  sed -i '/^extern int ksu_install_su_fd(void);$/d' fs/exec.c
  sed -i '/^extern int ksu_handle_post_execveat_sucompat(/,+1d' fs/exec.c
  sed -i 's/is_su_session = !\(ksu_handle_execveat[^;]*;\)/\1/' fs/exec.c
  sed -i '/^[[:space:]]*bool is_su_session = false;$/d' fs/exec.c
  sed -i '/^[[:space:]]*if (unlikely(is_su_session && retval >= 0))$/,+1d' fs/exec.c
  sed -i '/^[[:space:]]*if (unlikely(is_su_session))$/,+1d' fs/exec.c
  sed -i '/^#ifdef CONFIG_KSU_SUSFS$/N;/^#ifdef CONFIG_KSU_SUSFS\n#endif \/\/ #ifdef CONFIG_KSU_SUSFS$/d' fs/exec.c
  KSU_COMPAT_APPLIED=1
  if grep -qE 'ksu_install_su_fd|ksu_handle_post_execveat_sucompat|is_su_session' fs/exec.c; then
    KSU_COMPAT_FAILED=1
    write_apply_json
    echo "::error::$KSU_VARIANT exec hook 结构已变化，无法完成 KSU 侧适配"
    exit 1
  fi
fi

write_apply_json
