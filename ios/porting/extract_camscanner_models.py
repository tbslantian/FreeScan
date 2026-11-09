"""Extract the verified scan tensors from the user-supplied iOS reference IPA."""

import argparse
import hashlib
from pathlib import Path
import tempfile
import zipfile


IPA_SHA256 = "8260c3a19f99620242b06a28611b00fbc5a80ec9cd54b38623895a55d8cca550"
MAIN = "Payload/CamScanner_Lite.app/CamScanner_Lite"
MODELS = (
    ("border.mnn", 0x91BBB20, 1584816, "b9bde1e5d346f063018443bd3d54fca8b3316ef53e114cd5f8e13564590489e8"),
    ("dewarp-b.mnn", 0x941471C, 1584744, "9d1f68861920f0c0de9f6aa9ac55214276eec0d1856016d8ae741deff2992194"),
    ("direction.mnn", 0x972BA2C, 1063348, "2a72d0dcc1535b0229e274e2f29853822370a0fe39fc0685323e144f3638228a"),
    ("shadow.mnn", 0x990D050, 926496, "510129b077aa56dc249450745538c84b1e643ade1469c46795e2139276018db5"),
)
OUTPUT = Path(__file__).resolve().parents[1] / ".build" / "camscanner-models"


def verify() -> None:
    for name, _, length, expected in MODELS:
        data = (OUTPUT / name).read_bytes()
        if len(data) != length or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Reference model does not match the analyzed IPA: {name}")


def extract(ipa: Path) -> None:
    with ipa.open("rb") as source:
        digest = hashlib.sha256()
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
        if digest.hexdigest() != IPA_SHA256:
            raise ValueError("Expected the analyzed CamScanner iOS 7.28.5 IPA (SHA-256 mismatch)")
        source.seek(0)
        with zipfile.ZipFile(source) as package, package.open(MAIN) as program:
            blobs = {}
            for name, offset, length, digest in MODELS:
                program.seek(offset)
                data = program.read(length)
                if len(data) != length or hashlib.sha256(data).hexdigest() != digest:
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
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.verify_only:
            verify()
        elif args.ipa:
            extract(args.ipa)
        else:
            parser.error("an IPA is required unless --verify-only is selected")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{error}\n")
