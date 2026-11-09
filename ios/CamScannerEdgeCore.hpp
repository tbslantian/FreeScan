/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once

#include "DocumentEdgeCore.hpp"
#include <cmath>

namespace freescan {
namespace camScannerEdge {

struct Line {
    cv::Point2f start;
    cv::Point2f end;
    cv::Point2f tangent;
    float length;
};

inline float cross(cv::Point2f a, cv::Point2f b) {
    return a.x * b.y - a.y * b.x;
}

struct ChainFit {
    cv::Point2d sum{};
    double xx = 0, xy = 0, yy = 0;
    int count = 0;

    void add(cv::Point2f p) {
        sum += cv::Point2d(p);
        xx += p.x * p.x;
        xy += p.x * p.y;
        yy += p.y * p.y;
        ++count;
    }

    cv::Point2f center() const { return cv::Point2f(sum * (1.0 / count)); }

    cv::Point2f tangent() const {
        const double theta = .5 * std::atan2(2 * (xy - sum.x * sum.y / count),
                                            xx - yy - (sum.x * sum.x - sum.y * sum.y) / count);
        return {float(std::cos(theta)), float(std::sin(theta))};
    }

    Line endpoints(cv::Point2f first, cv::Point2f last) const {
        const auto mid = center(), dir = tangent();
        auto a = mid + dir * (first - mid).dot(dir);
        auto b = mid + dir * (last - mid).dot(dir);
        return {a, b, dir, float(cv::norm(b - a))};
    }
};

// EDLines' Helmholtz validation: test the number of aligned chain tangents
// against independent, uniformly distributed undirected orientations. The
// accepted angular interval occupies one quarter of that orientation space.
inline bool meaningfulLine(const std::vector<cv::Point> &chain, size_t first,
                           size_t end, const Line &line, double tests) {
    constexpr float alignedCosine = .9238795f; // cos(22.5 degrees)
    constexpr double chance = .25;
    int aligned = 0;
    for (size_t i = first + 2; i + 2 < end; ++i) {
        const cv::Point2f delta(chain[i + 2] - chain[i - 2]);
        if (std::abs(delta.dot(line.tangent)) > alignedCosine * cv::norm(delta)) ++aligned;
    }
    const int trials = int(end - first) - 4;
    if (trials < 5 || aligned <= chance * trials) return false;
    const double logFirst = std::lgamma(trials + 1.) - std::lgamma(aligned + 1.)
        - std::lgamma(trials - aligned + 1.) + aligned * std::log(chance)
        + (trials - aligned) * std::log1p(-chance);
    double term = 1, tail = 1;
    for (int k = aligned; k < trials && term > tail * 1e-10; ++k) {
        term *= (trials - k) * chance / ((k + 1) * (1 - chance));
        tail += term;
    }
    return std::log(tests) + logFirst + std::log(tail) < 0;
}

// The MNN probability map supplies the edge chains to the EDLines
// seed / extend / validate stage. Walk ordered contour pixels so a fit cannot
// mix unrelated straight strokes merely because they are close in the image.
inline std::vector<Line> extractLines(cv::Mat edges) {
    const float side = float(std::min(edges.rows, edges.cols));
    const int seed = std::max(8, int(side * .047f));
    const float tolerance = std::max(1.35f, side * .0075f);
    const double tests = double(edges.total()) * edges.total();
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_NONE);
    std::vector<Line> lines;
    for (auto &chain : contours) {
        if (chain.size() < size_t(seed * 2)) continue;
        // Close the traversal across the contour's arbitrary starting index.
        for (int i = 0; i < seed; ++i) {
            const auto point = chain[i];
            chain.push_back(point);
        }
        for (size_t first = 0; first + seed < chain.size();) {
            ChainFit fit;
            for (size_t i = first; i < first + seed; ++i) fit.add(chain[i]);
            auto mid = fit.center(), dir = fit.tangent();
            bool validSeed = true;
            for (size_t i = first; i < first + seed; ++i) {
                if (std::abs(cross(dir, cv::Point2f(chain[i]) - mid)) > tolerance) {
                    validSeed = false;
                    break;
                }
            }
            if (!validSeed) {
                first += 3;
                continue;
            }
            size_t end = first + seed;
            for (; end < chain.size(); ++end) {
                const cv::Point2f point(chain[end]);
                if (std::abs(cross(dir, point - mid)) > tolerance) break;
                fit.add(point);
                if ((end - first) % 8 == 0) {
                    mid = fit.center();
                    dir = fit.tangent();
                }
            }
            auto line = fit.endpoints(chain[first], chain[end - 1]);
            if (line.length >= side * .10f && meaningfulLine(chain, first, end, line, tests))
                lines.push_back(line);
            first = end;
        }
    }
    return lines;
}

inline bool mergeLines(Line &line, const Line &other, float side) {
    if (std::abs(line.tangent.dot(other.tangent)) < .984f) return false;
    const auto a = other.start - line.start, b = other.end - line.start;
    if (std::max(std::abs(cross(line.tangent, a)), std::abs(cross(line.tangent, b))) > side * .025f)
        return false;
    const auto dir = (line.end - line.start) * (1.f / line.length);
    const float projectionA = dir.dot(a), projectionB = dir.dot(b);
    const float left = std::min(projectionA, projectionB), right = std::max(projectionA, projectionB);
    if (left > line.length + side * .32f || right < -side * .32f)
        return false;
    const auto start = line.start + dir * std::min(0.f, left);
    const auto end = line.start + dir * std::max(line.length, right);
    line = {start, end, dir, float(cv::norm(end - start))};
    return true;
}

inline std::optional<cv::Point2f> intersection(const Line &a, const Line &b) {
    const float determinant = cross(a.tangent, b.tangent);
    if (std::abs(determinant) < .17f) return std::nullopt;
    return a.start + a.tangent * (cross(b.start - a.start, b.tangent) / determinant);
}

inline float sideSupport(cv::Point2f a, cv::Point2f b, const Line &line,
                         const cv::Mat &distance) {
    const auto dir = b - a;
    const float length = float(cv::norm(dir));
    const float side = float(std::min(distance.rows, distance.cols));
    if (length < side * .13f) return 0;
    const auto unit = dir * (1.f / length);
    const float first = (line.start - a).dot(unit), last = (line.end - a).dot(unit);
    const float overlap = std::min(length, std::max(first, last)) - std::max(0.f, std::min(first, last));
    if (overlap < length * .26f) return 0;
    constexpr int samples = 23;
    int supported = 0;
    for (int i = 1; i <= samples; ++i) {
        const auto p = a + dir * (float(i) / (samples + 1));
        const int x = cvRound(p.x), y = cvRound(p.y);
        if (x >= 0 && y >= 0 && x < distance.cols && y < distance.rows &&
            distance.at<float>(y, x) <= std::max(2.f, side * .016f)) ++supported;
    }
    return float(supported) / samples;
}

inline float scoreQuad(const std::array<Line, 4> &lines, const cv::Mat &distance, EdgeQuad &quad) {
    const float margin = float(std::min(distance.rows, distance.cols)) * .025f;
    for (size_t i = 0; i < quad.size(); ++i) {
        auto p = intersection(lines[i], lines[(i + 1) % 4]);
        if (!p || p->x < -margin || p->y < -margin ||
            p->x > distance.cols - 1 + margin || p->y > distance.rows - 1 + margin)
            return 0;
        quad[i] = *p;
    }
    float twiceArea = 0;
    float winding = 0;
    for (size_t i = 0; i < quad.size(); ++i) {
        const auto a = quad[i], b = quad[(i + 1) % 4], c = quad[(i + 2) % 4];
        const float turn = cross(b - a, c - b);
        if (i == 0) winding = turn;
        if (turn * winding <= 0) return 0;
        twiceArea += cross(a, b);
    }
    const float areaRatio = std::abs(twiceArea) / (2.f * distance.total());
    if (areaRatio < .08f || areaRatio > .98f) return 0;
    float totalSupport = 0;
    for (size_t i = 0; i < quad.size(); ++i) {
        const float support = sideSupport(quad[(i + 3) % 4], quad[i], lines[i], distance);
        if (support < .47f) return 0;
        totalSupport += support;
    }
    if (totalSupport < 2.7f) return 0;
    return totalSupport + areaRatio * .8f;
}

inline void normalizeQuad(EdgeQuad &quad, cv::Size size) {
    if (cross(quad[1] - quad[0], quad[2] - quad[1]) < 0) std::reverse(quad.begin(), quad.end());
    const auto topLeft = std::min_element(quad.begin(), quad.end(), [size](auto a, auto b) {
        return a.x / size.width + a.y / size.height < b.x / size.width + b.y / size.height;
    });
    std::rotate(quad.begin(), topLeft, quad.end());
    for (auto &p : quad) {
        p.x = std::clamp(p.x / (size.width - 1), 0.f, 1.f);
        p.y = std::clamp(p.y / (size.height - 1), 0.f, 1.f);
    }
}
} // namespace camScannerEdge

inline std::optional<EdgeQuad> fitCamScannerDocumentEdges(const cv::Mat &probabilities) {
    using namespace camScannerEdge;
    if (probabilities.dims != 2 || probabilities.type() != CV_32FC1 ||
        probabilities.rows < 24 || probabilities.cols < 24 ||
        !cv::checkRange(probabilities, true, nullptr, 0., 1.001))
        return std::nullopt;
    double peak = 0;
    cv::minMaxLoc(probabilities, nullptr, &peak);
    if (peak < .25) return std::nullopt;
    cv::Mat edges;
    cv::compare(probabilities, std::max(.18, .32 * peak), edges, cv::CMP_GT);
    const auto edgeCount = size_t(cv::countNonZero(edges));
    if (edgeCount < size_t(std::min(edges.rows, edges.cols)) || edgeCount > edges.total() / 3)
        return std::nullopt;

    cv::Mat connected;
    cv::morphologyEx(edges, connected, cv::MORPH_CLOSE,
                     cv::getStructuringElement(cv::MORPH_ELLIPSE, {5, 5}));
    auto candidates = extractLines(connected);
    std::sort(candidates.begin(), candidates.end(),
              [](const Line &a, const Line &b) { return a.length > b.length; });
    std::vector<Line> lines;
    const float side = float(std::min(edges.rows, edges.cols));
    for (const auto &candidate : candidates) {
        bool merged = false;
        for (auto &line : lines) {
            if (mergeLines(line, candidate, side)) {
                merged = true;
                break;
            }
        }
        if (!merged && lines.size() < 18) lines.push_back(candidate);
    }
    cv::Mat inverted, distance;
    cv::bitwise_not(edges, inverted);
    cv::distanceTransform(inverted, distance, cv::DIST_L2, cv::DIST_MASK_3);
    std::optional<EdgeQuad> best;
    float bestScore = 0;
    for (size_t a = 0; a < lines.size(); ++a) {
        for (size_t b = a + 1; b < lines.size(); ++b) {
            for (size_t c = b + 1; c < lines.size(); ++c) {
                for (size_t d = c + 1; d < lines.size(); ++d) {
                    for (const auto &cycle : {std::array<size_t, 4>{a, b, c, d},
                                              std::array<size_t, 4>{a, b, d, c},
                                              std::array<size_t, 4>{a, c, b, d}}) {
                        EdgeQuad quad;
                        const float score = scoreQuad(
                            {lines[cycle[0]], lines[cycle[1]], lines[cycle[2]], lines[cycle[3]]}, distance, quad);
                        if (score > bestScore) {
                            best = quad;
                            bestScore = score;
                        }
                    }
                }
            }
        }
    }
    if (best) normalizeQuad(*best, probabilities.size());
    return best;
}
} // namespace freescan
