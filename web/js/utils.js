/**
 * 通用工具函数
 */

import { RUNTIME_CACHE_KEY } from './config.js';

// 将文本转换为安全的 HTML 内容
export function esc(str) {
  var el = document.createElement('span');
  el.textContent = str == null ? '' : String(str);
  return el.innerHTML;
}

// 复制文本到剪贴板（兼容旧浏览器）
export function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand('copy'); } catch (e) { /* 旧浏览器不支持时静默 */ }
  document.body.removeChild(ta);
  return Promise.resolve();
}

// 给 URL 追加运行时缓存键
export function withCacheKey(url) {
  return url + (url.indexOf('?') === -1 ? '?' : '&') + 'v=' + encodeURIComponent(RUNTIME_CACHE_KEY);
}

// 绕过缓存获取 JSON
export async function fetchJsonFresh(url) {
  var r = await fetch(withCacheKey(url), { cache: 'no-store' });
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.json();
}

// localStorage 在隐私模式或禁用 Cookie 时会抛异常，统一在这里兜底
export function getStored(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}

export function setStored(key, value) {
  try { localStorage.setItem(key, value); } catch (e) { /* 存储不可用时静默 */ }
}

// 是否偏好减少动效
export function prefersReducedMotion() {
  return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}

// 简单模板替换：fmt('{a} / {b}', { a: 1, b: 2 })
export function fmt(template, vars) {
  return String(template).replace(/\{(\w+)\}/g, function (_, key) {
    return vars && vars[key] != null ? vars[key] : '';
  });
}
