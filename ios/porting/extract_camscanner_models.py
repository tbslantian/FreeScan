"""Extract the verified scan tensors from local reference app packages."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import tempfile
import zipfile


IPA_SHA256 = "8260c3a19f99620242b06a28611b00fbc5a80ec9cd54b38623895a55d8cca550"
MAIN = "Payload/CamScanner_Lite.app/CamScanner_Lite"
IPA_MODELS = (
    ("border.mnn", 0x91BBB20, 1584816, "b9bde1e5d346f063018443bd3d54fca8b3316ef53e114cd5f8e13564590489e8"),
    ("dewarp-b.mnn", 0x941471C, 1584744, "9d1f68861920f0c0de9f6aa9ac55214276eec0d1856016d8ae741deff2992194"),
    ("direction.mnn", 0x972BA2C, 1063348, "2a72d0dcc1535b0229e274e2f29853822370a0fe39fc0685323e144f3638228a"),
    ("shadow.mnn", 0x990D050, 926496, "510129b077aa56dc249450745538c84b1e643ade1469c46795e2139276018db5"),
)
APK_MODELS = (
    (
        "lib/arm64-v8a/libpagescanner.so",
        "cea4cbe6e1fd5569bfbd79cbf408a96dabb94a744602930f530dcb5262bac164",
        (
            ("curve-line.mnn", 0x2FF1A4, 847300, "b2046d3a8d3004822c5d22d0c37b4232b903081516d868e4d32f81c51f9f9dbe"),
        ),
    ),
    (
        "lib/arm64-v8a/libmagicenhancer.so",
        "4fecd50628352cc4d88fba220ee1059e8104857166014f8b5d6e8a13e9c92599",
        (
            ("magic-scan-new.mnn", 0x14D618, 866732, "3df415c62595af08aedd2f097ecdd0ea2a580bc2b8f5e4afa5d48f81c423a8ff"),
            ("magic-scan-old.mnn", 0x220FC4, 61224, "74403d63cfffeb90b0787b860eba4a47f193a94c20383574282f3c1d3b53a4cb"),
            ("magic-radar.mnn", 0x22FEEC, 623756, "4bc39e3f8f857eb876f37a01aaae14a7a66ce6fda491d311100bfaceda43eefe"),
            ("magic-scan-alt.mnn", 0x140B64, 51892, "3915980ba0c001efdd0e11027aacdbf3adad61fe960666113cc6bb7391ee4444"),
        ),
    ),
)
MODELS = IPA_MODELS + tuple(model for _, _, models in APK_MODELS for model in models)
OUTPUT = Path(__file__).resolve().parents[1] / ".build" / "camscanner-models"


def verify() -> None:
    for name, _, length, expected in MODELS:
        data = (OUTPUT / name).read_bytes()
        if len(data) != length or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Reference model does not match the analyzed package: {name}")


def extract(ipa: Path, android_reference_apk: Path | None = None) -> None:
    with ipa.open("rb") as source:
        digest = hashlib.sha256()
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
        if digest.hexdigest() != IPA_SHA256:
            raise ValueError("Expected the analyzed CamScanner iOS 7.28.5 IPA (SHA-256 mismatch)")
        source.seek(0)
        with zipfile.ZipFile(source) as package, package.open(MAIN) as program:
            blobs = {}
            for name, offset, length, digest in IPA_MODELS:
                program.seek(offset)
                data = program.read(length)
                if len(data) != length or hashlib.sha256(data).hexdigest() != digest:
                    raise ValueError(f"Unexpected model bytes at offset {offset:#x} ({name})")
                blobs[name] = data

    if android_reference_apk is not None:
        with zipfile.ZipFile(android_reference_apk) as package:
            for member, digest, models in APK_MODELS:
                library = package.read(member)
                if hashlib.sha256(library).hexdigest() != digest:
                    raise ValueError(f"Expected the analyzed Android arm64 library: {member}")
                for name, offset, length, expected in models:
                    data = library[offset : offset + length]
                    if len(data) != length or hashlib.sha256(data).hexdigest() != expected:
                        raise ValueError(f"Unexpected model bytes at offset {offset:#x} ({name})")
                    blobs[name] = data

    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=OUTPUT) as temp:
        staging = Path(temp)
        for name, data in blobs.items():
            (staging / name).write_bytes(data)
        for name in blobs:
            (staging / name).replace(OUTPUT / name)
    print(f"Verified {len(blobs)} reference models in {OUTPUT}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ipa", type=Path, nargs="?")
    parser.add_argument("--android-reference-apk", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--list-models", action="store_true")
    args = parser.parse_args()
    try:
        if args.list_models:
            print("\n".join(name for name, *_ in MODELS))
        elif args.verify_only:
            verify()
        elif args.ipa:
            extract(args.ipa, args.android_reference_apk)
        else:
            parser.error("an IPA is required unless --verify-only is selected")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{error}\n")
