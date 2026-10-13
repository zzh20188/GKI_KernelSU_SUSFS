/**
 * 公告「号外」：原生 <dialog>，含简易 Markdown 渲染与「今日不再显示」
 */

import { lang } from './i18n.js';
import { esc, fetchJsonFresh, getStored, setStored } from './utils.js';
import { openDialog, closeDialog } from './modal.js';

var dialog = document.getElementById('announceDialog');
var bodyEl = document.getElementById('announceBody');
var dateEl = document.getElementById('announceDate');
var closeBtn = document.getElementById('announceClose');
var okBtn = document.getElementById('announceOk');
var dismissCheck = document.getElementById('announceDismissToday');
var openBtn = document.getElementById('announceOpen');
var loaded = false;

var DISMISS_KEY = 'announce_dismiss';
var SEEN_KEY = 'announce_seen';

// 获取当前本地日期字符串
function localDateStr() {
  var d = new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

var getItem = getStored;
var setItem = setStored;
var latestDate = '';

// ---- 简易 Markdown 渲染 ----

// 渲染行内 Markdown（代码、链接、加粗、斜体）
function renderInlineMarkdown(text) {
  var html = esc(text == null ? '' : String(text));
  var codeTokens = [];

  // 先把行内代码换成占位符，避免其中的星号、方括号被后续规则误伤
  html = html.replace(/`([^`\n]+)`/g, function (_, codeText) {
    codeTokens.push('<code>' + codeText + '</code>');
    return '<!--CODE' + (codeTokens.length - 1) + '-->';
  });

  html = html.replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, function (_, label, url) {
    var safe = /^(https?:\/\/|\/|\.\/|[\w-]+\.html)/i.test(url) ? url : '#';
    var external = /^https?:/i.test(safe) ? ' target="_blank" rel="noopener noreferrer"' : '';
    return '<a href="' + safe + '"' + external + '>' + label + '</a>';
  });
  html = html.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*\n]+)\*/g, '<em>$1</em>');

  return html.replace(/<!--CODE(\d+)-->/g, function (_, idx) {
    return codeTokens[Number(idx)] || '';
  });
}

// 渲染块级 Markdown（标题、列表、引用、代码块、段落）
function renderMarkdown(markdownText) {
  var normalized = String(markdownText == null ? '' : markdownText)
    .replace(/\r\n?/g, '\n')
    .trim();
  if (!normalized) return '';

  var blocks = normalized.split(/\n{2,}/);
  var out = [];

  blocks.forEach(function (block) {
    var lines = block.split('\n');
    var fence = block.match(/^```([a-zA-Z0-9_-]+)?\n([\s\S]*?)\n```$/);
    if (fence) {
      var langClass = fence[1] ? ' class="lang-' + fence[1].toLowerCase() + '"' : '';
      out.push('<pre><code' + langClass + '>' + esc(fence[2]) + '</code></pre>');
      return;
    }

    var heading = block.match(/^\s*(#{1,6})\s+(.+)$/);
    if (heading && lines.length === 1) {
      var level = heading[1].length;
      out.push('<h' + level + '>' + renderInlineMarkdown(heading[2]) + '</h' + level + '>');
      return;
    }

    if (lines.every(function (line) { return /^\s*>\s?/.test(line); })) {
      out.push('<blockquote>' + lines.map(function (line) {
        return renderInlineMarkdown(line.replace(/^\s*>\s?/, ''));
      }).join('<br>') + '</blockquote>');
      return;
    }

    // 同一块内允许「段落行 + 列表行」混排：按行首标记切成若干段分别渲染
    var segments = [];
    lines.forEach(function (line) {
      var kind = /^\s*[-*]\s+/.test(line) ? 'ul' : /^\s*\d+\.\s+/.test(line) ? 'ol' : 'p';
      var seg = segments[segments.length - 1];
      if (!seg || seg.kind !== kind) { seg = { kind: kind, lines: [] }; segments.push(seg); }
      seg.lines.push(line);
    });
    segments.forEach(function (seg) {
      if (seg.kind === 'ul') {
        out.push('<ul>' + seg.lines.map(function (line) {
          return '<li>' + renderInlineMarkdown(line.replace(/^\s*[-*]\s+/, '')) + '</li>';
        }).join('') + '</ul>');
      } else if (seg.kind === 'ol') {
        out.push('<ol>' + seg.lines.map(function (line) {
          return '<li>' + renderInlineMarkdown(line.replace(/^\s*\d+\.\s+/, '')) + '</li>';
        }).join('') + '</ol>');
      } else {
        out.push('<p>' + seg.lines.map(renderInlineMarkdown).join('<br>') + '</p>');
      }
    });
  });

  return out.join('');
}

// 标准化公告文本（数组按行拼接）
function normalizeAnnouncementText(value) {
  if (Array.isArray(value)) return value.join('\n');
  if (value == null) return '';
  return String(value);
}

// 根据当前语言获取公告内容
function getAnnouncementText(item) {
  if (lang === 'zh') {
    return normalizeAnnouncementText(item.content_zh_md || item.content_zh || item.content_md || item.content);
  }
  return normalizeAnnouncementText(item.content_md || item.content || item.content_zh_md || item.content_zh);
}

function hideAnnounce() {
  if (dismissCheck.checked) setItem(DISMISS_KEY, localDateStr());
  closeDialog(dialog);
}

// auto = 页面加载时自动弹出：焦点落在对话框本身，避免首屏出现突兀的按钮焦点环
function showAnnounce(auto) {
  dismissCheck.checked = false;
  openBtn.classList.remove('has-unread');
  openBtn.removeAttribute('data-unread');
  // 真正展示给用户时才记为已读
  if (latestDate) setItem(SEEN_KEY, latestDate);
  openDialog(dialog);
  if (auto) dialog.focus();
  else okBtn.focus();
}

export function initAnnouncement() {
  closeBtn.addEventListener('click', hideAnnounce);
  okBtn.addEventListener('click', hideAnnounce);
  dialog.addEventListener('click', function (e) {
    if (e.target === dialog) hideAnnounce();
  });
  // Esc 关闭时同样尊重「今日不再显示」
  dialog.addEventListener('cancel', function () {
    if (dismissCheck.checked) setItem(DISMISS_KEY, localDateStr());
  });
  openBtn.addEventListener('click', function () {
    if (!loaded) return;
    showAnnounce(false);
  });

  loadAnnouncement();
}

async function loadAnnouncement() {
  try {
    var json = await fetchJsonFresh('data/announcement.json');
    var items = json.items;
    if (!items || items.length === 0) return;

    var html = '';
    items.forEach(function (item) {
      var text = getAnnouncementText(item);
      var markdownHtml = renderMarkdown(text);
      if (!markdownHtml) return;
      var dateText = item.date ? esc(item.date) : '';
      if (item.date && item.date > latestDate) latestDate = item.date;
      html += '<article class="announce__item">' +
        '<div class="md">' + markdownHtml + '</div>' +
        (dateText ? '<div class="announce__meta"><time class="announce__date" datetime="' + dateText + '">' + dateText + '</time></div>' : '') +
        '</article>';
    });
    if (!html) return;

    bodyEl.innerHTML = html;
    dateEl.textContent = latestDate;
    loaded = true;
    openBtn.hidden = false;

    // 未读点：最新公告日期比上次真正看过的新
    var seen = getItem(SEEN_KEY) || '';
    var unread = latestDate > seen;
    openBtn.classList.toggle('has-unread', unread);
    if (unread) openBtn.setAttribute('data-unread', '1');

    if (getItem(DISMISS_KEY) === localDateStr()) return;
    showAnnounce(true);
  } catch (e) {
    // 公告为可选内容，加载失败不阻断主页面
  }
}
