/**
 * 全局配置常量
 */

// 内核数据文件配置
export const DATA_FILES = [
  { android: 'android12', kernel: '5.10', label: 'android12 / 5.10' },
  { android: 'android13', kernel: '5.15', label: 'android13 / 5.15' },
  { android: 'android14', kernel: '6.1',  label: 'android14 / 6.1'  },
  { android: 'android15', kernel: '6.6',  label: 'android15 / 6.6'  },
  { android: 'android16', kernel: '6.12', label: 'android16 / 6.12' },
];

// 运行时缓存键（每次加载页面生成新的，防止缓存）
export var RUNTIME_CACHE_KEY = Date.now().toString(36);

// SUSFS 兼容性：sublevel >= 阈值表示可直接使用 susfs4ksu 补丁
export var SUSFS_COMPAT_MIN = {
  '5.10': 218,
  '5.15': 148,
  '6.1': 145,
  '6.6': 98,
  '6.12': 0,
};
