/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN
@interface FreeScanOpenCV : NSObject
+ (nullable UIImage *)removeColoredInk:(UIImage *)image NS_SWIFT_NAME(removeColoredInk(_:));
+ (nullable UIImage *)repairImage:(UIImage *)image
                         strokes:(NSArray<NSArray<NSValue *> *> *)strokes
                          widths:(NSArray<NSNumber *> *)widths NS_SWIFT_NAME(repair(_:strokes:widths:));
@end
NS_ASSUME_NONNULL_END
