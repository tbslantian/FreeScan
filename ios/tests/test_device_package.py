# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Packaging guards and ZIP layout tests; native Xcode builds are checked in CI."""
import importlib.util
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("device_package", Path(__file__).parents[1] / "package_device_test.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DevicePackageChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="FreeScanPackagingTests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / "FreeScan.app"
        self.app.mkdir()
        self.info = {"CFBundleExecutable": "FreeScan", "CFBundleVersion": "7", "CFBundleShortVersionString": "1.0",
                     "DTPlatformName": "iphoneos", "UIDeviceFamily": [1, 2], "MinimumOSVersion": "17.0"}
        self.save_info()
        (self.app / "FreeScan").write_bytes(b"synthetic executable fixture; not a native app")
        (self.app / "OCRLanguages.json").write_text(json.dumps({"models": [{}] * 120}))
        for name in ("OpenCV-LICENSE.txt", "Tesseract-LICENSE.txt", "Leptonica-LICENSE.txt"):
            (self.app / name).write_text("fixture")

    def save_info(self):
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))

    @staticmethod
    def probe(command, **unused):
        return "arm64\n" if "lipo" in command else "Load command 1\n  platform IOS\n"

    def test_rejects_arm64_simulator_or_catalyst_even_with_device_plist(self):
        for platform in ("IOSSIMULATOR", "MACCATALYST", "MACOS"):
            with self.subTest(platform=platform), patch.object(module.subprocess, "check_output", side_effect=
                    lambda cmd, **kwargs: "arm64\n" if "lipo" in cmd else f"  platform {platform}\n"):
                with self.assertRaisesRegex(ValueError, "Non-iOS-device Mach-O"):
                    module.validate(self.app)

    def test_requires_both_device_families(self):
        self.info["UIDeviceFamily"] = [1]
        self.save_info()
        with self.assertRaisesRegex(ValueError, "both iPhone and iPad"):
            module.validate(self.app)

    def test_rejects_missing_native_licenses(self):
        (self.app / "Tesseract-LICENSE.txt").unlink()
        with patch.object(module.subprocess, "check_output", side_effect=self.probe):
            with self.assertRaisesRegex(ValueError, "Missing bundled resource"):
                module.validate(self.app)

    def test_payload_excludes_signing_assets_and_keeps_source_untouched(self):
        (self.app / "_CodeSignature").mkdir()
        (self.app / "_CodeSignature" / "CodeResources").write_text("fixture")
        (self.app / "embedded.mobileprovision").write_text("fixture profile")
        output = self.root / "output"
        with patch.object(module.subprocess, "check_output", side_effect=self.probe), \
             patch.object(module.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            module.package(self.app, output)
        ipa = next(output.glob("*.ipa"))
        with zipfile.ZipFile(ipa) as archive:
            names = archive.namelist()
            self.assertIn("Payload/FreeScan.app/FreeScan", names)
            self.assertFalse(any("_CodeSignature" in name or "mobileprovision" in name for name in names))
            self.assertIsNone(archive.testzip())
        self.assertTrue((self.app / "embedded.mobileprovision").is_file())
        self.assertTrue((self.app / "_CodeSignature" / "CodeResources").is_file())
        self.assertEqual(json.loads((output / "BUILD-INFO.json").read_text())["signing"], "unsigned-local-signing-required")


if __name__ == "__main__":
    unittest.main()
