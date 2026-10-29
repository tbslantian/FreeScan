/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
// Match app/platform/Inpainting.kt. Use the same OpenCV version, BGR working
// space, masks, crop padding and Telea radii on both platforms.
#import <opencv2/opencv.hpp>
#import <opencv2/imgcodecs/ios.h>
#import "FreeScanOpenCV.h"
#include <algorithm>
#include <cmath>

static cv::Mat rgbaImage(UIImage *image) {
    cv::Mat rgba;
    UIImageToMat(image, rgba, true);
    return rgba;
}

static UIImage *repairedImage(cv::Mat &rgba, const cv::Rect &roi,
                              const cv::Mat &mask, double radius) {
    cv::Mat bgr, result, resultRGBA;
    cv::cvtColor(rgba(roi), bgr, cv::COLOR_RGBA2BGR);
    cv::inpaint(bgr, mask, result, radius, cv::INPAINT_TELEA);
    cv::cvtColor(result, resultRGBA, cv::COLOR_BGR2RGBA);
    resultRGBA.copyTo(rgba(roi));
    return MatToUIImage(rgba);
}

@implementation FreeScanOpenCV
+ (UIImage *)removeColoredInk:(UIImage *)image {
    try {
        cv::Mat rgba = rgbaImage(image), rgb, hsv, mask;
        if (rgba.empty()) return nil;
        cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
        cv::cvtColor(rgb, hsv, cv::COLOR_RGB2HSV);
        cv::inRange(hsv, cv::Scalar(0, 30, 20), cv::Scalar(180, 255, 255), mask);
        int count = cv::countNonZero(mask);
        if (count == 0 || int64_t(count) * 10 > int64_t(rgba.cols) * rgba.rows) return image;
        cv::Rect bounds = cv::boundingRect(mask);
        int left = std::max(0, bounds.x - 32), top = std::max(0, bounds.y - 32);
        int right = std::min(rgba.cols, bounds.x + bounds.width + 32);
        int bottom = std::min(rgba.rows, bounds.y + bounds.height + 32);
        cv::Rect roi(left, top, right - left, bottom - top);
        cv::Mat patchMask = mask(roi).clone(), closed, expanded;
        rgb.release(); hsv.release(); mask.release();
        int maxDimension = std::max(rgba.cols, rgba.rows);
        int expansion = std::clamp(int(std::round(maxDimension / 1200.0)) * 2 + 1, 5, 9);
        double radius = std::clamp(maxDimension / 850.0, 4.0, 9.0);
        cv::morphologyEx(patchMask, closed, cv::MORPH_CLOSE,
                        cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3)));
        cv::dilate(closed, expanded,
                   cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(expansion, expansion)));
        return repairedImage(rgba, roi, expanded, radius);
    } catch (const std::exception &error) {
        NSLog(@"FreeScan OpenCV colored-ink repair failed: %s", error.what());
        return nil;
    }
}

+ (UIImage *)repairImage:(UIImage *)image strokes:(NSArray<NSArray<NSValue *> *> *)strokes
                  widths:(NSArray<NSNumber *> *)widths {
    try {
        cv::Mat rgba = rgbaImage(image);
        if (rgba.empty() || strokes.count != widths.count) return nil;
        double maxWidth = 1, minX = rgba.cols, minY = rgba.rows, maxX = -1, maxY = -1;
        for (NSUInteger index = 0; index < strokes.count; ++index) {
            maxWidth = std::max(maxWidth, widths[index].doubleValue);
            for (NSValue *value in strokes[index]) {
                CGPoint p = value.CGPointValue;
                minX = std::min(minX, double(p.x)); minY = std::min(minY, double(p.y));
                maxX = std::max(maxX, double(p.x)); maxY = std::max(maxY, double(p.y));
            }
        }
        if (maxX < 0 || maxY < 0) return image;
        double radius = std::clamp(maxWidth * 0.22, 3.0, 12.0);
        int padding = std::max(48, int(std::ceil(maxWidth + radius * 4)));
        int left = std::max(0, int(std::floor(minX)) - padding);
        int top = std::max(0, int(std::floor(minY)) - padding);
        int right = std::min(rgba.cols, int(std::ceil(maxX)) + padding + 1);
        int bottom = std::min(rgba.rows, int(std::ceil(maxY)) + padding + 1);
        if (right <= left || bottom <= top) return nil;
        cv::Rect roi(left, top, right - left, bottom - top);
        cv::Mat mask = cv::Mat::zeros(roi.height, roi.width, CV_8UC1), expanded;
        for (NSUInteger index = 0; index < strokes.count; ++index) {
            NSArray<NSValue *> *points = strokes[index];
            int thickness = std::clamp(int(std::round(widths[index].doubleValue)), 1, std::max(roi.width, roi.height));
            auto localPoint = [&](NSUInteger i) {
                CGPoint p = points[i].CGPointValue;
                return cv::Point(cvRound(p.x - left), cvRound(p.y - top));
            };
            if (points.count == 1) {
                cv::circle(mask, localPoint(0), std::max(1, thickness / 2), cv::Scalar(255), -1, cv::LINE_AA);
            }
            for (NSUInteger i = 1; i < points.count; ++i) {
                cv::line(mask, localPoint(i - 1), localPoint(i), cv::Scalar(255), thickness, cv::LINE_AA);
            }
        }
        cv::dilate(mask, expanded, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3)));
        return repairedImage(rgba, roi, expanded, radius);
    } catch (const std::exception &error) {
        NSLog(@"FreeScan OpenCV brush repair failed: %s", error.what());
        return nil;
    }
}
@end
