/**
 * Toast 提示模块
 */

var toastEl = null;
var toastTimer = null;

export function showToast(msg) {
  if (!toastEl) toastEl = document.getElementById('toast');
  if (!toastEl) return;
  // 原生 dialog 位于顶层，body 里的 toast 会被遮罩压住；挂到打开的 dialog 内即可置顶
  var host = document.querySelector('dialog[open]') || document.body;
  if (toastEl.parentNode !== host) host.appendChild(toastEl);
  clearTimeout(toastTimer);
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  toastTimer = setTimeout(function () {
    toastEl.classList.remove('show');
  }, 1800);
}
