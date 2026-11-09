/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once

#if FREESCAN_USE_MNN
#include <MNN/Interpreter.hpp>
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

namespace freescan {
inline bool isFloatScanTensor(const MNN::Tensor *tensor) {
    if (!tensor) return false;
    const auto type = tensor->getType();
    return type.code == halide_type_float && type.bits == 32 && type.lanes == 1;
}

class ReferenceScanModel {
public:
    explicit ReferenceScanModel(const char *path) {
        if (!path) return;
        interpreter_.reset(MNN::Interpreter::createFromFile(path));
        if (!interpreter_) return;
        MNN::ScheduleConfig config;
        config.type = MNN_FORWARD_CPU;
        config.numThread = 2;
        session_ = interpreter_->createSession(config);
    }

    ~ReferenceScanModel() {
        if (session_) interpreter_->releaseSession(session_);
    }

    std::vector<float> run(const cv::Mat &bgr, const char *outputName,
                           const std::vector<int> &outputShape, float scale, float bias = 0) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!session_ || bgr.type() != CV_8UC3) return {};
        auto *input = interpreter_->getSessionInput(session_, nullptr);
        auto *output = interpreter_->getSessionOutput(session_, outputName);
        if (!isFloatScanTensor(input) || !isFloatScanTensor(output)) return {};

        MNN::Tensor hostInput(input, MNN::Tensor::CAFFE);
        if (hostInput.shape() != std::vector<int>{1, 3, bgr.rows, bgr.cols}) return {};
        auto *pixels = hostInput.host<float>();
        if (!pixels) return {};
        const size_t count = bgr.total();
        for (int y = 0; y < bgr.rows; ++y) {
            const auto *row = bgr.ptr<cv::Vec3b>(y);
            for (int x = 0; x < bgr.cols; ++x) {
                const size_t i = size_t(y) * bgr.cols + x;
                for (int c = 0; c < 3; ++c) pixels[c * count + i] = row[x][c] * scale + bias;
            }
        }

        if (!input->copyFromHostTensor(&hostInput) || int(interpreter_->runSession(session_)) != 0)
            return {};
        MNN::Tensor hostOutput(output, MNN::Tensor::CAFFE);
        if (hostOutput.shape() != outputShape || !output->copyToHostTensor(&hostOutput)) return {};
        const auto *values = hostOutput.host<float>();
        if (!values || !std::all_of(values, values + hostOutput.elementSize(),
                                    [](float value) { return std::isfinite(value); })) return {};
        return std::vector<float>(values, values + hostOutput.elementSize());
    }

private:
    std::unique_ptr<MNN::Interpreter> interpreter_;
    MNN::Session *session_ = nullptr;
    std::mutex mutex_;
};

inline cv::Mat referenceShadowCorrection(ReferenceScanModel &model, const cv::Mat &bgr) {
    constexpr int side = 480;
    cv::Mat sample;
    cv::resize(bgr, sample, {side, side}, 0, 0, cv::INTER_LINEAR);
    cv::cvtColor(sample, sample, cv::COLOR_BGR2RGB);
    const auto estimate = model.run(sample, nullptr, {1, 3, side, side}, 1.0f / 255.0f);
    if (estimate.empty()) return {};

    cv::Mat bgrField(side, side, CV_8UC3);
    for (int y = 0; y < side; ++y) {
        auto *row = bgrField.ptr<cv::Vec3b>(y);
        for (int x = 0; x < side; ++x) {
            for (int c = 0; c < 3; ++c) {
                row[x][c] = static_cast<uchar>(std::clamp(estimate[(2 - c) * side * side + y * side + x] * 255.f,
                                                         0.f, 255.f));
            }
        }
    }
    cv::resize(bgrField, bgrField, bgr.size(), 0, 0, cv::INTER_LINEAR);
    cv::Mat corrected(bgr.size(), CV_8UC3);
    for (int y = 0; y < bgr.rows; ++y) {
        const auto *src = bgr.ptr<cv::Vec3b>(y), *field = bgrField.ptr<cv::Vec3b>(y);
        auto *dst = corrected.ptr<cv::Vec3b>(y);
        for (int x = 0; x < bgr.cols; ++x) {
            for (int c = 0; c < 3; ++c) {
                const int background = std::max(1, int(field[x][c]));
                dst[x][c] = uchar(std::min(255, (int(src[x][c]) * 255 + background / 2) / background));
            }
        }
    }
    return corrected;
}
} // namespace freescan
#endif
