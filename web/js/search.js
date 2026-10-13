/**
 * 搜索模块：过滤当前分支的版本行；「/」聚焦，Esc 清空
 */

import { t } from './i18n.js';
import { esc, fmt } from './utils.js';
import { applyCollapse, readCollapsePref, refreshIndicator } from './render.js';

var searchWrap = document.getElementById('search');
var nav = document.getElementById('branchNav');
var input = document.getElementById('searchInput');
var clearBtn = document.getElementById('searchClear');
var countEl = document.getElementById('searchCount');
var toggleBtn = document.getElementById('searchToggle');

export function getQuery() {
  return input.value.trim().toLowerCase();
}

// 清空搜索词并恢复列表（刻度尺跳转到被过滤掉的行时使用）
export function clearSearch() {
  if (!input.value) return;
  input.value = '';
  filterActive();
}

export function initSearch() {
  input.addEventListener('input', function () { filterActive(); });

  clearBtn.addEventListener('click', function () {
    input.value = '';
    filterActive();
    input.focus();
  });

  // 账册无结果态里的「清除」按钮
  document.addEventListener('click', function (e) {
    if (!e.target.closest('.ledger__clear')) return;
    input.value = '';
    filterActive();
    input.focus();
  });

  input.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      if (input.value) { input.value = ''; filterActive(); }
      else input.blur();
      e.stopPropagation();
    }
  });

  // 「/」快捷键聚焦搜索
  document.addEventListener('keydown', function (e) {
    if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
    var active = document.activeElement;
    var tag = (active && active.tagName) || '';
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(tag) || (active && active.isContentEditable)) return;
    if (document.querySelector('dialog[open]')) return;
    e.preventDefault();
    if (window.matchMedia('(max-width: 767px)').matches) {
      nav.classList.add('search-open');
      toggleBtn.setAttribute('aria-expanded', 'true');
    }
    input.focus();
    input.select();
  });

  // 手机：放大镜展开 / 收起输入框
  toggleBtn.addEventListener('click', function () {
    var open = nav.classList.toggle('search-open');
    toggleBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) {
      input.focus();
    } else {
      input.value = '';
      filterActive();
    }
    refreshIndicator();
  });
}

// 过滤当前分支；分支切换后也会调用
export function filterActive() {
  var query = getQuery();
  searchWrap.classList.toggle('has-query', !!query);

  var panel = document.querySelector('.branch-panel.is-active');
  var ledger = panel ? panel.querySelector('.ledger') : null;
  if (!ledger) { countEl.textContent = ''; return; }

  var rows = ledger.querySelectorAll('.row');
  var total = rows.length;
  var shown = 0;

  rows.forEach(function (row) {
    if (!query) { row.hidden = false; shown++; return; }
    var hay = (row.dataset.date + ' ' + row.dataset.kernel + ' ' + (row.dataset.tags || '')).toLowerCase();
    var match = hay.indexOf(query) !== -1;
    row.hidden = !match;
    if (match) shown++;
  });

  ledger.classList.toggle('is-filtered', !!query);

  // 折叠偏好在有搜索词时不生效；同时会重置年份组显隐
  applyCollapse(ledger, readCollapsePref(), !!query);

  // 过滤态下没有可见行的年份组隐藏
  if (query) {
    ledger.querySelectorAll('.year').forEach(function (g) {
      g.hidden = g.querySelectorAll('.row:not([hidden])').length === 0;
    });
  }

  var empty = !!query && shown === 0;
  ledger.classList.toggle('is-empty', empty);
  if (empty) {
    ledger.querySelector('.ledger__empty-text').innerHTML =
      fmt(esc(t.noResults), { q: '<span class="q">' + esc(input.value.trim()) + '</span>' });
  }

  countEl.textContent = query ? fmt(t.searchCount, { n: shown, total: total }) : '';
  // 计数出现或消失会改变导航布局，指示线需要重算
  refreshIndicator();
}
