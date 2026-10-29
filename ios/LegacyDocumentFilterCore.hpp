/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <stdexcept>

namespace freescan {
// Reconstructed from the selected Android library's EnhanceLight/EnhanceGray
// paths. See porting/LEGACY_FILTERS.md for artifact identity and addresses.
inline cv::Mat legacyBrightness(const cv::Mat &bgr) {
    if (bgr.empty() || bgr.type()!=CV_8UC3) throw std::invalid_argument("Legacy brightness requires an 8-bit BGR image");
    cv::Size sampleSize;
    if (bgr.rows < bgr.cols) sampleSize=cv::Size(int(float(bgr.cols)/bgr.rows*300.f),300);
    else sampleSize=cv::Size(300,int(float(bgr.rows)/bgr.cols*300.f));
    cv::Mat sample,gray,histogram;
    cv::resize(bgr,sample,sampleSize,0,0,cv::INTER_LINEAR);
    // The Android JNI bridge drops RGBA alpha without swapping RGB, then calls
    // BGR2GRAY on that RGB matrix. Retain that histogram weighting in BGR input.
    cv::cvtColor(sample,gray,cv::COLOR_RGB2GRAY);
    int bins=256,channel=0;float range[]={0,256};const float *ranges[]={range};
    cv::calcHist(&gray,1,&channel,cv::Mat(),histogram,1,&bins,ranges);
    for (int i=1;i<256;++i) histogram.at<float>(i)+=histogram.at<float>(i-1);
    for (int i=1;i<256;++i) histogram.at<float>(i)/=float(gray.total());
    int low=1,high=255;
    while (low<255 && double(histogram.at<float>(low))<.02) ++low;
    while (high>0 && double(histogram.at<float>(high))>.95) --high;
    high+=int((255-high)*.3);
    if (low>=high) return bgr.clone();
    low=int(low*.75);high=std::min(high,255);
    cv::Mat table(1,256,CV_8U);
    for (int i=0;i<256;++i)
        table.at<uchar>(i)=uchar(int(double(std::clamp(i,low,high)-low)*255/(high-low)));
    cv::Mat output;cv::LUT(bgr,table,output);return output;
}
inline cv::Mat legacyGray(const cv::Mat &bgr) {
    if (bgr.empty() || bgr.type()!=CV_8UC3) throw std::invalid_argument("Legacy gray requires an 8-bit BGR image");
    cv::Mat output;cv::cvtColor(bgr,output,cv::COLOR_BGR2GRAY);return output;
}
} // namespace freescan
