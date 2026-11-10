#!/bin/bash
# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

if [ "$(uname -s)" != Darwin ] || { [ "$#" -ne 2 ] && { [ "$#" -ne 1 ] || [ "$1" != --runtime-only ]; }; }; then
    echo "Usage on macOS: bash ios/build-reference-scan.sh <reference.ipa> <android-reference.apk> | --runtime-only" >&2
    exit 2
fi

root="$(cd "$(dirname "$0")" && pwd)"
stage_models=YES
if [ "$1" = --runtime-only ]; then
    stage_models=NO
    mkdir -p "$root/.build/camscanner-models"
else
    python3 "$root/porting/extract_camscanner_models.py" "$1" --android-reference-apk "$2"
fi
revision=7e3c6fb5225d3ccf22807ee641332669df34650d
work="$root/.build/mnn-work"
source="$work/source-$revision"
prefix="$root/.build/mnn-iphoneos"
mkdir -p "$work"
if [ ! -d "$source/.git" ]; then
    git init "$source"
fi
if [ "$(git -C "$source" rev-parse HEAD 2>/dev/null || true)" != "$revision" ]; then
    git -C "$source" fetch --depth=1 https://github.com/alibaba/MNN.git "$revision"
    git -C "$source" checkout --detach "$revision"
fi
test "$(git -C "$source" rev-parse HEAD)" = "$revision"

cmake -S "$source" -B "$work/iphoneos-$revision" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_SYSTEM_PROCESSOR=arm64 \
    -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO \
    -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF -DMNN_AAPL_FMWK=OFF \
    -DMNN_BUILD_TOOLS=OFF -DMNN_BUILD_CONVERTER=OFF -DMNN_BUILD_BENCHMARK=OFF \
    -DMNN_BUILD_PROTOBUFFER=OFF -DMNN_METAL=OFF -DMNN_COREML=OFF -DMNN_SME2=OFF
cmake --build "$work/iphoneos-$revision" --target MNN --parallel 4
cmake --install "$work/iphoneos-$revision"
test -f "$prefix/lib/libMNN.a"
test -f "$prefix/include/MNN/Interpreter.hpp"
cp "$source/LICENSE.txt" "$prefix/MNN-LICENSE.txt"

cat >"$root/.build/camscanner-models/Enabled.xcconfig" <<'CONFIG'
FREESCAN_USE_MNN = YES
FREESCAN_MNN_INCLUDE = $(SRCROOT)/.build/mnn-iphoneos/include
FREESCAN_MNN_FORCE_LOAD = -force_load
FREESCAN_MNN_ARCHIVE = $(SRCROOT)/.build/mnn-iphoneos/lib/libMNN.a
GCC_PREPROCESSOR_DEFINITIONS = $(inherited) FREESCAN_USE_MNN=1
ENABLE_USER_SCRIPT_SANDBOXING = NO
CONFIG
printf 'FREESCAN_STAGE_MNN_MODELS = %s\n' "$stage_models" >>"$root/.build/camscanner-models/Enabled.xcconfig"
