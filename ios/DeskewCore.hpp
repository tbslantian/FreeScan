/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <optional>

namespace freescan {
inline std::optional<double> glyphBaselineAngle(const cv::Mat &gray) {
    cv::Mat ink, labels, stats, centers;
    cv::adaptiveThreshold(gray, ink, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C, cv::THRESH_BINARY_INV, 31, 13);
    int count = cv::connectedComponentsWithStats(ink, labels, stats, centers);
    std::vector<cv::Rect> glyphs;
    for (int i = 1; i < count; ++i) {
        const int *s = stats.ptr<int>(i);
        int width = s[cv::CC_STAT_WIDTH], height = s[cv::CC_STAT_HEIGHT];
        if (height >= 6 && height <= gray.rows * .055 && width >= 3 && width <= height * 2 &&
            s[cv::CC_STAT_AREA] >= 10) {
            glyphs.emplace_back(s[cv::CC_STAT_LEFT], s[cv::CC_STAT_TOP], width, height);
        }
    }
    if (glyphs.size() < 12 || glyphs.size() > 4500) return std::nullopt;
    std::sort(glyphs.begin(), glyphs.end(), [](auto a, auto b) { return a.x < b.x; });

    struct Vote { double angle; double weight; int baseline; };
    std::vector<Vote> votes;
    std::array<double, 33> bins{};
    double total = 0;
    for (size_t i = 0; i < glyphs.size(); ++i) {
        const auto &left = glyphs[i];
        for (size_t j = i + 1; j < glyphs.size() && glyphs[j].x - left.x <= left.height * 8; ++j) {
            const auto &right = glyphs[j];
            if (right.height < left.height * .8 || right.height > left.height * 1.25) continue;
            double dx = right.x + right.width * .5 - left.x - left.width * .5;
            if (dx < left.height * 2.0) continue;
            int dy = right.br().y - left.br().y;
            double angle = std::atan2(dy, dx) * 180 / CV_PI;
            if (std::abs(angle) > 8) continue;
            double weight = dx / left.height;
            bins[cvRound(angle * 2) + 16] += weight;
            votes.push_back({angle, weight, left.br().y});
            total += weight;
        }
    }
    double peak = (std::distance(bins.begin(), std::max_element(bins.begin(), bins.end())) - 16) * .5;
    double agreeingWeight = 0, weightedAngle = 0;
    int agreeingCount = 0, firstLine = gray.rows, lastLine = 0;
    for (const auto &vote : votes) {
        if (std::abs(vote.angle - peak) > .8) continue;
        agreeingWeight += vote.weight;
        weightedAngle += vote.angle * vote.weight;
        ++agreeingCount;
        firstLine = std::min(firstLine, vote.baseline);
        lastLine = std::max(lastLine, vote.baseline);
    }
    if (agreeingCount < 15 || agreeingWeight < total * .38 || lastLine - firstLine < gray.rows * .12)
        return std::nullopt;
    return weightedAngle / agreeingWeight;
}

inline double textLineAngle(const cv::Mat &image) {
    if (image.cols<400 || image.rows<400) return 0;
    cv::Mat gray,edges;
    if (image.channels()==4) cv::cvtColor(image,gray,cv::COLOR_RGBA2GRAY);
    else if (image.channels()==3) cv::cvtColor(image,gray,cv::COLOR_RGB2GRAY);
    else gray=image;
    if (std::max(gray.cols, gray.rows) > 1600) {
        double scale = 1600.0 / std::max(gray.cols, gray.rows);
        cv::resize(gray, gray, {}, scale, scale, cv::INTER_AREA);
    }
    int mx=int(std::round(gray.cols*.08)),my=int(std::round(gray.rows*.08));
    cv::Rect roi(mx,my,gray.cols-mx*2,gray.rows-my*2);
    if (auto angle = glyphBaselineAngle(gray(roi))) return *angle;
    cv::Canny(gray(roi),edges,50,150);
    std::vector<cv::Vec4i> lines;
    // Android uses 1-degree bins. Refine to 0.2 degrees so small tilts are not
    // rounded away before median estimation (validated with fractional slopes).
    cv::HoughLinesP(edges,lines,1,CV_PI/900,std::max(20,gray.cols/120),gray.cols*.12,gray.cols*.015);
    std::vector<double> angles;
    for (const auto &l:lines) {
        double angle=std::atan2(double(l[3]-l[1]),double(l[2]-l[0]))*180/CV_PI;
        if (angle>90) angle-=180;
        if (angle< -90) angle+=180;
        if (std::abs(angle)<=8) angles.push_back(angle);
    }
    if (angles.size()<4) return 0;
    std::sort(angles.begin(),angles.end());
    return angles[angles.size()/2];
}

inline cv::Mat deskewText(const cv::Mat &image,double &appliedAngle) {
    appliedAngle=0;
    double angle=textLineAngle(image);
    if (std::abs(angle)<.35 || std::abs(angle)>4) return image.clone();
    // Hough angles are measured with image y pointing down. OpenCV's positive
    // rotation subtracts this slope; negating it would double the skew.
    auto transform=cv::getRotationMatrix2D(cv::Point2f(image.cols/2.f,image.rows/2.f),angle,1);
    cv::Mat output;
    cv::warpAffine(image,output,transform,image.size(),cv::INTER_CUBIC,cv::BORDER_REPLICATE);
    appliedAngle=angle;
    return output;
}
} // namespace freescan
