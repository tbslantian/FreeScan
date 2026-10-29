/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "TesseractCore.hpp"
#import <opencv2/imgcodecs/ios.h>
#import "FreeScanTesseract.h"
@implementation FreeScanOCRWord
- (instancetype)initWithText:(NSString *)text bounds:(CGRect)bounds {
    if ((self=[super init])) {_text=[text copy];_bounds=bounds;}return self;
}
@end
@implementation FreeScanOCRResult
- (instancetype)initWithText:(NSString *)text words:(NSArray<FreeScanOCRWord *> *)words {
    if ((self=[super init])) {_text=[text copy];_words=[words copy];}return self;
}
@end
@implementation FreeScanTesseract
+ (FreeScanOCRResult *)recognize:(UIImage *)image dataPath:(NSString *)dataPath languages:(NSString *)languages {
    try {
        cv::Mat rgba;UIImageToMat(image,rgba,true);
        auto result=freescan::recognizeDocument(rgba,dataPath.UTF8String,languages.UTF8String);
        NSMutableArray<FreeScanOCRWord *> *words=[NSMutableArray array];
        for (const auto &word:result.words) {
            NSString *text=[[NSString alloc] initWithBytes:word.text.data() length:word.text.size() encoding:NSUTF8StringEncoding];
            if (!text) continue;
            CGRect bounds=CGRectMake(double(word.box.x)/rgba.cols,1-double(word.box.y+word.box.height)/rgba.rows,
                                      double(word.box.width)/rgba.cols,double(word.box.height)/rgba.rows);
            [words addObject:[[FreeScanOCRWord alloc] initWithText:text bounds:bounds]];
        }
        NSString *text=[[NSString alloc] initWithBytes:result.text.data() length:result.text.size() encoding:NSUTF8StringEncoding];
        return [[FreeScanOCRResult alloc] initWithText:text?:@"" words:words];
    } catch (const std::exception &error) {NSLog(@"FreeScan Tesseract failed: %s",error.what());return nil;}
}
@end
