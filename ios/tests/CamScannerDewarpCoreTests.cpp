/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../CamScannerDewarpCore.hpp"
#include <iostream>
#include <limits>
#include <stdexcept>

static void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

static float bend(float x) {
    return 12 * std::sin(float(CV_PI) * x);
}

static int lineY(const cv::Mat &image, int x) {
    for (int y = 10; y < image.rows - 10; ++y) {
        if (image.at<cv::Vec3b>(y, x)[0] < 128) return y;
    }
    throw std::runtime_error("The dewarp lost the curved line");
}

int main() {
    try {
        constexpr int side = freescan::dewarpModelSize;
        cv::Mat image(480, 640, CV_8UC3, cv::Scalar::all(255));
        cv::Mat field(side, side, CV_32FC2, cv::Scalar::all(0));
        expect(freescan::dewarpDocument(image, field).empty(), "Flat displacement should skip resampling");
        field.at<cv::Vec2f>(1, 1)[0] = std::numeric_limits<float>::quiet_NaN();
        expect(freescan::dewarpDocument(image, field).empty(), "A non-finite field must be rejected");
        expect(freescan::dewarpInput(image).size() == cv::Size(side, side), "Wrong model input shape");

        // A backward displacement should straighten a line bowed upward. Use an image larger
        // than the model grid and taller than one tile to catch scaling and row-offset mistakes.
        for (int x = 0; x < image.cols; ++x) {
            const int y = 210 - cvRound(bend(float(x) / (image.cols - 1)) * image.rows / side);
            cv::line(image, {x, y}, {x, y + 2}, cv::Scalar::all(0));
        }
        for (int y = 0; y < side; ++y) {
            auto *row = field.ptr<cv::Vec2f>(y);
            for (int x = 0; x < side; ++x) row[x] = {0, -bend(float(x) / (side - 1))};
        }
        const cv::Mat result = freescan::dewarpDocument(image, field);
        expect(result.size() == image.size(), "Dewarp changed the scan dimensions");
        const int before = std::abs(lineY(image, 0) - lineY(image, image.cols / 2));
        const int after = std::abs(lineY(result, 0) - lineY(result, result.cols / 2));
        expect(before >= 20 && after < before / 3, "Backward displacement did not flatten the page");
        std::cout << "All curved page checks passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
