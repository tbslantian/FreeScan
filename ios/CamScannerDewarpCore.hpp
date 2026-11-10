/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once

#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <optional>

namespace freescan {
inline constexpr int dewarpModelSize = 240;

inline cv::Mat contourInput(const cv::Mat &bgr) {
    if (bgr.type() != CV_8UC3 || bgr.cols < 2 || bgr.rows < 2) return {};
    cv::Mat input, hsv;
    cv::resize(bgr, input, {dewarpModelSize, dewarpModelSize}, 0, 0, cv::INTER_LINEAR);
    cv::cvtColor(input, hsv, cv::COLOR_BGR2HSV);
    const double brightness = cv::mean(hsv)[2];
    return brightness >= 60 && brightness <= 225 ? input : cv::Mat{};
}

inline std::optional<std::array<cv::Point2f, 4>> refineDocumentContour(
    const cv::Mat &probability, const std::array<cv::Point2f, 4> &normalized) {
    constexpr int side = dewarpModelSize;
    if (probability.type() != CV_32FC1 || probability.size() != cv::Size(side, side) ||
        !cv::checkRange(probability, true, nullptr, 0, 1.001)) return std::nullopt;

    cv::Mat mask;
    cv::compare(probability, 128.0 / 255.0, mask, cv::CMP_GE);
    cv::morphologyEx(mask, mask, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, {3, 3}));
    if (cv::countNonZero(mask) < side * side * 0.2) return std::nullopt;

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    if (contours.empty()) return std::nullopt;
    const auto largest = std::max_element(contours.begin(), contours.end(), [](const auto &a, const auto &b) {
        return cv::contourArea(a) < cv::contourArea(b);
    });
    if (cv::contourArea(*largest) < side * side * 0.2) return std::nullopt;

    std::array<cv::Point, 4> seed;
    for (size_t i = 0; i < seed.size(); ++i) {
        const auto &point = normalized[i];
        if (!std::isfinite(point.x) || !std::isfinite(point.y) || point.x < 0 || point.x > 1 ||
            point.y < 0 || point.y > 1) return std::nullopt;
        seed[i] = {cvRound(point.x * (side - 1)), cvRound(point.y * (side - 1))};
    }
    if (!cv::isContourConvex(seed)) return std::nullopt;

    cv::Mat seedMask = cv::Mat::zeros(side, side, CV_8UC1);
    cv::Mat contourMask = cv::Mat::zeros(side, side, CV_8UC1);
    cv::fillConvexPoly(seedMask, seed, 255);
    cv::drawContours(contourMask, contours, int(largest - contours.begin()), 255, cv::FILLED);
    cv::Mat combined;
    cv::bitwise_or(seedMask, contourMask, combined);
    const int unionCount = cv::countNonZero(combined);
    cv::bitwise_and(seedMask, contourMask, combined);
    if (cv::countNonZero(combined) <= unionCount * 0.93) return std::nullopt;

    // Retain the four detector corners if the curved outline does not supply a nearby vertex.
    std::vector<cv::Point> outline;
    cv::approxPolyDP(*largest, outline, 2.0, true);
    std::array<cv::Point2f, 4> corners;
    for (size_t i = 0; i < seed.size(); ++i) {
        auto corner = seed[i];
        double bestDistance = 0.05 * std::min(cv::norm(seed[(i + 1) % 4] - corner),
                                              cv::norm(seed[(i + 3) % 4] - corner));
        for (const auto &vertex : outline) {
            const double distance = cv::norm(vertex - seed[i]);
            if (distance < bestDistance) {
                corner = vertex;
                bestDistance = distance;
            }
        }
        corners[i] = corner == seed[i] ? normalized[i] : cv::Point2f(corner) * (1.0f / (side - 1));
    }
    return cv::isContourConvex(corners) ? std::optional{corners} : std::nullopt;
}

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

inline cv::Mat validatedDewarpField(const cv::Mat &displacement) {
    if (displacement.type() != CV_32FC2 ||
        displacement.size() != cv::Size(dewarpModelSize, dewarpModelSize) ||
        !cv::checkRange(displacement, true, nullptr, -500.0, 500.0)) {
        return {};
    }

    // PageScan blurs the field before measuring whether enough curvature was detected.
    cv::Mat field;
    cv::GaussianBlur(displacement, field, {39, 39}, 0);
    cv::Scalar mean, deviation;
    cv::meanStdDev(field, mean, deviation);
    if (deviation[0] + deviation[1] < 0.5 ||
        !cv::checkRange(field, true, nullptr, -48.0, 48.0)) {
        return {};
    }

    // Keep the full field up to the paper edges. Reject a folded mapping before resampling.
    for (int y = 0; y < dewarpModelSize - 1; ++y) {
        const auto *row = field.ptr<cv::Vec2f>(y);
        const auto *next = field.ptr<cv::Vec2f>(y + 1);
        for (int x = 0; x < dewarpModelSize - 1; ++x) {
            const cv::Vec2f dx = row[x + 1] - row[x];
            const cv::Vec2f dy = next[x] - row[x];
            const float horizontal = 1.0f + dx[0];
            const float vertical = 1.0f + dy[1];
            const float jacobian = horizontal * vertical - dx[1] * dy[0];
            if (horizontal <= 0 || vertical <= 0 || jacobian <= 0) {
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
