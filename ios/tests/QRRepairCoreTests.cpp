/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../QRRepairCore.hpp"
#include <iostream>

static void require(bool value, const char *message) {
    if (!value) throw std::runtime_error(message);
}

static void testRotatedPaperRepair() {
    cv::Mat page(200,240,CV_8UC4);
    for (int y=0;y<page.rows;++y) for (int x=0;x<page.cols;++x) {
        uchar v=uchar(210+x/12+y/20);
        page.at<cv::Vec4b>(y,x)=cv::Vec4b(v,v,v,255);
    }
    // This printed mark is inside the code's axis-aligned bounding box but
    // outside its actual quadrilateral. A rectangle erase would destroy it.
    cv::rectangle(page,cv::Rect(68,49,12,6),cv::Scalar(0,0,0,255),-1);
    std::vector<cv::Point> polygon{{120,45},{175,100},{120,155},{65,100}};
    cv::fillConvexPoly(page,polygon,cv::Scalar(5,5,5,255));
    cv::Mat original=page.clone();
    freescan::RepairRegion region{{{.5f,.225f},{175.f/240,.5f},{.5f,.775f},{65.f/240,.5f}},true};
    freescan::repairRegions(page,{region});
    require(page.at<cv::Vec4b>(51,72)==original.at<cv::Vec4b>(51,72),"Printed text outside rotated mask changed");
    for (int y=0;y<page.rows;++y) for (int x=0;x<page.cols;++x) {
        double distance=cv::pointPolygonTest(polygon,cv::Point2f(x,y),true);
        if (distance < -5) require(page.at<cv::Vec4b>(y,x)==original.at<cv::Vec4b>(y,x),"Outside-mask pixel changed");
    }
    require(std::abs(int(page.at<cv::Vec4b>(100,120)[0])-225)<=3,"Paper plane did not remove dark code");
}

static void testInsufficientBackground() {
    cv::Mat page(100,100,CV_8UC4,cv::Scalar(230,230,230,255));
    bool rejected=false;
    try { freescan::repairRegions(page,{{{{0,0},{1,0},{1,1},{0,1}},false}}); }
    catch (const std::runtime_error &) { rejected=true; }
    require(rejected,"Whole-page erase must reject missing background");
}

static void testDarkBackgroundFallback() {
    cv::Mat page(180,180,CV_8UC4,cv::Scalar(65,70,75,255));
    cv::rectangle(page,cv::Rect(65,65,50,50),cv::Scalar(255,255,255,255),-1);
    freescan::RepairRegion region{{{65.f/180,65.f/180},{115.f/180,65.f/180},
                                   {115.f/180,115.f/180},{65.f/180,115.f/180}},true};
    freescan::repairRegions(page,{region});
    auto center=page.at<cv::Vec4b>(90,90);
    require(center[0]<90 && center[1]<95 && center[2]<100,"Telea fallback failed on dark paper");
    require(page.at<cv::Vec4b>(0,0)==cv::Vec4b(65,70,75,255),"Fallback changed distant content");
}

static void testLargeImageAndInvalidCoordinates() {
    cv::Mat page(1600,2200,CV_8UC4,cv::Scalar(235,235,235,255));
    cv::rectangle(page,cv::Rect(500,400,1200,800),cv::Scalar(0,0,0,255),-1);
    freescan::repairRegions(page,{{{{500.f/2200,.25f},{1700.f/2200,.25f},
                                    {1700.f/2200,.75f},{500.f/2200,.75f}},false}});
    require(page.at<cv::Vec4b>(800,1100)[0]>230,"Downsampled paper repair failed");
    bool rejected=false;
    try { freescan::repairRegions(page,{{{{-1,0},{1,0},{1,1},{0,1}},false}}); }
    catch (const std::runtime_error &) { rejected=true; }
    require(rejected,"Out-of-image polygons must reject");
}

int main() {
    try {
        testRotatedPaperRepair(); testInsufficientBackground();
        testDarkBackgroundFallback(); testLargeImageAndInvalidCoordinates();
        std::cout<<"All QR polygon repair regression checks passed\n";
        return 0;
    } catch (const std::exception &error) { std::cerr<<error.what()<<'\n';return 1; }
}
