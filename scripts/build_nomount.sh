#!/usr/bin/env bash
# build_nomount.sh - patch 方式集成 NoMount-Suite hookless (模块, obj-m)
# 用法: build_nomount.sh <kernel_root> <defconfig_path>

set -euo pipefail

KERNEL_ROOT="$(realpath "$1")"
DEFCONFIG="$(realpath "$2")"

cd "$KERNEL_ROOT"

echo "=== NoMount-Suite hookless (patch → obj-m 模块) ==="

# 1. 拉取上游补丁
curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors \
  https://raw.githubusercontent.com/Bouteillepleine/NoMount-Suite/main/hookless/patches/nomount_kernel_integration.patch \
  -o /tmp/nomount-hookless.patch

echo "补丁预览（前20行）:"
head -20 /tmp/nomount-hookless.patch || true

# 2. 应用补丁
patch -p1 --fuzz=3 --no-backup-if-mismatch < /tmp/nomount-hookless.patch

# 3. 关键修改：把补丁引入的 obj-y 改成 obj-m
#    上游补丁默认把 nomount 编成 builtin (obj-y)，我们要改成模块
echo "将 nomount 从 builtin 改为可加载模块 (obj-m) ..."

# 处理 fs/Makefile 里补丁加的那行
if grep -q '^obj-\$(CONFIG_NOMOUNT) += nomount/' fs/Makefile; then
  sed -i 's|^obj-\$(CONFIG_NOMOUNT) += nomount/|obj-m += nomount/nomount.o|' fs/Makefile
  echo "  fs/Makefile: obj-y → obj-m"
elif grep -q '^obj-y += nomount/' fs/Makefile; then
  sed -i 's|^obj-y += nomount/|obj-m += nomount/nomount.o|' fs/Makefile
  echo "  fs/Makefile: obj-y → obj-m (direct)"
fi

# 如果补丁在 fs/nomount/Makefile 里有 obj-y，也改掉
if [ -f fs/nomount/Makefile ]; then
  sed -i 's/^obj-y /obj-m /' fs/nomount/Makefile
  sed -i 's/^obj-y$/obj-m/' fs/nomount/Makefile
  echo "  fs/nomount/Makefile: obj-y → obj-m"
fi

# 4. defconfig: CONFIG_NOMOUNT=m (模块) 而非 =y (builtin)
if grep -q '^CONFIG_NOMOUNT=' "$DEFCONFIG"; then
  sed -i 's/^CONFIG_NOMOUNT=.*/CONFIG_NOMOUNT=m/' "$DEFCONFIG"
  echo "  defconfig: CONFIG_NOMOUNT=m"
else
  echo "CONFIG_NOMOUNT=m" >> "$DEFCONFIG"
  echo "  defconfig: appended CONFIG_NOMOUNT=m"
fi

# 5. 确保模块相关选项开启
for opt in CONFIG_MODULES=y CONFIG_MODULE_UNLOAD=y CONFIG_KALLSYMS=y CONFIG_KALLSYMS_ALL=y; do
  if ! grep -q "^${opt%*=*}" "$DEFCONFIG"; then
    echo "$opt" >> "$DEFCONFIG"
    echo "  added $opt"
  fi
done

# 6. 构建模块（不触发 vmlinux link）
echo "编译 nomount.ko ..."
make -C "$KERNEL_ROOT" \
  M=fs/nomount \
  ARCH=arm64 \
  CC=clang \
  LLVM=1 \
  LLVM_IAS=1 \
  modules -j"$(nproc)"

# 7. 确认产物
KO="$KERNEL_ROOT/fs/nomount/nomount.ko"
if [ ! -s "$KO" ]; then
  echo "::error::nomount.ko 未生成"
  exit 1
fi
echo "nomount.ko 构建成功: $(ls -lh "$KO" | awk '{print $5}')"

# 8. 拷贝到产物目录
OUT_DIR="$KERNEL_ROOT/out/modules"
mkdir -p "$OUT_DIR"
cp "$KO" "$OUT_DIR/nomount.ko"
echo "nomount.ko 已拷贝到 $OUT_DIR/"

echo "=== NoMount patch 版集成完成 ==="
echo "  → fs/nomount/ 已 patch 进内核树"
echo "  → CONFIG_NOMOUNT=m (模块，不进 vmlinux)"
echo "  → $OUT_DIR/nomount.ko"
