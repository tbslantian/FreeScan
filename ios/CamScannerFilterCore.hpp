/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include "DocumentFilterCore.hpp"

namespace freescan {

// Keep the bridge values aligned with ScanFilter.nativeMode in Models.swift.
enum class ScanFilterMode : int {
    Automatic = 0,
    Original = 1,
    ShadowRemoval = 2,
    Brighten = 3,
    Sharpen = 4,
    SmartHD = 5,
    Grayscale = 6,
    BlackAndWhite = 7,
    InkSaver = 8,
    Invert = 9,
    RemoveHandwriting = 10,
    RemoveWatermark = 11,
};

// These integer weights come from the grayscale and negative operators.
inline cv::Mat scanGrayscale(const cv::Mat &bgr, bool negative = false) {
    cv::Mat output(bgr.size(), CV_8UC1);
    cv::parallel_for_(cv::Range(0, bgr.rows), [&](const cv::Range &rows) {
        for (int y = rows.start; y < rows.end; ++y) {
            const auto *input = bgr.ptr<cv::Vec3b>(y);
            auto *gray = output.ptr<uchar>(y);
            for (int x = 0; x < bgr.cols; ++x) {
                const auto pixel = input[x];
                gray[x] = negative
                    ? 255 - ((15 * pixel[0] + 75 * pixel[1] + 38 * pixel[2]) >> 7)
                    : (7472 * pixel[0] + 38469 * pixel[1] + 19595 * pixel[2]) >> 16;
            }
        }
    });
    return output;
}

template <typename Tone>
inline cv::Mat applyScanTone(const cv::Mat &image, Tone tone) {
    cv::Mat lut(1, 256, CV_8UC1);
    auto *table = lut.ptr<uchar>();
    for (int value = 0; value < 256; ++value) {
        table[value] = cv::saturate_cast<uchar>(int(tone(value)));
    }
    cv::Mat output;
    cv::LUT(image, lut, output);
    return output;
}

inline cv::Mat scanBrighten(const cv::Mat &bgr) {
    int peak = int(histogramPercentile(scanGrayscale(bgr), .9996));
    int black = 4 * (peak / 30);
    int white = std::max(black + 1, peak - 4 * (peak / 50));
    return applyScanTone(bgr, [=](int value) {
        double tone = std::clamp(double(value - black) / (white - black), 0., 1.);
        return std::lround(255 * std::pow(tone, .8333333));
    });
}

inline cv::Mat scanBlackAndWhite(const cv::Mat &bgr) {
    auto gray = scanGrayscale(bgr);
    cv::Mat background, normalized;
    cv::Size half(std::max(1, gray.cols / 2), std::max(1, gray.rows / 2));
    cv::resize(gray, background, half, 0, 0, cv::INTER_AREA);
    auto kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(15, 15));
    cv::morphologyEx(background, background, cv::MORPH_CLOSE, kernel);
    int blur = std::min(gray.cols, gray.rows) >= 96 ? 7 : 3;
    cv::GaussianBlur(background, background, cv::Size(blur, blur), std::sqrt(2.0));
    cv::resize(background, background, gray.size(), 0, 0, cv::INTER_NEAREST);
    cv::subtract(background, cv::Scalar(6), background);
    cv::max(background, 1, background);
    cv::divide(gray, background, normalized, 243);
    return applyScanTone(normalized, [](int value) {
        if (value <= 158) return 0;
        if (value > 230) return 255;
        return int(253 * std::pow((value - 158) / 72., .5555555702727522) + 1.5);
    });
}

inline int scanHalf(int value) {
    return (value - (value < 0)) / 2;
}

// Reconstruct the reference's contrast-aware box unsharp mask.
inline void sharpenScanEdges(cv::Mat &image, int strength = 4) {
    int side = std::min(image.cols, image.rows);
    int window = strength * (side > 1600 ? 4 : side > 400 ? 2 : 1) - 1;
    if (window <= 1) return;
    cv::Mat blurred;
    cv::blur(image, blurred, cv::Size(window, window), cv::Point(-1, -1), cv::BORDER_REFLECT_101);
    cv::parallel_for_(cv::Range(0, image.rows), [&](const cv::Range &rows) {
        for (int y = rows.start; y < rows.end; ++y) {
            auto *pixels = image.ptr<uchar>(y);
            const auto *smooth = blurred.ptr<uchar>(y);
            for (int x = 0; x < image.cols * image.channels(); ++x) {
                int difference = pixels[x] - smooth[x];
                int adjustment = difference > -10 ? scanHalf(difference) :
                                 difference < -20 ? difference * 2 : difference;
                pixels[x] = cv::saturate_cast<uchar>(pixels[x] + adjustment);
            }
        }
    });
}

inline void boostScanSaturation(cv::Mat &output) {
    cv::parallel_for_(cv::Range(0, output.rows), [&](const cv::Range &rows) {
        for (int y = rows.start; y < rows.end; ++y) {
            auto *pixels = output.ptr<cv::Vec3b>(y);
            for (int x = 0; x < output.cols; ++x) {
                auto &pixel = pixels[x];
                int luminance = (15 * pixel[0] + 75 * pixel[1] + 38 * pixel[2]) >> 7;
                for (int channel = 0; channel < 3; ++channel) {
                    pixel[channel] = cv::saturate_cast<uchar>(scanHalf(3 * pixel[channel] - luminance));
                }
            }
        }
    });
}

inline cv::Mat scanColorSharpen(const cv::Mat &bgr) {
    auto output = bgr.clone();
    boostScanSaturation(output);
    sharpenScanEdges(output);
    return output;
}

// Local HD combines corrected illumination with bounded local contrast and sharpening.
inline cv::Mat scanSmartHD(const cv::Mat &bgr) {
    cv::Mat lab, localContrast, adjusted;
    std::vector<cv::Mat> channels;
    cv::cvtColor(bgr, lab, cv::COLOR_BGR2Lab);
    cv::split(lab, channels);
    cv::createCLAHE(2.0, cv::Size(8, 8))->apply(channels[0], localContrast);
    cv::addWeighted(channels[0], .65, localContrast, .35, 0, channels[0]);
    cv::merge(channels, lab);
    cv::cvtColor(lab, adjusted, cv::COLOR_Lab2BGR);
    return scanColorSharpen(adjusted);
}

inline cv::Mat camScannerFilter(const cv::Mat &bgr, int mode, bool shadows) {
    if (bgr.empty()) return {};
    CV_Assert(bgr.type() == CV_8UC3);
    auto filter = static_cast<ScanFilterMode>(mode);
    if (filter == ScanFilterMode::Original ||
        filter == ScanFilterMode::RemoveHandwriting ||
        filter == ScanFilterMode::RemoveWatermark) return bgr.clone();
    if (filter == ScanFilterMode::BlackAndWhite) return scanBlackAndWhite(bgr);
    if (filter == ScanFilterMode::Automatic) {
        filter = containsDocumentColor(bgr) ? ScanFilterMode::Sharpen : ScanFilterMode::Grayscale;
    }
    const auto working = shadows || filter == ScanFilterMode::ShadowRemoval || filter == ScanFilterMode::SmartHD
        ? flattenIllumination(bgr, true, true) : bgr;
    switch (filter) {
        case ScanFilterMode::Brighten: return scanBrighten(working);
        case ScanFilterMode::Sharpen: return scanColorSharpen(working);
        case ScanFilterMode::SmartHD: return scanSmartHD(working);
        case ScanFilterMode::Grayscale: return scanGrayscale(working);
        case ScanFilterMode::InkSaver:
            return applyScanTone(scanGrayscale(working), [](int value) {
                double tone = std::clamp((value - 150) / 90., 0., 1.);
                return std::lround(255 * std::pow(tone, .8333333002196431));
            });
        case ScanFilterMode::Invert: return scanGrayscale(working, true);
        default: return working.clone();
    }
}

} // namespace freescan
