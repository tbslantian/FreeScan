/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <cmath>

namespace freescan {
// Port of imageprocessing/PostProcessing.kt. Core input/output uses BGR.
inline cv::Mat bounded(const cv::Mat &input,double maxPixels,int interpolation=cv::INTER_AREA) {
    if (input.total()<=maxPixels) return input.clone();
    double factor=std::sqrt(maxPixels/input.total());
    cv::Mat output;cv::resize(input,output,cv::Size(cvRound(input.cols*factor),cvRound(input.rows*factor)),0,0,interpolation);
    return output;
}
inline double histogramPercentile(const cv::Mat &input,double percentile) {
    int bins=256,channel=0;float range[]={0,256};const float *ranges[]={range};cv::Mat hist;
    cv::calcHist(&input,1,&channel,cv::Mat(),hist,1,&bins,ranges);
    double sum=0;
    for (int i=0;i<256;++i) {sum+=hist.at<float>(i);if (sum/input.total()>=percentile) return i;}
    return 255;
}
inline std::pair<double,double> percentiles(const cv::Mat &input,double low,double high) {
    auto sample=bounded(input,500000,cv::INTER_NEAREST);
    cv::Mat sorted;cv::sort(sample.reshape(1,1),sorted,cv::SORT_ASCENDING);
    int n=sorted.cols;
    return {sorted.at<float>(0,std::clamp(int(n*low),0,n-1)),sorted.at<float>(0,std::clamp(int(n*high),0,n-1))};
}
inline cv::Mat flattenIllumination(const cv::Mat &bgr,bool preserveColor,bool whitePaperTarget) {
    cv::Mat lab,luminance;std::vector<cv::Mat> channels;
    if (preserveColor) {cv::cvtColor(bgr,lab,cv::COLOR_BGR2Lab);cv::split(lab,channels);luminance=channels[0];}
    else cv::cvtColor(bgr,luminance,cv::COLOR_BGR2GRAY);
    int maxDim=std::max(bgr.cols,bgr.rows),limit=std::max(128,maxDim/8);
    cv::Mat reduced,small,background;
    if (maxDim<=limit) reduced=luminance.clone();
    else cv::resize(luminance,reduced,cv::Size(cvRound(luminance.cols*double(limit)/maxDim),cvRound(luminance.rows*double(limit)/maxDim)),0,0,cv::INTER_AREA);
    cv::GaussianBlur(reduced,small,cv::Size(31,31),0);
    cv::resize(small,background,luminance.size(),0,0,cv::INTER_CUBIC);
    double target=whitePaperTarget?242:std::clamp(histogramPercentile(background,.85),190.,248.);
    reduced.release();small.release();
    cv::Mat floating,gain,corrected;
    luminance.convertTo(floating,CV_32F);background.convertTo(gain,CV_32F);background.release();
    cv::max(gain,32,gain);cv::divide(target,gain,gain);cv::max(gain,.82,gain);cv::min(gain,1.85,gain);
    cv::multiply(floating,gain,floating);cv::min(floating,255,floating);cv::max(floating,0,floating);
    floating.convertTo(corrected,CV_8U);floating.release();gain.release();
    if (!preserveColor) return corrected;
    channels[0]=corrected;
    if (whitePaperTarget) {
        cv::Mat paper;cv::threshold(corrected,paper,205,255,cv::THRESH_BINARY);
        if (cv::countNonZero(paper)>=int(corrected.total()/10)) {
            double a=std::clamp(128-cv::mean(channels[1],paper)[0],-18.,18.);
            double b=std::clamp(128-cv::mean(channels[2],paper)[0],-18.,18.);
            cv::add(channels[1],cv::Scalar(a),channels[1]);cv::add(channels[2],cv::Scalar(b),channels[2]);
        }
    }
    cv::merge(channels,lab);cv::Mat output;cv::cvtColor(lab,output,cv::COLOR_Lab2BGR);return output;
}

inline cv::Mat flattenedGray(const cv::Mat &input) {
    cv::Mat gray;
    if (input.channels()==1) gray=input.clone();else cv::cvtColor(input,gray,cv::COLOR_BGR2GRAY);
    cv::Mat floating,logarithm,retinex=cv::Mat::zeros(gray.size(),CV_32F),work;
    gray.convertTo(floating,CV_32F);cv::add(floating,cv::Scalar(1),floating);cv::log(floating,logarithm);
    double maxDim=std::max(gray.cols,gray.rows);
    for (double width:{maxDim/6,maxDim/50}) {
        cv::boxFilter(floating,work,-1,cv::Size(std::max(1,cvRound(width)),std::max(1,cvRound(width))));
        cv::add(work,cv::Scalar(1),work);cv::log(work,work);cv::subtract(logarithm,work,work);
        cv::addWeighted(retinex,1,work,.5,0,retinex);
    }
    floating.release();logarithm.release();work.release();
    cv::exp(retinex,retinex);auto p=percentiles(retinex,.004,.99);
    // A perfectly flat page has no Retinex range. Normalizing that constant to
    // zero would turn blank paper black; retain its luminance instead.
    if (p.second-p.first<1e-6) return gray.clone();
    cv::subtract(retinex,cv::Scalar(p.first),retinex);
    cv::multiply(retinex,cv::Scalar(p.second>p.first?255/(p.second-p.first):1),retinex);
    cv::min(retinex,255,retinex);cv::max(retinex,0,retinex);
    cv::Mat normalized;retinex.convertTo(normalized,CV_8U);retinex.release();
    int bins=256,channel=0;float range[]={0,256};const float *ranges[]={range};cv::Mat hist;
    cv::calcHist(&normalized,1,&channel,cv::Mat(),hist,1,&bins,ranges);
    int mode=220;float count=0;
    for (int i=180;i<256;++i) if (hist.at<float>(i)>count) {mode=i;count=hist.at<float>(i);}
    cv::Mat stretched;
    if (mode>=254) {
        gray.convertTo(floating,CV_32F);auto g=percentiles(floating,.01,.99);
        cv::subtract(floating,cv::Scalar(g.first),floating);cv::multiply(floating,cv::Scalar(255/(g.second-g.first+1e-6)),floating);
        cv::min(floating,255,floating);cv::max(floating,0,floating);floating.convertTo(stretched,CV_8U);
    } else normalized.convertTo(stretched,CV_8U,255./mode);
    cv::Mat output;cv::bilateralFilter(stretched,output,9,20,10);return output;
}
inline cv::Mat deviation(const cv::Mat &source,int window) {
    cv::Mat mean,variance,squares;
    cv::boxFilter(source,mean,CV_32F,cv::Size(window,window));cv::multiply(source,source,squares);
    cv::boxFilter(squares,variance,CV_32F,cv::Size(window,window));cv::multiply(mean,mean,mean);
    cv::subtract(variance,mean,variance);cv::max(variance,0,variance);cv::sqrt(variance,variance);return variance;
}
inline void removeLightSpecks(cv::Mat &binary,int minArea,const cv::Mat &within=cv::Mat()) {
    cv::Mat labels,stats,centroids;int count=cv::connectedComponentsWithStats(binary,labels,stats,centroids,8,CV_32S);
    for (int i=1;i<count;++i) {
        if (stats.at<int>(i,cv::CC_STAT_AREA)>=minArea) continue;
        if (!within.empty()) {
            int x=std::clamp(int(centroids.at<double>(i,0)),0,within.cols-1);
            int y=std::clamp(int(centroids.at<double>(i,1)),0,within.rows-1);
            if (!within.at<uchar>(y,x)) continue;
        }
        cv::Rect box(stats.at<int>(i,cv::CC_STAT_LEFT),stats.at<int>(i,cv::CC_STAT_TOP),
                     stats.at<int>(i,cv::CC_STAT_WIDTH),stats.at<int>(i,cv::CC_STAT_HEIGHT));
        cv::Mat mask;cv::compare(labels(box),i,mask,cv::CMP_EQ);binary(box).setTo(0,mask);
    }
}
inline cv::Mat flatFill(const cv::Mat &source,int window) {
    cv::Mat dark,seeds,fill;cv::compare(source,155,dark,cv::CMP_LT);
    auto local=deviation(source,9);cv::compare(local,12,seeds,cv::CMP_LT);
    cv::bitwise_and(seeds,dark,seeds);int reach=std::max(3,window/2);
    cv::dilate(seeds,fill,cv::getStructuringElement(cv::MORPH_RECT,cv::Size(reach,reach)));
    cv::bitwise_and(fill,dark,fill);return fill;
}
inline cv::Mat binaryDocument(const cv::Mat &input) {
    cv::Mat originalGray;
    if (input.channels()==1) originalGray=input;else cv::cvtColor(input,originalGray,cv::COLOR_BGR2GRAY);
    double minimum=0,maximum=0;cv::minMaxLoc(originalGray,&minimum,&maximum);
    // A constant field has no text/background contrast to threshold locally.
    if (maximum-minimum<1) return cv::Mat(input.size(),CV_8UC1,cv::Scalar(maximum>=128?255:0));
    auto gray=flattenedGray(input);int maxDim=std::max(gray.cols,gray.rows),window=std::clamp(maxDim/10,15,1001)|1;
    cv::Mat originalFill;
    if (histogramPercentile(originalGray,.85)>=190) {
        cv::Mat originalFloat;originalGray.convertTo(originalFloat,CV_32F);
        originalFill=flatFill(originalFloat,window);
    }
    cv::Mat source,mean,threshold,binary;gray.convertTo(source,CV_32F);gray.release();
    cv::boxFilter(source,mean,CV_32F,cv::Size(window,window));threshold=deviation(source,window);
    cv::multiply(threshold,cv::Scalar(.25/128),threshold);cv::add(threshold,cv::Scalar(.75),threshold);
    cv::multiply(mean,threshold,threshold);cv::compare(source,threshold,binary,cv::CMP_GT);
    mean.release();threshold.release();
    auto fill=flatFill(source,window);source.release();
    // Retinex may whiten the centre of a wide dark box. Keep the original
    // smooth dark fill when a bright paper reference exists around it.
    if (!originalFill.empty()) cv::bitwise_or(fill,originalFill,fill);
    cv::subtract(binary,fill,binary);
    int area=std::max(2,int(std::round(12*std::pow(maxDim/3508.,2))));
    auto letters=binary.clone();removeLightSpecks(letters,area*4);
    cv::Mat near,inside,lonely;int gap=std::max(3,cvRound(8*std::sqrt(area)));
    cv::dilate(letters,near,cv::getStructuringElement(cv::MORPH_RECT,cv::Size(gap,gap)));
    int reach=std::max(3,window/4);cv::dilate(fill,inside,cv::getStructuringElement(cv::MORPH_RECT,cv::Size(reach,reach)));
    cv::bitwise_not(near,lonely);cv::bitwise_and(inside,lonely,inside);removeLightSpecks(binary,area*5,inside);
    return binary;
}

inline bool containsDocumentColor(const cv::Mat &bgr) {
    auto work=bounded(bgr,1024.*768);int k=std::clamp(cvRound(std::min(work.cols,work.rows)*.02),3,15);if (!(k&1)) ++k;
    cv::Mat mask(work.size(),CV_8U,cv::Scalar(255));cv::rectangle(mask,cv::Rect(0,0,work.cols,work.rows),cv::Scalar(0),k);
    int pixels=cv::countNonZero(mask);if (!pixels) return false;
    auto mean=cv::mean(work,mask);double avg=(std::max(mean[0],1e-6)+std::max(mean[1],1e-6)+std::max(mean[2],1e-6))/3;
    cv::Mat balanced;work.convertTo(balanced,CV_32FC3);
    cv::multiply(balanced,cv::Scalar(avg/std::max(mean[0],1e-6),avg/std::max(mean[1],1e-6),avg/std::max(mean[2],1e-6)),balanced);
    balanced.convertTo(balanced,CV_8UC3);cv::Mat lab;cv::cvtColor(balanced,lab,cv::COLOR_BGR2Lab);
    int colored=0;
    for (int y=0;y<lab.rows;++y) for (int x=0;x<lab.cols;++x) if (mask.at<uchar>(y,x)) {
        auto c=lab.at<cv::Vec3b>(y,x);
        if (c[0]>=40 && c[0]<=180 && std::hypot(double(c[1])-128,double(c[2])-128)>17.5) ++colored;
    }
    return double(colored)/pixels>.0003;
}

// 0=color, 1=gray, 2=bitonal, 3=auto, 4=illumination-only for local native filters.
inline cv::Mat documentFilter(const cv::Mat &bgr,int mode,bool shadows) {
    if (mode==3) mode=containsDocumentColor(bgr)?0:1;
    cv::Mat working=shadows?flattenIllumination(bgr,mode!=1 && mode!=2,mode==0):bgr.clone();
    if (mode==2) return binaryDocument(working);
    if (mode==1) return flattenedGray(working);
    return working;
}
} // namespace freescan
