/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once

#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>

namespace freescan {
inline constexpr int dewarpModelSize = 240;

inline cv::Mat dewarpInput(const cv::Mat &bgr) {
    const int shortSide = std::min(bgr.cols, bgr.rows);
    const int longSide = std::max(bgr.cols, bgr.rows);
    if (bgr.type() != CV_8UC3 || shortSide < 64 || longSide >= 32767 ||
        longSide >= 3 * shortSide) {
        return {};
    }

    // The matched PageScan input uses BGR values divided by 255 and staged area resizing.
    cv::Mat reduced = bgr;
    if (shortSide >= 1024) cv::resize(reduced, reduced, {1024, 1024}, 0, 0, cv::INTER_AREA);
    if (shortSide >= 352) cv::resize(reduced, reduced, {352, 352}, 0, 0, cv::INTER_AREA);
    cv::Mat input;
    cv::resize(reduced, input, {dewarpModelSize, dewarpModelSize}, 0, 0, cv::INTER_AREA);
    return input;
}

// Preserve the existing page boundary so a strong estimate cannot crop away content.
inline float preserveDewarpEdge(float shift, int position) {
    const float margin = float(std::min(position, dewarpModelSize - 1 - position));
    return shift * std::min(1.0f, margin / (2.0f * std::max(1.0f, std::abs(shift))));
}

inline cv::Mat validatedDewarpField(const cv::Mat &displacement) {
    if (displacement.type() != CV_32FC2 ||
        displacement.size() != cv::Size(dewarpModelSize, dewarpModelSize) ||
        !cv::checkRange(displacement, true, nullptr, -500.0, 500.0)) {
        return {};
    }

    // PageScan blurs the field before measuring whether enough curvature was detected.
    cv::Mat field;
    cv::GaussianBlur(displacement, field, {9, 9}, 0);
    cv::Scalar mean, deviation;
    cv::meanStdDev(field, mean, deviation);
    if (deviation[0] + deviation[1] < 0.5 ||
        !cv::checkRange(field, true, nullptr, -48.0, 48.0)) {
        return {};
    }

    for (int y = 0; y < dewarpModelSize; ++y) {
        auto *row = field.ptr<cv::Vec2f>(y);
        for (int x = 0; x < dewarpModelSize; ++x) {
            row[x][0] = preserveDewarpEdge(row[x][0], x);
            row[x][1] = preserveDewarpEdge(row[x][1], y);
        }
    }

    // A folded field can duplicate text or reverse a stripe of pixels. Reject it.
    for (int y = 0; y < dewarpModelSize - 1; ++y) {
        const auto *row = field.ptr<cv::Vec2f>(y);
        const auto *next = field.ptr<cv::Vec2f>(y + 1);
        for (int x = 0; x < dewarpModelSize - 1; ++x) {
            const cv::Vec2f dx = row[x + 1] - row[x];
            const cv::Vec2f dy = next[x] - row[x];
            const float horizontal = 1.0f + dx[0];
            const float vertical = 1.0f + dy[1];
            const float jacobian = horizontal * vertical - dx[1] * dy[0];
            if (horizontal < 0.35f || vertical < 0.35f || jacobian < 0.25f || jacobian > 3.0f) {
                return {};
            }
        }
    }
    return field;
}

// The matched model returns backward displacement in 240×240 grid pixels, interleaved x/y
// here. Add it to the identity map and sample the perspective-corrected page once.
inline cv::Mat dewarpDocument(const cv::Mat &perspective, const cv::Mat &displacement) {
    if (perspective.empty() || std::min(perspective.cols, perspective.rows) < 64 ||
        std::max(perspective.cols, perspective.rows) >= 32767) {
        return {};
    }
    const cv::Mat field = validatedDewarpField(displacement);
    if (field.empty()) return {};

    cv::Mat horizontalField;
    cv::resize(field, horizontalField, {perspective.cols, dewarpModelSize}, 0, 0, cv::INTER_LINEAR);
    const float scaleX = float(perspective.cols) / dewarpModelSize;
    const float scaleY = float(perspective.rows) / dewarpModelSize;
    cv::Mat output(perspective.size(), perspective.type());
    constexpr int tileHeight = 128;
    cv::Mat map(tileHeight, perspective.cols, CV_32FC2);
    for (int offset = 0; offset < perspective.rows; offset += tileHeight) {
        const int rows = std::min(tileHeight, perspective.rows - offset);
        for (int y = 0; y < rows; ++y) {
            const int outY = offset + y;
            const float sourceY = std::clamp((outY + 0.5f) / scaleY - 0.5f,
                                            0.0f, float(dewarpModelSize - 1));
            const int top = int(sourceY), bottom = std::min(top + 1, dewarpModelSize - 1);
            const float fraction = sourceY - top;
            const auto *first = horizontalField.ptr<cv::Vec2f>(top);
            const auto *second = horizontalField.ptr<cv::Vec2f>(bottom);
            auto *coords = map.ptr<cv::Vec2f>(y);
            for (int x = 0; x < perspective.cols; ++x) {
                const cv::Vec2f shift = first[x] + (second[x] - first[x]) * fraction;
                coords[x] = {x + scaleX * shift[0], outY + scaleY * shift[1]};
            }
        }
        cv::Mat tile = output.rowRange(offset, offset + rows);
        cv::remap(perspective, tile, map.rowRange(0, rows), cv::noArray(),
                  cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    }
    return output;
}
} // namespace freescan
