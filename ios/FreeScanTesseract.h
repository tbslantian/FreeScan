/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN
@interface FreeScanOCRWord : NSObject
@property(nonatomic, readonly, copy) NSString *text;
/// Normalized bounds with a bottom-left origin, matching Vision/PDF conversion.
@property(nonatomic, readonly) CGRect bounds;
- (instancetype)initWithText:(NSString *)text bounds:(CGRect)bounds;
@end
@interface FreeScanOCRResult : NSObject
@property(nonatomic, readonly, copy) NSString *text;
@property(nonatomic, readonly, copy) NSArray<FreeScanOCRWord *> *words;
- (instancetype)initWithText:(NSString *)text words:(NSArray<FreeScanOCRWord *> *)words;
@end
@interface FreeScanTesseract : NSObject
+ (nullable FreeScanOCRResult *)recognize:(UIImage *)image dataPath:(NSString *)dataPath languages:(NSString *)languages
    NS_SWIFT_NAME(recognize(_:dataPath:languages:));
@end
NS_ASSUME_NONNULL_END
