#!/usr/bin/env python3
"""OTA intake unit tests. Synthetic images are deliberately NOT real boot images."""
from __future__ import annotations
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSPECT = ROOT / "scripts/xiaomi-os4/inspect-ota-stock-input.py"
RESOLVE = ROOT / "scripts/xiaomi-os4/resolve-ota-gki-candidate.py"


def synthetic_boot() -> bytes:
    header = bytearray(4096)
    header[:8] = b"ANDROID!"
    kernel = (b"Linux version 6.12.69-android16-6-synthetic-ci-4k\x00" + b"\x00" * 12000)
    struct.pack_into("<I", header, 8, len(kernel))
    struct.pack_into("<I", header, 12, 0)
    struct.pack_into("<I", header, 20, 1584)
    struct.pack_into("<I", header, 40, 4)
    return bytes(header) + kernel


class AutoOTAGuards(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.d = Path(self.tmp.name)
        self.cfg = self.d / "current.env"
        self.cfg.write_text(
            "OS4_STOCK_INPUT_BUILD=OS4.0.5.0.XBLCNXM\n"
            "OS4_STOCK_BOOT_SHA256=" + "f" * 64 + "\n", encoding="utf-8"
        )
        self.report = self.d / "report.json"

    def make_zip(self, *, build="OS4.0.5.1.XBLCNXM", manifest=True,
                 software_revision=None):
        z = self.d / "test-xm.zip"
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as f:
            f.writestr("boot.img", synthetic_boot())
            if manifest:
                f.writestr("README.txt", "\n".join([
                    "device=pandora", "build=" + build,
                    "slot=_a", "uname=6.12.69-android16-6-synthetic-ci-4k",
                    "collected_from_live_device=YES", "operation=READ_ONLY",
                ] + (["software_revision=" + software_revision,
                      "software_revision_source=USER_CONFIRMED_UPDATE_SCREEN",
                      "software_revision_base_build=" + build]
                     if software_revision else [])) + "\n")
        return z

    def run_inspect(self, z):
        return subprocess.run(
            [sys.executable, str(INSPECT), "--zip", str(z),
             "--stock-config", str(self.cfg), "--output", str(self.report)],
            check=False, capture_output=True, text=True,
        )

    def test_new_version_only_creates_unflashable_candidate_report(self):
        result = self.run_inspect(self.make_zip())
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        x = json.loads(self.report.read_text())
        self.assertEqual(x["status"], "NEW_OTA_CANDIDATE")
        self.assertTrue(x["may_build_gki_candidate"])
        self.assertFalse(x["safe_to_flash"])
        self.assertEqual(x["kernel_version"], "6.12.69")
        self.assertEqual(x["kmi"], "android16-6")
        self.assertEqual(x["source_provenance"], "BOOT_KERNEL_MARKER_AND_README_AGREE")

    def test_same_build_different_boot_is_rejected(self):
        result = self.run_inspect(self.make_zip(build="OS4.0.5.0.XBLCNXM"))
        self.assertNotEqual(result.returncode, 0)
        x = json.loads(self.report.read_text())
        self.assertEqual(x["status"], "SAME_BUILD_BOOT_DRIFT_REVIEW")

    def test_older_build_is_rejected_before_stock_input_replacement(self):
        result = self.run_inspect(self.make_zip(build="OS4.0.4.0.XBLCNXM"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("older/non-forward HyperOS build", result.stderr + result.stdout)

    def test_cross_channel_build_requires_manual_review(self):
        result = self.run_inspect(self.make_zip(build="OS4.0.6.0.XBLMIXM"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("build channel changed", result.stderr + result.stdout)

    def test_unidentified_new_ota_cannot_use_old_legacy_format(self):
        result = self.run_inspect(self.make_zip(manifest=False))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("requires README.txt", result.stderr + result.stdout)

    def test_catalog_has_a_candidate_but_never_claims_oem_equivalence(self):
        result = self.run_inspect(self.make_zip())
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        catalog = {"name": "android16-6.12", "branches": [
            {"name": "android16-6.12-2026-03", "kernel_version": "6.12.69",
             "releases": [
                 {"tag": "android16-6.12-2026-03_r1", "sha1": "a" * 40,
                  "date": "2026-03-25", "kernel_bid": "12345"},
                 {"tag": "android16-6.12-2026-03_r2", "sha1": "b" * 40,
                  "date": "2026-03-30", "kernel_bid": "23456"},
             ]}
        ]}
        html = self.d / "catalog.html"
        html.write_text("<html><body><pre>" + json.dumps(catalog) + "</pre></body></html>")
        output = self.d / "candidate.json"
        env = self.d / "candidate.env"
        r = subprocess.run(
            [sys.executable, str(RESOLVE), "--ota-report", str(self.report),
             "--catalog-url", html.as_uri(), "--output", str(output), "--env", str(env)],
            capture_output=True, text=True,
        )
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        x = json.loads(output.read_text())
        self.assertEqual(x["candidate_commit"], "b" * 40)
        self.assertTrue(x["not_oem_source_proven"])
        self.assertFalse(x["safe_to_flash"])
        self.assertIn("OTA_GKI_TAG=", env.read_text())

    def test_same_real_build_with_manual_d02_becomes_review_only(self):
        result = self.run_inspect(self.make_zip(
            build="OS4.0.5.0.XBLCNXM", software_revision="D02"))
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        report = json.loads(self.report.read_text())
        self.assertEqual("OS4.0.5.0.XBLCNXM", report["build"])
        self.assertEqual("D02", report["software_revision"])
        self.assertEqual("USER_CONFIRMED_UPDATE_SCREEN", report["software_revision_source"])
        self.assertFalse(report["software_revision_verified_by_device"])
        self.assertEqual("SOFTWARE_OTA_REVIEW", report["status"])
        self.assertFalse(report["may_build_gki_candidate"])
        self.assertEqual("SOFTWARE_OTA_EVIDENCE_REVIEW", report["next_gate"])

    def test_manual_d02_cannot_disguise_new_real_firmware(self):
        result = self.run_inspect(self.make_zip(
            build="OS4.0.6.0.XBLCNXM", software_revision="D02"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot masquerade", result.stderr + result.stdout)

    def test_d02_is_recorded_without_gki_build(self):
        result = self.run_inspect(self.make_zip(build="OS4.0.5.0.XBLCNXM.D02"))
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        report = json.loads(self.report.read_text())
        self.assertEqual("SOFTWARE_OTA_REVIEW", report["status"])
        self.assertEqual("SOFTWARE_OTA_EVIDENCE_REVIEW", report["next_gate"])
        self.assertFalse(report["may_build_gki_candidate"])
        self.assertFalse(report["safe_to_flash"])

    def test_d02_older_or_malformed_software_revision_is_blocked(self):
        for build in ("OS4.0.5.0.XBLCNXM.D2", "OS4.0.5.0.XBLCNXM.D02.EXTRA"):
            result = self.run_inspect(self.make_zip(build=build))
            self.assertNotEqual(result.returncode, 0)
        self.cfg.write_text(
            "OS4_STOCK_INPUT_BUILD=OS4.0.5.0.XBLCNXM.D03\n"
            "OS4_STOCK_BOOT_SHA256=" + "f" * 64 + "\n", encoding="utf-8")
        result = self.run_inspect(self.make_zip(build="OS4.0.5.0.XBLCNXM.D02"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("older/non-forward HyperOS build", result.stderr + result.stdout)


if __name__ == "__main__":
    unittest.main()
