# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run isolated ARM64 workers from the user-supplied Buding 2.3.4 IPA."""

import argparse
import hashlib
import math
from pathlib import Path
import struct
from zipfile import ZipFile

from unicorn import UC_ARCH_ARM64, UC_MODE_ARM, Uc
from unicorn.arm64_const import (
    UC_ARM64_REG_LR,
    UC_ARM64_REG_PC,
    UC_ARM64_REG_S8,
    UC_ARM64_REG_SP,
    UC_ARM64_REG_X0,
    UC_ARM64_REG_X1,
    UC_ARM64_REG_X2,
    UC_ARM64_REG_X3,
    UC_ARM64_REG_X19,
    UC_ARM64_REG_X21,
    UC_ARM64_REG_X22,
    UC_ARM64_REG_X29,
)


EXECUTABLE_SHA = "b7cfb0311e74c2d69ac0d09ff494af5dc6c40d30287adaf46831501b0310f0c5"
WORK = 0x20000000
SOURCE, PAPER, RAW, REFLECTED = (WORK + page * 0x1000 for page in range(1, 5))
SOURCE_PIXELS, PAPER_PIXELS, RAW_TABLE, REFLECTED_TABLE = (
    WORK + page * 0x10000 for page in range(1, 5)
)
STACK = 0x30010000
RETURN = 0x40000000
ROWS = COLS = 32
STRIDE = COLS * 3 + 5
PIXEL_OFFSETS = [y * STRIDE + x for y in range(ROWS) for x in range(COLS * 3)]


def load_text(ipa: Path) -> tuple[int, bytes]:
    with ZipFile(ipa) as archive:
        exe = archive.read("Payload/VScanner.app/VScanner")
    if hashlib.sha256(exe).hexdigest() != EXECUTABLE_SHA:
        raise ValueError("VScanner digest does not match the analyzed iOS 2.3.4 executable")
    offset = 32
    for _ in range(struct.unpack_from("<I", exe, 16)[0]):
        command, size = struct.unpack_from("<II", exe, offset)
        if command == 0x19 and exe[offset + 8:offset + 24].rstrip(b"\0") == b"__TEXT":
            address, _, file_offset, file_size = struct.unpack_from("<4Q", exe, offset + 24)
            return address, exe[file_offset:file_offset + file_size]
        offset += size
    raise ValueError("Missing Mach-O __TEXT segment")


def machine(text: tuple[int, bytes]) -> Uc:
    address, contents = text
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    uc.mem_map(address, (len(contents) + 0xFFF) & ~0xFFF)
    uc.mem_write(address, contents)
    uc.mem_map(WORK, 0x50000)
    uc.mem_map(STACK - 0x10000, 0x20000)
    uc.reg_write(UC_ARM64_REG_SP, STACK)
    uc.reg_write(UC_ARM64_REG_LR, RETURN)
    return uc


def write(uc: Uc, address: int, fmt: str, *values: int | float) -> None:
    uc.mem_write(address, struct.pack("<" + fmt, *values))


def execute(uc: Uc, start: int, end: int, limit: int) -> None:
    uc.emu_start(start, end, count=limit)
    if uc.reg_read(UC_ARM64_REG_PC) != end:
        raise RuntimeError(f"Original worker at {start:#x} did not reach {end:#x}")


def pixels(uc: Uc) -> tuple[bytearray, bytearray]:
    source = bytearray([0xEE] * (ROWS * STRIDE))
    secondary = source.copy()
    for y in range(ROWS):
        for x in range(COLS * 3):
            source[y * STRIDE + x] = (13 * x + 17 * y) & 255
            secondary[y * STRIDE + x] = (8 * y + 71 * (x % 3)) & 255
    uc.mem_write(SOURCE_PIXELS, bytes(source))
    uc.mem_write(PAPER_PIXELS, bytes(secondary))
    write(uc, SOURCE, "4I", 0x42FF4010, 2, ROWS, COLS)
    write(uc, SOURCE + 0x10, "Q", SOURCE_PIXELS)
    write(uc, SOURCE + 0x48, "2Q", SOURCE + 0x50, STRIDE)
    write(uc, PAPER + 0x10, "Q", PAPER_PIXELS)
    write(uc, PAPER + 0x50, "Q", STRIDE)
    return source, secondary


def verify_reflection(text: tuple[int, bytes], soft: float) -> None:
    uc = machine(text)
    source, paper = pixels(uc)
    raw = bytes(int(i * soft) for i in range(256))
    reflected = bytes(int((i / 255) ** 2.5 * 253 * (1 - soft)) for i in range(256))
    write(uc, RAW + 0x10, "Q", RAW_TABLE)
    write(uc, REFLECTED + 0x10, "Q", REFLECTED_TABLE)
    uc.mem_write(RAW_TABLE, raw)
    uc.mem_write(REFLECTED_TABLE, reflected)
    uc.reg_write(UC_ARM64_REG_X0, SOURCE)
    uc.reg_write(UC_ARM64_REG_X1, PAPER)
    uc.reg_write(UC_ARM64_REG_X2, RAW)
    uc.reg_write(UC_ARM64_REG_X3, REFLECTED)
    execute(uc, 0x1004551FC, RETURN, 150_000)
    expected = source.copy()
    expected_paper = paper.copy()
    for i in PIXEL_OFFSETS:
        value, background = source[i], paper[i]
        ratio = min(255, value * 255 // background) if background else value
        expected[i] = raw[value] + reflected[ratio]
        expected_paper[i] = background or 1
    assert uc.mem_read(SOURCE_PIXELS, len(expected)) == expected, f"Reflection disagrees for {soft=}"
    assert uc.mem_read(PAPER_PIXELS, len(paper)) == expected_paper, "Paper zero branch or padding disagrees"


def verify_sharpening(text: tuple[int, bytes]) -> None:
    uc = machine(text)
    source, blurred = pixels(uc)
    uc.reg_write(UC_ARM64_REG_X19, SOURCE)
    uc.reg_write(UC_ARM64_REG_S8, 0x3FC00000)  # 1.5f
    write(uc, STACK + 0x50, "Q", PAPER_PIXELS)
    write(uc, STACK + 0x88, "2Q", STACK + 0x90, STRIDE)
    # Start after GaussianBlur, stop before freeing the temporary Mat.
    execute(uc, 0x1004568B8, 0x100456984, 150_000)
    expected = source.copy()
    for i in PIXEL_OFFSETS:
        expected[i] = max(0, min(255, math.trunc((5 * source[i] - 3 * blurred[i]) / 2)))
    assert uc.mem_read(SOURCE_PIXELS, len(expected)) == expected, "Sharpening or padding disagrees"


def geometry(text: tuple[int, bytes], points: tuple[float, ...]) -> tuple[tuple[int, int], tuple[float, ...]]:
    uc = machine(text)
    write(uc, SOURCE_PIXELS, "8f", *points)
    # Supply the existing four-point vector. It has space for four replacements.
    frame = STACK + 0x200
    write(uc, frame - 0x90, "3Q", SOURCE_PIXELS, SOURCE_PIXELS + 32, SOURCE_PIXELS + 32)
    uc.reg_write(UC_ARM64_REG_X29, frame)
    # This span measures edges and adjusts corners before any OpenCV allocation.
    execute(uc, 0x10046340C, 0x100463574, 10_000)
    size = uc.reg_read(UC_ARM64_REG_X21), uc.reg_read(UC_ARM64_REG_X22)
    inset = struct.unpack("<8f", uc.mem_read(SOURCE_PIXELS, 32))
    return size, inset


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ipa", type=Path)
    text = load_text(parser.parse_args().ipa)
    verify_reflection(text, 0)
    verify_reflection(text, 0.5)
    verify_sharpening(text)
    print(f"Reference reflection and sharpening matched {len(PIXEL_OFFSETS) * 3} channels and row padding.")

    size, inset = geometry(text, (20, 30, 420, 30, 420, 330, 20, 330))
    assert size == (400, 300)
    assert all(abs(a - b) < .001 for a, b in zip(inset, (24, 33, 416, 33, 416, 327, 24, 327)))
    size, inset = geometry(text, (91, 40, 540, 73, 585, 397, 51, 406))
    assert size == (534, 388)
    print(f"Reference rectification fixture: {size=}, inset={tuple(round(p, 3) for p in inset)}.")


if __name__ == "__main__":
    main()
