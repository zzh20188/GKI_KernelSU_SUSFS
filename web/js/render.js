/**
 * 渲染模块：分支页签、章首页（大数字 / 规格表 / 图注 / 刻度尺）、账册列表
 */

import { t, lang } from './i18n.js';
import { esc, fmt, prefersReducedMotion, getStored, setStored } from './utils.js';

// 探测来源说明（变体 / 日期 / susfs4ksu 提交），供图注与参数单复用
function probeSource(m) {
  if (!m.probe) return '';
  return fmt(t.probeSource, {
    variant: m.probe.variant || '?',
    date: m.probe.probedAt || '?',
    commit: m.probe.commitShort || '?',
  });
}

var activeKey = null;
// 用无原型对象存分支模型，避免 #constructor 之类的 hash 命中 Object.prototype
var modelsByKey = Object.create(null);
var onActivate = null;
var mobileQuery = window.matchMedia ? window.matchMedia('(max-width: 767px)') : null;

function isMobile() {
  return !!(mobileQuery && mobileQuery.matches);
}

// ---------- 分支页签 ----------

export function renderTabs(models, activateCb) {
  onActivate = activateCb;
  var tabsEl = document.getElementById('tabs');
  tabsEl.innerHTML = '';

  models.forEach(function (m) {
    modelsByKey[m.key] = m;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'tab';
    btn.id = 'tab-' + m.key;
    btn.setAttribute('role', 'tab');
    btn.setAttribute('aria-selected', 'false');
    btn.setAttribute('aria-controls', 'panel-' + m.key);
    btn.setAttribute('aria-label', m.meta.android + ' · ' + m.meta.kernel);
    btn.setAttribute('tabindex', '-1');
    btn.dataset.key = m.key;
    btn.innerHTML =
      '<span class="tab__android tab__android--full" aria-hidden="true">' + esc(m.meta.android) + '</span>' +
      '<span class="tab__android tab__android--short" aria-hidden="true">' + esc(m.meta.android.replace(/^android/, 'A')) + '</span>' +
      '<span class="tab__num" aria-hidden="true">' + esc(m.meta.kernel) + '</span>' +
      buildMicroRuler(m);
    btn.addEventListener('click', function () { activateBranch(m.key, true); });
    tabsEl.appendChild(btn);
  });

  var indicator = document.createElement('div');
  indicator.className = 'tab-indicator';
  indicator.setAttribute('aria-hidden', 'true');
  tabsEl.appendChild(indicator);

  // 方向键切换分支
  tabsEl.addEventListener('keydown', function (e) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight' && e.key !== 'Home' && e.key !== 'End') return;
    var tabs = Array.prototype.slice.call(tabsEl.querySelectorAll('.tab'));
    var idx = tabs.findIndex(function (b) { return b.dataset.key === activeKey; });
    var next = idx;
    if (e.key === 'ArrowLeft') next = (idx - 1 + tabs.length) % tabs.length;
    if (e.key === 'ArrowRight') next = (idx + 1) % tabs.length;
    if (e.key === 'Home') next = 0;
    if (e.key === 'End') next = tabs.length - 1;
    e.preventDefault();
    activateBranch(tabs[next].dataset.key, true);
    tabs[next].focus();
  });

  window.addEventListener('resize', function () { moveIndicator(); });
  // 字体异步到达后页签宽度会变，重算指示线位置
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () { moveIndicator(); });
  }
}

// 页签底部 2px 微刻度尺：分支整条时间线的横向缩略
function buildMicroRuler(m) {
  if (!m.months.length) return '';
  var cells = m.months.map(function (mo) {
    var cls = [];
    if (mo.row) cls.push('on');
    if (mo.deprecated) cls.push('dep');
    if (mo.susfs) cls.push('susfs');
    if (mo.latest) cls.push('latest');
    return '<span class="' + cls.join(' ') + '"></span>';
  }).join('');
  return '<span class="tab__ruler" aria-hidden="true">' + cells + '</span>';
}

function moveIndicator() {
  var indicator = document.querySelector('.tab-indicator');
  var tab = activeKey ? document.getElementById('tab-' + activeKey) : null;
  if (!indicator || !tab) return;
  var padRight = 16;
  var width = Math.max(tab.offsetWidth - padRight, 24);
  if (isMobile()) width = tab.offsetWidth;
  indicator.style.width = width + 'px';
  indicator.style.transform = 'translateX(' + tab.offsetLeft + 'px)';
}

// 供搜索等改变导航布局的操作调用
export function refreshIndicator() {
  moveIndicator();
}

export function activateBranch(key, updateHash) {
  var m = getModel(key);
  if (!m) return;
  activeKey = key;

  document.querySelectorAll('.tab').forEach(function (b) {
    var on = b.dataset.key === key;
    b.setAttribute('aria-selected', on ? 'true' : 'false');
    b.setAttribute('tabindex', on ? '0' : '-1');
  });
  document.querySelectorAll('.branch-panel').forEach(function (p) {
    p.classList.toggle('is-active', p.id === 'panel-' + key);
  });

  // 时间转换器只有一个实例，随当前分支移动到章首页的工具槽
  var tools = document.getElementById('tools');
  var slot = document.querySelector('#panel-' + cssEscape(key) + ' .tools-slot');
  if (tools && slot && tools.parentNode !== slot) slot.appendChild(tools);

  moveIndicator();

  var tab = document.getElementById('tab-' + key);
  if (tab && tab.scrollIntoView && isMobile()) {
    tab.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  if (updateHash) {
    try { history.replaceState(null, '', '#' + key); } catch (e) { /* file:// 下可能失败 */ }
  }
  setStored('last_branch', key);
  if (onActivate) onActivate(m);
}

export function getActiveKey() { return activeKey; }

export function getModel(key) {
  if (typeof key !== 'string') return null;
  return Object.prototype.hasOwnProperty.call(modelsByKey, key) ? modelsByKey[key] : null;
}

function cssEscape(s) {
  return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/([.:])/g, '\\$1');
}

// ---------- 面板 ----------

export function renderPanels(models) {
  var content = document.getElementById('content');
  content.innerHTML = '';
  models.forEach(function (m) {
    var panel = document.createElement('section');
    panel.className = 'branch-panel';
    panel.id = 'panel-' + m.key;
    panel.setAttribute('role', 'tabpanel');
    panel.setAttribute('aria-labelledby', 'tab-' + m.key);
    panel.innerHTML = buildChapter(m) + buildLedger(m);
    content.appendChild(panel);
  });

  // 时间转换器实例放进第一个面板的槽里，之后随分支移动
  var first = content.querySelector('.tools-slot');
  if (first) first.innerHTML = buildTools();
}

// ---------- 章首页 ----------

function branchStatus(m) {
  if (m.fullyDeprecated) return { cls: 'is-deprecated', text: t.statusDeprecated };
  if (m.deprecatedCount > 0) return { cls: 'is-partly', text: t.statusPartly };
  return { cls: 'is-active', text: t.statusActive };
}

function buildChapter(m) {
  var status = branchStatus(m);
  var ltsBranch = m.meta.android + '-' + m.meta.kernel + '-lts';
  var ltsSusfs = m.ltsSusfs;
  var ltsProbe = m.ltsSusfsState && m.ltsSusfsState.probed ? m.ltsSusfsState : null;

  var ltsHtml = m.lts
    ? '<button type="button" class="chapter__lts" data-lts="1" data-android="' + esc(m.meta.android) + '" data-kernel="' + esc(m.meta.kernel) + '" data-sublevel="' + esc(m.ltsSublevel) + '" data-patch="lts" data-version="' + esc(m.lts) + '" data-susfs="' + (ltsSusfs ? '1' : '0') + '"' + (ltsProbe ? ' data-probe="' + (ltsProbe.susfs ? 'clean' : ltsProbe.rej ? 'built_with_rej' : 'failed') + '" data-probe-rej="' + ltsProbe.rejCount + '"' : '') + ' title="' + esc(fmt(t.ltsHint, { branch: ltsBranch })) + '">' +
        '<span class="lts__label">' + esc(t.lts) + '</span>' +
        '<span class="lts__value">' + esc(m.lts) + '</span>' +
        '<span class="lts__arrow" aria-hidden="true">→</span>' +
      '</button>'
    : '';

  var spec =
    '<dl class="spec">' +
      specRow(t.releases, String(m.count), true) +
      specRow(t.first, m.first ? m.first.date : '—') +
      specRow(t.latest, m.last ? m.last.date : '—') +
      specRow(t.latestKernel, m.last ? m.last.kernel : '—') +
    '</dl>';

  // 图注：桌面常显；手机默认收起以让列表首行更早出现
  var legend = '<details class="legend-wrap"' + (isMobile() ? '' : ' open') + '>' +
    '<summary class="legend-wrap__summary">' + esc(t.legend) + '</summary>' +
    '<div class="legend">';
  if (m.deprecatedCount > 0) {
    legend += '<div class="legend__item legend__item--dep"><span class="legend__sym legend__sym--dep" aria-hidden="true"></span><span>' + esc(fmt(t.legendDep, { cutoff: m.cutoff })) + '</span></div>';
  }
  // SUSFS 图注：有探测数据时写明来源；探测结果不连续时只按逐行标记
  var susfsRepoUrl = (m.probe && m.probe.repo) ? m.probe.repo : t.susfsUrl;
  var susfsLink = ' <a class="legend__link" href="' + esc(susfsRepoUrl) + '" target="_blank" rel="noopener">susfs4ksu ↗</a>';
  var probeLink = (m.probe && m.probe.runUrl) ? ' <a class="legend__link" href="' + esc(m.probe.runUrl) + '" target="_blank" rel="noopener">' + esc(t.probeRun) + ' ↗</a>' : '';
  var susfsText = '';
  if (m.allSusfs) susfsText = t.legendSusfsAll;
  else if (m.hasSusfs && m.susfsContiguous) susfsText = fmt(t.legendSusfs, { kernel: m.susfsMinKernel });
  else if (m.hasSusfs) susfsText = t.legendSusfsSparse;
  if (susfsText) {
    legend += '<div class="legend__item legend__item--susfs"><span class="legend__sym legend__sym--susfs" aria-hidden="true"></span><span>' + esc(susfsText) +
      (m.probe ? ' · ' + esc(probeSource(m)) : '') + susfsLink + probeLink + '</span></div>';
  } else if (m.probe) {
    legend += '<div class="legend__item"><span class="legend__sym" aria-hidden="true"></span><span>' + esc(t.legendSusfsProbeNone) + ' · ' + esc(probeSource(m)) + susfsLink + probeLink + '</span></div>';
  } else if (m.susfsMinKernel) {
    legend += '<div class="legend__item"><span class="legend__sym" aria-hidden="true"></span><span>' + esc(fmt(t.legendSusfsNone, { kernel: m.susfsMinKernel })) + '</span></div>';
  }
  if (m.hasSusfsRej) {
    legend += '<div class="legend__item legend__item--susfs"><span class="legend__sym legend__sym--susfs-rej" aria-hidden="true"></span><span>' + esc(t.legendSusfsRej) + '</span></div>';
  }
  legend += '<div class="legend__item"><span class="legend__sym legend__sym--latest" aria-hidden="true"></span><span>' + esc(t.legendLatest) + '</span></div>';
  legend += '<div class="legend__item"><span class="legend__sym legend__sym--delta" aria-hidden="true">+N</span><span>' + esc(t.legendDelta) + '</span></div>';
  legend += '</div></details>';

  return '<section class="chapter">' +
      '<h2 class="visually-hidden">' + esc(m.meta.android + ' · ' + m.meta.kernel) + '</h2>' +
      '<div class="chapter__grid">' +
        '<div class="chapter__name">' +
          '<div class="chapter__eyebrow"><span class="chapter__id">' + esc(m.meta.android) + '</span><span class="chapter__status ' + status.cls + '">' + esc(status.text) + '</span></div>' +
          '<div class="chapter__num" aria-hidden="true">' + esc(m.meta.kernel) + '</div>' +
          ltsHtml +
        '</div>' +
        spec +
        '<div class="chapter__aside">' + legend + '<div class="tools-slot"></div></div>' +
      '</div>' +
      buildRuler(m) +
    '</section>';
}

function specRow(label, value, isNumber) {
  return '<div class="spec__row"><dt class="spec__label">' + esc(label) + '</dt><dd class="spec__value' + (isNumber ? ' is-number' : '') + '">' + esc(value) + '</dd></div>';
}

// 时间转换器（单实例，随分支移动）
function buildTools() {
  return '<details class="tools" id="tools">' +
      '<summary class="tools__summary">' + esc(t.tcTitle) + '</summary>' +
      '<div class="tc">' +
        '<div class="tc__hint">' + esc(t.tcHint) + '</div>' +
        '<label class="tc__field"><span class="tc__label">' + esc(t.tcDate) + '</span><input type="date" class="tc__input" id="tcDate"></label>' +
        '<label class="tc__field"><span class="tc__label">' + esc(t.tcTime) + '</span><input type="time" class="tc__input" id="tcTime" step="1"></label>' +
        '<div class="tc__result"><span class="tc__value" id="tcResult">—</span><button type="button" class="tc__copy copy-btn" id="tcCopy" data-copy="" data-copy-what="' + esc(t.tcWhat) + '">' + esc(t.copy) + '</button></div>' +
      '</div>' +
    '</details>';
}

// 月份刻度尺：整条轨道是一个 Tab 停留点，← → 在有发布的月份间移动，回车跳转
function buildRuler(m) {
  if (!m.months.length) return '';
  var ticks = m.months.map(function (mo) {
    var cls = ['tick'];
    if (mo.row) cls.push('on');
    if (mo.deprecated) cls.push('dep');
    if (mo.susfs) cls.push('susfs');
    if (mo.latest) cls.push('latest');
    if (mo.lts) cls.push('lts');
    if (mo.cutoff) cls.push('cutoff');
    if (mo.threshold) cls.push('threshold');
    var inner = '';
    if (mo.threshold) inner += '<span class="tick__tri" aria-hidden="true"></span>';
    if (mo.lts) inner += '<span class="tick__lts" aria-hidden="true"></span>';
    if (mo.row) {
      inner += '<span class="tick__tip" aria-hidden="true">' + esc(mo.date) + ' · ' + esc(mo.row.kernel) + '</span>';
      return '<button type="button" tabindex="-1" class="' + cls.join(' ') + '" data-jump="' + esc(mo.date) + '" aria-label="' + esc(mo.date + ' · ' + mo.row.kernel) + '">' + inner + '</button>';
    }
    return '<span class="' + cls.join(' ') + '" aria-hidden="true">' + inner + '</span>';
  }).join('');

  // 年份标签：每年一月（或范围内的第一个月），定位到该格中心
  var total = m.months.length;
  var years = m.months.map(function (mo, i) {
    if (!(mo.isJan || i === 0)) return '';
    var left = ((i + 0.5) / total) * 100;
    return '<span class="ruler__year" style="left:' + left.toFixed(3) + '%">' + esc(mo.year) + '</span>';
  }).join('');

  return '<div class="ruler">' +
      '<div class="ruler__track" role="group" tabindex="0" aria-label="' + esc(t.rulerGroup) + '">' + ticks + '</div>' +
      '<div class="ruler__years" aria-hidden="true">' + years + '</div>' +
    '</div>';
}

// ---------- 账册 ----------

function buildLedger(m) {
  var status = m.first ? fmt(t.ledgerStatus, { n: m.count, first: m.first.date, last: m.last.date }) : '';

  // 展示为倒序；按（年份, 是否弃用）切组，弃用截止线穿过一年时该年拆成两段
  var groups = [];
  var rowsDesc = m.rows.slice().reverse();
  rowsDesc.forEach(function (row) {
    var g = groups[groups.length - 1];
    if (!g || g.year !== row.year || g.deprecated !== row.deprecated) {
      g = { year: row.year, deprecated: row.deprecated, items: [], showNote: !groups.length || groups[groups.length - 1].year !== row.year };
      groups.push(g);
    }
    g.items.push(row);
  });

  // 规则线位置：在展示顺序里「第一条弃用行」之前、「第一条非兼容行」之前
  var firstDepRow = rowsDesc.find(function (r) { return r.deprecated; });
  // 兼容线只在「从某行起全部兼容」时才画（探测结果不连续时只逐行标记）
  var firstNonSusfsRow = m.hasSusfs && !m.allSusfs && m.susfsContiguous
    ? rowsDesc.find(function (r) { return !r.susfs; })
    : null;

  var probeAttrs = m.probe
    ? ' data-probe-variant="' + esc(m.probe.variant) + '" data-probe-date="' + esc(m.probe.probedAt) + '" data-probe-commit="' + esc(m.probe.commitShort) + '"'
    : '';
  var html = '<section class="ledger" id="ledger-' + esc(m.key) + '" data-key="' + esc(m.key) + '" data-dep-count="' + m.deprecatedCount + '" data-susfs-min="' + esc(m.susfsMinKernel) + '"' + probeAttrs + '>' +
    '<h3 class="visually-hidden">' + esc(t.ledgerHeading) + '</h3>' +
    '<div class="ledger__head">' +
      '<div class="ledger__head-margin" aria-hidden="true"></div>' +
      '<div class="ledger__cols"><span>' + esc(t.date) + '</span><span>' + esc(t.kernelVersion) + '</span><span class="ledger__status">' + esc(status) + '</span><span></span></div>' +
    '</div>' +
    '<div class="ledger__body">';

  var depRuleId = 'rule-dep-' + m.key;
  var susfsRuleId = 'rule-susfs-' + m.key;

  groups.forEach(function (g) {
    var cls = ['year'];
    if (g.deprecated) cls.push('is-dep-margin');
    html += '<section class="' + cls.join(' ') + '" data-year="' + esc(g.year) + '">' +
      '<div class="year__margin" aria-hidden="true">' + (g.showNote ? '<div class="year__note">' + esc(g.year) + '</div>' : '') + '</div>' +
      (g.showNote ? '<div class="year__heading" aria-hidden="true">' + esc(g.year) + '</div>' : '') +
      '<ol class="year__rows">';

    g.items.forEach(function (row) {
      var isDep = firstDepRow && row === firstDepRow;
      var isSus = firstNonSusfsRow && row === firstNonSusfsRow;
      if (isDep || isSus) html += buildRule(m, isDep, isSus, depRuleId, susfsRuleId, row);
      html += buildRow(m, row, depRuleId);
    });

    html += '</ol></section>';
  });

  html += '</div>' +
    '<div class="ledger__empty"><span class="ledger__empty-text"></span><button type="button" class="ledger__clear">' + esc(t.clearSearch) + '</button></div>' +
    '</section>';
  return html;
}

function buildRule(m, isDep, isSus, depRuleId, susfsRuleId, row) {
  var cls = 'rule ' + (isDep && isSus ? 'rule--both' : isDep ? 'rule--dep' : 'rule--susfs');
  // SUSFS 线落在弃用区内时，折叠弃用行需要一并隐藏它
  if (isSus && !isDep && row.deprecated) cls += ' is-in-dep';
  var html = '<li class="' + cls + '">';
  if (isSus) {
    html += '<div class="rule__line rule__line--susfs"><span class="rule__text" id="' + esc(susfsRuleId) + '">' + esc(fmt(t.susfsRule, { kernel: m.susfsMinKernel })) + '</span></div>';
  }
  if (isDep) {
    html += '<div class="rule__line rule__line--dep">' +
      '<span class="rule__text" id="' + esc(depRuleId) + '">' + esc(fmt(t.deprecatedRule, { cutoff: m.cutoff })) + '</span>' +
      '<button type="button" class="rule__toggle" data-collapse-toggle="' + esc(m.key) + '"></button>' +
    '</div>';
  }
  html += '</li>';
  return html;
}

function buildRow(m, row, depRuleId) {
  var cls = ['row'];
  if (row.deprecated) cls.push('is-deprecated');
  if (row.susfs) cls.push('is-susfs');
  if (row.susfsRej) cls.push('is-susfs-rej');
  if (row.runCont) cls.push('is-cont');
  if (row.runCont && row.runPos === 0) cls.push('is-cont-last');
  if (row.latest) cls.push('is-latest');

  var meta = '';
  if (row.runHead) {
    if (row.runDelta != null && row.runDelta !== 0) {
      meta += '<span class="delta' + (row.runDelta >= 20 ? ' is-big' : '') + '">' + (row.runDelta > 0 ? '+' : '') + row.runDelta + '</span>';
    }
    if (row.runLen > 1) meta += '<span class="months">×' + row.runLen + ' ' + esc(t.monthsUnit) + '</span>';
  }
  var metaHtml = meta ? '<span class="row__meta">' + meta + '</span>' : '';

  var marks = '';
  if (row.deprecated) marks += '<span class="mark mark--dep mark--filtered-only">' + esc(t.deprecated) + '</span>';
  if (row.susfs) marks += '<span class="mark mark--susfs mark--filtered-only">' + esc(t.susfsCompat) + '</span>';
  if (row.susfsRej) marks += '<span class="mark mark--susfs-rej mark--filtered-only">' + esc(t.susfsRejMark) + '</span>';
  if (row.isLts) marks += '<span class="mark mark--lts">' + esc(t.lts) + '</span>';
  if (row.latest) marks += '<span class="mark mark--latest">' + esc(t.newBadge) + '</span>';

  // 搜索用的状态词：中英文标签与固定英文关键字都能命中
  var tags = [];
  if (row.deprecated) tags.push('deprecated', t.deprecated);
  if (row.susfs) tags.push('susfs', t.susfsCompat);
  if (row.susfsRej) tags.push('susfs', 'rej', t.susfsRejMark);
  if (row.latest) tags.push('latest', 'new', t.newBadge);
  if (row.isLts) tags.push('lts');

  var describedBy = row.deprecated ? ' aria-describedby="' + esc(depRuleId) + '"' : '';

  return '<li class="' + cls.join(' ') + '" id="row-' + esc(m.key) + '-' + esc(row.date) + '" data-date="' + esc(row.date) + '" data-kernel="' + esc(row.kernel) + '" data-tags="' + esc(tags.join(' ').toLowerCase()) + '">' +
    '<button type="button" class="row__btn" data-android="' + esc(m.meta.android) + '" data-kernel="' + esc(m.meta.kernel) + '" data-sublevel="' + esc(row.sublevel) + '" data-patch="' + esc(row.date) + '" data-version="' + esc(row.kernel) + '" data-deprecated="' + (row.deprecated ? '1' : '0') + '" data-susfs="' + (row.susfs ? '1' : '0') + '" data-lts="' + (row.isLts ? '1' : '0') + '" data-ref="' + esc(row.ref) + '"' + (row.susfsProbed ? ' data-probe="' + (row.susfs ? 'clean' : row.susfsRej ? 'built_with_rej' : 'failed') + '" data-probe-rej="' + row.susfsRejCount + '"' : '') + describedBy + '>' +
      '<span class="row__date">' + esc(row.date) + '</span>' +
      '<span class="row__ver"><span class="row__kernel">' + esc(row.kernel) + '</span>' + metaHtml + '</span>' +
      '<span class="row__marks">' + marks + '</span>' +
      '<span class="visually-hidden">' + esc(t.rowAction) + '</span>' +
      '<span class="row__arrow" aria-hidden="true">→</span>' +
    '</button>' +
  '</li>';
}

// ---------- 账册状态：弃用折叠 ----------

var COLLAPSE_KEY = 'ledger_collapse_deprecated';

export function readCollapsePref() {
  return getStored(COLLAPSE_KEY) === '1';
}

export function applyCollapse(ledger, collapsed, hasQuery) {
  var n = parseInt(ledger.dataset.depCount || '0', 10);
  var effective = collapsed && !hasQuery;
  ledger.classList.toggle('is-dep-collapsed', effective);
  var btn = ledger.querySelector('[data-collapse-toggle]');
  if (btn) {
    btn.textContent = fmt(collapsed ? t.expand : t.collapse, { n: n });
    btn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
  }
  // 折叠后整组都是弃用行的年份组一并隐藏；含规则线的组保留，否则「展开」按钮会一起消失
  ledger.querySelectorAll('.year').forEach(function (g) {
    if (!effective) { g.hidden = false; return; }
    var keep = !!g.querySelector('.rule') || g.querySelectorAll('.row:not(.is-deprecated)').length > 0;
    g.hidden = !keep;
  });
}

export function initCollapse(getQuery) {
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-collapse-toggle]');
    if (!btn) return;
    // 切换折叠偏好；若当前有搜索词，偏好只记录不生效
    var next = !readCollapsePref();
    setStored(COLLAPSE_KEY, next ? '1' : '0');
    document.querySelectorAll('.ledger').forEach(function (l) { applyCollapse(l, next, !!getQuery()); });
  });
}

// ---------- 图注 details 随断点同步开合 ----------

export function initLegendSync() {
  if (!mobileQuery || !mobileQuery.addEventListener) return;
  mobileQuery.addEventListener('change', function (e) {
    document.querySelectorAll('.legend-wrap').forEach(function (d) { d.open = !e.matches; });
    moveIndicator();
  });
}

// ---------- 刻度尺：键盘漫游 + 点击跳转 ----------

function jumpToRow(tick, clearSearch) {
  var panel = tick.closest('.branch-panel');
  var ledger = panel && panel.querySelector('.ledger');
  if (!ledger) return;
  var row = ledger.querySelector('.row[data-date="' + tick.dataset.jump + '"]');
  if (!row) return;
  // 目标行若被搜索过滤掉，先清空搜索
  if (ledger.classList.contains('is-filtered') && clearSearch) clearSearch();
  // 目标行若被折叠隐藏，临时展开当前账册（不改写用户的折叠偏好）
  if (row.classList.contains('is-deprecated') && ledger.classList.contains('is-dep-collapsed')) {
    applyCollapse(ledger, false, false);
  }
  row.scrollIntoView({ block: 'center', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
  row.classList.remove('is-flash');
  void row.offsetWidth;
  row.classList.add('is-flash');
  // 定位提示：减少动效时没有闪烁，改用「刚看过」的朱砂左线
  document.querySelectorAll('.row.is-visited').forEach(function (r) { r.classList.remove('is-visited'); });
  row.classList.add('is-visited');
  var btn = row.querySelector('.row__btn');
  if (btn) btn.focus({ preventScroll: true });
}

export function initRulerJump(clearSearch) {
  document.addEventListener('click', function (e) {
    var tick = e.target.closest('.tick[data-jump]');
    if (!tick) return;
    jumpToRow(tick, clearSearch);
  });

  // 轨道聚焦后 ← → 在有发布的月份间移动，回车 / 空格跳转
  document.addEventListener('keydown', function (e) {
    var track = e.target.closest ? e.target.closest('.ruler__track') : null;
    if (!track) return;
    var ticks = Array.prototype.slice.call(track.querySelectorAll('.tick[data-jump]'));
    if (!ticks.length) return;
    var current = ticks.indexOf(document.activeElement);
    var next = -1;
    if (e.key === 'ArrowRight') next = current < 0 ? 0 : Math.min(current + 1, ticks.length - 1);
    else if (e.key === 'ArrowLeft') next = current < 0 ? ticks.length - 1 : Math.max(current - 1, 0);
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = ticks.length - 1;
    else if ((e.key === 'Enter' || e.key === ' ') && current >= 0) {
      e.preventDefault();
      jumpToRow(ticks[current], clearSearch);
      return;
    } else return;
    e.preventDefault();
    ticks[next].focus();
  });
}

// ---------- 报头读数 ----------

export function renderReadout(summary) {
  var el = document.getElementById('readout');
  if (!el) return;
  el.textContent = fmt(t.readout, { latest: summary.latest, branches: summary.branches, total: summary.total });
}

export { lang };
