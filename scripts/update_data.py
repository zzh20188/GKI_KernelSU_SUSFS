"""增量更新 GKI 内核版本数据。

读取现有 JSON 数据，仅抓取缺失的月份，同时更新 LTS 版本。
"""

import json
import os
import time

from gki_fetch import (
    TARGETS, DATA_DIR,
    make_date_range, get_end_date,
    fetch_makefile, fetch_lts, parse_version, json_path,
)


def update_target(android_ver: str, kernel_ver: str,
                  date_start: str, date_end: str | None,
                  dep_cutoff: str) -> bool:
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

            text = fetch_makefile(android_ver, kernel_ver, date, dep_cutoff)
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
    for (android_ver, kernel_ver), (date_start, date_end, dep_cutoff) in TARGETS.items():
        print(f"\n=== {android_ver} / {kernel_ver} ===")
        if update_target(android_ver, kernel_ver, date_start, date_end, dep_cutoff):
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
