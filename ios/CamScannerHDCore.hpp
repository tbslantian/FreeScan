/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once

#include "CamScannerFilterCore.hpp"
#include "ReferenceScanModels.hpp"
#include <array>

#if FREESCAN_USE_MNN
namespace freescan {

inline int classifyHdDocument(ReferenceScanModel &radar, const cv::Mat &bgr) {
    constexpr int side = 448;
    const float scale = float(side) / std::max(bgr.cols, bgr.rows);
    const int width = std::clamp(int(bgr.cols * scale), 1, side);
    const int height = std::clamp(int(bgr.rows * scale), 1, side);
    cv::Mat sample(side, side, CV_8UC3, cv::Scalar::all(128));
    cv::Mat fitted = sample(cv::Rect((side - width) / 2, (side - height) / 2, width, height));
    cv::resize(bgr, fitted, fitted.size(), 0, 0, cv::INTER_LINEAR);
    const auto scores = radar.run(sample, "195", {1, 9}, 1.f / 128.f, -1.f);
    if (scores.empty()) return -1;
    return int(std::max_element(scores.begin(), scores.end()) - scores.begin());
}

inline cv::Mat hdInputFromShadow(const cv::Mat &bgr, std::vector<float> &shadow,
                                int shadowSide, int side) {
    cv::Mat source;
    cv::resize(bgr, source, {side, side}, 0, 0, cv::INTER_LINEAR);
    std::array<cv::Mat, 3> channels;
    for (int channel = 0; channel < 3; ++channel) {
        const cv::Mat plane(shadowSide, shadowSide, CV_32F,
                            shadow.data() + channel * shadowSide * shadowSide);
        cv::Mat paper;
        cv::resize(plane, paper, {side, side}, 0, 0, cv::INTER_LINEAR);
        cv::max(paper, 1.f / 255.f, paper);
        cv::extractChannel(source, channels[channel], 2 - channel);
        channels[channel].convertTo(channels[channel], CV_32F);
        cv::divide(channels[channel], paper, channels[channel], 1.f / 255.f);
        cv::min(channels[channel], 1.f, channels[channel]);
    }
    cv::Mat rgb;
    cv::merge(channels.data(), int(channels.size()), rgb);
    return rgb;
}

// Sample correction fields in the output loop, without allocating full-size resized maps.
class HdLinearField {
public:
    HdLinearField(const cv::Mat &field, cv::Size size)
        : field_(field), rows_(taps(field.rows, size.height)), columns_(taps(field.cols, size.width)) {}

    template <typename Pixel>
    class Row {
    public:
        Row(const Pixel *top, const Pixel *bottom, float fraction, const std::vector<cv::Vec2f> &columns)
            : top_(top), bottom_(bottom), fraction_(fraction), columns_(columns) {}

        int at(int x) const {
            const int left = int(columns_[x][0]);
            const float rightWeight = columns_[x][1];
            const float leftWeight = 1.f - rightWeight;
            const float top = top_[left] * leftWeight + top_[left + 1] * rightWeight;
            const float bottom = bottom_[left] * leftWeight + bottom_[left + 1] * rightWeight;
            return cvRound(top * (1.f - fraction_) + bottom * fraction_);
        }

    private:
        const Pixel *top_, *bottom_;
        float fraction_;
        const std::vector<cv::Vec2f> &columns_;
    };

    template <typename Pixel>
    Row<Pixel> row(int y) const {
        const int top = int(rows_[y][0]);
        return {field_.ptr<Pixel>(top), field_.ptr<Pixel>(top + 1), rows_[y][1], columns_};
    }

private:
    static std::vector<cv::Vec2f> taps(int source, int target) {
        std::vector<cv::Vec2f> weights(target);
        const double scale = double(source) / target;
        for (int i = 0; i < target; ++i) {
            const float position = std::clamp(float((i + 0.5) * scale - 0.5), 0.f, float(source - 1));
            const int low = std::min(int(position), source - 2);
            weights[i] = cv::Vec2f(float(low), position - low);
        }
        return weights;
    }

    cv::Mat field_;
    std::vector<cv::Vec2f> rows_, columns_;
};

inline cv::Mat applyHdOffsetBlend(const cv::Mat &bgr, std::vector<float> &shadow, int shadowSide,
                                  std::vector<float> &offset, int offsetSide) {
    cv::Mat result(bgr.size(), CV_8UC3);
    for (int channel = 0; channel < 3; ++channel) {
        const int rgbChannel = 2 - channel;
        cv::Mat paper(shadowSide, shadowSide, CV_32F,
                      shadow.data() + rgbChannel * shadowSide * shadowSide);
        cv::Mat adjustment(offsetSide, offsetSide, CV_32F,
                           offset.data() + rgbChannel * offsetSide * offsetSide);
        cv::Mat paperBytes(shadowSide, shadowSide, CV_8U);
        cv::Mat offsetBytes(offsetSide, offsetSide, CV_8U);
        for (int y = 0; y < shadowSide; ++y) {
            const auto *field = paper.ptr<float>(y);
            auto *bytes = paperBytes.ptr<uchar>(y);
            for (int x = 0; x < shadowSide; ++x) {
                bytes[x] = uchar(std::clamp(field[x] * 255.f, 0.f, 255.f));
            }
        }
        adjustment.convertTo(offsetBytes, CV_8U, 127.5, 127.5);
        cv::GaussianBlur(offsetBytes, offsetBytes, {5, 5}, 3, 3);
        const HdLinearField paperField(paperBytes, bgr.size()), offsetField(offsetBytes, bgr.size());
        cv::parallel_for_(cv::Range(0, bgr.rows), [&](const cv::Range &rows) {
            for (int y = rows.start; y < rows.end; ++y) {
                const auto *source = bgr.ptr<cv::Vec3b>(y);
                const auto paperRow = paperField.row<uchar>(y);
                const auto offsetRow = offsetField.row<uchar>(y);
                auto *target = result.ptr<cv::Vec3b>(y);
                for (int x = 0; x < bgr.cols; ++x) {
                    const int background = std::max(1, paperRow.at(x));
                    const int corrected = (255 * source[x][channel] + background / 2) / background;
                    target[x][channel] = cv::saturate_cast<uchar>(corrected + 2 * offsetRow.at(x) - 255);
                }
            }
        });
    }
    return result;
}

inline cv::Mat applyHdAffine(const cv::Mat &bgr, std::vector<float> &shadow, int shadowSide,
                             std::vector<float> &coarse, int coarseSide,
                             std::vector<float> &fine, int fineSide) {
    cv::Mat result(bgr.size(), CV_8UC3);
    for (int channel = 0; channel < 3; ++channel) {
        const int rgbChannel = 2 - channel;
        cv::Mat paper(shadowSide, shadowSide, CV_32F,
                      shadow.data() + rgbChannel * shadowSide * shadowSide);
        cv::Mat coarseOffset(coarseSide, coarseSide, CV_32F,
                             coarse.data() + rgbChannel * coarseSide * coarseSide);
        cv::Mat fineOffset(fineSide, fineSide, CV_32F,
                           fine.data() + rgbChannel * fineSide * fineSide);
        cv::Mat gain, coarseFixed, fineFixed;
        cv::max(paper, 1.f / 255.f, paper);
        cv::divide(256., paper, gain);
        gain.convertTo(gain, CV_16U);
        coarseOffset.convertTo(coarseFixed, CV_16S, 256);
        fineOffset.convertTo(fineFixed, CV_16S, 256);
        const HdLinearField gainField(gain, bgr.size()), coarseField(coarseFixed, bgr.size()),
                            fineField(fineFixed, bgr.size());
        cv::parallel_for_(cv::Range(0, bgr.rows), [&](const cv::Range &rows) {
            for (int y = rows.start; y < rows.end; ++y) {
                const auto *source = bgr.ptr<cv::Vec3b>(y);
                const auto gainRow = gainField.row<ushort>(y);
                const auto coarseRow = coarseField.row<short>(y);
                const auto fineRow = fineField.row<short>(y);
                auto *target = result.ptr<cv::Vec3b>(y);
                for (int x = 0; x < bgr.cols; ++x) {
                    const int corrected = (source[x][channel] * gainRow.at(x)) >> 8;
                    target[x][channel] = cv::saturate_cast<uchar>(corrected + coarseRow.at(x) + fineRow.at(x));
                }
            }
        });
    }
    return result;
}

inline cv::Mat androidSmartHD(const cv::Mat &bgr, ReferenceScanModel &shadowModel,
                              ReferenceScanModel &scanOld, ReferenceScanModel &scanNew,
                              ReferenceScanModel &scanAlt, ReferenceScanModel &radar) {
    if (bgr.empty() || bgr.type() != CV_8UC3) return {};
    constexpr int shadowSide = 480;
    constexpr int coarseSide = 360;
    constexpr int fineSide = 1024;
    const int category = classifyHdDocument(radar, bgr);
    if (category < 0) return {};

    cv::Mat sample;
    cv::resize(bgr, sample, {shadowSide, shadowSide}, 0, 0, cv::INTER_LINEAR);
    cv::cvtColor(sample, sample, cv::COLOR_BGR2RGB);
    auto shadow = shadowModel.run(sample, "output", {1, 3, shadowSide, shadowSide}, 1.f / 255.f);
    if (shadow.empty()) return {};
    cv::Mat normalized = hdInputFromShadow(bgr, shadow, shadowSide, fineSide);

    // Android's deshadowFileNew uses the two-stage affine path for radar classes 0 and 5.
    if (category != 0 && category != 5) {
        auto offsets = scanAlt.run(normalized, "output", {1, 3, fineSide, fineSide}, 1.f);
        if (offsets.empty()) return {};
        auto result = applyHdOffsetBlend(bgr, shadow, shadowSide, offsets, fineSide);
        boostScanSaturation(result);
        sharpenScanEdges(result);
        return result;
    }

    cv::resize(normalized, sample, {coarseSide, coarseSide}, 0, 0, cv::INTER_LINEAR);
    auto coarse = scanNew.run(sample, "output", {1, 3, coarseSide, coarseSide}, 1.f);
    if (coarse.empty()) return {};
    for (int channel = 0; channel < 3; ++channel) {
        const cv::Mat plane(coarseSide, coarseSide, CV_32F,
                            coarse.data() + channel * coarseSide * coarseSide);
        cv::Mat scaled, inputChannel;
        cv::resize(plane, scaled, normalized.size(), 0, 0, cv::INTER_LINEAR);
        cv::extractChannel(normalized, inputChannel, channel);
        cv::add(inputChannel, scaled, inputChannel);
        cv::insertChannel(inputChannel, normalized, channel);
    }
    auto fine = scanOld.run(normalized, "output", {1, 3, fineSide, fineSide}, 1.f);
    if (fine.empty()) return {};
    auto result = applyHdAffine(bgr, shadow, shadowSide, coarse, coarseSide, fine, fineSide);
    boostScanSaturation(result);
    sharpenScanEdges(result, 2);
    return result;
}

} // namespace freescan
#endif
