/**
 * 视图模型模块：把原始 JSON 数据整理成渲染所需的结构
 * （版本块、子版本增量、弃用区间、SUSFS 兼容、月份刻度尺、最新条目）
 *
 * SUSFS 兼容有两个来源：
 *   1. data/susfs_probe/<android>-<kernel>.json：工具分支用原始 susfs4ksu 补丁全量编译得到的逐条结论
 *      （clean = 0 rej 且编译成功；built_with_rej = 有 rej 但编译成功；failed = 编译失败）
 *   2. 没有探测数据时退回 config.js 里的 SUSFS_COMPAT_MIN 阈值
 * 只有 clean 才算「可直接应用」
 */

import { SUSFS_COMPAT_MIN } from './config.js';

// 取内核版本串中的子版本号，如 "6.6.50" -> 50
export function sublevelOf(kernel) {
  var parts = String(kernel || '').split('.');
  var n = parseInt(parts[2], 10);
  return isNaN(n) ? null : n;
}

// 按阈值判断某条内核版本是否达到 SUSFS 直接兼容（无探测数据时的兜底）
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

// 整理探测文件：只保留结构正确的部分，缺失或损坏时返回 null
function normalizeProbe(probe) {
  if (!probe || typeof probe !== 'object' || !probe.results || typeof probe.results !== 'object') return null;
  var results = {};
  var count = 0;
  Object.keys(probe.results).forEach(function (key) {
    var r = probe.results[key];
    if (!r || typeof r !== 'object') return;
    var verdict = r.verdict === 'clean' || r.verdict === 'built_with_rej' || r.verdict === 'failed' ? r.verdict : '';
    if (!verdict) return;
    results[key] = {
      kernel: typeof r.kernel === 'string' ? r.kernel : '',
      rej: typeof r.rej === 'number' ? r.rej : parseInt(r.rej, 10) || 0,
      compile: typeof r.compile === 'string' ? r.compile : '',
      verdict: verdict,
    };
    count++;
  });
  if (!count) return null;
  return {
    variant: typeof probe.ksu_variant === 'string' ? probe.ksu_variant : '',
    repo: typeof probe.susfs_repo === 'string' ? probe.susfs_repo : '',
    branch: typeof probe.susfs_branch === 'string' ? probe.susfs_branch : '',
    commit: typeof probe.susfs_commit === 'string' ? probe.susfs_commit : '',
    commitShort: typeof probe.susfs_commit === 'string' ? probe.susfs_commit.slice(0, 7) : '',
    probedAt: typeof probe.probed_at === 'string' ? probe.probed_at : '',
    runUrl: typeof probe.run_url === 'string' ? probe.run_url : '',
    results: results,
  };
}

/**
 * 构建单个分支的视图模型
 * @param {object} data   原始 JSON（entries / lts / deprecated_cutoff）
 * @param {object} meta   DATA_FILES 中的分支元信息（android / kernel / label）
 * @param {object} probe  可选，data/susfs_probe/<android>-<kernel>.json 的内容
 */
export function buildBranchModel(data, meta, probe) {
  var entries = Array.isArray(data.entries) ? data.entries.slice() : [];
  var cutoff = typeof data.deprecated_cutoff === 'string' ? data.deprecated_cutoff : '';
  var lts = typeof data.lts === 'string' ? data.lts : '';
  var susfsMin = SUSFS_COMPAT_MIN[meta.kernel];
  var key = meta.android + '-' + meta.kernel;
  var probeInfo = normalizeProbe(probe);

  // 单条记录的 SUSFS 结论：有探测文件时只认探测结果（缺失的月份视为未探测，不再按阈值猜测），
  // 没有探测文件时才按阈值兜底；LTS 分支会前进，探测到的版本与当前 lts 不一致时也视为未探测
  function susfsOf(date, kernel) {
    if (probeInfo) {
      var r = probeInfo.results[date];
      if (r && (!r.kernel || r.kernel === kernel)) {
        return { susfs: r.verdict === 'clean', rej: r.verdict === 'built_with_rej', failed: r.verdict === 'failed', probed: true, unknown: false, rejCount: r.rej };
      }
      return { susfs: false, rej: false, failed: false, probed: false, unknown: true, rejCount: 0 };
    }
    return { susfs: isSusfsCompat(kernel, meta.kernel), rej: false, failed: false, probed: false, unknown: false, rejCount: 0 };
  }

  // ---- 逐行基础字段（时间正序） ----
  var rows = [];
  var prevSub = null;
  var deprecatedCount = 0;
  var monotonic = true;

  entries.forEach(function (entry, i) {
    var sub = sublevelOf(entry.kernel);
    var delta = (prevSub == null || sub == null) ? null : sub - prevSub;
    if (delta != null && delta < 0) monotonic = false;
    var deprecated = !!cutoff && entry.date <= cutoff;
    // 上游实际位置（由 update_data.py 写入）：refs/heads/... / refs/heads/deprecated/... / refs/tags/..._rN
    var ref = typeof entry.ref === 'string' ? entry.ref : '';
    var s = susfsOf(entry.date, entry.kernel);
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
      susfs: s.susfs,
      susfsRej: s.rej,
      susfsFailed: s.failed,
      susfsProbed: s.probed,
      susfsUnknown: s.unknown,
      susfsRejCount: s.rejCount,
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

  // ---- SUSFS 兼容区：只有「从某一行起全部 clean」时才画兼容线，否则只逐行标记 ----
  var susfsFirstIndex = -1;
  var susfsContiguous = false;
  for (var s0 = rows.length - 1; s0 >= 0; s0--) {
    if (rows[s0].susfs) susfsFirstIndex = s0; else break;
  }
  var anySusfs = rows.some(function (r) { return r.susfs; });
  if (susfsFirstIndex !== -1) {
    susfsContiguous = rows.slice(0, susfsFirstIndex).every(function (r) { return !r.susfs; });
  }
  if (!susfsContiguous) susfsFirstIndex = -1;

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
  var ltsState = lts ? susfsOf('lts', lts) : { susfs: false, rej: false, failed: false, probed: false, unknown: false, rejCount: 0 };

  // 兼容线起点：探测且连续时取该行版本，否则退回阈值
  var susfsMinKernel = '';
  if (susfsFirstIndex !== -1) susfsMinKernel = rows[susfsFirstIndex].kernel;
  else if (!probeInfo && susfsMin != null) susfsMinKernel = meta.kernel + '.' + susfsMin;

  // ---- 月份刻度尺：从起始月到最新月的每一个自然月，无记录的月份沿用前一条记录的 SUSFS 状态 ----
  var months = [];
  if (first && last) {
    var byDate = {};
    rows.forEach(function (r) { byDate[r.date] = r; });
    var total = monthDiff(first.date, last.date) + 1;
    var cutoffIdx = -1;
    var carrySusfs = false;
    for (var m = 0; m < total; m++) {
      var ym = addMonths(first.date, m);
      var row = byDate[ym] || null;
      if (row) carrySusfs = row.susfs;
      var dep = !!cutoff && ym <= cutoff;
      if (dep) cutoffIdx = m;
      months.push({
        index: m,
        date: ym,
        year: ym.slice(0, 4),
        isJan: ym.slice(5, 7) === '01',
        row: row,
        deprecated: dep,
        susfs: carrySusfs,
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
    ltsSusfs: ltsState.susfs,
    ltsSusfsState: ltsState,
    cutoff: cutoff,
    deprecatedCount: deprecatedCount,
    // 全部条目均已弃用时视为整分支弃用
    fullyDeprecated: rows.length > 0 && deprecatedCount === rows.length,
    monotonic: monotonic,
    probe: probeInfo,
    susfsMin: susfsMin == null ? null : susfsMin,
    susfsMinKernel: susfsMinKernel,
    susfsFirstIndex: susfsFirstIndex,
    susfsFirstDate: susfsFirstIndex !== -1 ? rows[susfsFirstIndex].date : '',
    susfsContiguous: susfsContiguous,
    hasSusfs: anySusfs,
    allSusfs: rows.length > 0 && susfsFirstIndex === 0,
    hasSusfsRej: rows.some(function (r) { return r.susfsRej; }),
    susfsUnknownCount: rows.filter(function (r) { return r.susfsUnknown; }).length,
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
