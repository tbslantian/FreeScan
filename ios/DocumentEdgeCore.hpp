/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/dnn.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <optional>

namespace freescan {
using EdgeQuad = std::array<cv::Point2f, 4>;
inline constexpr int edgeInputSize = 256;

inline std::optional<EdgeQuad> fitDocumentEdges(const cv::Mat &logits) {
    if (logits.size() != cv::Size(edgeInputSize, edgeInputSize) || logits.type() != CV_32FC1)
        return std::nullopt;
    cv::Mat edges;
    cv::compare(logits, 0, edges, cv::CMP_GT);
    if (cv::countNonZero(edges) > edges.total() / 3) return std::nullopt;
    cv::Mat connected;
    cv::morphologyEx(edges, connected, cv::MORPH_CLOSE,
                     cv::getStructuringElement(cv::MORPH_RECT, {5, 5}));
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(connected, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    std::sort(contours.begin(), contours.end(), [](const auto &left, const auto &right) {
        return cv::contourArea(left) > cv::contourArea(right);
    });

    const double imageArea = edges.total();
    for (const auto &contour : contours) {
        if (cv::contourArea(contour) < imageArea * .08) break;
        std::vector<cv::Point> hull, polygon;
        cv::convexHull(contour, hull);
        const double perimeter = cv::arcLength(hull, true);
        for (double tolerance : {.015, .025, .04}) {
            cv::approxPolyDP(hull, polygon, perimeter * tolerance, true);
            if (polygon.size() == 4) break;
        }
        if (polygon.size() != 4 || cv::contourArea(polygon) < imageArea * .08 ||
            cv::contourArea(polygon) > imageArea * .96 || !cv::isContourConvex(polygon))
            continue;
        if (cv::contourArea(polygon, true) < 0) std::reverse(polygon.begin(), polygon.end());
        auto topLeft = std::min_element(polygon.begin(), polygon.end(), [](const auto &left, const auto &right) {
            return left.x + left.y < right.x + right.y;
        });
        std::rotate(polygon.begin(), topLeft, polygon.end());
        EdgeQuad quad;
        std::transform(polygon.begin(), polygon.end(), quad.begin(), [](const auto &point) {
            return cv::Point2f(point) * (1.f / (edgeInputSize - 1));
        });
        return quad;
    }
    return std::nullopt;
}

inline std::optional<EdgeQuad> detectDocumentEdges(cv::dnn::Net &model, const cv::Mat &rgb) {
    if (model.empty() || rgb.empty()) return std::nullopt;
    cv::Mat input = cv::dnn::blobFromImage(rgb, 1.0 / 255, {edgeInputSize, edgeInputSize});
    model.setInput(input);
    cv::Mat logits = model.forward();
    if (logits.type() != CV_32FC1 || logits.total() != edgeInputSize * edgeInputSize) return std::nullopt;
    return fitDocumentEdges(logits.reshape(1, edgeInputSize));
}
} // namespace freescan
