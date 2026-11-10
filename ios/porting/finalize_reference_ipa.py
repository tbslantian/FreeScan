#!/usr/bin/env python3
# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Attach verified local scan models to an unsigned FreeScan reference-runtime IPA."""

import argparse
import hashlib
from pathlib import Path, PurePosixPath
import plistlib
from zipfile import BadZipFile, ZIP_DEFLATED, ZipFile

from extract_camscanner_models import MODELS, OUTPUT, extract, verify


def finalize(source: Path, output: Path) -> None:
    verify()
    with ZipFile(source) as runtime:
        names = runtime.namelist()
        paths = [PurePosixPath(name) for name in names]
        if len(set(names)) != len(names) or any(
            path.is_absolute() or ".." in path.parts or "\\" in name
            for name, path in zip(names, paths)
        ):
            raise ValueError("Unexpected or duplicate paths in the runtime IPA")
        plists = [path for path in paths if len(path.parts) == 3 and path.parts[0] == "Payload"
                  and path.parts[1].endswith(".app") and path.name == "Info.plist"]
        if len(plists) != 1:
            raise ValueError("Expected one app payload in the runtime IPA")
        app = plists[0].parent
        info = plistlib.loads(runtime.read(str(plists[0])))
        if (info.get("CFBundleIdentifier") != "org.freescan.app" or
                info.get("DTPlatformName") != "iphoneos" or set(info.get("UIDeviceFamily", [])) != {1, 2}):
            raise ValueError("Expected a FreeScan iPhone and iPad build")
        models_path = app / "ReferenceScan"
        if str(models_path / "MNN-LICENSE.txt") not in names:
            raise ValueError("Expected the reference-device-test runtime from GitHub Actions")
        if any("_CodeSignature" in path.parts or path.name == "embedded.mobileprovision" for path in paths):
            raise ValueError("Expected an unsigned runtime IPA. Add models before signing.")
        model_paths = [str(models_path / name) for name, *_ in MODELS]
        if any(path in names for path in model_paths):
            raise ValueError("The runtime IPA already has reference models")

        destination = output.open("xb")
        try:
            with destination, ZipFile(destination, "w", ZIP_DEFLATED, compresslevel=6) as ipa:
                for member in runtime.infolist():
                    ipa.writestr(member, runtime.read(member))
                for (name, _, _, _), path in zip(MODELS, model_paths):
                    ipa.write(OUTPUT / name, path)
            with ZipFile(output) as ipa:
                if ipa.testzip() is not None:
                    raise ValueError("Output IPA CRC check failed")
                for (_, _, _, digest), path in zip(MODELS, model_paths):
                    if hashlib.sha256(ipa.read(path)).hexdigest() != digest:
                        raise ValueError(f"Output IPA model check failed: {path}")
        except Exception:
            output.unlink(missing_ok=True)
            raise
    print(f"Created {output}. Apple signing is required before installation.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--reference-ipa", type=Path)
    parser.add_argument("--android-reference-apk", type=Path)
    args = parser.parse_args()
    try:
        if args.reference_ipa:
            extract(args.reference_ipa, args.android_reference_apk)
        elif args.android_reference_apk:
            parser.error("--android-reference-apk requires --reference-ipa")
        finalize(args.runtime, args.output)
    except (OSError, ValueError, BadZipFile, plistlib.InvalidFileException) as error:
        parser.exit(1, f"{error}\n")
