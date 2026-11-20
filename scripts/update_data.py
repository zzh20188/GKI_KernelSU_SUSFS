"""增量更新 GKI 内核版本数据。

读取现有 JSON 数据，仅抓取缺失的月份，同时更新 LTS 版本，
并按上游实际位置（活跃 / deprecated/ / 发布 tag）刷新每条记录的 ref 字段。
"""

import json
import os
import time

from gki_fetch import (
    TARGETS, DATA_DIR,
    make_date_range, get_end_date,
    fetch_makefile, fetch_lts, fetch_refs, refresh_refs,
    parse_version, json_path,
)


def update_target(android_ver: str, kernel_ver: str,
                  date_start: str, date_end: str | None,
                  dep_cutoff: str,
                  refs: tuple[set[str], set[str]] | None = None) -> bool:
    """增量更新单个目标，返回是否有数据变更"""
    path = json_path(android_ver, kernel_ver)
    end = get_end_date(date_end)
    changed = False

    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        entries = data.get("entries", [])
        # 同步 deprecated_cutoff（来源从 TARGETS，不在 JSON 里手动维护）
        if data.get("deprecated_cutoff", "") != dep_cutoff:
            data["deprecated_cutoff"] = dep_cutoff
            changed = True
    else:
        data = {
            "android_version": android_ver,
            "kernel_version": kernel_ver,
            "deprecated_cutoff": dep_cutoff,
            "lts": None,
            "entries": [],
        }
        entries = []

    # 扫描完整日期范围并排除已有月份，以补齐历史缺失数据
    existing_dates = {e["date"] for e in entries}
    all_dates = make_date_range(date_start, end)
    new_dates = [d for d in all_dates if d not in existing_dates]

    if not new_dates:
        print(f"  No new months to fetch")
    else:
        print(f"  Fetching {len(new_dates)} new month(s): {new_dates[0]} ~ {new_dates[-1]}")
        for date in new_dates:
            label = f"{android_ver}-{kernel_ver}-{date}"
            print(f"    [{label}] ", end="", flush=True)

            text = fetch_makefile(android_ver, kernel_ver, date, dep_cutoff, refs)
            if text is None:
                print("not found, skip")
                continue

            ver = parse_version(text)
            if ver is None:
                print("parse failed, skip")
                continue

            version, patchlevel, sublevel = ver
            detail = f"{version}.{patchlevel}.{sublevel}"
            entries.append({"date": date, "kernel": detail})
            changed = True
            print(f"-> {detail}")
            time.sleep(0.3)

    entries.sort(key=lambda e: e["date"])

    # 上游位置会随时间变化（活跃 -> deprecated/ -> 仅剩 tag），每次全量刷新
    if refs is not None:
        ref_changes = refresh_refs(entries, android_ver, kernel_ver, refs)
        if ref_changes:
            changed = True
            print(f"  Refreshed upstream ref for {ref_changes} entr{'y' if ref_changes == 1 else 'ies'}")

    lts_label = f"{android_ver}-{kernel_ver}-lts"
    print(f"  [{lts_label}] ", end="", flush=True)
    lts_text = fetch_lts(android_ver, kernel_ver)
    if lts_text is None:
        print("not found, skip")
    else:
        ver = parse_version(lts_text)
        if ver is None:
            print("parse failed, skip")
        else:
            version, patchlevel, sublevel = ver
            lts_value = f"{version}.{patchlevel}.{sublevel}"
            old_lts = data.get("lts")
            if old_lts != lts_value:
                changed = True
                print(f"-> {lts_value} (was {old_lts})")
            else:
                print(f"-> {lts_value} (unchanged)")
            data["lts"] = lts_value

    data["entries"] = entries
    if changed:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        print(f"  => Saved {len(entries)} entries to {path}")
    else:
        print(f"  => No changes")

    return changed


def main():
    any_changed = False
    refs = fetch_refs()
    if refs is None:
        # refs 接口偶尔失败时不中断整次更新，只跳过 ref 刷新
        print("WARNING: failed to fetch upstream refs, ref fields will not be refreshed this run")
    else:
        print(f"Fetched upstream refs: {len(refs[0])} heads, {len(refs[1])} tags")
    for (android_ver, kernel_ver), (date_start, date_end, dep_cutoff) in TARGETS.items():
        print(f"\n=== {android_ver} / {kernel_ver} ===")
        if update_target(android_ver, kernel_ver, date_start, date_end, dep_cutoff, refs):
            any_changed = True

    print(f"\n{'Data updated.' if any_changed else 'All data up-to-date.'}")
    return any_changed


if __name__ == "__main__":
    import sys
    try:
        changed = main()
    except Exception as e:
        print(f"\nFATAL: {e}", file=sys.stderr)
        sys.exit(1)
    # 退出码 0 表示有变更，2 表示无变更；异常返回 1
    sys.exit(0 if changed else 2)
