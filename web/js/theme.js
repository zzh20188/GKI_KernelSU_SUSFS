/**
 * 主题切换模块（深色 / 浅色）
 * 优先级：localStorage > 系统 prefers-color-scheme > 深色
 * 仪表盘与教程页共用同一个存储键，切换后两页一致
 */

import { getStored, setStored } from './utils.js';

var STORAGE_KEY = 'theme';

// 读取初始主题
export function resolveInitialTheme() {
  var saved = getStored(STORAGE_KEY);
  if (saved === 'dark' || saved === 'light') return saved;
  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) return 'light';
  return 'dark';
}

// 应用主题到根元素
export function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', theme === 'dark' ? '#111214' : '#f7f6f2');
}

/**
 * 初始化主题按钮
 * @param {object} opts  { button: HTMLElement, labels: { light, dark }, onChange }
 */
export function initTheme(opts) {
  var theme = resolveInitialTheme();
  applyTheme(theme);

  var button = opts && opts.button;
  if (!button) return;

  function syncButton() {
    var current = document.documentElement.getAttribute('data-theme');
    var next = current === 'dark' ? 'light' : 'dark';
    var label = opts.labels ? (next === 'light' ? opts.labels.light : opts.labels.dark) : next;
    button.setAttribute('aria-label', label);
    button.setAttribute('title', label);
    button.dataset.theme = current;
  }

  syncButton();

  button.addEventListener('click', function () {
    var current = document.documentElement.getAttribute('data-theme');
    var next = current === 'dark' ? 'light' : 'dark';
    setStored(STORAGE_KEY, next);
    applyTheme(next);
    syncButton();
    if (opts.onChange) opts.onChange(next);
  });
}
