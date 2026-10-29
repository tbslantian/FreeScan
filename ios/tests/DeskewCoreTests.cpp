/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../DeskewCore.hpp"
#include <iostream>
#include <stdexcept>

static void require(bool ok,const char *message) { if (!ok) throw std::runtime_error(message); }
static cv::Mat linesAt(double degrees) {
    cv::Mat page(1000,800,CV_8UC4,cv::Scalar(250,250,250,255));
    double slope=std::tan(degrees*CV_PI/180);
    for (int y=150;y<850;y+=45)
        cv::line(page,cv::Point(80,cvRound(y-320*slope)),cv::Point(720,cvRound(y+320*slope)),
                 cv::Scalar(20,20,20,255),2,cv::LINE_AA);
    return page;
}
int main() {
    try {
        for (double angle:{2.3,-2.4}) {
            cv::Mat page=linesAt(angle);
            double applied=0;
            cv::Mat result=freescan::deskewText(page,applied);
            std::cout<<"fixture="<<angle<<" detected="<<applied
                     <<" residual="<<freescan::textLineAngle(result)<<std::endl;
            require(std::abs(applied-angle)<.25,"Hough angle does not match fixture");
            require(std::abs(freescan::textLineAngle(result))<.35,"Deskew increased or retained the line slope");
            require(result.size()==page.size(),"Deskew changed page size");
        }
        for (double angle:{0.0,6.0}) {
            cv::Mat page=linesAt(angle); double applied=99;
            cv::Mat result=freescan::deskewText(page,applied);
            require(applied==0 && cv::norm(page,result,cv::NORM_INF)==0,"Uncertain/straight page must remain unchanged");
        }
        cv::Mat small(300,300,CV_8UC4,cv::Scalar(200,210,220,255)); double applied=99;
        cv::Mat unchanged=freescan::deskewText(small,applied);
        require(applied==0 && cv::norm(small,unchanged,cv::NORM_INF)==0,"Small image must remain unchanged");
        std::cout<<"All text deskew regression checks passed\n";
        return 0;
    } catch (const std::exception &error) { std::cerr<<error.what()<<'\n';return 1; }
}
