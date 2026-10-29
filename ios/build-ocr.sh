#!/bin/bash
# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
work="$root/.build/ocr-work"
mkdir -p "$work"
fetch() {
  local name="$1" url="$2" digest="$3"
  if [ ! -f "$work/$name.tar.gz" ]; then curl --fail --location --retry 3 "$url" -o "$work/$name.tar.gz"; fi
  printf '%s  %s\n' "$digest" "$work/$name.tar.gz" | shasum -a 256 -c -
  if [ ! -d "$work/$name" ]; then mkdir -p "$work/$name"; tar -xzf "$work/$name.tar.gz" -C "$work/$name" --strip-components=1; fi
}
fetch tesseract https://codeload.github.com/tesseract-ocr/tesseract/tar.gz/0995615022dc368f65c6b88f31784d8bc384f855 7b530ec0ab44ab97136be47590e0ecd0cbe1c3fcc165421c98680a6d5a158eba
fetch leptonica https://codeload.github.com/DanBloomberg/leptonica/tar.gz/63aef18d98432b8582a1565e241f7bd2ee9cc8d9 beb4804d5cc9d274986e22b01a9c7e1ad4858d8487bfe9215728eb5e906f07dc
for platform in device host; do
  prefix="$root/.build/ocr-$platform"
  if [ -f "$prefix/.complete" ]; then continue; fi
  args=(-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX="$prefix")
  if [ "$platform" = device ]; then
    args+=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO)
  else
    args+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 -DCMAKE_OSX_ARCHITECTURES="$(uname -m)")
  fi
  cmake -S "$work/leptonica" -B "$work/leptonica-$platform" "${args[@]}" \
    -DSW_BUILD=OFF -DBUILD_PROG=OFF -DENABLE_ZLIB=OFF -DENABLE_PNG=OFF -DENABLE_GIF=OFF \
    -DENABLE_JPEG=OFF -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF
  cmake --build "$work/leptonica-$platform" --parallel 4
  cmake --install "$work/leptonica-$platform"
  cmake -S "$work/tesseract" -B "$work/tesseract-$platform" "${args[@]}" \
    -DLeptonica_DIR="$prefix/lib/cmake/leptonica" -DSW_BUILD=OFF -DBUILD_TRAINING_TOOLS=OFF \
    -DBUILD_TESTS=OFF -DOPENMP_BUILD=OFF -DENABLE_NATIVE=OFF -DGRAPHICS_DISABLED=ON \
    -DDISABLE_TIFF=ON -DDISABLE_ARCHIVE=ON -DDISABLE_CURL=ON \
    -DLEPT_TIFF_RESULT=1 -DLEPT_TIFF_RESULT__TRYRUN_OUTPUT=""
  cmake --build "$work/tesseract-$platform" --parallel 4
  cmake --install "$work/tesseract-$platform"
  test -f "$prefix/lib/libtesseract.a"
  test -f "$prefix/lib/libleptonica.a"
  touch "$prefix/.complete"
done
