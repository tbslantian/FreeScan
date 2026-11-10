/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../DeskewCore.hpp"
#include "../DocumentGeometryCore.hpp"
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
        cv::Mat text(920, 760, CV_8U, cv::Scalar(232));
        for (int y = 100; y < 830; y += 48) {
            cv::putText(text, "EXAM PRINTED PAGE 1234", {100, y}, cv::FONT_HERSHEY_SIMPLEX, .85, cv::Scalar(25), 2);
        }
        auto tilted = cv::getRotationMatrix2D(cv::Point2f(380, 460), -2.7, 1);
        cv::warpAffine(text, text, tilted, text.size(), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
        auto angle = freescan::glyphBaselineAngle(text);
        require(angle && std::abs(*angle - 2.7) < .45, "Disconnected text did not produce a usable baseline");
        auto corrected = freescan::deskewText(text, applied);
        require(std::abs(applied - 2.7) < .45 && std::abs(freescan::textLineAngle(corrected)) < .5,
                "Glyph baseline correction left tilted print");

        cv::Mat page(460, 640, CV_8UC3, cv::Scalar(25, 25, 25));
        std::vector<cv::Point> paper{{91, 40}, {540, 73}, {585, 397}, {51, 406}};
        cv::fillConvexPoly(page, paper, cv::Scalar(241, 241, 241));
        std::array<cv::Point2f, 4> points;
        std::array<cv::Point2f, 4> referenceCorners;
        for (size_t i = 0; i < points.size(); ++i) {
            points[i] = {paper[i].x / 639.f, paper[i].y / 459.f};
            referenceCorners[i] = cv::Point2f(paper[i]);
        }
        const auto referenceSize = freescan::rectifiedDocumentSize(referenceCorners);
        require(referenceSize == cv::Size(540, 392), "Rectification did not match the Android output size");
        cv::circle(page, paper.front() + cv::Point(2, 2), 3, cv::Scalar(10, 10, 10), cv::FILLED);
        auto crop = freescan::rectifyDocument(page, points);
        require(crop.size() == referenceSize, "Rectification ignored reference output dimensions");
        require(cv::mean(crop)[0] > 237, "Rectified paper kept its surroundings");
        require(crop.at<cv::Vec3b>(2, 2)[0] < 50, "Rectification lost a marking near the paper corner");
        std::cout<<"All text deskew regression checks passed\n";
        return 0;
    } catch (const std::exception &error) { std::cerr<<error.what()<<'\n';return 1; }
}
