#!/usr/bin/env python3
"""Guard mobile OTA replacement against the CURRENT fixed stock-input release."""
from __future__ import annotations
import argparse
import hashlib
import re
import zipfile
from pathlib import Path

BUILD_RE = re.compile(r"^OS([0-9]+(?:\.[0-9]+)+)\.([A-Z0-9]+)(?:\.D([0-9]{2}))?$")


def abort(message: str) -> None:
    raise SystemExit("ERROR: " + message)


def build_order(value: str) -> tuple[tuple[int, ...], str, int]:
    match = BUILD_RE.fullmatch(value)
    if not match:
        abort("unrecognized HyperOS build ordering: " + value)
    return (tuple(int(x) for x in match.group(1).split(".")),
            match.group(2), int(match.group(3) or "0"))


def build_from_zip(path: Path, fallback: str) -> tuple[str, bool]:
    with zipfile.ZipFile(path) as archive:
        matches = [item for item in archive.infolist()
                   if not item.is_dir()
                   and item.filename.replace("\\", "/").rsplit("/", 1)[-1] == "README.txt"]
        if len(matches) > 1:
            abort("current fixed stock-input has multiple README.txt files")
        if len(matches) == 1:
            fields: dict[str, str] = {}
            for row in archive.read(matches[0]).decode("utf-8", "replace").splitlines():
                if "=" not in row:
                    continue
                key, value = row.split("=", 1)
                if key in fields:
                    abort("current fixed stock-input has duplicate README key: " + key)
                fields[key] = value.strip()
            value = fields.get("build", "")
            if not value:
                abort("current fixed stock-input README lacks build")
            build_order(value)
            return value, False
    build_order(fallback)
    return fallback, True


def file_sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(262144), b""):
            digest.update(block)
    return digest.hexdigest()


def decide(current_zip: Path, incoming_zip: Path, incoming_build: str,
           fallback_build: str, incoming_state: str) -> tuple[str, str]:
    incoming_order, incoming_channel, incoming_revision = build_order(incoming_build)
    current_build, legacy_fallback = build_from_zip(current_zip, fallback_build)
    current_order, current_channel, current_revision = build_order(current_build)

    if incoming_channel != current_channel:
        abort("HyperOS build channel differs from current fixed stock-input")
    if (incoming_order, incoming_revision) < (current_order, current_revision):
        abort("incoming HyperOS build is older than current fixed stock-input")

    if incoming_state == "SOFTWARE_OTA_REVIEW" and incoming_order != current_order:
        abort("software-revision evidence cannot auto-replace a different kernel build")

    if incoming_order == current_order:
        if incoming_revision > current_revision:
            if incoming_state not in ("SOFTWARE_OTA_REVIEW", "NEW_OTA_CANDIDATE"):
                abort("software revision was not classified as a supported OTA")
            # The pinned legacy config may predate the current full OTA. Even when
            # inspector called it NEW_OTA_CANDIDATE, the CURRENT fixed Release
            # proves this is only a higher Dxx revision of the same build.
            # Capture remains in its source Draft; NO fixed input mutation or GKI dispatch.
            return "SOFTWARE_REVIEW", current_build
        if incoming_build != current_build:
            abort("ambiguous HyperOS build ordering at equal numeric version")
        if incoming_state == "SOFTWARE_OTA_REVIEW":
            # Same real Android Build but a user-confirmed update-screen Dxx
            # is evidence only; never treat a manually asserted label as a
            # new kernel, canonical stock input or verified software release.
            with zipfile.ZipFile(incoming_zip) as incoming:
                raw = incoming.read("README.txt").decode("utf-8", "strict")
            rows = {}
            for line in raw.splitlines():
                if "=" not in line:
                    continue
                key, value = line.split("=", 1)
                if key in rows:
                    abort("duplicate incoming software revision evidence key")
                rows[key] = value
            manual_fields = {"software_revision", "software_revision_source",
                             "software_revision_base_build"}
            if manual_fields.intersection(rows):
                if (re.fullmatch(r"D[0-9]{2}", rows.get("software_revision", "")) is None
                        or rows.get("software_revision_source") != "USER_CONFIRMED_UPDATE_SCREEN"
                        or rows.get("software_revision_base_build") != incoming_build):
                    abort("same-build software review requires explicit bound user testimony")
                return "SOFTWARE_REVIEW", current_build
            # A property-reported .Dxx at the same current Build must still
            # pass the original exact-ZIP equality check below.
        # The original fixed OS4 input is a legacy two-image ZIP without README.
        # inspect-ota-stock-input.py already proved the exact pinned boot SHA.
        if legacy_fallback and incoming_state == "SAME_PINNED_BUILD":
            return "ALREADY_CURRENT", current_build
        if file_sha(current_zip) == file_sha(incoming_zip):
            return "ALREADY_CURRENT", current_build
        abort("same current OTA build has different canonical ZIP bytes; manual review required")

    return "FORWARD", current_build


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current-zip", type=Path, required=True)
    parser.add_argument("--incoming-zip", type=Path, required=True)
    parser.add_argument("--incoming-build", required=True)
    parser.add_argument("--fallback-current-build", required=True)
    parser.add_argument("--incoming-state", required=True,
                        choices=("SAME_PINNED_BUILD", "NEW_OTA_CANDIDATE", "SOFTWARE_OTA_REVIEW"))
    args = parser.parse_args()
    for item in (args.current_zip, args.incoming_zip):
        if not item.is_file():
            abort("missing ZIP: " + str(item))
    decision, current_build = decide(
        args.current_zip, args.incoming_zip, args.incoming_build,
        args.fallback_current_build, args.incoming_state)
    print("CURRENT_BUILD=" + current_build)
    print("INCOMING_BUILD=" + args.incoming_build)
    print("OTA_REPLACEMENT_DECISION=" + decision)
    if decision == "ALREADY_CURRENT":
        raise SystemExit(80)
    if decision == "SOFTWARE_REVIEW":
        raise SystemExit(81)


if __name__ == "__main__":
    main()
