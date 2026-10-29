/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>

namespace freescan {
struct RepairRegion { std::vector<cv::Point2f> corners; bool verified; };
struct PaperSample { double x, y; cv::Vec4b color; };
inline int luma(const cv::Vec4b &c) { return (c[0]*77 + c[1]*150 + c[2]*29)/256; }

// Port of QrRegionRepair.paperBackground/fitPlane; channels are RGB(A).
inline bool paperPlane(const cv::Mat &rgba, const cv::Mat &mask, double coefficients[3][3]) {
    std::vector<PaperSample> samples;
    for (int gy=0; gy<8; ++gy) for (int gx=0; gx<8; ++gx) {
        std::vector<PaperSample> cell;
        int x0=gx*rgba.cols/8, x1=(gx+1)*rgba.cols/8;
        int y0=gy*rgba.rows/8, y1=(gy+1)*rgba.rows/8;
        for (int y=y0; y<y1; y+=std::max(1,(y1-y0)/10))
            for (int x=x0; x<x1; x+=std::max(1,(x1-x0)/10)) {
                auto color=rgba.at<cv::Vec4b>(y,x);
                if (!mask.at<uchar>(y,x) && color[3]>=240)
                    cell.push_back({double(x)/std::max(1,rgba.cols-1), double(y)/std::max(1,rgba.rows-1), color});
            }
        if (cell.size()>=4) {
            std::stable_sort(cell.begin(),cell.end(),[](const auto &a,const auto &b){return luma(a.color)<luma(b.color);});
            samples.push_back(cell[size_t(cell.size()*0.75)]);
        }
    }
    if (samples.size()<3) throw std::runtime_error("Insufficient surrounding background; select a smaller area.");
    std::vector<int> brightness;
    int colorful=0;
    for (const auto &s:samples) {
        brightness.push_back(luma(s.color));
        if (std::max({s.color[0],s.color[1],s.color[2]})-std::min({s.color[0],s.color[1],s.color[2]})>65) ++colorful;
    }
    std::sort(brightness.begin(),brightness.end());
    if (brightness[brightness.size()/2]<110 || colorful>int(samples.size()/3)) return false;
    double matrix[3][6] = {};
    for (const auto &s:samples) {
        double v[3]={1,s.x,s.y};
        for (int r=0;r<3;++r) for (int c=0;c<3;++c) {
            matrix[r][c]+=v[r]*v[c];
            matrix[r][c+3]+=v[r]*s.color[c];
        }
    }
    for (int column=0;column<3;++column) {
        int pivot=column;
        for (int r=column+1;r<3;++r) if (std::abs(matrix[r][column])>std::abs(matrix[pivot][column])) pivot=r;
        if (std::abs(matrix[pivot][column])<1e-8) return false;
        for (int j=0;j<6;++j) std::swap(matrix[column][j],matrix[pivot][j]);
        double divisor=matrix[column][column];
        for (int j=column;j<6;++j) matrix[column][j]/=divisor;
        for (int r=0;r<3;++r) if (r!=column) {
            double factor=matrix[r][column];
            for (int j=column;j<6;++j) matrix[r][j]-=factor*matrix[column][j];
        }
    }
    for (int c=0;c<3;++c) for (int k=0;k<3;++k) coefficients[c][k]=matrix[k][c+3];
    double residual=0;
    for (const auto &s:samples) for (int c=0;c<3;++c)
        residual+=std::abs(s.color[c]-coefficients[c][0]-coefficients[c][1]*s.x-coefficients[c][2]*s.y)/3;
    return residual/samples.size()<18;
}

inline void repairRegions(cv::Mat &rgba, const std::vector<RepairRegion> &regions) {
    CV_Assert(rgba.type()==CV_8UC4);
    for (const auto &region:regions) {
        if (region.corners.size()!=4) throw std::runtime_error("Invalid repair polygon.");
        for (auto p:region.corners)
            if (!std::isfinite(p.x)||!std::isfinite(p.y)||p.x<0||p.y<0||p.x>1||p.y>1)
                throw std::runtime_error("Repair polygon is outside the image.");
    }
    const auto kernel=cv::getStructuringElement(cv::MORPH_ELLIPSE,cv::Size(3,3));
    for (const auto &region:regions) {
        if (region.corners.size()!=4) throw std::runtime_error("Invalid repair polygon.");
        float left=rgba.cols,top=rgba.rows,right=0,bottom=0;
        std::vector<cv::Point2f> polygon;
        for (const auto &p:region.corners) {
            if (!std::isfinite(p.x)||!std::isfinite(p.y)||p.x<0||p.y<0||p.x>1||p.y>1)
                throw std::runtime_error("Repair polygon is outside the image.");
            cv::Point2f q(p.x*rgba.cols,p.y*rgba.rows); polygon.push_back(q);
            left=std::min(left,q.x);top=std::min(top,q.y);right=std::max(right,q.x);bottom=std::max(bottom,q.y);
        }
        float edge=std::min(right-left,bottom-top);
        if (edge<1) continue;
        if (!cv::isContourConvex(polygon)) throw std::runtime_error("Invalid repair polygon.");
        float expansion=region.verified?std::clamp(edge*0.015f,2.f,10.f):1.f;
        float padding=std::max(24.f,edge*0.18f);
        int x0=std::max(0,int(std::floor(left-padding))),y0=std::max(0,int(std::floor(top-padding)));
        int x1=std::min(rgba.cols,int(std::ceil(right+padding))),y1=std::min(rgba.rows,int(std::ceil(bottom+padding)));
        cv::Rect roi(x0,y0,x1-x0,y1-y0);
        if (roi.empty()) continue;
        double scale=std::min(1.0,1024.0/std::max(roi.width,roi.height));
        cv::Size size(std::max(1,int(std::round(roi.width*scale))),std::max(1,int(std::round(roi.height*scale))));
        cv::Mat sample,mask=cv::Mat::zeros(size,CV_8UC1);
        cv::resize(rgba(roi),sample,size,0,0,cv::INTER_LINEAR);
        // Exclude every selected code, not just this code, from paper estimation.
        for (const auto &other:regions) {
            std::vector<cv::Point> points;
            for (const auto &p:other.corners) points.emplace_back(
                int((p.x*rgba.cols-roi.x)*size.width/roi.width),
                int((p.y*rgba.rows-roi.y)*size.height/roi.height));
            if (points.size()==4) cv::fillConvexPoly(mask,points,cv::Scalar(255));
        }
        cv::dilate(mask,mask,kernel,cv::Point(-1,-1),std::max(2,int(std::ceil(expansion*scale))+1));
        double coefficients[3][3];
        if (paperPlane(sample,mask,coefficients)) {
            for (int y=0;y<size.height;++y) for (int x=0;x<size.width;++x) {
                auto &c=sample.at<cv::Vec4b>(y,x);
                for (int channel=0;channel<3;++channel) c[channel]=uchar(std::clamp(int(std::round(
                    coefficients[channel][0]+coefficients[channel][1]*x/std::max(1,size.width-1)+
                    coefficients[channel][2]*y/std::max(1,size.height-1))),0,255));
                c[3]=255;
            }
        } else {
            cv::Mat bgr,repaired;
            cv::cvtColor(sample,bgr,cv::COLOR_RGBA2BGR);
            cv::inpaint(bgr,mask,repaired,5,cv::INPAINT_TELEA);
            cv::cvtColor(repaired,sample,cv::COLOR_BGR2RGBA);
        }
        // Full-resolution polygon clip: leave original pixels outside it intact.
        cv::Mat clip=cv::Mat::zeros(roi.size(),CV_8UC1),replacement;
        std::vector<cv::Point> points;
        for (auto p:polygon) points.emplace_back(cvRound(p.x-roi.x),cvRound(p.y-roi.y));
        cv::fillConvexPoly(clip,points,cv::Scalar(255));
        cv::polylines(clip,std::vector<std::vector<cv::Point>>{points},true,cv::Scalar(255),std::max(1,cvRound(expansion*2)),cv::LINE_8);
        for (auto p:points) cv::circle(clip,p,std::max(1,cvRound(expansion)),cv::Scalar(255),-1);
        cv::resize(sample,replacement,roi.size(),0,0,cv::INTER_LINEAR);
        replacement.copyTo(rgba(roi),clip);
    }
}
} // namespace freescan
