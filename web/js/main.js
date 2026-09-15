/**
 * 入口模块：初始化所有子模块，加载并渲染内核数据
 */

import '../scss/main.scss';

import { t, initI18n } from './i18n.js';
import { initTheme } from './theme.js';
import { initModal } from './modal.js';
import { initAnnouncement } from './announcement.js';
import { initSearch, filterActive, getQuery, clearSearch } from './search.js';
import { initTimeConverter } from './time-converter.js';
import { initBackToTop } from './back-to-top.js';
import { showToast } from './toast.js';
import { copyText, fetchJsonFresh, fmt, getStored } from './utils.js';
import { DATA_FILES } from './config.js';
import { buildBranchModel, buildSummary } from './model.js';
import {
  renderTabs, renderPanels, activateBranch, renderReadout,
  initCollapse, initRulerJump, initLegendSync, applyCollapse, readCollapsePref, getModel,
} from './render.js';

initI18n();
initTheme({
  button: document.getElementById('themeToggle'),
  labels: { light: t.light, dark: t.dark },
});
initModal();
initSearch();
var timeConverter = initTimeConverter();
initBackToTop();
initCollapse(getQuery);
initRulerJump(clearSearch);
initLegendSync();

// 复制按钮全局事件委托（参数单、时间转换器共用）
document.addEventListener('click', function (e) {
  var btn = e.target.closest('.copy-btn');
  if (!btn) return;
  var text = btn.dataset.copy;
  if (!text) return;
  copyText(text).then(function () {
    // 连续点击时清掉上一次的还原计时器，避免按钮停在「已复制」
    if (btn._copyTimer) clearTimeout(btn._copyTimer);
    btn.textContent = t.copied;
    btn.classList.add('copied');
    showToast(btn.dataset.copyWhat ? fmt(t.copyToast, { what: btn.dataset.copyWhat }) : t.copyToastGeneric);
    btn._copyTimer = setTimeout(function () {
      btn.textContent = t.copy;
      btn.classList.remove('copied');
      btn._copyTimer = null;
    }, 1200);
  }).catch(function () {});
});

// 从 URL hash 读取初始分支（#android15-6.6）
function keyFromHash() {
  var h = (location.hash || '').replace(/^#/, '');
  return getModel(h) ? h : null;
}

async function loadData() {
  var results = await Promise.allSettled(
    DATA_FILES.map(function (f) {
      return fetchJsonFresh('data/' + f.android + '/' + f.kernel + '.json');
    })
  );

  var models = [];
  for (var i = 0; i < results.length; i++) {
    if (results[i].status === 'fulfilled') {
      models.push(buildBranchModel(results[i].value, DATA_FILES[i]));
    }
  }

  if (models.length === 0) {
    document.getElementById('content').innerHTML =
      '<div class="error"><p>' + t.errorTitle + '</p><p>' + t.errorHint + '</p></div>';
    return;
  }

  renderReadout(buildSummary(models));
  renderTabs(models, function () { filterActive(); });
  renderPanels(models);

  // 每个账册记录 SUSFS 阈值，供参数单状态行使用；并应用弃用折叠偏好
  models.forEach(function (m) {
    var ledger = document.getElementById('ledger-' + m.key);
    if (ledger) {
      ledger.dataset.susfsMin = m.susfsMinKernel;
      applyCollapse(ledger, readCollapsePref(), false);
    }
  });

  // 分支优先级：URL hash > 上次访问的分支 > 最新的分支（最后一个）
  var remembered = getStored('last_branch');
  var initial = keyFromHash() || (getModel(remembered) ? remembered : null) || models[models.length - 1].key;
  activateBranch(initial, false);
  timeConverter.seed();

  window.addEventListener('hashchange', function () {
    var k = keyFromHash();
    if (k) activateBranch(k, false);
  });
}

initAnnouncement();
loadData();
