/**
 * 国际化模块：多语言文本 + 语言切换
 * 仪表盘与教程页共用同一个存储键 lang
 */

var I18N = {
  en: {
    title: 'GKI Kernel Versions',
    subtitle: 'Security patch month to kernel sublevel, for every Android GKI branch. Click a release for build parameters.',
    tagline: 'Android Generic Kernel Image · Almanac',
    readout: 'LATEST PATCH {latest} · {branches} branches · {total} releases',
    siteShort: 'GKI Almanac',
    light: 'Switch to light theme',
    dark: 'Switch to dark theme',
    loading: 'Loading kernel data…',
    errorTitle: 'Failed to load kernel data.',
    errorHint: 'Make sure <code>web/data/</code> contains the JSON files.',
    statusActive: 'Active',
    statusPartly: 'Partially deprecated',
    statusDeprecated: 'Deprecated',
    releases: 'Releases',
    first: 'First',
    latest: 'Latest',
    latestKernel: 'Latest kernel',
    lts: 'LTS',
    ltsHint: 'Long-term support tag on the {branch} branch. Click for build parameters.',
    legendDep: 'Grey rows below the rule · security patches no longer merged for {cutoff} and earlier',
    legendSusfs: 'SUSFS compatible · from {kernel}, susfs4ksu patches apply directly',
    legendSusfsAll: 'SUSFS compatible · every release on this branch',
    legendSusfsNone: 'SUSFS · no release on this branch has reached {kernel} yet',
    legendSusfsSparse: 'SUSFS compatible · probe results are not contiguous, see the per-row marks',
    legendSusfsProbeNone: 'SUSFS · the probe found no release where the raw patch applies and builds',
    legendSusfsRej: 'Hollow square · raw patch left .rej conflicts but the kernel still built, fix by hand before trusting it',
    probeSource: 'probed by a full {variant} build on {date} (susfs4ksu {commit})',
    probeRun: 'probe run',
    susfsRejMark: 'SUSFS rej',
    statusProbeClean: 'Probe: raw patch applied with 0 .rej and the kernel built · {variant} · {date} · susfs4ksu {commit}',
    statusProbeRej: 'Probe: raw patch left {rej} .rej but the kernel still built · {variant} · {date} · susfs4ksu {commit}',
    statusProbeFailed: 'Probe: kernel failed to build with the raw patch ({rej} .rej) · {variant} · {date} · susfs4ksu {commit}',
    legend: 'Legend',
    legendLatest: 'Latest release on this branch',
    legendDelta: '+N · sublevels advanced since the previous version · ×N · months on the same version',
    susfsUrl: 'https://gitlab.com/simonpunk/susfs4ksu',
    rulerGroup: 'Month ruler: long ticks are releases. Use ← → to pick a month and Enter to jump to it',
    ledgerHeading: 'Releases',
    rowAction: 'Build parameters',
    tcWhat: 'UTC time',
    tcTitle: 'Time converter → UTC',
    tcHint: 'Turn a date and time into the UTC string used by kernel version strings',
    tcDate: 'Date',
    tcTime: 'Time (UTC)',
    date: 'Patch',
    kernelVersion: 'Kernel',
    ledgerStatus: '{n} releases · {first} → {last}',
    deprecatedRule: '{cutoff} and earlier · security patches no longer merged',
    susfsRule: 'SUSFS line · ≥ {kernel} · rows above apply susfs4ksu directly',
    collapse: 'Collapse ({n})',
    expand: 'Expand ({n})',
    monthsUnit: 'mo',
    newBadge: 'Latest',
    deprecated: 'Deprecated',
    susfsCompat: 'SUSFS',
    rowHint: 'Build parameters for {date} · {kernel}',
    rulerHint: 'Month ruler: long ticks are releases, short ticks are months without one',
    search: 'Search',
    searchPlaceholder: 'Filter month, version or status…',
    searchCount: '{n} of {total} match',
    noResults: 'No match for “{q}”',
    clearSearch: 'Clear',
    announce: 'Announcement',
    announceUnread: 'New announcement',
    announceClose: 'Got it',
    announceDismiss: 'Don’t show today',
    modalTitle: 'Build parameters',
    modalBranch: 'AOSP branch',
    modalAndroid: 'Android version',
    modalKernel: 'Kernel version',
    modalSublevel: 'Sublevel',
    modalPatch: 'Security patch level',
    modalRepoInit: 'repo init',
    modalRepoInitWhat: 'repo init command',
    modalManifest: 'manifest rewrite',
    modalManifestWhat: 'manifest rewrite command',
    modalManifestHint: 'This branch has moved under deprecated/ upstream. After repo init, point the manifest at the deprecated/ path before repo sync.',
    modalManifestTagHint: 'Upstream has deleted this monthly branch; only the release tag {tag} remains. After repo init, point the manifest revision at the tag before repo sync.',
    statusUpstreamMoved: 'Upstream: branch moved under deprecated/',
    statusUpstreamTag: 'Upstream: branch deleted, release tag {tag} remains',
    modalSusfsClone: 'susfs4ksu clone',
    modalSusfsCloneWhat: 'susfs4ksu clone command',
    modalOpenSource: 'Open branch on googlesource',
    modalGuide: 'Read the guide',
    modalHint: 'Paste these values into the “Android 内核构建-自定义” (custom build) workflow inputs, or run the commands locally.',
    statusDep: 'Security patches are no longer merged for this month',
    statusSusfs: '≥ {kernel} · susfs4ksu patches apply directly',
    statusLts: 'Long-term support tag on {branch}',
    copy: 'Copy',
    copied: 'Copied',
    copyToast: 'Copied {what}',
    copyToastGeneric: 'Copied to clipboard',
    guide: 'Guide',
    backToTop: 'Back to top',
    closeDialog: 'Close',
    footerPre: 'Data sourced from',
    footerPost: ', refreshed automatically by GitHub Actions.',
    langZh: '切换到中文',
    langEn: 'Switch to English',
  },
  zh: {
    title: 'GKI 内核版本',
    subtitle: '各 Android GKI 分支的「安全补丁月份 → 内核子版本号」对照。点击任一版本查看构建参数。',
    tagline: 'Android 通用内核镜像 · 年鉴',
    readout: '最新补丁 {latest} · {branches} 个分支 · {total} 个版本',
    siteShort: 'GKI 年鉴',
    light: '切换到浅色主题',
    dark: '切换到深色主题',
    loading: '正在加载内核数据…',
    errorTitle: '加载内核数据失败。',
    errorHint: '请确保 <code>web/data/</code> 包含 JSON 文件。',
    statusActive: '活跃',
    statusPartly: '部分弃用',
    statusDeprecated: '已弃用',
    releases: '发布数',
    first: '起始',
    latest: '最新',
    latestKernel: '最新内核',
    lts: 'LTS',
    ltsHint: '{branch} 分支上的长期支持版本，点击查看构建参数。',
    legendDep: '规则线以下的灰字行 · {cutoff} 及之前已停止合并安全补丁',
    legendSusfs: 'SUSFS 兼容 · 自 {kernel} 起可直接应用 susfs4ksu 补丁',
    legendSusfsAll: 'SUSFS 兼容 · 本分支全部版本',
    legendSusfsNone: 'SUSFS · 本分支尚未达到 {kernel}',
    legendSusfsSparse: 'SUSFS 兼容 · 探测结果不连续，以逐行标记为准',
    legendSusfsProbeNone: 'SUSFS · 探测显示本分支没有原始补丁能直接应用并编译成功的版本',
    legendSusfsRej: '空心方块 · 原始补丁留下 .rej 冲突但内核仍编译成功，需手动处理后才可信',
    probeSource: '由 {variant} 全量编译探测于 {date}（susfs4ksu {commit}）',
    probeRun: '探测运行',
    susfsRejMark: 'SUSFS 有 rej',
    statusProbeClean: '探测：原始补丁 0 个 .rej 且编译成功 · {variant} · {date} · susfs4ksu {commit}',
    statusProbeRej: '探测：原始补丁留下 {rej} 个 .rej，内核仍编译成功 · {variant} · {date} · susfs4ksu {commit}',
    statusProbeFailed: '探测：原始补丁下编译失败（{rej} 个 .rej）· {variant} · {date} · susfs4ksu {commit}',
    legend: '图例',
    legendLatest: '本分支最新版本',
    legendDelta: '+N · 相对上一版本前进的子版本数 · ×N · 同一版本沿用的月数',
    susfsUrl: 'https://gitlab.com/simonpunk/susfs4ksu',
    rulerGroup: '月份刻度尺：长刻度为有发布的月份。← → 选择月份，回车跳转到对应行',
    ledgerHeading: '版本列表',
    rowAction: '查看构建参数',
    tcWhat: 'UTC 时间',
    tcTitle: '时间转换 → UTC',
    tcHint: '把日期和时间转成内核版本串里使用的 UTC 字符串',
    tcDate: '日期',
    tcTime: '时间 (UTC)',
    date: '安全补丁',
    kernelVersion: '内核版本',
    ledgerStatus: '{n} 个版本 · {first} → {last}',
    deprecatedRule: '{cutoff} 及之前 · 已停止合并安全补丁',
    susfsRule: 'SUSFS 兼容线 · ≥ {kernel} · 此线以上可直接应用 susfs4ksu 补丁',
    collapse: '折叠 ({n})',
    expand: '展开 ({n})',
    monthsUnit: '个月',
    newBadge: '最新',
    deprecated: '已弃用',
    susfsCompat: 'SUSFS',
    rowHint: '查看 {date} · {kernel} 的构建参数',
    rulerHint: '月份刻度尺：长刻度为有发布的月份，短刻度为无发布的月份',
    search: '搜索',
    searchPlaceholder: '过滤月份、版本或状态…',
    searchCount: '匹配 {n} / {total}',
    noResults: '无匹配「{q}」',
    clearSearch: '清除',
    announce: '公告',
    announceUnread: '有新公告',
    announceClose: '知道了',
    announceDismiss: '今日不再显示',
    modalTitle: '构建参数',
    modalBranch: 'AOSP 分支',
    modalAndroid: 'Android 版本',
    modalKernel: '内核版本',
    modalSublevel: '子版本号',
    modalPatch: '安全补丁级别',
    modalRepoInit: 'repo init',
    modalRepoInitWhat: 'repo init 命令',
    modalManifest: 'manifest 改写',
    modalManifestWhat: 'manifest 改写命令',
    modalManifestHint: '该分支在上游已迁到 deprecated/ 之下，repo init 之后、repo sync 之前需要把 manifest 指向 deprecated/ 路径。',
    modalManifestTagHint: '上游已删除该月份分支，只剩发布 tag {tag}。repo init 之后、repo sync 之前需要把 manifest 的 revision 指向该 tag。',
    statusUpstreamMoved: '上游：分支已迁到 deprecated/ 之下',
    statusUpstreamTag: '上游：分支已删除，仅剩发布 tag {tag}',
    modalSusfsClone: 'susfs4ksu 克隆',
    modalSusfsCloneWhat: 'susfs4ksu 克隆命令',
    modalOpenSource: '在 googlesource 打开该分支',
    modalGuide: '查看教程',
    modalHint: '把这些值填入「Android 内核构建-自定义」工作流的输入框，或在本地直接运行命令。',
    statusDep: '该月份已停止合并安全补丁',
    statusSusfs: '≥ {kernel} · 可直接应用 susfs4ksu 补丁',
    statusLts: '{branch} 分支的长期支持版本',
    copy: '复制',
    copied: '已复制',
    copyToast: '已复制 {what}',
    copyToastGeneric: '已复制到剪贴板',
    guide: '教程',
    backToTop: '回到顶部',
    closeDialog: '关闭',
    footerPre: '数据来源于',
    footerPost: '，由 GitHub Actions 自动刷新。',
    langZh: '切换到中文',
    langEn: 'Switch to English',
  },
};

// 语言优先级：localStorage > navigator.language
import { getStored, setStored } from './utils.js';

var savedLang = getStored('lang');
export var lang = (savedLang === 'zh' || savedLang === 'en')
  ? savedLang
  : (/^zh\b/i.test(navigator.language) ? 'zh' : 'en');
export var t = I18N[lang];

// 切换语言并刷新页面
export function setLang(next) {
  if (next !== 'zh' && next !== 'en') return;
  if (next === lang) return;
  setStored('lang', next);
  location.reload();
}

// 把静态元素的文案按 data-i18n / data-i18n-attr 属性填入
export function applyStaticText(root) {
  var scope = root || document;
  scope.querySelectorAll('[data-i18n]').forEach(function (el) {
    var key = el.getAttribute('data-i18n');
    if (t[key] != null) {
      if (el.hasAttribute('data-i18n-html')) el.innerHTML = t[key];
      else el.textContent = t[key];
    }
  });
  scope.querySelectorAll('[data-i18n-attr]').forEach(function (el) {
    // 形如 data-i18n-attr="title:search,placeholder:searchPlaceholder"
    el.getAttribute('data-i18n-attr').split(',').forEach(function (pair) {
      var parts = pair.split(':');
      var attr = parts[0].trim();
      var key = (parts[1] || '').trim();
      if (attr && t[key] != null) el.setAttribute(attr, t[key]);
    });
  });
}

/**
 * 初始化 i18n：设置页面语言属性、静态文本、语言切换按钮
 */
export function initI18n() {
  document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
  document.title = t.title;
  applyStaticText(document);

  document.querySelectorAll('.lang-switch [data-lang]').forEach(function (btn) {
    var target = btn.getAttribute('data-lang');
    var isCurrent = target === lang;
    btn.setAttribute('aria-current', isCurrent ? 'true' : 'false');
    btn.setAttribute('title', target === 'zh' ? t.langZh : t.langEn);
    btn.setAttribute('aria-label', target === 'zh' ? t.langZh : t.langEn);
    btn.addEventListener('click', function () { setLang(target); });
  });
}
