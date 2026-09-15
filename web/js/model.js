/**
 * 视图模型模块：把原始 JSON 数据整理成渲染所需的结构
 * （版本块、子版本增量、弃用区间、SUSFS 阈值、月份刻度尺、最新条目）
 */

import { SUSFS_COMPAT_MIN } from './config.js';

// 取内核版本串中的子版本号，如 "6.6.50" -> 50
export function sublevelOf(kernel) {
  var parts = String(kernel || '').split('.');
  var n = parseInt(parts[2], 10);
  return isNaN(n) ? null : n;
}

// 判断某条内核版本是否达到 SUSFS 直接兼容阈值
export function isSusfsCompat(kernel, majorMinor) {
  var min = SUSFS_COMPAT_MIN[majorMinor];
  if (min == null) return false;
  var sub = sublevelOf(kernel);
  return sub != null && sub >= min;
}

// 上游 ref 的类型：head（活跃分支）/ deprecated（已迁移）/ tag（分支已删除，仅剩发布 tag）/ unknown
export function refKindOf(ref) {
  if (!ref) return 'unknown';
  if (ref.indexOf('refs/heads/deprecated/') === 0) return 'deprecated';
  if (ref.indexOf('refs/heads/') === 0) return 'head';
  if (ref.indexOf('refs/tags/') === 0) return 'tag';
  return 'unknown';
}

// 月份字符串加减：addMonths('2024-10', 1) -> '2024-11'
export function addMonths(ym, n) {
  var y = parseInt(ym.slice(0, 4), 10);
  var m = parseInt(ym.slice(5, 7), 10) - 1 + n;
  y += Math.floor(m / 12);
  m = ((m % 12) + 12) % 12;
  return y + '-' + String(m + 1).padStart(2, '0');
}

// 两个月份之间相差的月数（含符号）
export function monthDiff(a, b) {
  var ya = parseInt(a.slice(0, 4), 10), ma = parseInt(a.slice(5, 7), 10);
  var yb = parseInt(b.slice(0, 4), 10), mb = parseInt(b.slice(5, 7), 10);
  return (yb - ya) * 12 + (mb - ma);
}

/**
 * 构建单个分支的视图模型
 * @param {object} data  原始 JSON（entries / lts / deprecated_cutoff）
 * @param {object} meta  DATA_FILES 中的分支元信息（android / kernel / label）
 */
export function buildBranchModel(data, meta) {
  var entries = Array.isArray(data.entries) ? data.entries.slice() : [];
  var cutoff = typeof data.deprecated_cutoff === 'string' ? data.deprecated_cutoff : '';
  var lts = typeof data.lts === 'string' ? data.lts : '';
  var susfsMin = SUSFS_COMPAT_MIN[meta.kernel];
  var key = meta.android + '-' + meta.kernel;

  // ---- 逐行基础字段（时间正序） ----
  var rows = [];
  var prevSub = null;
  var susfsFirstIndex = -1;
  var deprecatedCount = 0;
  var monotonic = true;

  entries.forEach(function (entry, i) {
    var sub = sublevelOf(entry.kernel);
    var delta = (prevSub == null || sub == null) ? null : sub - prevSub;
    if (delta != null && delta < 0) monotonic = false;
    var deprecated = !!cutoff && entry.date <= cutoff;
    // 上游实际位置（由 update_data.py 写入）：refs/heads/... / refs/heads/deprecated/... / refs/tags/..._rN
    var ref = typeof entry.ref === 'string' ? entry.ref : '';
    var susfs = isSusfsCompat(entry.kernel, meta.kernel);
    if (susfs && susfsFirstIndex === -1) susfsFirstIndex = i;
    if (deprecated) deprecatedCount++;
    rows.push({
      index: i,
      key: key,
      date: entry.date,
      year: entry.date.slice(0, 4),
      month: entry.date.slice(5, 7),
      kernel: entry.kernel,
      sublevel: sub,
      delta: delta,
      deprecated: deprecated,
      ref: ref,
      refKind: refKindOf(ref),
      susfs: susfs,
      latest: i === entries.length - 1,
      isLts: false,
      // 版本块字段，下面填充
      runHead: true,
      runCont: false,
      runLen: 1,
      runPos: 0,
      runDelta: null,
    });
    if (sub != null) prevSub = sub;
  });

  // ---- 版本块：连续相同版本合并；弃用截止线穿过时按规则线切开 ----
  var runStart = 0;
  var prevRunSub = null;
  function closeRun(start, end) {
    var len = end - start;
    var headSub = rows[start].sublevel;
    var runDelta = (prevRunSub == null || headSub == null) ? null : headSub - prevRunSub;
    for (var j = start; j < end; j++) {
      rows[j].runLen = len;
      rows[j].runPos = j - start;
      rows[j].runHead = j === end - 1;   // 展示为倒序，块首是最晚的月份
      rows[j].runCont = j !== end - 1;
      rows[j].runDelta = runDelta;
    }
    if (headSub != null) prevRunSub = headSub;
  }
  for (var i = 1; i <= rows.length; i++) {
    var sameRun = i < rows.length &&
      rows[i].kernel === rows[i - 1].kernel &&
      rows[i].deprecated === rows[i - 1].deprecated;
    if (!sameRun) {
      closeRun(runStart, i);
      runStart = i;
    }
  }

  // LTS 标记落在该版本的块首
  if (lts) {
    for (var k = rows.length - 1; k >= 0; k--) {
      if (rows[k].kernel === lts && rows[k].runHead) { rows[k].isLts = true; break; }
    }
  }

  var first = rows.length ? rows[0] : null;
  var last = rows.length ? rows[rows.length - 1] : null;
  var susfsFirstDate = susfsFirstIndex >= 0 ? rows[susfsFirstIndex].date : '';

  // ---- 月份刻度尺：从起始月到最新月的每一个自然月 ----
  var months = [];
  if (first && last) {
    var byDate = {};
    rows.forEach(function (r) { byDate[r.date] = r; });
    var total = monthDiff(first.date, last.date) + 1;
    var cutoffIdx = -1;
    for (var m = 0; m < total; m++) {
      var ym = addMonths(first.date, m);
      var row = byDate[ym] || null;
      var dep = !!cutoff && ym <= cutoff;
      if (dep) cutoffIdx = m;
      months.push({
        index: m,
        date: ym,
        year: ym.slice(0, 4),
        isJan: ym.slice(5, 7) === '01',
        row: row,
        deprecated: dep,
        susfs: !!susfsFirstDate && ym >= susfsFirstDate,
        threshold: !!row && row.index === susfsFirstIndex && susfsFirstIndex > 0,
        latest: !!row && row.latest,
        lts: !!row && row.isLts,
        cutoff: false,
      });
    }
    if (cutoffIdx >= 0) months[cutoffIdx].cutoff = true;
  }

  return {
    key: key,
    meta: meta,
    rows: rows,
    count: rows.length,
    first: first,
    last: last,
    lts: lts,
    ltsSublevel: lts ? sublevelOf(lts) : null,
    ltsInList: rows.some(function (r) { return r.isLts; }),
    cutoff: cutoff,
    deprecatedCount: deprecatedCount,
    // 全部条目均已弃用时视为整分支弃用
    fullyDeprecated: rows.length > 0 && deprecatedCount === rows.length,
    monotonic: monotonic,
    susfsMin: susfsMin == null ? null : susfsMin,
    susfsMinKernel: susfsMin == null ? '' : meta.kernel + '.' + susfsMin,
    susfsFirstIndex: susfsFirstIndex,
    susfsFirstDate: susfsFirstDate,
    hasSusfs: susfsFirstIndex !== -1,
    allSusfs: rows.length > 0 && susfsFirstIndex === 0,
    months: months,
  };
}

// 汇总多个分支的读数：最新月份、分支数、总条目数
export function buildSummary(models) {
  var latest = '';
  var total = 0;
  models.forEach(function (m) {
    total += m.count;
    if (m.last && m.last.date > latest) latest = m.last.date;
  });
  return { latest: latest, branches: models.length, total: total };
}
