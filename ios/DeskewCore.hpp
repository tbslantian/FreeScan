/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <cmath>

namespace freescan {
// Match Android's text-line detector limits; input is RGBA on iOS.
inline double textLineAngle(const cv::Mat &image) {
    if (image.cols<400 || image.rows<400) return 0;
    cv::Mat gray,edges;
    if (image.channels()==4) cv::cvtColor(image,gray,cv::COLOR_RGBA2GRAY);
    else if (image.channels()==3) cv::cvtColor(image,gray,cv::COLOR_RGB2GRAY);
    else gray=image;
    cv::Canny(gray,edges,50,150);
    int mx=int(std::round(image.cols*.08)),my=int(std::round(image.rows*.08));
    cv::Rect roi(mx,my,std::max(1,image.cols-mx*2),std::max(1,image.rows-my*2));
    std::vector<cv::Vec4i> lines;
    // Android uses 1-degree bins. Refine to 0.2 degrees so small tilts are not
    // rounded away before median estimation (validated with fractional slopes).
    cv::HoughLinesP(edges(roi),lines,1,CV_PI/900,std::max(20,image.cols/120),image.cols*.12,image.cols*.015);
    std::vector<double> angles;
    for (const auto &l:lines) {
        double angle=std::atan2(double(l[3]-l[1]),double(l[2]-l[0]))*180/CV_PI;
        if (angle>90) angle-=180;
        if (angle< -90) angle+=180;
        if (std::abs(angle)<=8) angles.push_back(angle);
    }
    if (angles.size()<4) return 0;
    std::sort(angles.begin(),angles.end());
    return angles[angles.size()/2];
}

inline cv::Mat deskewText(const cv::Mat &image,double &appliedAngle) {
    appliedAngle=0;
    double angle=textLineAngle(image);
    if (std::abs(angle)<.35 || std::abs(angle)>4) return image.clone();
    // Hough angles are measured with image y pointing down. OpenCV's positive
    // rotation subtracts this slope; negating it would double the skew.
    auto transform=cv::getRotationMatrix2D(cv::Point2f(image.cols/2.f,image.rows/2.f),angle,1);
    cv::Mat output;
    cv::warpAffine(image,output,transform,image.size(),cv::INTER_CUBIC,cv::BORDER_REPLICATE);
    appliedAngle=angle;
    return output;
}
} // namespace freescan
