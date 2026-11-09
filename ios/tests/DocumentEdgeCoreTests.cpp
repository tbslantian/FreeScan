/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../CamScannerEdgeCore.hpp"
#include <opencv2/imgcodecs.hpp>
#include <iostream>
#include <stdexcept>

static void expectQuad(const std::optional<freescan::EdgeQuad> &quad,
                       const std::array<cv::Point, 4> &expected, double tolerance,
                       float inputMax = 255.f) {
    if (!quad) throw std::runtime_error("The edge fitter did not find a document");
    for (size_t i = 0; i < quad->size(); ++i) {
        if (cv::norm(quad->at(i) * inputMax - cv::Point2f(expected[i])) > tolerance)
            throw std::runtime_error("The document quad is too far from the expected corner");
    }
}

static void checkCamScannerEdgeFitter() {
    const std::array<cv::Point, 4> corners{{{32, 26}, {199, 38}, {212, 206}, {26, 216}}};
    cv::Mat probabilities = cv::Mat::zeros(240, 240, CV_32FC1);
    for (size_t i = 0; i < corners.size(); ++i) {
        const cv::Point2f a = corners[i], b = corners[(i + 1) % corners.size()];
        // Short disconnected sections mimic edges broken by shadow or glare.
        cv::line(probabilities, a, a + (b - a) * .43f, cv::Scalar(.9), 3);
        cv::line(probabilities, a + (b - a) * .57f, b, cv::Scalar(.9), 3);
    }
    expectQuad(freescan::fitCamScannerDocumentEdges(probabilities), corners, 9, 239);
    probabilities.setTo(0);
    if (freescan::fitCamScannerDocumentEdges(probabilities))
        throw std::runtime_error("Blank edge probabilities became a document");
    cv::line(probabilities, {10, 80}, {230, 94}, cv::Scalar(.95), 4);
    if (freescan::fitCamScannerDocumentEdges(probabilities))
        throw std::runtime_error("A single edge became a document");
}

int main() {
    try {
        checkCamScannerEdgeFitter();
        auto model = cv::dnn::readNetFromONNX("ios/ScanEdgeModel.onnx");
        const std::array<cv::Point, 4> paper{{{32, 25}, {210, 38}, {225, 221}, {26, 231}}};
        cv::Mat synthetic(256, 256, CV_8UC3, cv::Scalar(103, 91, 68));
        cv::fillConvexPoly(synthetic, paper.data(), int(paper.size()), cv::Scalar(234, 238, 237));
        for (int y = 63; y < 210; y += 19) {
            cv::putText(synthetic, "Exam sample", {49, y}, cv::FONT_HERSHEY_SIMPLEX, .36,
                        cv::Scalar(52, 54, 55), 1, cv::LINE_AA);
        }
        expectQuad(freescan::detectDocumentEdges(model, synthetic), paper, 9);

        cv::Mat photo = cv::imread("app/src/debug/assets/uncropped/img01.jpg");
        if (photo.empty()) throw std::runtime_error("Missing scan photo fixture");
        cv::cvtColor(photo, photo, cv::COLOR_BGR2RGB);
        expectQuad(freescan::detectDocumentEdges(model, photo),
                   {{{50, 57}, {206, 50}, {225, 230}, {40, 231}}}, 12);

        cv::Mat blank(256, 256, CV_32FC1, cv::Scalar(-1));
        if (freescan::fitDocumentEdges(blank)) throw std::runtime_error("Blank edge logits became a document");
        std::cout << "All document edge regression checks passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
