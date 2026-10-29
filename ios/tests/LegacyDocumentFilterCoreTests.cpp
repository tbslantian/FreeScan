/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../DocumentFilterCore.hpp"
#include <iostream>
static void require(bool value,const char *message) {if(!value) throw std::runtime_error(message);}
int main() {
    try {
        cv::Mat ramp(300,700,CV_8UC3);
        const int input[]={10,50,90,130,170,210,250};
        // Hand-derived from the observed 2%/95% CDF and endpoint rules:
        // low=10*.75 -> 7, high=249+(255-249)*.3 -> 250.
        const int expected[]={3,45,87,129,171,213,255};
        for(int i=0;i<7;++i) ramp(cv::Rect(i*100,0,100,300)).setTo(cv::Scalar::all(input[i]));
        auto unchanged=ramp.clone();auto bright=freescan::documentFilter(ramp,5,false);
        require(bright.size()==ramp.size() && bright.type()==ramp.type(),"Brightness changed size or color format");
        for(int i=0;i<7;++i) require(bright.at<cv::Vec3b>(150,i*100+50)==cv::Vec3b::all(expected[i]),"Brightness does not match recovered tone mapping");
        require(cv::norm(ramp,unchanged,cv::NORM_INF)==0,"Brightness modified its source");
        for(int value:{0,128,255}) {
            cv::Mat page(16,16,CV_8UC3,cv::Scalar::all(value));
            auto result=freescan::documentFilter(page,5,false);
            require(result.at<cv::Vec3b>(8,8)==cv::Vec3b::all(value==128?118:value),"Constant page differs from native endpoint rules");
        }
        cv::Mat colored(300,300,CV_8UC3,cv::Scalar(0,0,255));
        colored(cv::Rect(0,0,3,300)).setTo(cv::Scalar::all(100));
        auto colorResult=freescan::documentFilter(colored,5,false);
        require(colorResult.at<cv::Vec3b>(150,1)==cv::Vec3b::all(255),"Android RGB histogram compatibility was lost");
        cv::Mat primaries(1,3,CV_8UC3);
        primaries.at<cv::Vec3b>(0,0)={255,0,0};primaries.at<cv::Vec3b>(0,1)={0,255,0};primaries.at<cv::Vec3b>(0,2)={0,0,255};
        auto gray=freescan::documentFilter(primaries,7,false);
        require(gray.channels()==1 && gray.at<uchar>(0,0)==29 && gray.at<uchar>(0,1)==150 && gray.at<uchar>(0,2)==76,"Gray conversion uses incorrect channel weights");
        auto neutral=freescan::documentFilter(ramp,7,false);
        require(neutral.at<uchar>(150,350)==130,"Enhanced gray unexpectedly changes contrast");
        cv::Mat parent(340,740,CV_8UC3,cv::Scalar::all(200));auto roi=parent(cv::Rect(20,20,700,300));ramp.copyTo(roi);
        require(!roi.isContinuous(),"ROI fixture is unexpectedly contiguous");
        require(cv::norm(freescan::documentFilter(roi,5,false),bright,cv::NORM_INF)==0,"Brightness does not handle row stride");
        for(int mode:{5,7}) {
            auto corrected=freescan::documentFilter(ramp,4,true);
            auto expectedOrder=mode==5?freescan::legacyBrightness(corrected):freescan::legacyGray(corrected);
            require(cv::norm(expectedOrder,freescan::documentFilter(ramp,mode,true),cv::NORM_INF)==0,"Legacy filter does not follow common shadow correction");
        }
        std::cout<<"All legacy brightness/gray filter regression checks passed\n";
        return 0;
    } catch(const std::exception &error) {std::cerr<<error.what()<<'\n';return 1;}
}
