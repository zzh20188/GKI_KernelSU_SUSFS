#!/usr/bin/env bash
# 工具分支 susfs-probe 专用：以「原始模式」应用 SUSFS 补丁
#
# 与 dev 分支的 apply.sh 不同，这里不做任何内核上下文调整、也不做任何版本修复，
# 只把 susfs4ksu 的文件拷进内核树并直接 patch -p1，然后由编译结果说明
# 原始补丁在这个子版本上到底能不能用。结论写入 $SUSFS_PROBE_DIR/apply.json，
# 供 build.yml 的「写入 SUSFS 探测结果」步骤汇总。
#
# 唯一保留的 KSU 侧适配：SukiSU/ReSukiSU 尚未提供 su 会话 FD 接口时恢复旧版 exec hook。
# 它只取决于 KernelSU 变体本身，与内核子版本无关；是否触发会记录在 apply.json 里。
#
# 依赖环境变量：
#   ANDROID_VERSION KERNEL_VERSION KSU_VARIANT OS_PATCH_LEVEL SUB_LEVEL
#   KERNEL_ROOT SUSFS4KSU
# 可选：SUSFS_PROBE_DIR（结论输出目录，默认 $KERNEL_ROOT/susfs-probe）
# 调用前必须将工作目录设为 $KERNEL_ROOT
set -eo pipefail

PROBE_DIR="${SUSFS_PROBE_DIR:-$KERNEL_ROOT/susfs-probe}"
mkdir -p "$PROBE_DIR"

echo "以原始模式应用 SUSFS 补丁（不做上下文调整与版本修复）..."

SUSFS_PATCH="50_add_susfs_in_gki-$ANDROID_VERSION-$KERNEL_VERSION.patch"
cp "$SUSFS4KSU/kernel_patches/$SUSFS_PATCH" ./common/
cp "$SUSFS4KSU"/kernel_patches/fs/* ./common/fs/
cp "$SUSFS4KSU"/kernel_patches/include/linux/* ./common/include/linux/

# KSU 侧补丁：只与 KernelSU 变体有关，与内核子版本无关
case "$KSU_VARIANT" in
  "Official")
    cd ./KernelSU
    cp "$SUSFS4KSU"/kernel_patches/KernelSU/10_enable_susfs_for_ksu.patch ./
    patch -p1 --forward < 10_enable_susfs_for_ksu.patch || true
    cd ..
    ;;
  *)
    echo "$KSU_VARIANT 使用内置 SUSFS 支持"
    ;;
esac

cd "$KERNEL_ROOT/common"

# 主补丁：不做任何预处理，直接打入；输出留档以便统计偏移与模糊匹配
PATCH_LOG="$PROBE_DIR/patch.log"
patch -p1 < "$SUSFS_PATCH" 2>&1 | tee "$PATCH_LOG" || true

# 唯一保留的 KSU 侧适配（触发与否记入结论）
KSU_COMPAT_APPLIED=0
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
    echo "::error::$KSU_VARIANT exec hook 结构已变化，无法完成 KSU 侧适配"
    exit 1
  fi
fi

# 统计主补丁留下的 .rej（只看 common/，此时还没有别的补丁介入）
mapfile -t REJ_FILES < <(find . -name '*.rej' -type f | sed 's|^\./||' | sort)
SUSFS_REJ_COUNT=${#REJ_FILES[@]}
if [ "$SUSFS_REJ_COUNT" -gt 0 ]; then
  echo "::warning title=SUSFS 原始补丁冲突::原始补丁产生了 ${SUSFS_REJ_COUNT} 个 .rej 文件"
  printf '  %s\n' "${REJ_FILES[@]}"
else
  echo "原始补丁干净打入，0 个 .rej"
fi

# 写入结论：rej 数、rej 文件、按偏移 / 模糊匹配打入的 hunk 数
SUSFS_REJ_COUNT="$SUSFS_REJ_COUNT" KSU_COMPAT_APPLIED="$KSU_COMPAT_APPLIED" \
PATCH_LOG="$PATCH_LOG" PROBE_DIR="$PROBE_DIR" SUSFS_PATCH="$SUSFS_PATCH" \
python3 - "${REJ_FILES[@]}" <<'PY'
import json
import os
import re
import sys

log = open(os.environ["PATCH_LOG"], encoding="utf-8", errors="replace").read()
result = {
    "patch": os.environ["SUSFS_PATCH"],
    "rej": int(os.environ["SUSFS_REJ_COUNT"]),
    "rej_files": sys.argv[1:],
    "hunks_failed": len(re.findall(r"^Hunk #\d+ FAILED", log, re.M)),
    "hunks_offset": len(re.findall(r"^Hunk #\d+ succeeded at \d+ \(offset", log, re.M)),
    "hunks_fuzz": len(re.findall(r"with fuzz \d+", log)),
    "ksu_compat_applied": os.environ["KSU_COMPAT_APPLIED"] == "1",
}
with open(os.path.join(os.environ["PROBE_DIR"], "apply.json"), "w", encoding="utf-8") as f:
    json.dump(result, f, indent=2, ensure_ascii=False)
print("apply.json:", json.dumps(result, ensure_ascii=False))
PY
