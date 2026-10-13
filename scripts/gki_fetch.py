import os
import re
import json
import base64
import time
import http.client
import urllib.request
import urllib.error
from datetime import datetime

REPO_URL = "https://android.googlesource.com/kernel/common"
BASE_URL = f"{REPO_URL}/+/refs/heads"
# gitiles 的 refs 列表接口，一次拿到全部分支名 / tag 名
REFS_URL = f"{REPO_URL}/+refs"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
DATA_DIR = os.path.join(PROJECT_ROOT, "data")

# (android版本, 内核版本): (起始日期, 结束日期, deprecated截止日期)
# 结束日期为 None 表示活跃版本，运行时自动使用当前月份
TARGETS = {
    ("android12", "5.10"): ("2021-08", "2025-12", "2024-08"),
    ("android13", "5.15"): ("2022-06", "2025-12", "2024-09"),
    ("android14", "6.1"):  ("2023-06", None,       "2024-09"),
    ("android15", "6.6"):  ("2024-10", None,       ""),
    ("android16", "6.12"): ("2025-06", None,       ""),
}

import binascii

ERRORS = (urllib.error.HTTPError, urllib.error.URLError,
          TimeoutError, http.client.RemoteDisconnected,
          ConnectionResetError, OSError, binascii.Error)


def get_end_date(end: str | None) -> str:
    """返回结束日期：如果为 None 则使用当前月份"""
    if end is not None:
        return end
    return datetime.now().strftime("%Y-%m")


def make_date_range(start: str, end: str) -> list[str]:
    """生成从 start 到 end 的 YYYY-MM 列表"""
    sy, sm = map(int, start.split("-"))
    ey, em = map(int, end.split("-"))
    dates = []
    y, m = sy, sm
    while (y, m) <= (ey, em):
        dates.append(f"{y}-{m:02d}")
        m += 1
        if m > 12:
            m = 1
            y += 1
    return dates


def try_fetch(url: str) -> str | None:
    """尝试请求一个 URL，失败返回 None"""
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            return base64.b64decode(resp.read()).decode("utf-8", errors="replace")
    except ERRORS:
        return None


def fetch_refs() -> tuple[set[str], set[str]] | None:
    """一次性获取上游全部分支名与 tag 名（不含 refs/heads、refs/tags 前缀），失败返回 None"""
    result = []
    for kind in ("heads", "tags"):
        url = f"{REFS_URL}/{kind}?format=JSON"
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
        except ERRORS:
            return None
        # gitiles 的 JSON 响应前面带一行防 XSSI 前缀
        if raw.startswith(")]}'"):
            raw = raw.split("\n", 1)[1]
        try:
            result.append(set(json.loads(raw).keys()))
        except ValueError:
            return None
    return result[0], result[1]


def resolve_ref(branch: str, heads: set[str], tags: set[str]) -> str | None:
    """按 build.yml 的规则解析月度分支在上游的实际位置：
    活跃分支 -> refs/heads/<branch>
    已迁移   -> refs/heads/deprecated/<branch>
    已删除   -> refs/tags/<branch>_rN（编号最大的发布 tag）
    都没有   -> None
    """
    if branch in heads:
        return f"refs/heads/{branch}"
    if f"deprecated/{branch}" in heads:
        return f"refs/heads/deprecated/{branch}"
    prefix = f"{branch}_r"
    best, best_n = None, -1
    for tag in tags:
        if tag.startswith(prefix) and tag[len(prefix):].isdigit():
            n = int(tag[len(prefix):])
            if n > best_n:
                best, best_n = tag, n
    return f"refs/tags/{best}" if best else None


def fetch_makefile(android_ver: str, kernel_ver: str, date: str,
                   dep_cutoff: str,
                   refs: tuple[set[str], set[str]] | None = None) -> str | None:
    """获取日期分支 Makefile。

    提供 refs 时直接按上游实际位置取（含分支已删除时的发布 tag）；
    否则退回逐个路径试探：优先预期路径，失败再回退。
    """
    branch = f"{android_ver}-{kernel_ver}-{date}"

    if refs is not None:
        ref = resolve_ref(branch, *refs)
        if ref is None:
            return None
        return try_fetch(f"{REPO_URL}/+/{ref}/Makefile?format=TEXT")

    if dep_cutoff and date <= dep_cutoff:
        paths = [f"deprecated/{branch}", branch]
    else:
        paths = [branch, f"deprecated/{branch}"]

    for p in paths:
        url = f"{BASE_URL}/{p}/Makefile?format=TEXT"
        text = try_fetch(url)
        if text is not None:
            return text
        time.sleep(0.3)
    return None


def refresh_refs(entries: list[dict], android_ver: str, kernel_ver: str,
                 refs: tuple[set[str], set[str]]) -> int:
    """为每条记录写入 / 更新 ref 字段（上游实际路径），返回变更条数。

    分支会随时间从活跃迁到 deprecated/ 再被删除，因此每次运行都要全量刷新。
    """
    heads, tags = refs
    changed = 0
    for entry in entries:
        branch = f"{android_ver}-{kernel_ver}-{entry['date']}"
        ref = resolve_ref(branch, heads, tags)
        if entry.get("ref") == ref:
            continue
        if ref is None:
            entry.pop("ref", None)
        else:
            entry["ref"] = ref
        changed += 1
    return changed


def fetch_lts(android_ver: str, kernel_ver: str) -> str | None:
    """获取 LTS 分支 Makefile"""
    lts_branch = f"{android_ver}-{kernel_ver}-lts"
    url = f"{BASE_URL}/{lts_branch}/Makefile?format=TEXT"
    return try_fetch(url)


def parse_version(makefile_text: str) -> tuple[str, str, str] | None:
    """从 Makefile 提取 VERSION, PATCHLEVEL, SUBLEVEL"""
    vals = {}
    for key in ("VERSION", "PATCHLEVEL", "SUBLEVEL"):
        m = re.search(rf"^{key}\s*=\s*(\d+)", makefile_text, re.MULTILINE)
        if not m:
            return None
        vals[key] = m.group(1)
    return vals["VERSION"], vals["PATCHLEVEL"], vals["SUBLEVEL"]


def json_path(android_ver: str, kernel_ver: str) -> str:
    """返回对应的 JSON 文件路径"""
    return os.path.join(DATA_DIR, android_ver, f"{kernel_ver}.json")
