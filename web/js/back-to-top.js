/**
 * 回到顶部按钮模块
 */

import { prefersReducedMotion } from './utils.js';

export function initBackToTop() {
  var backToTop = document.getElementById('backToTop');
  if (!backToTop) return;

  var ticking = false;
  function update() {
    ticking = false;
    backToTop.classList.toggle('visible', window.scrollY > 320);
  }

  window.addEventListener('scroll', function () {
    if (!ticking) {
      ticking = true;
      requestAnimationFrame(update);
    }
  }, { passive: true });

  backToTop.addEventListener('click', function () {
    window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
  });

  update();
}
