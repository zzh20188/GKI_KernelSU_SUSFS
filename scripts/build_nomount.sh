#!/usr/bin/env bash
# build_nomount.sh - 独立构建 NoMount-Suite hookless LKM
# 用法: build_nomount.sh <kernel_root> <defconfig_path>
# 示例: build_nomount.sh "$KERNEL_ROOT/common" "$DEFCONFIG"

set -euo pipefail

KERNEL_ROOT="$(realpath "$1")"
DEFCONFIG="$(realpath "$2")"

echo "=== 构建 NoMount-Suite hookless 引擎 (LKM: nomount.ko) ==="
echo "KERNEL_ROOT=$KERNEL_ROOT"
echo "DEFCONFIG=$DEFCONFIG"

# 1. 准备源码目录
NOMOUNT_DIR="/tmp/nomount-lkm"
rm -rf "$NOMOUNT_DIR"
mkdir -p "$NOMOUNT_DIR"

# 2. 拉取上游引擎源码
echo "下载 nomount.c / nomount.h ..."
curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors \
  https://raw.githubusercontent.com/Bouteillepleine/NoMount-Suite/main/hookless/src/nomount.c \
  -o "$NOMOUNT_DIR/nomount.c"

curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors \
  https://raw.githubusercontent.com/Bouteillepleine/NoMount-Suite/main/hookless/src/nomount.h \
  -o "$NOMOUNT_DIR/nomount.h"

if [ ! -s "$NOMOUNT_DIR/nomount.c" ] || [ ! -s "$NOMOUNT_DIR/nomount.h" ]; then
  echo "::error::nomount.c / nomount.h 下载为空或失败"
  exit 1
fi
echo "引擎源码: $(wc -l < "$NOMOUNT_DIR/nomount.c") lines"

# 3. 写 Kbuild Makefile
cat > "$NOMOUNT_DIR/Makefile" << 'KBUILD_EOF'
# NoMount-Suite hookless LKM Kbuild
obj-m += nomount.o
ccflags-y := -I$(src) -DCONFIG_NOMOUNT -Wno-unused-parameter
KBUILD_EOF

# 4. 写兼容头（kallsyms 运行时解析，避免 GOT/PLT）
cat > "$NOMOUNT_DIR/compat.h" << 'COMPAT_EOF'
#ifndef NOMOUNT_KO_COMPAT_H
#define NOMOUNT_KO_COMPAT_H

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/kallsyms.h>
#include <linux/fs.h>
#include <linux/dcache.h>
#include <linux/namei.h>
#include <linux/uaccess.h>

#define NOMOUNT_LOOKUP(fn, type) \
  static type (*fn##_sym)(void); \
  do { fn##_sym = (type (*)(void))kallsyms_lookup_name(#fn); } while(0)

#endif
COMPAT_EOF

# 5. 编译 ko
echo "开始编译 nomount.ko ..."
make -C "$KERNEL_ROOT" \
  M="$NOMOUNT_DIR" \
  ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- \
  CC=clang \
  LLVM=1 \
  LLVM_IAS=1 \
  modules -j"$(nproc)"

if [ ! -s "$NOMOUNT_DIR/nomount.ko" ]; then
  echo "::error::nomount.ko 编译失败"
  exit 1
fi
echo "nomount.ko 编译成功: $(ls -lh "$NOMOUNT_DIR/nomount.ko" | awk '{print $5}')"

# 6. 拷贝产物
OUT_DIR="$KERNEL_ROOT/out/modules"
mkdir -p "$OUT_DIR"
cp "$NOMOUNT_DIR/nomount.ko" "$OUT_DIR/nomount.ko"
echo "nomount.ko 已拷贝到 $OUT_DIR/"

# 7. defconfig 确保模块支持
echo "检查内核配置..."
for opt in CONFIG_MODULES=y CONFIG_MODULE_UNLOAD=y CONFIG_KALLSYMS=y CONFIG_KALLSYMS_ALL=y; do
  if ! grep -q "^${opt%*=*}" "$DEFCONFIG"; then
    echo "$opt" >> "$DEFCONFIG"
    echo "  added $opt"
  fi
done

echo "NoMount-Suite hookless LKM 构建完成"
echo "  → $OUT_DIR/nomount.ko"
echo "  → 加载: insmod nomount.ko"
