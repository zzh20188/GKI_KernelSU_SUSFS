#!/usr/bin/env python3
"""Read-only Xiaomi pandora OTA input audit. Outputs a provenance report, not a boot image."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

PAGE = 4096
MAX_ZIP_SIZE = 950 * 1024 * 1024
MAX_MEMBER_SIZE = 650 * 1024 * 1024
MAX_UNCOMPRESSED = 1900 * 1024 * 1024
BUILD_RE = re.compile(r"^OS[0-9]+(?:\.[0-9]+)+\.[A-Z0-9]+(?:\.D[0-9]{2})?$")
BUILD_ORDER_RE = re.compile(r"^OS([0-9]+(?:\.[0-9]+)+)\.([A-Z0-9]+)(?:\.D([0-9]{2}))?$")
RELEASE_RE = re.compile(rb"Linux version ([0-9]+\.[0-9]+\.[0-9]+-android[0-9]+-[0-9]+-[^\x00\r\n ]+)")
UNAME_RE = re.compile(r"^([0-9]+\.[0-9]+\.[0-9]+)-android([0-9]+)-([0-9]+)-[A-Za-z0-9_.-]+$")


def abort(message: str) -> None:
    raise SystemExit("ERROR: " + message)


def build_order(value: str) -> tuple[tuple[int, ...], str, int]:
    """Parse firmware build and optional software revision without conflating them."""
    match = BUILD_ORDER_RE.fullmatch(value)
    if not match:
        abort("unrecognized HyperOS build ordering: " + value)
    return (tuple(int(part) for part in match.group(1).split(".")),
            match.group(2), int(match.group(3) or "0"))


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def env(path: Path) -> dict[str, str]:
    data = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        data[k] = v
    return data


def members(z: zipfile.ZipFile, target: str) -> list[zipfile.ZipInfo]:
    return [x for x in z.infolist()
            if not x.is_dir() and x.filename.replace("\\", "/").rsplit("/", 1)[-1].lower() == target.lower()]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", type=Path, required=True)
    ap.add_argument("--stock-config", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--github-asset-id", default="")
    args = ap.parse_args()
    if not args.zip.is_file() or args.zip.stat().st_size > MAX_ZIP_SIZE:
        abort("missing/oversized xm.zip")
    with zipfile.ZipFile(args.zip) as z:
        info = z.infolist()
        if len(info) > 40 or sum(x.file_size for x in info) > MAX_UNCOMPRESSED:
            abort("unexpected number/size of ZIP members")
        if any(x.file_size > MAX_MEMBER_SIZE or x.flag_bits & 0x1 for x in info):
            abort("oversized or encrypted ZIP member")
        bo = [x for x in z.infolist() if not x.is_dir() and
              re.fullmatch(r"boot(?:_[^/]*)?\.img", x.filename.replace("\\", "/").rsplit("/", 1)[-1], re.I)]
        rd = members(z, "README.txt")
        if len(bo) != 1 or len(rd) > 1:
            abort("ZIP must contain exactly one boot*.img and at most one README.txt")
        boot = z.read(bo[0])
        readme = z.read(rd[0]).decode("utf-8", "replace") if rd else ""
    if not rd:
        # Old OS4.0.5.0 input contained only boot and init_boot. It may be
        # recognized ONLY by the previously confirmed exact stock boot SHA.
        # New OTA inputs without version/slot/uname manifest are rejected.
        previous = env(args.stock_config)
        boot_hash = sha(boot)
        if boot_hash != previous.get("OS4_STOCK_BOOT_SHA256", ""):
            abort("new OTA requires README.txt; legacy two-image zip is only accepted for the already pinned exact boot SHA")
        report = {
            "schema": 1, "status": "SAME_PINNED_BUILD", "device": "pandora",
            "build": previous.get("OS4_STOCK_INPUT_BUILD", ""), "slot": "UNKNOWN_LEGACY",
            "uname": "UNKNOWN_LEGACY", "kernel_version": "UNKNOWN_LEGACY",
            "kmi": "UNKNOWN_LEGACY", "kernel_release_marker": "NOT_INSPECTED_LEGACY",
            "stock_boot_sha256": boot_hash, "ota_source_id": sha(args.zip.read_bytes()),
            "github_asset_id": args.github_asset_id, "may_build_gki_candidate": False,
            "source_provenance": "LEGACY_PINNED_BOOT_SHA256_ONLY",
            "boot_oem_original_verified": False,
            "boot_role": "GKI_KERNEL_ANALYSIS_INPUT",
            "init_boot_oem_original_verified": False,
            "init_boot_role": "DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE",
            "init_boot_source": "LEGACY_UNKNOWN_MAY_BE_ROOT_PATCHED",
            "safe_to_flash": False, "fixture_promoted": False, "lkg_unchanged": True,
            "next_gate": "NOOP_CURRENT_BUILD",
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2, ensure_ascii=False))
        print("OTA_AUTO_INTAKE=NO_CHANGE_LEGACY_PINNED_SHA")
        return
    fields = {}
    for line in readme.splitlines():
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        if k in fields:
            abort("duplicated README key " + k)
        fields[k] = v
    if fields.get("device") != "pandora":
        abort("device must be pandora")
    # For rooted mobile collections these labels are mandatory when present.
    # Old v1 mobile ZIPs had no per-partition labels: they remain readable,
    # but their provenance is NEVER silently elevated to OEM original.
    expected_snapshot = {
        "boot_source": "ACTIVE_SLOT_UNVERIFIED_OEM",
        "boot_oem_original_verified": "NO",
        "boot_role": "GKI_KERNEL_ANALYSIS_INPUT",
        "init_boot_source": "ROOTED_ACTIVE_SLOT_MAY_BE_PATCHED",
        "init_boot_oem_original_verified": "NO",
        "init_boot_role": "DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE",
        "vendor_boot_source": "ACTIVE_SLOT_UNVERIFIED_OEM",
        "dtbo_source": "ACTIVE_SLOT_UNVERIFIED_OEM",
    }
    if fields.get("collected_from_live_device") == "YES":
        for key, expected in expected_snapshot.items():
            if key in fields and fields[key] != expected:
                abort("invalid or falsely verified OEM image provenance: " + key)
    build = fields.get("build", "")
    manual_revision = fields.get("software_revision", "")
    if manual_revision or any(k in fields for k in
                              ("software_revision_source", "software_revision_base_build")):
        if (not re.fullmatch(r"D[0-9]{2}", manual_revision)
                or fields.get("software_revision_source") != "USER_CONFIRMED_UPDATE_SCREEN"
                or fields.get("software_revision_base_build") != build
                or re.search(r"\.D[0-9]{2}$", build)):
            abort("invalid manually asserted software revision provenance")
    if not BUILD_RE.fullmatch(build):
        abort("invalid HyperOS build")
    if fields.get("slot") not in ("_a", "_b"):
        abort("invalid active slot")
    uname = fields.get("uname", "")
    m = UNAME_RE.fullmatch(uname)
    if not m:
        abort("unrecognized kernel release in README")
    patch, android, kmi_generation = m.groups()
    if len(boot) < PAGE or boot[:8] != b"ANDROID!":
        abort("invalid Android boot image")
    kernel_size, ramdisk_size, os_version, header_size = struct.unpack_from("<IIII", boot, 8)
    version = struct.unpack_from("<I", boot, 40)[0]
    signature_size = struct.unpack_from("<I", boot, 1580)[0]
    if version != 4 or header_size != 1584 or kernel_size < 1024:
        abort("unknown boot layout: require boot v4/header_size 1584")
    if PAGE + kernel_size > len(boot):
        abort("truncated boot kernel")
    kernel = boot[PAGE:PAGE + kernel_size]
    marker = RELEASE_RE.search(kernel)
    embedded = marker.group(1).decode("ascii") if marker else ""
    if embedded and not embedded.startswith(f"{patch}-android{android}-{kmi_generation}-"):
        abort("embedded boot kernel disagrees with README uname")
    stock = env(args.stock_config)
    pinned = stock.get("OS4_STOCK_INPUT_BUILD", "")
    expected_boot = stock.get("OS4_STOCK_BOOT_SHA256", "")
    boot_hash = sha(boot)
    if manual_revision and build_order(build)[:2] != build_order(pinned)[:2]:
        abort("software revision evidence cannot masquerade as a new firmware build")
    if build == pinned:
        # Explicit update-screen testimony is review-only, even when the
        # Android Build and boot partition remain byte-for-byte unchanged.
        state = ("SOFTWARE_OTA_REVIEW" if manual_revision else
                 "SAME_PINNED_BUILD" if boot_hash == expected_boot else
                 "SAME_BUILD_BOOT_DRIFT_REVIEW")
    else:
        incoming_order, incoming_channel, incoming_revision = build_order(build)
        pinned_order, pinned_channel, pinned_revision = build_order(pinned)
        if incoming_channel != pinned_channel:
            abort("HyperOS build channel changed; require manual stock-input review")
        if incoming_order < pinned_order or (incoming_order == pinned_order
                                               and incoming_revision <= pinned_revision):
            abort("older/non-forward HyperOS build; refuse automated stock-input replacement")
        # D02-like app/software revisions are evidence, not a proved new kernel.
        # Do not resolve a GKI candidate or overwrite the pinned stock-input.
        state = ("SOFTWARE_OTA_REVIEW" if incoming_order == pinned_order
                 else "NEW_OTA_CANDIDATE")
    kmi = f"android{android}-{kmi_generation}"
    can_build = state == "NEW_OTA_CANDIDATE" and android == "16" and kmi_generation == "6" and patch.startswith("6.12.")
    report = {
        "schema": 1, "status": state, "device": "pandora", "build": build, "slot": fields["slot"],
        "software_revision": manual_revision,
        "software_revision_source": ("USER_CONFIRMED_UPDATE_SCREEN" if manual_revision else "NONE"),
        "software_revision_verified_by_device": False,
        "uname": uname, "kernel_release_marker": embedded or "NOT_FOUND",
        "kernel_marker_verified": bool(embedded), "kernel_version": patch, "kmi": kmi,
        "pinned_build": pinned, "stock_boot_sha256": boot_hash, "stock_header_sha256": sha(boot[:PAGE]),
        "stock_kernel_sha256": sha(kernel), "boot_size": len(boot),
        "header_version": version, "header_size": header_size, "kernel_size": kernel_size,
        "ramdisk_size": ramdisk_size, "signature_size": signature_size,
        "ota_source_id": sha(args.zip.read_bytes()), "github_asset_id": args.github_asset_id,
        "may_build_gki_candidate": can_build,
        # An embedded kernel marker verifies version consistency, NOT OEM
        # image originality. The rooted init_boot is never flashable stock.
        "source_provenance": "STOCK_INPUT_UNVERIFIED_OEM" if not embedded else "BOOT_KERNEL_MARKER_AND_README_AGREE",
        "boot_oem_original_verified": False,
        "boot_source": fields.get("boot_source", "UNVERIFIED_INPUT"),
        "boot_role": "GKI_KERNEL_ANALYSIS_INPUT",
        "init_boot_oem_original_verified": False,
        "init_boot_source": fields.get("init_boot_source", "UNVERIFIED_INPUT_MAY_BE_ROOT_PATCHED"),
        "init_boot_role": "DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE",
        "init_boot_may_be_root_patched": True,
        "vendor_boot_oem_original_verified": False,
        "dtbo_oem_original_verified": False,
        "safe_to_flash": False, "fixture_promoted": False, "lkg_unchanged": True,
        "next_gate": ("NOOP_CURRENT_BUILD" if state == "SAME_PINNED_BUILD" else
                      "HUMAN_REVIEW_STOCK_BOOT_DRIFT" if state == "SAME_BUILD_BOOT_DRIFT_REVIEW" else
                      "SOFTWARE_OTA_EVIDENCE_REVIEW" if state == "SOFTWARE_OTA_REVIEW" else
                      "AUTO_RESOLVE_GKI_CANDIDATE" if can_build else "MANUAL_KMI_SOURCE_REVIEW"),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if state == "SAME_BUILD_BOOT_DRIFT_REVIEW":
        abort("same HyperOS build but stock boot changed; refuse automated input")
    if state == "NEW_OTA_CANDIDATE" and not can_build:
        print("::warning::New OTA changes GKI family/KMI. Recorded for human source review; no build/boot allowed.")
    if state == "SAME_PINNED_BUILD":
        print("OTA_AUTO_INTAKE=NO_CHANGE")
    elif can_build:
        print("OTA_AUTO_INTAKE=NEW_OTA_GKI_CANDIDATE")
    else:
        print("OTA_AUTO_INTAKE=REVIEW_REQUIRED")


if __name__ == "__main__":
    main()
