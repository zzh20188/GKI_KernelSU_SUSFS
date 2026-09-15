/**
 * 构建参数「参数单」：原生 <dialog>，点击版本行或 LTS 打开
 */

import { t } from './i18n.js';
import { esc, fmt } from './utils.js';
import { LINKS, REPO_REV } from './config.js';
import { refKindOf } from './model.js';

var dialog = document.getElementById('paramsDialog');
var eyebrowEl = document.getElementById('paramsEyebrow');
var titleEl = document.getElementById('paramsTitle');
var slugEl = document.getElementById('paramsSlug');
var bodyEl = document.getElementById('paramsBody');
var footEl = document.getElementById('paramsFoot');
var closeBtn = document.getElementById('paramsClose');
var lastTrigger = null;

function specRow(label, value) {
  return '<div class="spec__row spec__row--copyable">' +
      '<dt class="spec__label">' + esc(label) + '</dt>' +
      '<dd class="spec__value"><span class="spec__value-wrap"><span>' + esc(value) + '</span>' +
        '<button type="button" class="spec__copy copy-btn" data-copy="' + esc(value) + '" data-copy-what="' + esc(label) + '">' + esc(t.copy) + '</button>' +
      '</span></dd>' +
    '</div>';
}

function cmdBlock(label, what, cmd, hint) {
  return '<div class="cmd">' +
      '<div class="cmd__head"><span>' + esc(label) + '</span>' +
        '<button type="button" class="cmd__copy copy-btn" data-copy="' + esc(cmd) + '" data-copy-what="' + esc(what) + '">' + esc(t.copy) + '</button>' +
      '</div>' +
      '<pre class="cmd__body"><code>' + esc(cmd) + '</code></pre>' +
      (hint ? '<p class="cmd__hint">' + esc(hint) + '</p>' : '') +
    '</div>';
}

/**
 * 打开参数单
 * @param {object} p  { android, kernel, sublevel, patch, version, deprecated, susfs, isLts, susfsMinKernel, trigger }
 */
export function showParams(p) {
  var branch = p.android + '-' + p.kernel + '-' + p.patch;
  var manifestBranch = 'common-' + branch;
  var repoInitCmd = 'repo init --depth=1 -u ' + LINKS.aospManifest + ' -b ' + manifestBranch + ' --repo-rev=' + REPO_REV;
  var susfsBranch = 'gki-' + p.android + '-' + p.kernel;
  // 有探测数据时，克隆命令指向探测实际用的仓库（SukiSU 用 ShirkNeko fork）
  var susfsRepo = /^https?:\/\//.test(p.probeRepo || '') ? p.probeRepo.replace(/\.git$/, '') : LINKS.susfs;
  var susfsCloneCmd = 'git clone ' + susfsRepo + '.git -b ' + susfsBranch;
  var version = p.version || (p.kernel + '.' + p.sublevel);

  // 上游实际位置：优先用数据里的 ref；LTS 与缺 ref 的条目退回按分支名猜测
  var ref = p.ref || '';
  var refKind = refKindOf(ref);
  if (refKind === 'unknown') {
    ref = 'refs/heads/' + (p.deprecated && p.patch !== 'lts' ? 'deprecated/' : '') + branch;
  }
  var sourceUrl = LINKS.aospCommon + '/+/' + ref;
  var tagName = refKind === 'tag' ? ref.replace(/^refs\/tags\//, '') : '';

  // 与 build.yml 一致：分支已迁到 deprecated/ 时改写 manifest 里的分支名；
  // 分支已被删除时把 manifest 的 revision 指向发布 tag
  var manifestFixCmd = '';
  var manifestHint = '';
  if (refKind === 'deprecated') {
    manifestFixCmd = 'sed -i \'s/"' + branch + '"/"deprecated\\/' + branch + '"/g\' .repo/manifests/default.xml';
    manifestHint = t.modalManifestHint;
  } else if (refKind === 'tag') {
    manifestFixCmd = 'sed -i \'/path="common"/ s|revision="' + branch + '"|revision="refs/tags/' + tagName + '"|\' .repo/manifests/default.xml';
    manifestHint = fmt(t.modalManifestTagHint, { tag: tagName });
  }

  eyebrowEl.innerHTML = '<span>' + esc(t.modalTitle) + '</span><span>' + esc(p.android) + ' · ' + esc(p.patch) + '</span>';
  titleEl.textContent = version;
  slugEl.innerHTML = '<code>' + esc(manifestBranch) + '</code>' +
    '<button type="button" class="copy-btn" data-copy="' + esc(manifestBranch) + '" data-copy-what="' + esc(t.modalBranch) + '">' + esc(t.copy) + '</button>';

  var status = '';
  if (p.isLts) status += '<div class="status-line"><span>' + esc(fmt(t.statusLts, { branch: branch })) + '</span></div>';
  if (p.deprecated) status += '<div class="status-line status-line--dep"><span>' + esc(t.statusDep) + '</span></div>';
  if (p.probe) {
    // 有探测数据：直接给出原始补丁的 rej 数与编译结果
    var probeVars = { rej: p.probeRej || 0, variant: p.probeVariant || '?', date: p.probeDate || '?', commit: p.probeCommit || '?' };
    if (p.probe === 'clean') status += '<div class="status-line status-line--susfs"><span>' + esc(fmt(t.statusProbeClean, probeVars)) + '</span></div>';
    else if (p.probe === 'built_with_rej') status += '<div class="status-line status-line--susfs-rej"><span>' + esc(fmt(t.statusProbeRej, probeVars)) + '</span></div>';
    else status += '<div class="status-line status-line--upstream"><span>' + esc(fmt(t.statusProbeFailed, probeVars)) + '</span></div>';
  } else if (p.susfs) {
    status += '<div class="status-line status-line--susfs"><span>' + esc(fmt(t.statusSusfs, { kernel: p.susfsMinKernel || '' })) + '</span></div>';
  }
  if (refKind === 'deprecated') status += '<div class="status-line status-line--upstream"><span>' + esc(t.statusUpstreamMoved) + '</span></div>';
  if (refKind === 'tag') status += '<div class="status-line status-line--upstream"><span>' + esc(fmt(t.statusUpstreamTag, { tag: tagName })) + '</span></div>';

  bodyEl.innerHTML =
    (status ? '<div class="dialog__status">' + status + '</div>' : '') +
    '<dl class="spec">' +
      specRow(t.modalAndroid, p.android) +
      specRow(t.modalKernel, p.kernel) +
      specRow(t.modalSublevel, String(p.sublevel)) +
      specRow(t.modalPatch, p.patch) +
    '</dl>' +
    cmdBlock(t.modalRepoInit, t.modalRepoInitWhat, repoInitCmd) +
    (manifestFixCmd ? cmdBlock(t.modalManifest, t.modalManifestWhat, manifestFixCmd, manifestHint) : '') +
    cmdBlock(t.modalSusfsClone, t.modalSusfsCloneWhat, susfsCloneCmd) +
    '<p class="dialog__hint">' + esc(t.modalHint) + '</p>';

  footEl.innerHTML =
    '<a class="dialog__link" href="' + esc(sourceUrl) + '" target="_blank" rel="noopener">' + esc(t.modalOpenSource) +
      '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M3 9l6-6M4 3h5v5"/></svg></a>' +
    '<a class="dialog__link" href="guide.html">' + esc(t.modalGuide) + ' →</a>';

  lastTrigger = p.trigger || null;
  openDialog(dialog);
  closeBtn.focus();
}

export function openDialog(d) {
  if (!d) return;
  if (typeof d.showModal === 'function') {
    if (!d.open) d.showModal();
  } else {
    d.setAttribute('open', '');
  }
}

export function closeDialog(d) {
  if (!d) return;
  if (typeof d.close === 'function' && d.open) d.close();
  else d.removeAttribute('open');
}

export function hideParams() {
  closeDialog(dialog);
}

export function initModal() {
  closeBtn.addEventListener('click', hideParams);

  // 点击遮罩关闭：只有点在 dialog 元素自身（内容区之外）时
  dialog.addEventListener('click', function (e) {
    if (e.target === dialog) hideParams();
  });

  dialog.addEventListener('close', function () {
    // 关闭后焦点回到触发行，并给该行留下「刚看过」的朱砂左边线
    if (lastTrigger) {
      document.querySelectorAll('.row.is-visited').forEach(function (r) { r.classList.remove('is-visited'); });
      var row = lastTrigger.closest('.row');
      if (row) row.classList.add('is-visited');
      if (typeof lastTrigger.focus === 'function') lastTrigger.focus({ preventScroll: true });
    }
  });

  // 事件委托：版本行与 LTS 按钮
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.row__btn, .chapter__lts');
    if (!btn) return;
    var panel = btn.closest('.branch-panel');
    var ledger = panel ? panel.querySelector('.ledger') : null;
    showParams({
      android: btn.dataset.android,
      kernel: btn.dataset.kernel,
      sublevel: btn.dataset.sublevel,
      patch: btn.dataset.patch,
      version: btn.dataset.version,
      deprecated: btn.dataset.deprecated === '1',
      susfs: btn.dataset.susfs === '1',
      isLts: btn.dataset.lts === '1',
      ref: btn.dataset.ref || '',
      susfsMinKernel: ledger ? ledger.dataset.susfsMin : '',
      probe: btn.dataset.probe || '',
      probeRej: btn.dataset.probeRej || '0',
      probeVariant: ledger ? ledger.dataset.probeVariant : '',
      probeDate: ledger ? ledger.dataset.probeDate : '',
      probeCommit: ledger ? ledger.dataset.probeCommit : '',
      probeRepo: ledger ? ledger.dataset.probeRepo : '',
      trigger: btn,
    });
  });
}
