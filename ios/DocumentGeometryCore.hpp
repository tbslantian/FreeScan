/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>
#include <cstdint>

namespace freescan {
inline cv::Size rectifiedDocumentSize(const std::array<cv::Point2f, 4> &source, int maxDimension) {
    auto length = [&](int first, int second) {
        const cv::Point a(cvRound(source[first].x), cvRound(source[first].y));
        const cv::Point b(cvRound(source[second].x), cvRound(source[second].y));
        return int(cv::norm(a - b));
    };
    auto widths = std::minmax({length(0, 1), length(2, 3)});
    auto heights = std::minmax({length(0, 3), length(1, 2)});
    if (widths.first < 2 || heights.first < 2) return {};

    int width = widths.second, height = heights.second;
    if (width > height) {
        height = int(std::int64_t(width) * heights.first / widths.first);
    } else {
        width = int(std::int64_t(height) * widths.first / heights.first);
    }
    const double scale = std::min(1.0, double(maxDimension) / std::max(width, height));
    return {std::max(2, cvRound(width * scale)), std::max(2, cvRound(height * scale))};
}

inline bool insetDocumentEdges(std::array<cv::Point2f, 4> &source, cv::Size size) {
    // Move the top/right/bottom/left supporting lines by 1% along their axes.
    const float dx = size.width * .01f, dy = size.height * .01f;
    const std::array<cv::Point2f, 4> offsets{{{0, dy}, {-dx, 0}, {0, -dy}, {dx, 0}}};
    std::array<cv::Point2f, 4> inset;
    for (int i = 0; i < 4; ++i) {
        const int previous = (i + 3) % 4, next = (i + 1) % 4;
        const auto a = source[i] + offsets[i], b = source[previous] + offsets[previous];
        const auto along = source[next] - source[i], across = source[i] - source[previous];
        const double determinant = along.cross(across);
        if (std::abs(determinant) < 1e-6) return false;
        inset[i] = a + along * float((b - a).cross(across) / determinant);
    }
    source = inset;
    return true;
}

inline cv::Mat rectifyDocument(const cv::Mat &image, const std::array<cv::Point2f, 4> &normalized) {
    if (image.cols < 2 || image.rows < 2) return {};
    std::array<cv::Point2f, 4> source;
    std::transform(normalized.begin(), normalized.end(), source.begin(), [&](const auto &p) {
        return cv::Point2f(p.x * (image.cols - 1), p.y * (image.rows - 1));
    });
    const auto size = rectifiedDocumentSize(source, std::max(image.cols, image.rows));
    if (size.empty() || !insetDocumentEdges(source, size) || !cv::isContourConvex(std::vector<cv::Point2f>(source.begin(), source.end())))
        return {};
    const float width = float(size.width - 1), height = float(size.height - 1);
    const std::array<cv::Point2f, 4> target{{{0, 0}, {width, 0}, {width, height}, {0, height}}};
    const cv::Mat homography = cv::findHomography(source, target, 0);
    if (homography.empty() || !cv::checkRange(homography)) return {};
    cv::Mat output;
    cv::warpPerspective(image, output, homography,
                        size, cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar::all(255));
    return output;
}
} // namespace freescan
