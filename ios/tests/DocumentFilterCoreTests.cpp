/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../DocumentFilterCore.hpp"
#include <iostream>
#include <stdexcept>
static void require(bool ok,const char *message) { if (!ok) throw std::runtime_error(message); }

int main() {
    try {
        cv::Mat page(512,512,CV_8UC3);
        for (int y=0;y<512;++y) for (int x=0;x<512;++x) {
            uchar v=uchar(140+95*x/511.);page.at<cv::Vec3b>(y,x)=cv::Vec3b(v,v,v);
        }
        cv::putText(page,"Exam 123",cv::Point(60,280),cv::FONT_HERSHEY_SIMPLEX,1.5,cv::Scalar(12,12,12),3);
        auto original=page.clone();auto color=freescan::documentFilter(page,0,false);
        require(cv::norm(original,color,cv::NORM_INF)==0,"Color without shadow correction must preserve pixels");
        require(cv::norm(original,page,cv::NORM_INF)==0,"Filter mutated source");
        auto flattened=freescan::documentFilter(page,0,true);
        cv::Scalar beforeMean,beforeStd,afterMean,afterStd;
        cv::meanStdDev(page(cv::Rect(64,60,384,40)),beforeMean,beforeStd);
        cv::meanStdDev(flattened(cv::Rect(64,60,384,40)),afterMean,afterStd);
        require(afterStd[0]<beforeStd[0]*.5,"Shadow correction did not flatten paper illumination");
        require(flattened.at<cv::Vec3b>(270,100)[0]<150,"Shadow correction destroyed dark text");
        auto gray=freescan::documentFilter(page,1,true);
        require(gray.type()==CV_8UC1 && gray.size()==page.size(),"Grayscale type or size changed");
        cv::Mat blank(300,300,CV_8UC3,cv::Scalar(246,246,246));
        require(cv::mean(freescan::documentFilter(blank,1,false))[0]>=240,"Flat blank paper turned black");
        cv::Mat exam(900,640,CV_8UC3,cv::Scalar(238,238,238));
        cv::putText(exam,"Printed text",cv::Point(80,180),cv::FONT_HERSHEY_SIMPLEX,1.4,cv::Scalar(8,8,8),3);
        cv::rectangle(exam,cv::Rect(100,320,400,160),cv::Scalar(10,10,10),-1);
        cv::putText(exam,"i",cv::Point(180,430),cv::FONT_HERSHEY_SIMPLEX,2,cv::Scalar(245,245,245),3);
        exam.at<cv::Vec3b>(400,450)=cv::Vec3b(250,250,250);
        auto binary=freescan::documentFilter(exam,2,true);
        cv::Mat notBlack,notWhite,nonBinary;
        cv::compare(binary,0,notBlack,cv::CMP_NE);cv::compare(binary,255,notWhite,cv::CMP_NE);
        cv::bitwise_and(notBlack,notWhite,nonBinary);
        require(cv::countNonZero(nonBinary)==0,"Black/white output contains gray values");
        require(binary.at<uchar>(410,300)==0,"Large dark fill became white");
        require(binary.at<uchar>(400,450)==0,"Isolated hole in dark fill was not closed");
        require(cv::countNonZero(binary(cv::Rect(175,375,30,60)))>30,"Light print in dark fill was removed");
        require(!freescan::containsDocumentColor(exam),"Neutral printed page classified as color");
        cv::rectangle(exam,cv::Rect(450,120,40,40),cv::Scalar(220,30,30),-1);
        require(freescan::containsDocumentColor(exam),"Colored annotation missed by auto mode");
        auto automatic=freescan::documentFilter(exam,3,true);
        require(automatic.channels()==3,"Color auto mode became grayscale");
        auto ink=automatic.at<cv::Vec3b>(140,470);
        require(ink[0]>ink[2]+80,"Color ink lost its hue");
        std::cout<<"All document filter regression checks passed\n";
        return 0;
    } catch (const std::exception &error) { std::cerr<<error.what()<<'\n';return 1; }
}
