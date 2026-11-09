/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
// Match app/platform/Inpainting.kt. Use the same OpenCV version, BGR working
// space, masks, crop padding and Telea radii on both platforms.
#import <opencv2/opencv.hpp>
#import <opencv2/imgcodecs/ios.h>
#import "FreeScanOpenCV.h"
#include "QRRepairCore.hpp"
#include "DeskewCore.hpp"
#include "DocumentGeometryCore.hpp"
#include "DocumentEdgeCore.hpp"
#include "CamScannerEdgeCore.hpp"
#include "CamScannerDewarpCore.hpp"
#include "CamScannerFilterCore.hpp"
#include "ReferenceScanModels.hpp"
#include <algorithm>
#include <cmath>
#include <mutex>

@implementation FreeScanDeskewResult
- (instancetype)initWithImage:(UIImage *)image angle:(double)angle {
    if ((self=[super init])) { _image=image; _angle=angle; }
    return self;
}
@end

@implementation FreeScanQRCodeCandidate
- (instancetype)initWithCorners:(NSArray<NSValue *> *)corners verified:(BOOL)verified {
    if ((self = [super init])) {
        _corners = [corners copy]; _verified = verified;
        CGFloat x0=1,y0=1,x1=0,y1=0;
        for (NSValue *value in corners) {
            CGPoint p=value.CGPointValue;
            x0=std::min(x0,p.x);y0=std::min(y0,p.y);x1=std::max(x1,p.x);y1=std::max(y1,p.y);
        }
        _bounds=CGRectMake(x0,y0,x1-x0,y1-y0);
    }
    return self;
}
@end

struct QRRegion {
    cv::Rect2f bounds;
    bool verified;
    std::vector<cv::Point2f> corners;
};

static float overlap(const cv::Rect2f &a, const cv::Rect2f &b) {
    return (a & b).area() / std::max(0.000001f, a.area());
}

static bool canDecode(cv::GraphicalCodeDetector &detector, const cv::Mat &input, const cv::Mat &quad) {
    try {
        // C++ decode returns raw bytes in std::string. Android decodeBytes is
        // generated from this same function, so non-UTF8 payloads also verify.
        return !detector.decode(input, quad).empty();
    } catch (const cv::Exception &) { return false; }
}

static std::vector<cv::Mat> detectQuads(cv::GraphicalCodeDetector &detector, const cv::Mat &input) {
    std::vector<cv::Mat> result;
    try {
        cv::Mat points;
        if (!detector.detectMulti(input, points) && !detector.detect(input, points)) return result;
        if (points.empty()) return result;
        cv::Mat flattened = points.reshape(2, 1);
        if (flattened.depth() != CV_32F) flattened.convertTo(flattened, CV_32F);
        for (int start = 0; start + 4 <= flattened.cols; start += 4) {
            cv::Mat quad = flattened.colRange(start, start + 4).clone();
            std::vector<cv::Point2f> corners;
            bool valid = true;
            for (int i = 0; i < 4; ++i) {
                auto p = quad.at<cv::Point2f>(0, i);
                if (!std::isfinite(p.x) || !std::isfinite(p.y) || p.x < -2 || p.y < -2 ||
                    p.x > input.cols + 2 || p.y > input.rows + 2) { valid = false; break; }
                corners.push_back(p);
            }
            if (!valid) continue;
            float minX = corners[0].x, maxX = minX, minY = corners[0].y, maxY = minY;
            for (const auto &p : corners) {
                minX = std::min(minX, p.x); maxX = std::max(maxX, p.x);
                minY = std::min(minY, p.y); maxY = std::max(maxY, p.y);
            }
            float w = maxX - minX, h = maxY - minY;
            if (std::abs(cv::contourArea(corners)) < 64 || std::min(w, h) < 8 ||
                std::max(w, h) > std::min(w, h) * 5) continue;
            result.push_back(quad);
        }
    } catch (const cv::Exception &error) {
        NSLog(@"FreeScan QR detection pass failed: %s", error.what());
    }
    return result;
}

static void detectQRView(const cv::Mat &rgba, const cv::Rect &roi,
                         std::vector<cv::GraphicalCodeDetector> &detectors,
                         std::vector<QRRegion> &regions) {
    if (std::min(roi.width, roi.height) < 24) return;
    cv::Mat view, gray, enhanced;
    double scale = std::min(1.0, 1600.0 / std::max(roi.width, roi.height));
    cv::resize(rgba(roi), view, cv::Size(std::max(1, int(std::round(roi.width * scale))),
                                      std::max(1, int(std::round(roi.height * scale)))));
    cv::cvtColor(view, gray, cv::COLOR_RGBA2GRAY);
    view.release();
    auto collect = [&](const cv::Mat &input) {
        bool anyVerified = false;
        for (auto &detector : detectors) {
            for (const auto &quad : detectQuads(detector, input)) {
                bool readable = canDecode(detector, input, quad) ||
                    (input.data != gray.data && canDecode(detectors[0], gray, quad));
                float minX = 1, minY = 1, maxX = 0, maxY = 0;
                std::vector<cv::Point2f> normalizedCorners;
                for (int i = 0; i < 4; ++i) {
                    cv::Point2f p = quad.at<cv::Point2f>(0, i);
                    float x = std::clamp(float((roi.x + p.x * roi.width / gray.cols) / rgba.cols), 0.f, 1.f);
                    float y = std::clamp(float((roi.y + p.y * roi.height / gray.rows) / rgba.rows), 0.f, 1.f);
                    minX = std::min(minX, x); maxX = std::max(maxX, x);
                    minY = std::min(minY, y); maxY = std::max(maxY, y);
                    normalizedCorners.emplace_back(x,y);
                }
                cv::Rect2f bounds(minX, minY, maxX - minX, maxY - minY);
                bool safe = std::max(bounds.width * rgba.cols, bounds.height * rgba.rows) <=
                    std::min(rgba.cols, rgba.rows) * 0.30f;
                QRRegion candidate{bounds, readable && safe, normalizedCorners};
                anyVerified |= candidate.verified;
                auto existing = std::find_if(regions.begin(), regions.end(), [&](const QRRegion &r) {
                    return overlap(r.bounds, bounds) > 0.65f && overlap(bounds, r.bounds) > 0.65f;
                });
                if (existing == regions.end()) regions.push_back(candidate);
                else if (!existing->verified && candidate.verified) *existing = candidate;
            }
        }
        return anyVerified;
    };
    if (!collect(gray)) {
        cv::createCLAHE(2.0, cv::Size(8, 8))->apply(gray, enhanced);
        if (!collect(enhanced)) {
            cv::bitwise_not(gray, enhanced);
            if (!collect(enhanced)) {
                cv::adaptiveThreshold(gray, enhanced, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                                      cv::THRESH_BINARY, 31, 5);
                collect(enhanced);
            }
        }
    }
}

static std::vector<int> tileStarts(int length) {
    if (length <= 1280) return {0};
    std::vector<int> starts;
    for (int start = 0; start <= length - 1280; start += 1024) starts.push_back(start);
    if (starts.back() != length - 1280) starts.push_back(length - 1280);
    return starts;
}

static cv::Mat rgbaImage(CGImageRef source) {
    if (!source) return {};

    // The input profile may be wide-gamut, extended-range or monochrome.
    // OpenCV always expects the same 8-bit RGBA layout here.
    cv::Mat rgba(int(CGImageGetHeight(source)), int(CGImageGetWidth(source)), CV_8UC4, cv::Scalar(0, 0, 0, 0));
    CGColorSpaceRef colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!colorSpace) return {};
    CGContextRef context = CGBitmapContextCreate(rgba.data, rgba.cols, rgba.rows, 8, rgba.step[0],
        colorSpace, kCGBitmapByteOrder32Big | kCGImageAlphaPremultipliedLast);
    CGColorSpaceRelease(colorSpace);
    if (!context) return {};

    CGContextDrawImage(context, CGRectMake(0, 0, rgba.cols, rgba.rows), source);
    CGContextRelease(context);
    return rgba;
}

static cv::Mat rgbaImage(UIImage *image) {
    return rgbaImage(image.CGImage);
}

#if FREESCAN_USE_MNN
static NSString *referenceModelPath(NSString *name) {
    return [NSBundle.mainBundle pathForResource:name ofType:@"mnn" inDirectory:@"ReferenceScan"];
}

static UIImage *scanImage(const cv::Mat &pixels, UIImage *source) {
    UIImage *result = MatToUIImage(pixels);
    return result.CGImage ? [UIImage imageWithCGImage:result.CGImage scale:source.scale
                                         orientation:UIImageOrientationUp] : nil;
}

static std::optional<freescan::EdgeQuad> referenceDocumentCorners(const cv::Mat &rgba) {
    static freescan::ReferenceScanModel model(referenceModelPath(@"border").fileSystemRepresentation);
    constexpr int side = 240;
    cv::Mat sample;
    cv::resize(rgba, sample, {side, side}, 0, 0, cv::INTER_LINEAR);
    cv::cvtColor(sample, sample, cv::COLOR_RGBA2BGR);
    auto prediction = model.run(sample, "output", {1, 2, side, side}, 1.0f / 255.0f);
    if (prediction.empty()) return std::nullopt;
    return freescan::fitCamScannerDocumentEdges(cv::Mat(side, side, CV_32FC1, prediction.data()));
}
#endif

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
+ (NSArray<NSValue *> *)documentCorners:(CGImageRef)image {
    try {
        auto rgba = rgbaImage(image);
        if (rgba.empty()) return nil;
        std::optional<freescan::EdgeQuad> quad;
#if FREESCAN_USE_MNN
        quad = referenceDocumentCorners(rgba);
#endif
        if (!quad) {
            static cv::dnn::Net fallback = [] {
                NSString *path = [NSBundle.mainBundle pathForResource:@"ScanEdgeModel" ofType:@"onnx"];
                return path ? cv::dnn::readNetFromONNX(path.fileSystemRepresentation) : cv::dnn::Net();
            }();
            static std::mutex fallbackMutex;
            cv::Mat rgb;
            cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
            std::lock_guard<std::mutex> guard(fallbackMutex);
            quad = freescan::detectDocumentEdges(fallback, rgb);
        }
        if (!quad) return nil;
        NSMutableArray<NSValue *> *points = [NSMutableArray arrayWithCapacity:4];
        for (const auto &point : *quad)
            [points addObject:[NSValue valueWithCGPoint:CGPointMake(point.x, point.y)]];
        return points;
    } catch (const std::exception &error) {
        NSLog(@"FreeScan edge detection failed: %s", error.what());
        return nil;
    }
}
+ (UIImage *)filteredImage:(UIImage *)image mode:(NSInteger)mode shadows:(BOOL)shadows {
    try {
        cv::Mat rgba=rgbaImage(image),bgr,result;
        if (rgba.empty()) return nil;
        cv::cvtColor(rgba,bgr,cv::COLOR_RGBA2BGR);rgba.release();
        cv::Mat filtered;
#if FREESCAN_USE_MNN
        using freescan::ScanFilterMode;
        const auto filter = static_cast<ScanFilterMode>(mode);
        if (filter == ScanFilterMode::ShadowRemoval || filter == ScanFilterMode::SmartHD) {
            static freescan::ReferenceScanModel model(referenceModelPath(@"shadow").fileSystemRepresentation);
            filtered = freescan::referenceShadowCorrection(model, bgr);
            if (!filtered.empty()) {
                if (filter == ScanFilterMode::SmartHD) filtered = freescan::scanSmartHD(filtered);
                else freescan::sharpenScanEdges(filtered);
            }
        }
#endif
        if (filtered.empty()) filtered = freescan::camScannerFilter(bgr,int(mode),shadows);
        if (filtered.empty()) return nil;
        cv::cvtColor(filtered,result,filtered.channels()==1?cv::COLOR_GRAY2RGBA:cv::COLOR_BGR2RGBA);
        return MatToUIImage(result);
    } catch (const std::exception &error) {
        NSLog(@"FreeScan document filter failed: %s",error.what());return nil;
    }
}
+ (FreeScanDeskewResult *)deskewText:(UIImage *)image {
    try {
        cv::Mat rgba=rgbaImage(image);
        if (rgba.empty()) return nil;
        double angle=0;
        cv::Mat output=freescan::deskewText(rgba,angle);
        return [[FreeScanDeskewResult alloc] initWithImage:angle==0?image:MatToUIImage(output) angle:angle];
    } catch (const std::exception &error) {
        NSLog(@"FreeScan text deskew failed: %s",error.what());return nil;
    }
}
+ (UIImage *)rectifyDocument:(UIImage *)image corners:(NSArray<NSValue *> *)corners {
    if (corners.count != 4) return nil;
    try {
        std::array<cv::Point2f, 4> points;
        for (NSUInteger i = 0; i < points.size(); ++i) {
            CGPoint p = corners[i].CGPointValue;
            points[i] = cv::Point2f(float(p.x), float(p.y));
        }
        auto rectified = freescan::rectifyDocument(rgbaImage(image), points);
        if (rectified.empty()) return nil;
        auto result = MatToUIImage(rectified);
        return result.CGImage ? [UIImage imageWithCGImage:result.CGImage scale:image.scale orientation:UIImageOrientationUp] : nil;
    } catch (const std::exception &error) {
        NSLog(@"FreeScan document rectification failed: %s", error.what());
        return nil;
    }
}
+ (BOOL)hasReferenceDewarpModel {
#if FREESCAN_USE_MNN
    return referenceModelPath(@"dewarp-b") != nil;
#else
    return NO;
#endif
}
+ (UIImage *)orientDocument:(UIImage *)image {
#if FREESCAN_USE_MNN
    try {
        constexpr int side = 512;
        static freescan::ReferenceScanModel model(referenceModelPath(@"direction").fileSystemRepresentation);
        auto rgba = rgbaImage(image);
        if (rgba.empty()) return nil;
        cv::Mat sample;
        cv::resize(rgba, sample, {side, side}, 0, 0, cv::INTER_LINEAR);
        cv::cvtColor(sample, sample, cv::COLOR_RGBA2BGR);
        const auto scores = model.run(sample, "175", {1, 5}, 1.0f / 128.0f, -1.0f);
        if (scores.empty()) return nil;
        const auto best = std::max_element(scores.begin(), scores.end());
        const auto id = best - scores.begin();
        // The IPA reads a configuration threshold. This local threshold avoids low-confidence turns.
        if (*best < .75f || id < 1 || id > 3) return image;
        const int rotations[]{cv::ROTATE_90_COUNTERCLOCKWISE, cv::ROTATE_180, cv::ROTATE_90_CLOCKWISE};
        cv::Mat rotated;
        cv::rotate(rgba, rotated, rotations[id - 1]);
        return scanImage(rotated, image);
    } catch (const std::exception &error) {
        NSLog(@"FreeScan page direction failed: %s", error.what());
    }
#endif
    return nil;
}
+ (UIImage *)flattenDocument:(UIImage *)image {
#if FREESCAN_USE_MNN
    try {
        constexpr int side = 240;
        static freescan::ReferenceScanModel model(referenceModelPath(@"dewarp-b").fileSystemRepresentation);
        auto rgba = rgbaImage(image);
        if (rgba.empty()) return nil;
        cv::Mat bgr;
        cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
        const auto sample = freescan::dewarpInput(bgr);
        auto prediction = model.run(sample, "output", {1, 2, side, side}, 1.0f / 255.0f);
        if (prediction.empty()) return nil;
        cv::Mat flow;
        cv::merge(std::vector<cv::Mat>{cv::Mat(side, side, CV_32FC1, prediction.data()),
                                      cv::Mat(side, side, CV_32FC1, prediction.data() + side * side)}, flow);
        auto flattened = freescan::dewarpDocument(rgba, flow);
        return flattened.empty() ? nil : scanImage(flattened, image);
    } catch (const std::exception &error) {
        NSLog(@"FreeScan curved-page correction failed: %s", error.what());
    }
#endif
    return nil;
}
+ (NSArray<FreeScanQRCodeCandidate *> *)qrCandidates:(UIImage *)image {
    NSMutableArray<FreeScanQRCodeCandidate *> *output = [NSMutableArray array];
    try {
        cv::Mat rgba = rgbaImage(image);
        if (rgba.empty()) return output;
        std::vector<cv::GraphicalCodeDetector> detectors{cv::QRCodeDetector(), cv::QRCodeDetectorAruco()};
        std::vector<QRRegion> regions;
        auto detect = [&](cv::Rect roi) {
            try { detectQRView(rgba, roi, detectors, regions); }
            catch (const cv::Exception &error) { NSLog(@"FreeScan QR view failed: %s", error.what()); }
        };
        detect(cv::Rect(0, 0, rgba.cols, rgba.rows));
        if (std::max(rgba.cols, rgba.rows) > 1280) {
            for (int top : tileStarts(rgba.rows)) for (int left : tileStarts(rgba.cols)) {
                detect(cv::Rect(left, top, std::min(1280, rgba.cols - left), std::min(1280, rgba.rows - top)));
            }
        }
        for (const auto &r : regions) {
            NSMutableArray<NSValue *> *corners=[NSMutableArray array];
            for (auto p:r.corners) [corners addObject:[NSValue valueWithCGPoint:CGPointMake(p.x,p.y)]];
            [output addObject:[[FreeScanQRCodeCandidate alloc]
                initWithCorners:corners
                verified:r.verified]];
        }
    } catch (const std::exception &error) {
        NSLog(@"FreeScan QR detection failed: %s", error.what());
    }
    return output;
}
+ (UIImage *)repairRegions:(UIImage *)image corners:(NSArray<NSArray<NSValue *> *> *)corners
                  verified:(NSArray<NSNumber *> *)verified {
    try {
        if (corners.count!=verified.count) return nil;
        cv::Mat rgba=rgbaImage(image);
        if (rgba.empty()) return nil;
        std::vector<freescan::RepairRegion> regions;
        for (NSUInteger i=0;i<corners.count;++i) {
            freescan::RepairRegion region{{},verified[i].boolValue};
            for (NSValue *value in corners[i]) {
                CGPoint p=value.CGPointValue;region.corners.emplace_back(p.x,p.y);
            }
            regions.push_back(region);
        }
        freescan::repairRegions(rgba,regions);
        return MatToUIImage(rgba);
    } catch (const std::exception &error) {
        NSLog(@"FreeScan polygon repair failed: %s",error.what());
        return nil;
    }
}
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
