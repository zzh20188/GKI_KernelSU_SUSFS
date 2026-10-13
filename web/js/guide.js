/**
 * 教程页入口：共用主题 / 语言模块，提供目录高亮与截图灯箱
 */

import '../scss/guide.scss';

import { t, lang, initI18n } from './i18n.js';
import { initTheme } from './theme.js';
import { initBackToTop } from './back-to-top.js';

// initI18n 会把标题改成仪表盘标题，教程页需要保留自己的标题
var pageTitle = document.title;
initI18n();
document.title = pageTitle;
initTheme({
  button: document.getElementById('themeToggle'),
  labels: { light: t.light, dark: t.dark },
});
initBackToTop();

// ---- 教程正文双语：中文写在 HTML 里，英文来自 data-en 属性 ----
if (lang === 'en') {
  document.querySelectorAll('[data-en]').forEach(function (el) {
    el.innerHTML = el.getAttribute('data-en');
  });
  var titleEn = document.documentElement.getAttribute('data-title-en');
  if (titleEn) document.title = titleEn;
}

// ---- 灯箱 ----
var lightbox = document.getElementById('lightbox');
var lightboxImg = document.getElementById('lightboxImg');

document.addEventListener('click', function (e) {
  var shot = e.target.closest('.shot');
  if (!shot || !lightbox) return;
  var img = shot.querySelector('img');
  if (!img) return;
  lightboxImg.src = img.currentSrc || img.src;
  lightboxImg.alt = img.alt || '';
  if (typeof lightbox.showModal === 'function') lightbox.showModal();
  else lightbox.setAttribute('open', '');
});

if (lightbox) {
  lightbox.addEventListener('click', function () {
    if (typeof lightbox.close === 'function') lightbox.close();
    else lightbox.removeAttribute('open');
  });
}

// ---- 目录高亮：滚动时标出当前步骤 ----
var tocLinks = Array.prototype.slice.call(document.querySelectorAll('.toc a[href^="#"]'));
var steps = tocLinks.map(function (a) { return document.querySelector(a.getAttribute('href')); }).filter(Boolean);

if ('IntersectionObserver' in window && steps.length) {
  var current = null;
  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) current = entry.target.id;
    });
    tocLinks.forEach(function (a) {
      var active = a.getAttribute('href') === '#' + current;
      a.classList.toggle('is-active', active);
      if (active) a.setAttribute('aria-current', 'true');
      else a.removeAttribute('aria-current');
    });
  }, { rootMargin: '-20% 0px -70% 0px', threshold: 0 });
  steps.forEach(function (s) { observer.observe(s); });
}
