/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../CamScannerFilterCore.hpp"
#include <iostream>
#include <stdexcept>

using freescan::ScanFilterMode;

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

static cv::Mat filter(const cv::Mat &input, ScanFilterMode mode, bool shadows = false) {
    return freescan::camScannerFilter(input, static_cast<int>(mode), shadows);
}

int main() {
    try {
        cv::Mat page(192, 256, CV_8UC3);
        for (int x = 0; x < page.cols; ++x) page.col(x).setTo(cv::Scalar::all(135 + x * 105 / 255));
        cv::putText(page, "Text 123", cv::Point(28, 117), cv::FONT_HERSHEY_SIMPLEX, 1.5, cv::Scalar::all(12), 3);
        auto original = page.clone();
        require(cv::norm(filter(page, ScanFilterMode::Original, true), original, cv::NORM_INF) == 0,
                "Original must bypass optional shadows");
        for (auto mode : {ScanFilterMode::Automatic, ScanFilterMode::ShadowRemoval, ScanFilterMode::Brighten,
                          ScanFilterMode::Sharpen, ScanFilterMode::SmartHD, ScanFilterMode::Grayscale,
                          ScanFilterMode::BlackAndWhite, ScanFilterMode::InkSaver, ScanFilterMode::Invert}) {
            auto image = filter(page, mode, true);
            require(image.size() == page.size() && image.depth() == CV_8U, "Scan filter changed the raster geometry");
        }
        require(cv::norm(page, original, cv::NORM_INF) == 0, "Scan filter modified its input image");

        auto shadowless = filter(page, ScanFilterMode::ShadowRemoval);
        const cv::Rect paper(30, 25, 196, 30);
        cv::Scalar mean, before, after;
        cv::meanStdDev(page(paper), mean, before);
        cv::meanStdDev(shadowless(paper), mean, after);
        require(after[0] < before[0] * .65, "Dedicated shadow mode did not flatten the page illumination");
        auto blackAndWhite = filter(page, ScanFilterMode::BlackAndWhite);
        require(blackAndWhite.channels() == 1 && cv::mean(blackAndWhite(paper))[0] > 220,
                "Black and white did not normalize shaded paper");
        cv::Mat inkMask;
        cv::inRange(page, cv::Scalar::all(0), cv::Scalar::all(40), inkMask);
        require(cv::countNonZero(inkMask) > 100 && cv::mean(blackAndWhite, inkMask)[0] < 50,
                "Black and white lost the printed text");

        cv::Mat primaries(1, 3, CV_8UC3);
        primaries.at<cv::Vec3b>(0, 0) = {255, 0, 0};
        primaries.at<cv::Vec3b>(0, 1) = {0, 255, 0};
        primaries.at<cv::Vec3b>(0, 2) = {0, 0, 255};
        auto gray = filter(primaries, ScanFilterMode::Grayscale);
        auto negative = filter(primaries, ScanFilterMode::Invert);
        require(gray.at<uchar>(0, 0) == 29 && gray.at<uchar>(0, 1) == 149 && gray.at<uchar>(0, 2) == 76,
                "Grayscale weights differ from the reconstructed operator");
        require(negative.at<uchar>(0, 0) == 226 && negative.at<uchar>(0, 1) == 106 && negative.at<uchar>(0, 2) == 180,
                "Negative weights differ from the reconstructed operator");

        cv::Mat ramp(1, 256, CV_8UC3);
        for (int i = 0; i < 256; ++i) ramp.at<cv::Vec3b>(0, i) = cv::Vec3b(i, i, i);
        auto inkSaver = filter(ramp, ScanFilterMode::InkSaver);
        require(inkSaver.at<uchar>(0, 150) == 0 && inkSaver.at<uchar>(0, 240) == 255 &&
                inkSaver.at<uchar>(0, 195) > 0 && inkSaver.at<uchar>(0, 195) < 195,
                "Ink-saving curve did not use the reference thresholds");
        auto bright = filter(ramp, ScanFilterMode::Brighten);
        require(bright.at<cv::Vec3b>(0, 32) == cv::Vec3b(0, 0, 0) &&
                bright.at<cv::Vec3b>(0, 235) == cv::Vec3b(255, 255, 255), "Brightness cutoffs are incorrect");

        cv::Mat edge(32, 48, CV_8UC3, cv::Scalar::all(120));
        edge.colRange(24, 48).setTo(cv::Scalar::all(180));
        auto sharpened = filter(edge, ScanFilterMode::Sharpen);
        require(sharpened.at<cv::Vec3b>(16, 23)[0] < 120 && sharpened.at<cv::Vec3b>(16, 24)[0] > 180,
                "Sharpening did not enhance the step contrast");
        auto hd = filter(page, ScanFilterMode::SmartHD);
        require(cv::norm(hd, filter(page, ScanFilterMode::Sharpen, true), cv::NORM_INF) > 0,
                "Local HD has the same output as ordinary sharpening");

        std::cout << "CamScanner filter regression checks passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
