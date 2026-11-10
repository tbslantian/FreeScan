/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <cmath>

namespace freescan {
inline cv::Size rectifiedDocumentSize(const std::array<cv::Point2f, 4> &source) {
    const auto length = [&](int first, int second) { return float(cv::norm(source[first] - source[second])); };
    const auto widths = std::minmax({length(0, 1), length(2, 3)});
    const auto heights = std::minmax({length(0, 3), length(1, 2)});
    if (widths.first < 2 || heights.first < 2) return {};

    // Anchor the aspect ratio from the two shorter edges to the axis with greater foreshortening.
    const float ratio = widths.first / heights.first;
    int width, height;
    if (widths.second - widths.first > heights.second - heights.first) {
        width = int(widths.second);
        height = int(width / ratio + 0.5f);
    } else {
        height = int(heights.second);
        width = int(height * ratio + 0.5f);
    }
    width = (width + 6) & ~3;
    height = (height + 6) & ~3;
    if (width > 2.5f * widths.second || height > 2.5f * heights.second) {
        width = int((widths.first + widths.second) * 0.5f);
        height = int((heights.first + heights.second) * 0.5f);
    }
    return {width, height};
}

inline cv::Mat rectifyDocument(const cv::Mat &image, const std::array<cv::Point2f, 4> &normalized) {
    if (image.cols < 2 || image.rows < 2) return {};
    std::array<cv::Point2f, 4> source;
    for (size_t i = 0; i < normalized.size(); ++i) {
        const auto &p = normalized[i];
        if (!std::isfinite(p.x) || !std::isfinite(p.y)) return {};
        source[i] = {std::round(std::clamp(p.x, 0.0f, 1.0f) * (image.cols - 1)),
                     std::round(std::clamp(p.y, 0.0f, 1.0f) * (image.rows - 1))};
    }
    if (!cv::isContourConvex(source)) return {};
    const auto size = rectifiedDocumentSize(source);
    // Bound malformed corner estimates before allocating the output bitmap.
    if (size.empty() || size.width >= 32767 || size.height >= 32767 ||
        static_cast<double>(size.width) * size.height > 2.0 * image.cols * image.rows) return {};
    const float width = float(size.width), height = float(size.height);
    const std::array<cv::Point2f, 4> target{{{0, 0}, {width, 0}, {width, height}, {0, height}}};
    const cv::Mat homography = cv::findHomography(source, target, 0);
    if (homography.empty() || !cv::checkRange(homography)) return {};
    cv::Mat output;
    cv::warpPerspective(image, output, homography,
                        size, cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar::all(255));
    return output;
}
} // namespace freescan
