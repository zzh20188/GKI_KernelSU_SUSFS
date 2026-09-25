import json
import os
import time

from gki_fetch import (
    TARGETS,
    make_date_range, get_end_date,
    fetch_makefile, fetch_lts, fetch_refs, refresh_refs,
    parse_version, json_path,
)


def fetch_all():
    refs = fetch_refs()
    if refs is None:
        print("WARNING: failed to fetch upstream refs, falling back to path probing")
    for (android_ver, kernel_ver), (date_start, date_end, dep_cutoff) in TARGETS.items():
        print(f"\n=== {android_ver} / {kernel_ver} ===")

        end = get_end_date(date_end)
        entries = []
        for date in make_date_range(date_start, end):
            label = f"{android_ver}-{kernel_ver}-{date}"
            print(f"  [{label}] ", end="", flush=True)

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
            print(f"-> {detail}")
            time.sleep(0.2)

        if refs is not None:
            refresh_refs(entries, android_ver, kernel_ver, refs)

        # 抓取 LTS
        lts_label = f"{android_ver}-{kernel_ver}-lts"
        print(f"  [{lts_label}] ", end="", flush=True)
        lts_text = fetch_lts(android_ver, kernel_ver)
        lts_value = None
        if lts_text is None:
            print("not found, skip")
        else:
            ver = parse_version(lts_text)
            if ver is None:
                print("parse failed, skip")
            else:
                version, patchlevel, sublevel = ver
                lts_value = f"{version}.{patchlevel}.{sublevel}"
                print(f"-> {lts_value}")

        if not entries and lts_value is None:
            print(f"  No data for {android_ver}/{kernel_ver}\n")
            continue

        data = {
            "android_version": android_ver,
            "kernel_version": kernel_ver,
            "deprecated_cutoff": dep_cutoff,
            "lts": lts_value,
            "entries": entries,
        }

        out = json_path(android_ver, kernel_ver)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

        print(f"  => Saved {len(entries)} entries to {out}\n")

    print("Done.")


if __name__ == "__main__":
    fetch_all()
