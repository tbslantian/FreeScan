/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "../TesseractCore.hpp"
#include <iostream>
#include <cctype>
static void require(bool value,const char *message) {if (!value) throw std::runtime_error(message);}
int main(int argc,char **argv) {
    try {
        require(argc==3,"Missing test tessdata directory or bilingual image");
        cv::Mat image(240,1100,CV_8UC4,cv::Scalar(255,255,255,255));
        cv::putText(image,"FreeScan 12345",cv::Point(60,145),cv::FONT_HERSHEY_SIMPLEX,2.4,cv::Scalar(0,0,0,255),3,cv::LINE_AA);
        auto result=freescan::recognizeDocument(image,argv[1],"eng");
        auto lower=result.text;std::transform(lower.begin(),lower.end(),lower.begin(),[](unsigned char c){return std::tolower(c);});
        std::cout<<"Synthetic OCR: "<<result.text<<std::endl;
        require(lower.find("freescan")!=std::string::npos && lower.find("12345")!=std::string::npos,"Printed text was not recognized");
        require(result.words.size()>=2,"Confident word boxes missing");
        for (const auto &word:result.words) {
            require(word.confidence>50,"Low-confidence word included");
            require(word.box.x>=0 && word.box.y>=0 && word.box.br().x<=image.cols && word.box.br().y<=image.rows,"Word box outside image");
            require(word.line.height>0 && word.line.y<=word.box.y,"Text-line geometry invalid");
        }
        cv::Mat blank(240,1100,CV_8UC4,cv::Scalar(255,255,255,255));
        require(freescan::recognizeDocument(blank,argv[1],"eng").words.empty(),"Blank page produced word boxes");
        bool rejected=false;
        try {freescan::recognizeDocument(image,argv[1],"missing_language");} catch (const std::runtime_error &) {rejected=true;}
        require(rejected,"Missing model must report initialization failure");
        auto bilingualBGR=cv::imread(argv[2]);cv::Mat bilingualImage;
        require(!bilingualBGR.empty(),"Bilingual test image missing");
        cv::cvtColor(bilingualBGR,bilingualImage,cv::COLOR_BGR2RGBA);
        auto bilingual=freescan::recognizeDocument(bilingualImage,argv[1],"chi_sim+eng");
        std::cout<<"Synthetic bilingual OCR: "<<bilingual.text<<std::endl;
        require(bilingual.text.find(u8"扫描")!=std::string::npos && bilingual.text.find("12345")!=std::string::npos,
                "Chinese/English multi-language recognition failed");
        require(!bilingual.words.empty(),"Bilingual word boxes missing");
        std::string confidentText;
        for (const auto &word:bilingual.words) confidentText+=word.text;
        require(confidentText.find(u8"扫描")!=std::string::npos,"Searchable Chinese word boxes missing");
        std::cout<<"All Tesseract OCR regression checks passed\n";
        return 0;
    } catch (const std::exception &error) {std::cerr<<error.what()<<'\n';return 1;}
}
