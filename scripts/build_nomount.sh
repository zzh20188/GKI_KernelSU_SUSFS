#!/usr/bin/env bash
# scripts/build_nomount.sh — 只 patch + 改 defconfig，不编 ko
# 用法: build_nomount.sh <kernel_common_dir> <defconfig_path>

set -euo pipefail

KERNEL_DIR="$(realpath "$1")"
DEFCONFIG="$(realpath "$2")"

echo "=== NoMount-Suite hookless (patch → obj-m, 仅 patch 阶段) ==="
cd "$KERNEL_DIR"

# 1. 拉取补丁
curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors \
  https://raw.githubusercontent.com/Bouteillepleine/NoMount-Suite/main/hookless/patches/nomount_kernel_integration.patch \
  -o /tmp/nomount-hookless.patch

echo "补丁预览（前20行）:"
head -20 /tmp/nomount-hookless.patch || true

# 2. 应用补丁
patch -p1 --fuzz=3 --no-backup-if-mismatch < /tmp/nomount-hookless.patch

# 3. 把 nomount 从 builtin (obj-y) 改成模块 (obj-m)
echo "将 nomount 从 builtin 改为可加载模块..."

# 处理 fs/Makefile 里补丁写入的行
if grep -q '^obj-\$(CONFIG_NOMOUNT) += nomount/' fs/Makefile; then
  sed -i 's|^obj-\$(CONFIG_NOMOUNT) += nomount/|obj-m += nomount/nomount.o|' fs/Makefile
elif grep -q '^obj-y += nomount/' fs/Makefile; then
  sed -i 's|^obj-y += nomount/|obj-m += nomount/nomount.o|' fs/Makefile
fi

# 处理 fs/nomount/Makefile（如果存在）
if [ -f fs/nomount/Makefile ]; then
  sed -i 's/^obj-y /obj-m /' fs/nomount/Makefile
  sed -i 's/^obj-y$/obj-m/' fs/nomount/Makefile
fi

# 4. defconfig: CONFIG_NOMOUNT=m
if grep -q '^CONFIG_NOMOUNT=' "$DEFCONFIG"; then
  sed -i 's/^CONFIG_NOMOUNT=.*/CONFIG_NOMOUNT=m/' "$DEFCONFIG"
else
  echo "CONFIG_NOMOUNT=m" >> "$DEFCONFIG"
fi

# 5. 确保模块基础选项
grep -q '^CONFIG_MODULES=y' "$DEFCONFIG" || echo "CONFIG_MODULES=y" >> "$DEFCONFIG"
grep -q '^CONFIG_MODULE_UNLOAD=y' "$DEFCONFIG" || echo "CONFIG_MODULE_UNLOAD=y" >> "$DEFCONFIG"
grep -q '^CONFIG_KALLSYMS=y' "$DEFCONFIG" || echo "CONFIG_KALLSYMS=y" >> "$DEFCONFIG"
grep -q '^CONFIG_KALLSYMS_ALL=y' "$DEFCONFIG" || echo "CONFIG_KALLSYMS_ALL=y" >> "$DEFCONFIG"

echo "NoMount patch 阶段完成（nomount 已设为 obj-m，ko 将在 build.sh 之后编译）"
