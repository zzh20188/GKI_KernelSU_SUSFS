#!/usr/bin/env python3
"""Synthetic positive bridge: Android device capture -> xm.zip -> existing OEM stock-input audit.

No firmware, releases, secrets, or actual stock input are touched.
"""
from __future__ import annotations
import io
import json
import os
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from normalize_mobile_capture import normalize

BASE = Path(__file__).resolve().parent
STOCK_AUDIT = BASE.parents[1] / "scripts" / "xiaomi-os4" / "inspect-ota-stock-input.py"
BUILD = "OS4.0.6.0.XBLCNXM"
KERNEL = "6.12.70-android16-6-gabcdef01-4k"


def boot_v4() -> bytes:
    kernel_size = 8192
    image = bytearray(os.urandom(4096 + kernel_size))
    image[:8] = b"ANDROID!"
    struct.pack_into("<IIII", image, 8, kernel_size, 0, 0, 1584)
    struct.pack_into("<I", image, 40, 4)
    struct.pack_into("<I", image, 1580, 0)
    marker = f"Linux version {KERNEL} #1 SMP PREEMPT".encode()
    image[4096:4096 + len(marker)] = marker
    return bytes(image)


def capture(path: Path, *, build: str = BUILD, revision: str | None = None) -> None:
    manifest = (
        "schema=pandora-ota-capture-v1\n"
        "source=ACTIVE_DEVICE_SNAPSHOT_NOT_VERIFIED_STOCK\n"
        "device=pandora\n"
        "ro.product.device=pandora\n"
        f"ro.mi.os.version.incremental={build}\n"
        "ro.boot.slot_suffix=_a\nslot=_a\n"
        f"uname={KERNEL}\n"
    )
    if revision:
        manifest += (f"software_revision={revision}\n"
                     "software_revision_source=USER_CONFIRMED_UPDATE_SCREEN\n"
                     f"software_revision_base_build={build}\n")
    tar_stream = io.BytesIO()
    with tarfile.open(fileobj=tar_stream, mode="w") as archive:
        data = b"\x7fELF" + os.urandom(512)
        item = tarfile.TarInfo("vendor_dlkm/lib/modules/synthetic.ko")
        item.size = len(data)
        archive.addfile(item, io.BytesIO(data))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as outer:
        outer.writestr("manifest.txt", manifest)
        outer.writestr("kernel-release.txt", KERNEL + "\n")
        outer.writestr("oem-modules.tar", tar_stream.getvalue())
        for part in ("boot", "init_boot", "vendor_boot", "dtbo"):
            outer.writestr(part + "_a.img",
                           boot_v4() if part == "boot" else os.urandom(8192))


class MobileIntakeIntegration(unittest.TestCase):
    def test_mobile_normalized_bundle_passes_real_stock_audit(self):
        self.assertTrue(STOCK_AUDIT.is_file(), STOCK_AUDIT)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            input_zip = base / "mobile.zip"
            xm = base / f"pandora-stock-{BUILD}-_a-xm.zip"
            config = base / "stock.env"
            result = base / "stock-audit.json"
            config.write_text(
                "OS4_STOCK_INPUT_BUILD=OS4.0.5.0.XBLCNXM\n"
                "OS4_STOCK_BOOT_SHA256=" + "0" * 64 + "\n", encoding="utf-8"
            )
            capture(input_zip)
            meta = normalize(input_zip, xm)
            self.assertEqual(meta["modules"], 1)
            run = subprocess.run(
                [sys.executable, str(STOCK_AUDIT), "--zip", str(xm),
                 "--stock-config", str(config), "--output", str(result),
                 "--github-asset-id", "12345"],
                check=False, text=True, capture_output=True
            )
            self.assertEqual(run.returncode, 0, run.stderr + run.stdout)
            report = json.loads(result.read_text())
            self.assertEqual(report["device"], "pandora")
            self.assertEqual(report["status"], "NEW_OTA_CANDIDATE")
            self.assertEqual(report["build"], BUILD)
            self.assertEqual(report["header_version"], 4)
            self.assertEqual(report["kernel_version"], "6.12.70")
            self.assertTrue(report["kernel_marker_verified"])
            self.assertTrue(report["may_build_gki_candidate"])
            self.assertFalse(report["safe_to_flash"])
            self.assertEqual(report["github_asset_id"], "12345")
            self.assertEqual(report["source_provenance"], "BOOT_KERNEL_MARKER_AND_README_AGREE")
            self.assertFalse(report["boot_oem_original_verified"])
            self.assertEqual(report["boot_role"], "GKI_KERNEL_ANALYSIS_INPUT")
            self.assertFalse(report["init_boot_oem_original_verified"])
            self.assertTrue(report["init_boot_may_be_root_patched"])
            self.assertEqual(report["init_boot_source"], "ROOTED_ACTIVE_SLOT_MAY_BE_PATCHED")
            self.assertEqual(report["init_boot_role"], "DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE")

    def test_unsafe_oem_init_boot_claim_cannot_pass_stock_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, xm = base / "mobile.zip", base / "xm.zip"
            config, report = base / "stock.env", base / "report.json"
            capture(source)
            normalize(source, xm)
            with zipfile.ZipFile(xm) as archive:
                members = {n: archive.read(n) for n in archive.namelist()}
            members["README.txt"] = members["README.txt"].replace(
                b"init_boot_oem_original_verified=NO",
                b"init_boot_oem_original_verified=YES")
            xm.unlink()
            with zipfile.ZipFile(xm, "w", zipfile.ZIP_DEFLATED) as archive:
                for name, data in members.items():
                    archive.writestr(name, data)
            config.write_text(
                "OS4_STOCK_INPUT_BUILD=OS4.0.5.0.XBLCNXM\n"
                "OS4_STOCK_BOOT_SHA256=" + "0" * 64 + "\n",
                encoding="utf-8"
            )
            result = subprocess.run(
                [sys.executable, str(STOCK_AUDIT), "--zip", str(xm),
                 "--stock-config", str(config), "--output", str(report)],
                check=False, text=True, capture_output=True
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("falsely verified OEM", result.stderr + result.stdout)

    def test_same_build_boot_drift_stops_before_release_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            input_zip, xm = base / "mobile.zip", base / "mobile-xm.zip"
            config, report = base / "old.env", base / "report.json"
            capture(input_zip, build="OS4.0.5.0.XBLCNXM")
            normalize(input_zip, xm)
            config.write_text(
                "OS4_STOCK_INPUT_BUILD=OS4.0.5.0.XBLCNXM\n"
                "OS4_STOCK_BOOT_SHA256=" + "f" * 64 + "\n",
                encoding="utf-8"
            )
            p = subprocess.run(
                [sys.executable, str(STOCK_AUDIT), "--zip", str(xm),
                 "--stock-config", str(config), "--output", str(report)],
                check=False, capture_output=True, text=True
            )
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("same HyperOS build but stock boot changed",
                          p.stderr + p.stdout)

    def test_real_same_build_with_manual_d02_is_review_without_kernel_promote(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, xm = base / "mobile.zip", base / "xm.zip"
            config, report = base / "stock.env", base / "audit.json"
            build = "OS4.0.5.0.XBLCNXM"
            capture(source, build=build, revision="D02")
            normalized = normalize(source, xm)
            self.assertEqual(build, normalized["build"])
            self.assertEqual("D02", normalized["software_revision"])
            config.write_text(
                f"OS4_STOCK_INPUT_BUILD={build}\n"
                "OS4_STOCK_BOOT_SHA256=" + "0" * 64 + "\n",
                encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(STOCK_AUDIT), "--zip", str(xm),
                 "--stock-config", str(config), "--output", str(report)],
                text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            audit = json.loads(report.read_text())
            self.assertEqual(build, audit["build"])
            self.assertEqual("D02", audit["software_revision"])
            self.assertEqual("SOFTWARE_OTA_REVIEW", audit["status"])
            self.assertFalse(audit["software_revision_verified_by_device"])
            self.assertFalse(audit["may_build_gki_candidate"])
            self.assertFalse(audit["safe_to_flash"])

    def test_real_capture_contract_accepts_d02_as_review_not_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, xm = base / "mobile.zip", base / "mobile-xm.zip"
            config, report = base / "stock.env", base / "report.json"
            new_build = "OS4.0.5.0.XBLCNXM.D02"
            capture(source, build=new_build)
            result = normalize(source, xm)
            self.assertEqual(result["build"], new_build)
            config.write_text(
                "OS4_STOCK_INPUT_BUILD=OS4.0.5.0.XBLCNXM\n"
                "OS4_STOCK_BOOT_SHA256=" + "0" * 64 + "\n", encoding="utf-8")
            run = subprocess.run(
                [sys.executable, str(STOCK_AUDIT), "--zip", str(xm),
                 "--stock-config", str(config), "--output", str(report)],
                check=False, text=True, capture_output=True)
            self.assertEqual(0, run.returncode, run.stderr + run.stdout)
            data = json.loads(report.read_text())
            self.assertEqual("SOFTWARE_OTA_REVIEW", data["status"])
            self.assertFalse(data["may_build_gki_candidate"])
            self.assertFalse(data["safe_to_flash"])


if __name__ == "__main__":
    unittest.main()
