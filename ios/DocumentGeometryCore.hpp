/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>

namespace freescan {
inline cv::Mat rectifyDocument(const cv::Mat &image, const std::array<cv::Point2f, 4> &normalized) {
    if (image.cols < 2 || image.rows < 2) return {};
    std::array<cv::Point2f, 4> source;
    std::transform(normalized.begin(), normalized.end(), source.begin(), [&](const auto &p) {
        return cv::Point2f(p.x * (image.cols - 1), p.y * (image.rows - 1));
    });
    int width = cvRound(std::max(cv::norm(source[0] - source[1]), cv::norm(source[3] - source[2]))) + 1;
    int height = cvRound(std::max(cv::norm(source[0] - source[3]), cv::norm(source[1] - source[2]))) + 1;
    if (std::min(width, height) < 2) return {};
    const double scale = std::min(1.0, double(std::max(image.cols, image.rows)) / std::max(width, height));
    width = std::max(2, cvRound(width * scale));
    height = std::max(2, cvRound(height * scale));
    const std::array<cv::Point2f, 4> target{{{0, 0}, {float(width - 1), 0},
                                            {float(width - 1), float(height - 1)}, {0, float(height - 1)}}};
    cv::Mat output;
    cv::warpPerspective(image, output, cv::getPerspectiveTransform(source.data(), target.data()),
                        cv::Size(width, height), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    return output;
}
} // namespace freescan
