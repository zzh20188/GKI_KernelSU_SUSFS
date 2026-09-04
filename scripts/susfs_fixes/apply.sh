#!/usr/bin/env bash
# 应用 SUSFS 补丁 + 各内核版本特定的上下文修复。
# 从 build.yml 抽取，逐字保留原逻辑（仅将 ${{ inputs.X }} 改为 env 变量）。
#
# 依赖环境变量（由 build.yml 通过 env 传入或 GITHUB_ENV 继承）：
#   ANDROID_VERSION KERNEL_VERSION KSU_VARIANT OS_PATCH_LEVEL SUB_LEVEL
#   KERNEL_ROOT SUSFS4KSU KERNEL_PATCHES LEGACY_SUKISU_CONFIG
# 工作目录：$KERNEL_ROOT（与原 step 的 working-directory 一致）
set -eo pipefail

echo "应用 SUSFS 补丁..."

SUSFS_PATCH="50_add_susfs_in_gki-$ANDROID_VERSION-$KERNEL_VERSION.patch"
cp "$SUSFS4KSU/kernel_patches/$SUSFS_PATCH" ./common/
cp $SUSFS4KSU/kernel_patches/fs/* ./common/fs/
cp $SUSFS4KSU/kernel_patches/include/linux/* ./common/include/linux/

case "$KSU_VARIANT" in
  "Official")
    cd ./KernelSU
    cp $SUSFS4KSU/kernel_patches/KernelSU/10_enable_susfs_for_ksu.patch ./
    # 兼容旧版补丁：仅当补丁仍只修改 kernel/Makefile 时，才替换为 kernel/Kbuild
    if grep -q '^diff --git a/kernel/Makefile b/kernel/Makefile' ./10_enable_susfs_for_ksu.patch \
      && ! grep -q '^diff --git a/kernel/Kbuild b/kernel/Kbuild' ./10_enable_susfs_for_ksu.patch; then
      sed -i 's|kernel/Makefile|kernel/Kbuild|g' ./10_enable_susfs_for_ksu.patch
    fi
    patch -p1 --forward < 10_enable_susfs_for_ksu.patch || true

    cd ..
    ;;
  "Next"|"SukiSU"|"SukiSU(40726)"|"SukiSU(40548)"|"ReSukiSU")
    echo "Next/SukiSU/SukiSU(40726)/SukiSU(40548)/ReSukiSU 使用内置 SUSFS 支持"
    ;;
esac

cd $KERNEL_ROOT/common
CURRENT_SUB="$SUB_LEVEL"
if [[ ! "$CURRENT_SUB" =~ ^[0-9]+$ ]]; then
  CURRENT_SUB=99999
fi

# 兜底兼容 SUSFS 上游误将 5.15+ 的 set_nameidata 四参数调用用于 Android 12 5.10，上游修复后自动跳过
if [[ "$ANDROID_VERSION" == "android12" && "$KERNEL_VERSION" == "5.10" ]] \
  && grep -qF 'static void set_nameidata(struct nameidata *p, int dfd, struct filename *name)' fs/namei.c \
  && grep -qF 'set_nameidata(nd, old_dfd, fake_filename, NULL);' "$SUSFS_PATCH"; then
  echo "检测到 Android 12 5.10 set_nameidata 三参数接口，修正 SUSFS 补丁中的四参数调用"
  sed -i 's/set_nameidata(nd, old_dfd, fake_filename, NULL);/set_nameidata(nd, old_dfd, fake_filename);/g' "$SUSFS_PATCH"
fi

# 兼容 5.10.66~5.10.209、5.15.74~5.15.144 和 6.1.25~6.1.68 缺少 VMA padding 接口
if grep -qF 'VMA_PAD_START(vma)' "$SUSFS_PATCH" \
  && ! grep -Rqs 'VMA_PAD_START' ./include/linux; then
  echo "目标内核未提供 VMA_PAD_START，使用 vma->vm_end 兼容 SUSFS OPEN_REDIRECT"
  sed -i 's/VMA_PAD_START(vma)/vma->vm_end/g' "$SUSFS_PATCH"
fi

# 在应用主补丁前临时调整源码上下文，替代已归档的 wild 修复小补丁
if [[ "$ANDROID_VERSION" == "android12" && "$KERNEL_VERSION" == "5.10" ]]; then
  if [[ "$CURRENT_SUB" -le 43 ]]; then
    echo "临时调整 Android 12 5.10 base.c 上下文"
    perl -i -pe 's/(int|size_t)\s+this_len\s*=\s*min_t\s*\(\s*\1\s*,/size_t this_len = min_t(size_t,/;' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -le 117 ]]; then
    echo "临时调整 Android 12 5.10 fdinfo.c 上下文"
    sed -i '/^[[:space:]]*\/\*$/,/^[[:space:]]*u32 mask = mark->mask & IN_ALL_EVENTS;$/d' fs/notify/fdinfo.c
    perl -i -pe 's/\bmask,\s*mark->ignored_mask/inotify_mark_user_mask(mark)/g' fs/notify/fdinfo.c
    perl -i -pe 's/ignored_mask:%x/ignored_mask:0/g' fs/notify/fdinfo.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android13" && "$KERNEL_VERSION" == "5.10" ]]; then
  if [[ "$CURRENT_SUB" -le 107 ]]; then
    echo "临时调整 Android 13 5.10 fdinfo.c 上下文"
    sed -i '/^[[:space:]]*\/\*$/,/^[[:space:]]*u32 mask = mark->mask & IN_ALL_EVENTS;$/d' fs/notify/fdinfo.c
    perl -i -pe 's/\bmask,\s*mark->ignored_mask/inotify_mark_user_mask(mark)/g' fs/notify/fdinfo.c
    perl -i -pe 's/ignored_mask:%x/ignored_mask:0/g' fs/notify/fdinfo.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android13" && "$KERNEL_VERSION" == "5.15" ]]; then
  if [[ "$CURRENT_SUB" -le 41 ]]; then
    echo "临时调整 Android 13 5.15 namespace.c/open.c/fdinfo.c 上下文"
    if ! grep -qF '#include <linux/mnt_idmapping.h>' fs/namespace.c; then
      sed -i '/^#include <linux\/shmem_fs.h>$/a #include <linux/mnt_idmapping.h>' fs/namespace.c
    fi
    if ! grep -qF '#include <linux/mnt_idmapping.h>' fs/open.c; then
      sed -i '/^#include <linux\/compat.h>$/a #include <linux/mnt_idmapping.h>' fs/open.c
    fi
    sed -i '/^[[:space:]]*\/\*$/,/^[[:space:]]*u32 mask = mark->mask & IN_ALL_EVENTS;$/d' fs/notify/fdinfo.c
    perl -i -pe 's/\bmask,\s*mark->ignored_mask/inotify_mark_user_mask(mark)/g' fs/notify/fdinfo.c
    perl -i -pe 's/ignored_mask:%x/ignored_mask:0/g' fs/notify/fdinfo.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android14" && "$KERNEL_VERSION" == "6.1" ]]; then
  if [[ "$CURRENT_SUB" -le 25 ]] && ! grep -qF '#include <trace/hooks/sched.h>' fs/proc/base.c; then
    echo "临时调整 Android 14 6.1 sched.h 上下文"
    sed -i '/^#include <trace\/events\/oom.h>$/a #include <trace/hooks/sched.h>' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -le 141 ]] && ! grep -qF '#include <linux/dma-buf.h>' fs/proc/base.c; then
    echo "临时调整 Android 14 6.1 dma-buf.h 上下文"
    sed -i '/^#include <linux\/cpufreq_times.h>$/a #include <linux/dma-buf.h>' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -ge 157 ]]; then
    echo "临时调整 Android 14 6.1 namespace.c 上下文"
    sed -i '/^#include <trace\/hooks\/blk.h>$/d' fs/namespace.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android15" && "$KERNEL_VERSION" == "6.6" ]]; then
  if [[ "$CURRENT_SUB" -le 30 ]] && ! grep -qF 'last_vma_end = vma->vm_end;' fs/proc/task_mmu.c; then
    echo "临时调整 Android 15 6.6 task_mmu.c 上下文"
    sed -i '/smap_gather_stats(vma, &mss, last_vma_end);/a\last_vma_end = vma->vm_end;' fs/proc/task_mmu.c
  fi
  if [[ "$CURRENT_SUB" -le 92 ]] && ! grep -qF '#include <linux/dma-buf.h>' fs/proc/base.c; then
    echo "临时调整 Android 15 6.6 base.c 上下文"
    sed -i '/^#include <linux\/cpufreq_times.h>$/a #include <linux/dma-buf.h>' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -le 57 ]] && ! grep -qF '#include <linux/zswap.h>' mm/memory.c; then
    echo "临时调整 Android 15 6.6 memory.c 上下文"
    sed -i '/^#include <linux\/sched\/sysctl.h>$/a #include <linux/zswap.h>' mm/memory.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android16" && "$KERNEL_VERSION" == "6.12" ]]; then
  if [[ "$CURRENT_SUB" -ge 58 ]]; then
    echo "临时调整 Android 16 6.12 exec.c 上下文"
    sed -i '/^#include <linux\/dma-buf.h>$/d' fs/exec.c
  fi
fi

patch -p1 < "$SUSFS_PATCH" || true

# 主补丁应用后立即统计冲突，提前暴露补丁失配（无需等到编译失败再翻产物）
SUSFS_REJ_COUNT=$(find . -name '*.rej' | wc -l)
if [ "$SUSFS_REJ_COUNT" -gt 0 ]; then
  echo "::warning title=SUSFS 补丁冲突::SUSFS 主补丁产生了 ${SUSFS_REJ_COUNT} 个 .rej 冲突文件，可能导致后续编译失败（详见 Rejects 产物）"
  find . -name '*.rej' -print
fi

# 主补丁应用后还原临时上下文，避免无关源码差异保留到最终产物
if [[ "$ANDROID_VERSION" == "android12" && "$KERNEL_VERSION" == "5.10" ]]; then
  if [[ "$CURRENT_SUB" -le 43 ]]; then
    echo "还原 Android 12 5.10 base.c 临时调整"
    sed -i 's/^size_t this_len = min_t(size_t, count, PAGE_SIZE);$/int this_len = min_t(int, count, PAGE_SIZE);/' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -le 117 ]]; then
    echo "还原 Android 12 5.10 fdinfo.c 临时调整"
    perl -i -pe 's/^(\s+if \(inode\) \{)/$1\n\t\t\/\*\n\t\t * IN_ALL_EVENTS represents all of the mask bits\n\t\t * that we expose to userspace.  There is at\n\t\t * least one bit (FS_EVENT_ON_CHILD) which is\n\t\t * used only internally to the kernel.\n\t\t *\/\n\t\tu32 mask = mark->mask & IN_ALL_EVENTS;/m' fs/notify/fdinfo.c
    perl -i -pe 's/\binotify_mark_user_mask\(mark\)/mask, mark->ignored_mask/g' fs/notify/fdinfo.c
    perl -i -pe 's/ignored_mask:0/ignored_mask:%x/g' fs/notify/fdinfo.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android13" && "$KERNEL_VERSION" == "5.10" ]]; then
  if [[ "$CURRENT_SUB" -le 107 ]]; then
    echo "还原 Android 13 5.10 fdinfo.c 临时调整"
    perl -i -pe 's/^(\s+if \(inode\) \{)/$1\n\t\t\/\*\n\t\t * IN_ALL_EVENTS represents all of the mask bits\n\t\t * that we expose to userspace.  There is at\n\t\t * least one bit (FS_EVENT_ON_CHILD) which is\n\t\t * used only internally to the kernel.\n\t\t *\/\n\t\tu32 mask = mark->mask & IN_ALL_EVENTS;/m' fs/notify/fdinfo.c
    perl -i -pe 's/\binotify_mark_user_mask\(mark\)/mask, mark->ignored_mask/g' fs/notify/fdinfo.c
    perl -i -pe 's/ignored_mask:0/ignored_mask:%x/g' fs/notify/fdinfo.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android13" && "$KERNEL_VERSION" == "5.15" ]]; then
  if [[ "$CURRENT_SUB" -le 41 ]]; then
    echo "还原 Android 13 5.15 临时调整"
    sed -i '/#include <linux\/mnt_idmapping.h>$/d' fs/namespace.c
    sed -i '/#include <linux\/mnt_idmapping.h>$/d' fs/open.c
    perl -i -pe 's/^(\s+if \(inode\) \{)/$1\n\t\t\/\*\n\t\t * IN_ALL_EVENTS represents all of the mask bits\n\t\t * that we expose to userspace.  There is at\n\t\t * least one bit (FS_EVENT_ON_CHILD) which is\n\t\t * used only internally to the kernel.\n\t\t *\/\n\t\tu32 mask = mark->mask & IN_ALL_EVENTS;/m' fs/notify/fdinfo.c
    perl -i -pe 's/\binotify_mark_user_mask\(mark\)/mask, mark->ignored_mask/g' fs/notify/fdinfo.c
    perl -i -pe 's/ignored_mask:0/ignored_mask:%x/g' fs/notify/fdinfo.c
    sed -i 's|i_uid_into_mnt(i_user_ns(&fi->inode), &fi->inode).val|i_uid_into_mnt(\&init_user_ns, \&fi->inode).val|g' fs/susfs.c
    sed -i 's|i_uid_into_mnt(i_user_ns(inode), inode).val|i_uid_into_mnt(\&init_user_ns, inode).val|g' fs/susfs.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android14" && "$KERNEL_VERSION" == "6.1" ]]; then
  if [[ "$CURRENT_SUB" -le 25 ]]; then
    sed -i '/^#include <trace\/hooks\/sched.h>$/d' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -le 141 ]]; then
    echo "还原 Android 14 6.1 base.c 临时调整"
    sed -i '/^#include <linux\/dma-buf.h>$/d' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -ge 157 ]] && ! grep -qF '#include <trace/hooks/blk.h>' fs/namespace.c; then
    echo "还原 Android 14 6.1 namespace.c 临时调整"
    sed -i '/^#include "internal.h"$/a #include <trace/hooks/blk.h>' fs/namespace.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android15" && "$KERNEL_VERSION" == "6.6" ]]; then
  if [[ "$CURRENT_SUB" -le 92 ]]; then
    echo "还原 Android 15 6.6 base.c 临时调整"
    sed -i '/^#include <linux\/dma-buf.h>$/d' fs/proc/base.c
  fi
  if [[ "$CURRENT_SUB" -le 57 ]]; then
    echo "还原 Android 15 6.6 memory.c 临时调整"
    sed -i '/^#include <linux\/zswap.h>$/d' mm/memory.c
  fi
fi

if [[ "$ANDROID_VERSION" == "android16" && "$KERNEL_VERSION" == "6.12" ]]; then
  if [[ "$CURRENT_SUB" -ge 58 ]] && ! grep -qF '#include <linux/dma-buf.h>' fs/exec.c; then
    echo "还原 Android 16 6.12 exec.c 临时调整"
    sed -i '0,/^#include /s//#include <linux\/dma-buf.h>\n&/' fs/exec.c
  fi
fi

if [ "$KSU_VARIANT" == "Official" ] || [ "$KSU_VARIANT" == "Next" ] || [ "$KSU_VARIANT" == "SukiSU" ] || [ "$KSU_VARIANT" == "SukiSU(40726)" ] || [ "$KSU_VARIANT" == "SukiSU(40548)" ] || [ "$KSU_VARIANT" == "ReSukiSU" ]; then
  fix_namespace_susfs_mount_decls() {
    local label="$1"
    local marker_pattern="$2"

    if ! grep -q "$marker_pattern" ./fs/namespace.c; then
      return
    fi

    if ! grep -qF '#include <linux/susfs_def.h>' ./fs/namespace.c; then
      echo "检测到 ${label} namespace.c 缺少 susfs_def.h，注入声明..."
      if grep -qF '#include <linux/mnt_idmapping.h>' ./fs/namespace.c; then
        sed -i '/#include <linux\/mnt_idmapping.h>/a #ifdef CONFIG_KSU_SUSFS_SUS_MOUNT\n#include <linux\/susfs_def.h>\n#endif' ./fs/namespace.c
      elif grep -qF '#include <linux/shmem_fs.h>' ./fs/namespace.c; then
        sed -i '/#include <linux\/shmem_fs.h>/a #ifdef CONFIG_KSU_SUSFS_SUS_MOUNT\n#include <linux\/susfs_def.h>\n#endif' ./fs/namespace.c
      else
        sed -i '0,/^#include /s//#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT\n#include <linux\/susfs_def.h>\n#endif\n&/' ./fs/namespace.c
      fi
    fi

    if ! grep -q 'extern bool susfs_is_current_ksu_domain' ./fs/namespace.c; then
      echo "检测到 ${label} namespace.c 缺少 SUSFS mount extern，注入声明..."
      sed -i '/#include "internal.h"/a \\n#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT\nextern bool susfs_is_current_ksu_domain(void);\nextern struct static_key_true susfs_is_sdcard_android_data_not_decrypted;\n\n#define CL_COPY_MNT_NS BIT(25)\n\n#endif' ./fs/namespace.c
    fi
  }

  fix_missing_vm_flags_clear() {
    if [[ "$OS_PATCH_LEVEL" == "2024-11" ]] && grep -qF 'vm_flags_clear(new_vma, VM_PAD_MASK);' ./mm/mmap.c; then
      sed -i 's/vm_flags_clear(new_vma, VM_PAD_MASK);/new_vma->vm_flags \&= ~VM_PAD_MASK;/' ./mm/mmap.c
    fi
  }

  fix_task_mmu_show_pad() {
    local max_sub="$1"
    local excluded_patch_level="${2:-}"

    if [[ "$CURRENT_SUB" -le "$max_sub" ]] \
      && { [[ -z "$excluded_patch_level" ]] || [[ "$OS_PATCH_LEVEL" != "$excluded_patch_level" ]]; }; then
      sed -i -e 's/goto show_pad;/return 0;/' ./fs/proc/task_mmu.c
    fi
  }

  # Android 12 - 5.10 修复
  if [[ "$ANDROID_VERSION" == "android12" && "$KERNEL_VERSION" == "5.10" ]]; then
    # 修复 2024-11 分支: mmap.c 调用了 vm_flags_clear()，但同分支 mm.h 未提供 helper
    fix_missing_vm_flags_clear
    fix_task_mmu_show_pad 209
  fi

  # Android 13 - 5.15 修复
  if [[ "$ANDROID_VERSION" == "android13" && "$KERNEL_VERSION" == "5.15" ]]; then
    # 修复 2024-11 分支: mmap.c 调用了 vm_flags_clear()，但同分支 mm.h 未提供 helper
    fix_missing_vm_flags_clear
    fix_task_mmu_show_pad 148 "2024-05"
    # 修复 5.15 LTS: task_mmu.c 头部 hunk 失配，导致 SUSFS 宏缺少声明
    if grep -q 'SUSFS_IS_INODE_SUS_MAP\|SUSFS_IS_INODE_OPEN_REDIRECT' ./fs/proc/task_mmu.c && ! grep -qF '#include <linux/susfs_def.h>' ./fs/proc/task_mmu.c; then
      if grep -qF '#include <linux/pkeys.h>' ./fs/proc/task_mmu.c; then
        sed -i '/#include <linux\/pkeys.h>/a #if defined(CONFIG_KSU_SUSFS_SUS_KSTAT) || defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n#include <linux\/susfs_def.h>\n#endif' ./fs/proc/task_mmu.c
      elif grep -qF '#include <linux/uaccess.h>' ./fs/proc/task_mmu.c; then
        sed -i '/#include <linux\/uaccess.h>/a #if defined(CONFIG_KSU_SUSFS_SUS_KSTAT) || defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n#include <linux\/susfs_def.h>\n#endif' ./fs/proc/task_mmu.c
      else
        sed -i '0,/^#include /s//#if defined(CONFIG_KSU_SUSFS_SUS_KSTAT) || defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n#include <linux\/susfs_def.h>\n#endif\n&/' ./fs/proc/task_mmu.c
      fi
      echo "已修复 Android 13 5.15 task_mmu.c 缺少 susfs_def.h 问题"
    fi
    # 修复 5.15 LTS: namespace.c 头部 hunk 失配，导致 SUSFS mount 符号缺少声明
    fix_namespace_susfs_mount_decls "Android 13 5.15" 'DEFAULT_KSU_MNT_ID\|VFSMOUNT_MNT_FLAGS_KSU_UNSHARED_MNT\|CL_COPY_MNT_NS'
  fi

  # Android 14 - 6.1 修复
  if [[ "$ANDROID_VERSION" == "android14" && "$KERNEL_VERSION" == "6.1" ]]; then
    if grep -q 'susfs_is_current_proc_umounted\|SUSFS_IS_INODE_SUS_MAP\|SUSFS_IS_INODE_OPEN_REDIRECT' ./fs/proc/base.c && ! grep -qF '#include <linux/susfs_def.h>' ./fs/proc/base.c; then
      if grep -qF '#include <linux/dma-buf.h>' ./fs/proc/base.c; then
        sed -i '/#include <linux\/dma-buf.h>/a #if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n#include <linux\/susfs_def.h>\n#endif' ./fs/proc/base.c
      else
        sed -i '/#include <linux\/cpufreq_times.h>/a #if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n#include <linux\/susfs_def.h>\n#endif' ./fs/proc/base.c
      fi
    fi
    fix_task_mmu_show_pad 75 "2024-05"
    # 修复 6.1 LTS: namespace.c 头部 hunk 失配，导致 SUSFS mount 符号缺少声明
    fix_namespace_susfs_mount_decls "Android 14 6.1" 'DEFAULT_KSU_MNT_ID\|susfs_mnt_id_ida'
  fi

  # Android 15 - 6.6 修复
  if [[ "$ANDROID_VERSION" == "android15" && "$KERNEL_VERSION" == "6.6" ]]; then
    # 修复 6.6.50~6.6.97: fs/proc/base.c 头部 hunk 失配，导致 susfs_def.h 漏打
    if grep -q 'AS_FLAGS_SUS_MAP\|susfs_is_current_proc_umounted\|SUSFS_IS_INODE_SUS_MAP\|SUSFS_IS_INODE_OPEN_REDIRECT' ./fs/proc/base.c && ! grep -qF 'susfs_def.h' ./fs/proc/base.c; then
      if ! grep -qF '#include <linux/dma-buf.h>' ./fs/proc/base.c; then
        sed -i '/#include <linux\/cpufreq_times.h>/a #include <linux\/dma-buf.h>' ./fs/proc/base.c
      fi
      sed -i '/#include <linux\/dma-buf.h>/a #endif' ./fs/proc/base.c
      sed -i '/#include <linux\/dma-buf.h>/a #include <linux\/susfs_def.h>' ./fs/proc/base.c
      sed -i '/#include <linux\/dma-buf.h>/a #if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)' ./fs/proc/base.c
    fi
    # 修复 6.6 早期分支: memory.c 头部上下文缺少 zswap.h，导致 SUSFS 头文件 hunk 漏打
    if grep -q 'SUSFS_IS_INODE_SUS_MAP' ./mm/memory.c && ! grep -qF '#include <linux/susfs_def.h>' ./mm/memory.c; then
      if grep -qF '#include <linux/zswap.h>' ./mm/memory.c; then
        sed -i '/#include <linux\/zswap.h>/a #ifdef CONFIG_KSU_SUSFS_SUS_MAP\n#include <linux\/susfs_def.h>\n#endif' ./mm/memory.c
      else
        sed -i '/#include <linux\/sched\/sysctl.h>/a #ifdef CONFIG_KSU_SUSFS_SUS_MAP\n#include <linux\/susfs_def.h>\n#endif' ./mm/memory.c
      fi
    fi
    # 修复老版 SukiSU 6.6.50~6.6.58: task_mmu.c 打入 SUSFS 后使用 vma，但旧源码没有对应声明
    if [[ -n "$LEGACY_SUKISU_CONFIG" && "$CURRENT_SUB" -ge 50 && "$CURRENT_SUB" -le 58 ]] \
      && grep -qF 'vma = find_vma(mm, start_vaddr);' ./fs/proc/task_mmu.c; then
      TASK_MMU_PATCH="$KERNEL_PATCHES/wild/archived/susfs_fix_patches/v2.1.0/a15-6.6/task_mmu.c.patch"
      if [ ! -f "$TASK_MMU_PATCH" ]; then
        echo "::error::补丁不存在: $TASK_MMU_PATCH"
        exit 1
      fi
      cp "$TASK_MMU_PATCH" ./
      if patch -p1 --dry-run < task_mmu.c.patch >/dev/null 2>&1; then
        patch -p1 --no-backup-if-mismatch < task_mmu.c.patch
        echo "已应用 Android 15 6.6.50~6.6.58 task_mmu.c 归档修复补丁"
      else
        echo "Android 15 6.6.50~6.6.58 task_mmu.c 归档修复补丁已应用或当前上下文不匹配，跳过"
      fi
    fi

  fi

  # Android 16 - 6.12 修复
  if [[ "$ANDROID_VERSION" == "android16" && "$KERNEL_VERSION" == "6.12" ]]; then
    # 修复 setuid_hook.c 重复定义
    SETUID_HOOK="$KERNEL_ROOT/common/drivers/kernelsu/setuid_hook.c"
    if [ -f "$SETUID_HOOK" ]; then
      sed -i 's/defined(CONFIG_KSU_MANUAL_HOOK))/!defined(CONFIG_KSU_SUSFS) \&\& defined(CONFIG_KSU_MANUAL_HOOK))/' "$SETUID_HOOK"
      echo "已修复 setuid_hook.c 重复定义问题"
    fi

    # 修复 6.12.58+: exec.c 头部上下文变化导致 SUSFS 补丁漏掉 susfs_def.h
    if grep -q 'susfs_is_current_proc_umounted' ./fs/exec.c && ! grep -qF '#include <linux/susfs_def.h>' ./fs/exec.c; then
      if grep -qF '#include <linux/dma-buf.h>' ./fs/exec.c; then
        sed -i '/#include <linux\/dma-buf.h>/a #ifdef CONFIG_KSU_SUSFS\n#include <linux\/susfs_def.h>\n#endif' ./fs/exec.c
      else
        sed -i '/#include <linux\/ksm.h>/a #ifdef CONFIG_KSU_SUSFS\n#include <linux\/susfs_def.h>\n#endif' ./fs/exec.c
      fi
      echo "已修复 exec.c 缺少 susfs_def.h 问题"
    fi
  fi
fi
