#!/usr/bin/env python3
"""Synthetic, no-device contract tests. Not a replacement for real pandora OTA validation."""
import io
import os
import tempfile
import tarfile
import unittest
import zipfile
from pathlib import Path
from normalize_mobile_capture import normalize

BASE = (
    "schema=pandora-ota-capture-v1\n"
    "source=ACTIVE_DEVICE_SNAPSHOT_NOT_VERIFIED_STOCK\n"
    "device=pandora\n"
    "ro.product.device=pandora\n"
    "ro.mi.os.version.incremental=OS4.0.6.0.XBLCNXM\n"
    "ro.boot.slot_suffix=_a\n"
    "slot=_a\n"
    "uname=6.12.70-android16-6-gabcdef01-4k\n"
)


def create_capture(path, manifest=BASE, include_dtbo=True, tar_kind="normal"):
    nested = io.BytesIO()
    with tarfile.open(fileobj=nested, mode="w") as stream:
        # Simulate real OEM trees with module aliases, not extracted links.
        if tar_kind in ("symlink", "mixed_symlink", "unsafe_symlink", "mixed_hardlink"):
            member = tarfile.TarInfo(
                "/outside/lib/modules/escape.ko" if tar_kind == "unsafe_symlink"
                else "vendor_dlkm/lib/modules/alias.ko"
            )
            member.type = (tarfile.LNKTYPE if tar_kind == "mixed_hardlink"
                           else tarfile.SYMTYPE)
            member.linkname = "../../another.ko"
            stream.addfile(member)
        if tar_kind != "symlink" and tar_kind != "unsafe_symlink":
            mod = b"\x7fELF" + b"A" * 512
            member = tarfile.TarInfo("vendor_dlkm/lib/modules/x.ko")
            member.size = len(mod)
            stream.addfile(member, io.BytesIO(mod))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as outer:
        outer.writestr("manifest.txt", manifest)
        outer.writestr("kernel-release.txt", "6.12.70-android16-6-gabcdef01-4k\n")
        outer.writestr("oem-modules.tar", nested.getvalue())
        for part in ("boot", "init_boot", "vendor_boot", "dtbo"):
            if part == "dtbo" and not include_dtbo:
                continue
            outer.writestr(part + "_a.img", b"ANDROID!" + os.urandom(8192))


class MobileNormalizerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.capture = self.root / "source.zip"
        self.output = self.root / "pandora-stock-OS4.0.6.0.XBLCNXM-_a-xm.zip"

    def tearDown(self):
        self.temp.cleanup()

    def test_expected_xm_contract_and_modules(self):
        create_capture(self.capture)
        result = normalize(self.capture, self.output)
        self.assertEqual(result["modules"], 1)
        self.assertEqual(result["build"], "OS4.0.6.0.XBLCNXM")
        with zipfile.ZipFile(self.output) as output:
            expected = {
                "boot.img", "init_boot.img", "vendor_boot.img", "dtbo.img",
                "README.txt", "SHA256SUMS.txt", "oem-modules.zip",
                "oem-modules-count.txt"
            }
            self.assertEqual(set(output.namelist()), expected)
            self.assertEqual(output.read("oem-modules-count.txt"), b"MODULE_COUNT=1\n")
            with zipfile.ZipFile(io.BytesIO(output.read("oem-modules.zip"))) as nested:
                self.assertEqual(nested.namelist(), ["vendor_dlkm/lib/modules/x.ko"])
            readme = output.read("README.txt")
            self.assertIn(b"ACTIVE_DEVICE_SNAPSHOT_NOT_VERIFIED_STOCK", readme)
            self.assertIn(b"boot_role=GKI_KERNEL_ANALYSIS_INPUT", readme)
            self.assertIn(b"boot_oem_original_verified=NO", readme)
            self.assertIn(b"init_boot_source=ROOTED_ACTIVE_SLOT_MAY_BE_PATCHED", readme)
            self.assertIn(b"init_boot_oem_original_verified=NO", readme)
            self.assertIn(b"init_boot_role=DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE", readme)
        self.assertFalse(result["boot_oem_original_verified"])
        self.assertFalse(result["init_boot_oem_original_verified"])

    def test_missing_boot_fails_closed(self):
        create_capture(self.capture, include_dtbo=False)
        with self.assertRaisesRegex(ValueError, "missing or too-small"):
            normalize(self.capture, self.output)

    def test_forged_oem_original_claim_fails_closed(self):
        # Rooted clients must never turn a patched init_boot into OEM "stock"
        # by placing an assertion in the untrusted manifest.
        forged = BASE + "init_boot_oem_original_verified=YES\n"
        create_capture(self.capture, manifest=forged)
        with self.assertRaisesRegex(ValueError, "false OEM provenance"):
            normalize(self.capture, self.output)

    def test_old_v1_capture_is_backward_compatible_but_not_oem_trusted(self):
        create_capture(self.capture, manifest=BASE)
        result = normalize(self.capture, self.output)
        self.assertFalse(result["init_boot_oem_original_verified"])
        with zipfile.ZipFile(self.output) as z:
            self.assertIn(b"init_boot_oem_original_verified=NO",
                          z.read("README.txt"))

    def test_wrong_device_fails_closed(self):
        create_capture(self.capture, manifest=BASE.replace(
            "ro.product.device=pandora", "ro.product.device=wrongdevice"))
        with self.assertRaisesRegex(ValueError, "not pandora"):
            normalize(self.capture, self.output)

    def test_wrong_slot_fails_closed(self):
        create_capture(self.capture, manifest=BASE.replace("slot=_a", "slot=_b"))
        with self.assertRaisesRegex(ValueError, "slot mismatch"):
            normalize(self.capture, self.output)

    def test_link_only_cannot_masquerade_as_kernel_modules(self):
        create_capture(self.capture, tar_kind="symlink")
        with self.assertRaisesRegex(ValueError, "no real OEM kernel modules"):
            normalize(self.capture, self.output)

    def test_real_modules_with_symlink_aliases_ignore_links(self):
        create_capture(self.capture, tar_kind="mixed_symlink")
        result = normalize(self.capture, self.output)
        self.assertEqual(result["modules"], 1)
        self.assertEqual(result["ignored_module_links"], 1)
        with zipfile.ZipFile(self.output) as final:
            with zipfile.ZipFile(io.BytesIO(final.read("oem-modules.zip"))) as modules:
                self.assertEqual(modules.namelist(), ["vendor_dlkm/lib/modules/x.ko"])

    def test_real_modules_with_hardlink_aliases_ignore_links(self):
        create_capture(self.capture, tar_kind="mixed_hardlink")
        result = normalize(self.capture, self.output)
        self.assertEqual(result["modules"], 1)
        self.assertEqual(result["ignored_module_links"], 1)

    def test_outside_root_symlink_path_is_rejected_even_if_not_extracted(self):
        create_capture(self.capture, tar_kind="unsafe_symlink")
        with self.assertRaisesRegex(ValueError, "unsafe module TAR member path"):
            normalize(self.capture, self.output)

    def test_existing_archive_never_overwritten(self):
        create_capture(self.capture)
        self.output.write_bytes(b"KEEP")
        with self.assertRaisesRegex(ValueError, "existing destination"):
            normalize(self.capture, self.output)
        self.assertEqual(self.output.read_bytes(), b"KEEP")

    def test_d02_capture_is_valid_and_keeps_full_build_identity(self):
        manifest = BASE.replace("OS4.0.6.0.XBLCNXM", "OS4.0.5.0.XBLCNXM.D02")
        create_capture(self.capture, manifest=manifest)
        result = normalize(self.capture, self.output)
        self.assertEqual(result["build"], "OS4.0.5.0.XBLCNXM.D02")
        with zipfile.ZipFile(self.output) as archive:
            self.assertIn(b"build=OS4.0.5.0.XBLCNXM.D02",
                          archive.read("README.txt"))

    def test_manual_d02_is_separate_claim_and_does_not_change_real_build(self):
        claim = (BASE + "software_revision=D02\n"
                 "software_revision_source=USER_CONFIRMED_UPDATE_SCREEN\n"
                 "software_revision_base_build=OS4.0.6.0.XBLCNXM\n")
        create_capture(self.capture, manifest=claim)
        report = normalize(self.capture, self.output)
        self.assertEqual("OS4.0.6.0.XBLCNXM", report["build"])
        self.assertEqual("D02", report["software_revision"])
        self.assertEqual("USER_CONFIRMED_UPDATE_SCREEN", report["software_revision_source"])
        with zipfile.ZipFile(self.output) as archive:
            text = archive.read("README.txt").decode()
            self.assertIn("build=OS4.0.6.0.XBLCNXM\n", text)
            self.assertIn("software_revision=D02\n", text)
            self.assertIn("software_revision_base_build=OS4.0.6.0.XBLCNXM\n", text)
            self.assertNotIn("build=OS4.0.6.0.XBLCNXM.D02", text)

    def test_manual_revision_claim_must_match_real_build_and_source(self):
        for change in (
            "software_revision=D2\n",
            "software_revision=D002\n",
            "software_revision=D02.EXTRA\n",
            "software_revision=D02\nsoftware_revision_source=UNKNOWN\n",
            "software_revision=D02\nsoftware_revision_source=USER_CONFIRMED_UPDATE_SCREEN\n"
            "software_revision_base_build=OS4.0.7.0.XBLCNXM\n",
        ):
            manifest = BASE + change
            create_capture(self.capture, manifest=manifest)
            with self.assertRaisesRegex(ValueError, "invalid or unbound"):
                normalize(self.capture, self.output)
            self.capture.unlink()

    def test_property_revision_cannot_be_duplicated_by_manual_claim(self):
        manifest = BASE.replace("OS4.0.6.0.XBLCNXM", "OS4.0.6.0.XBLCNXM.D02")
        manifest += ("software_revision=D02\n"
                     "software_revision_source=USER_CONFIRMED_UPDATE_SCREEN\n"
                     "software_revision_base_build=OS4.0.6.0.XBLCNXM.D02\n")
        create_capture(self.capture, manifest=manifest)
        with self.assertRaisesRegex(ValueError, "manual software revision"):
            normalize(self.capture, self.output)

    def test_malformed_suffix_fails_closed(self):
        manifest = BASE.replace("OS4.0.6.0.XBLCNXM", "OS4.0.5.0.XBLCNXM.D2")
        create_capture(self.capture, manifest=manifest)
        with self.assertRaisesRegex(ValueError, "invalid HyperOS build"):
            normalize(self.capture, self.output)


if __name__ == "__main__":
    unittest.main()
