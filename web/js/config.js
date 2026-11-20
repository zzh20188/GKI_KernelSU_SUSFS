/**
 * 全局配置常量
 */

// 内核数据文件配置（顺序即分支导航顺序）
// 探测数据目录（工具分支 susfs-probe 全量编译原始补丁后的逐条结论）
export var PROBE_DIR = 'data/susfs_probe';

export const DATA_FILES = [
  { android: 'android12', kernel: '5.10', label: 'android12 / 5.10' },
  { android: 'android13', kernel: '5.15', label: 'android13 / 5.15' },
  { android: 'android14', kernel: '6.1',  label: 'android14 / 6.1'  },
  { android: 'android15', kernel: '6.6',  label: 'android15 / 6.6'  },
  { android: 'android16', kernel: '6.12', label: 'android16 / 6.12' },
];

// 运行时缓存键（每次加载页面生成新的，防止缓存）
export var RUNTIME_CACHE_KEY = Date.now().toString(36);

// SUSFS 兼容性兜底阈值：sublevel >= 阈值表示可直接使用 susfs4ksu 补丁。
// 只在 data/susfs_probe/<android>-<kernel>.json 缺失时使用；有探测数据时以探测结论为准
export var SUSFS_COMPAT_MIN = {
  '5.10': 218,
  '5.15': 148,
  '6.1': 145,
  '6.6': 98,
  '6.12': 0,
};

// 外部链接
export var LINKS = {
  repo: 'https://github.com/zzh20188/GKI_KernelSU_SUSFS',
  susfs: 'https://gitlab.com/simonpunk/susfs4ksu',
  aospCommon: 'https://android.googlesource.com/kernel/common',
  aospManifest: 'https://android.googlesource.com/kernel/manifest',
};

// repo init 使用的 repo 工具版本
export var REPO_REV = 'v2.16';
