#!/usr/bin/env python3
# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate device-only builds and optionally package an IPA for local signing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


def executable(bundle):
    with (bundle / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    name = info.get("CFBundleExecutable", "")
    if not name or Path(name).name != name or name in (".", ".."):
        raise ValueError("Invalid bundle executable name")
    return bundle / name, info


def validate(app):
    app = app.resolve(strict=True)
    binary, info = executable(app)
    if info.get("DTPlatformName") != "iphoneos":
        raise ValueError("Only an iphoneos device build can be packaged")
    if set(info.get("UIDeviceFamily", [])) != {1, 2}:
        raise ValueError("The app must target both iPhone and iPad")
    binaries = {binary}
    for framework in app.rglob("*.framework"):
        framework_binary, _ = executable(framework)
        binaries.add(framework_binary)
    binaries.update(app.rglob("*.dylib"))
    architectures = set()
    for item in binaries:
        if not item.resolve(strict=True).is_relative_to(app):
            raise ValueError("A bundled executable resolves outside the app")
        archs = subprocess.check_output(["xcrun", "lipo", "-archs", str(item)], text=True).split()
        if "arm64" not in archs or not set(archs) <= {"arm64", "arm64e"}:
            raise ValueError(f"Non-device architecture in {item.name}: {archs}")
        build = subprocess.check_output(["xcrun", "vtool", "-show-build", str(item)], text=True)
        platforms = re.findall(r"^\s*platform\s+(\S+)\s*$", build, re.MULTILINE)
        if not platforms or set(platforms) != {"IOS"}:
            raise ValueError(f"Non-iOS-device Mach-O platform in {item.name}: {platforms}")
        architectures.update(archs)
    if "arm64" not in architectures:
        raise ValueError("An arm64 device slice is required")
    for item in app.rglob("*"):
        if item.is_symlink() and not item.resolve(strict=True).is_relative_to(app):
            raise ValueError("A bundle symlink resolves outside the app")
    catalog = json.loads((app / "OCRLanguages.json").read_text())
    if len(catalog.get("models", [])) != 120:
        raise ValueError("The OCR language catalog is missing or incomplete")
    for name in ("OpenCV-LICENSE.txt", "Tesseract-LICENSE.txt", "Leptonica-LICENSE.txt"):
        if not (app / name).is_file():
            raise ValueError(f"Missing bundled resource: {name}")
    return app, info, binaries, sorted(architectures)


def package(app, output):
    app, info, binaries, architectures = validate(app)
    output = output.resolve()
    if output.is_relative_to(app):
        raise ValueError("Output must be outside the app bundle")
    output.mkdir(parents=True, exist_ok=False)
    build = re.sub(r"[^A-Za-z0-9._-]", "_", os.environ.get("GITHUB_RUN_NUMBER", "local"))
    ipa = output / f"FreeScan-iOS-device-test-unsigned-{build}.ipa"
    with tempfile.TemporaryDirectory(prefix="FreeScanDeviceTest-") as temporary:
        root = Path(temporary)
        payload = root / "Payload"
        copied = payload / app.name
        shutil.copytree(app, copied, symlinks=True,
                        ignore=shutil.ignore_patterns("_CodeSignature", "embedded.mobileprovision"))
        for original in binaries:
            item = copied / original.relative_to(app)
            signed = subprocess.run(["codesign", "-d", str(item)], capture_output=True).returncode == 0
            if signed:
                subprocess.run(["codesign", "--remove-signature", str(item)], check=True)
        with zipfile.ZipFile(ipa, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for item in sorted(payload.rglob("*")):
                name = item.relative_to(root).as_posix()
                if item.is_symlink():
                    entry = zipfile.ZipInfo(name)
                    entry.create_system = 3
                    entry.external_attr = item.lstat().st_mode << 16
                    archive.writestr(entry, os.readlink(item))
                else:
                    archive.write(item, name)
        with zipfile.ZipFile(ipa) as archive:
            if archive.testzip() is not None:
                raise ValueError("IPA ZIP integrity check failed")
            if f"Payload/{app.name}/Info.plist" not in archive.namelist():
                raise ValueError("IPA payload is missing")
    manifest = {
        "artifact": ipa.name, "signing": "unsigned-local-signing-required",
        "platform": "iphoneos", "architectures": architectures,
        "version": info.get("CFBundleShortVersionString"), "build": info.get("CFBundleVersion"),
        "minimumOS": info.get("MinimumOSVersion"), "deviceFamilies": info.get("UIDeviceFamily"),
        "sourceCommit": os.environ.get("GITHUB_SHA"), "runID": os.environ.get("GITHUB_RUN_ID"),
        "sha256": hashlib.sha256(ipa.read_bytes()).hexdigest(),
    }
    (output / "BUILD-INFO.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text(f"{manifest['sha256']}  {ipa.name}\n")
    shutil.copyfile(Path(__file__).with_name("TESTING.md"), output / "TESTING.md")
    (output / "READ-ME-FIRST.txt").write_text(
        "FreeScan iPhone/iPad 真机测试包（非模拟器）\n\n"
        "此 IPA 未签名，不能直接安装，也不能上传 TestFlight/App Store。\n"
        "需要在个人电脑上使用自己的 Apple 账户重新签名后安装。\n"
        "Apple 账户和密码不要上传到 GitHub，也不要发送到聊天中。\n"
        "功能差异和测试步骤见 TESTING.md；实际版本及源码提交见 BUILD-INFO.json。\n",
        encoding="utf-8")
    print(f"Packaged unsigned device-test IPA: {ipa.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("output", type=Path, nargs="?")
    parser.add_argument("--verify-only", action="store_true")
    arguments = parser.parse_args()
    try:
        if arguments.verify_only:
            _, _, _, archs = validate(arguments.app)
            print(f"Verified iPhone/iPad device build: {', '.join(archs)}; Mach-O platform IOS")
        elif arguments.output is None:
            parser.error("output is required unless --verify-only is selected")
        else:
            package(arguments.app, arguments.output)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Device-test packaging failed: {error}", file=sys.stderr)
        sys.exit(1)
