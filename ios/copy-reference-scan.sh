#!/bin/bash
# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

extractor="$SRCROOT/porting/extract_camscanner_models.py"
model_names="$(/usr/bin/python3 "$extractor" --list-models)"
IFS=$'\n' read -r -d '' -a models <<< "$model_names" || true
destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/ReferenceScan"
if [ "${FREESCAN_USE_MNN:-NO}" != YES ]; then
    if [ -d "$destination" ]; then
        for name in "${models[@]}" MNN-LICENSE.txt; do rm -f -- "$destination/$name"; done
        rmdir -- "$destination"
    fi
    exit 0
fi
if [ "$PLATFORM_NAME" != iphoneos ]; then
    echo "error: Reference scan models are configured for the iPhone/iPad device target." >&2
    exit 1
fi

mkdir -p "$destination"
if [ "${FREESCAN_STAGE_MNN_MODELS:-YES}" = YES ]; then
    /usr/bin/python3 "$extractor" --verify-only
    for name in "${models[@]}"; do
        cp "$SRCROOT/.build/camscanner-models/$name" "$destination/$name"
    done
else
    for name in "${models[@]}"; do rm -f -- "$destination/$name"; done
fi
cp "$SRCROOT/.build/mnn-iphoneos/MNN-LICENSE.txt" "$destination/MNN-LICENSE.txt"
