/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN
@interface FreeScanDeskewResult : NSObject
@property(nonatomic, readonly, strong) UIImage *image;
@property(nonatomic, readonly) double angle;
- (instancetype)initWithImage:(UIImage *)image angle:(double)angle;
@end

@interface FreeScanQRCodeCandidate : NSObject
@property(nonatomic, readonly) CGRect bounds;
@property(nonatomic, readonly) BOOL verified;
@property(nonatomic, readonly, copy) NSArray<NSValue *> *corners;
- (instancetype)initWithCorners:(NSArray<NSValue *> *)corners verified:(BOOL)verified;
@end

@interface FreeScanOpenCV : NSObject
+ (nullable FreeScanDeskewResult *)deskewText:(UIImage *)image NS_SWIFT_NAME(deskewText(_:));
+ (NSArray<FreeScanQRCodeCandidate *> *)qrCandidates:(UIImage *)image NS_SWIFT_NAME(qrCandidates(_:));
+ (nullable UIImage *)repairRegions:(UIImage *)image
                            corners:(NSArray<NSArray<NSValue *> *> *)corners
                           verified:(NSArray<NSNumber *> *)verified NS_SWIFT_NAME(repairRegions(_:corners:verified:));
+ (nullable UIImage *)removeColoredInk:(UIImage *)image NS_SWIFT_NAME(removeColoredInk(_:));
+ (nullable UIImage *)repairImage:(UIImage *)image
                         strokes:(NSArray<NSArray<NSValue *> *> *)strokes
                          widths:(NSArray<NSNumber *> *)widths NS_SWIFT_NAME(repair(_:strokes:widths:));
@end
NS_ASSUME_NONNULL_END
