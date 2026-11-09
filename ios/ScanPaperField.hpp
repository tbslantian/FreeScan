/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once

#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <utility>
#include <vector>

namespace freescan {
namespace scanpaper {
inline constexpr int tile = 6;
inline constexpr int window = tile * 3;

inline cv::Size analysisSize(cv::Size size) {
    const float aspect = float(size.width) / size.height;
    if (aspect >= 3 || aspect <= .3) {
        const double scale = std::min(.8, 1800.0 / std::max(size.width, size.height));
        return {std::max(1, cvRound(size.width * scale)), std::max(1, cvRound(size.height * scale))};
    }
    return aspect >= 1 ? cv::Size(int(600 * aspect), 600)
                       : cv::Size(600, int(600 * (float(size.height) / size.width)));
}

inline void sampleCandidates(const cv::Mat &padded, cv::Mat3b &modes, cv::Mat1b &candidates) {
    for (int y = 0; y < modes.rows - 2; ++y) {
        std::array<std::array<int, 256>, 3> histogram{};
        auto update = [&](int first, int count, int amount) {
            for (int row = y * tile; row < y * tile + window; ++row) {
                const auto *pixels = padded.ptr<cv::Vec3b>(row);
                for (int x = first; x < first + count; ++x) {
                    for (int c = 0; c < 3; ++c) histogram[c][pixels[x][c]] += amount;
                }
            }
        };
        update(0, window, 1);
        for (int x = 0; x < modes.cols - 3; ++x) {
            cv::Vec3b color;
            bool accepted = false;
            for (int c = 0; c < 3; ++c) {
                const auto &bins = histogram[c];
                const int peak = int(std::max_element(bins.begin(), bins.end()) - bins.begin());
                const int center = std::clamp(peak, 6, 249);
                int support = 0;
                for (int i = center - 6; i <= center + 6; ++i) support += bins[i];
                // The reference divides by 225 even though the window spans 18×18.
                accepted |= support > .65 * 225;
                color[c] = uchar(center);
            }
            if (accepted) {
                modes(y + 1, x + 1) = color;
                candidates(y + 1, x + 1) = 1;
            }
            if (x + 1 < modes.cols - 3) {
                update(x * tile, tile, -1);
                update((x + 3) * tile, tile, 1);
            }
        }
    }
}

inline std::vector<cv::Point> largestGroup(const cv::Mat3b &modes, cv::Mat1b &candidates) {
    std::vector<cv::Point> largest, group;
    const cv::Rect bounds(0, 0, modes.cols, modes.rows);
    for (int y = 0; y < modes.rows; ++y) for (int x = 0; x < modes.cols; ++x) {
        if (!candidates(y, x)) continue;
        group.clear();
        group.emplace_back(x, y);
        candidates(y, x) = 0;
        for (size_t i = 0; i < group.size(); ++i) {
            const cv::Point point = group[i];
            const auto color = modes(point);
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                const cv::Point neighbor = point + cv::Point(dx, dy);
                if (!bounds.contains(neighbor) || !candidates(neighbor)) continue;
                const auto next = modes(neighbor);
                const int difference = std::max({std::abs(int(color[0]) - next[0]),
                                                 std::abs(int(color[1]) - next[1]),
                                                 std::abs(int(color[2]) - next[2])});
                if (difference >= 15) continue;
                candidates(neighbor) = 0;
                group.push_back(neighbor);
            }
        }
        if (group.size() > largest.size()) largest.swap(group);
    }
    return largest;
}
} // namespace scanpaper

// Paper-sampling path recovered from the reference's histogram, component and
// inpainting calls. An empty result requests the histogram brightness fallback.
inline cv::Mat scanPaperField(const cv::Mat &bgr) {
    cv::Mat sample;
    cv::resize(bgr, sample, scanpaper::analysisSize(bgr.size()), 0, 0, cv::INTER_LINEAR);
    cv::Mat padded;
    constexpr int tile = scanpaper::tile;
    cv::copyMakeBorder(sample, padded, tile, tile, tile, tile, cv::BORDER_REFLECT_101);
    const cv::Size grid(padded.cols / tile, padded.rows / tile);
    if (grid.width < 4 || grid.height < 3) return {};
    cv::Mat3b modes(grid, cv::Vec3b::all(0));
    cv::Mat1b candidates(grid, uchar(0));
    scanpaper::sampleCandidates(padded, modes, candidates);
    const auto group = scanpaper::largestGroup(modes, candidates);
    if (group.empty()) return {};

    cv::Mat3b seeds(grid, cv::Vec3b::all(0));
    cv::Mat1b missing(grid, uchar(255));
    for (const auto &point : group) {
        seeds(point) = modes(point);
        missing(point) = 0;
    }
    cv::Mat3b field;
    cv::inpaint(seeds, missing, field, 3, cv::INPAINT_NS);
    for (auto &pixel : field) {
        for (int c = 0; c < 3; ++c) {
            if (pixel[c] != 255) pixel[c] = uchar(float(pixel[c]) * .95f);
        }
    }
    return field(cv::Rect(1, 1, field.cols - 2, field.rows - 2));
}
} // namespace freescan
