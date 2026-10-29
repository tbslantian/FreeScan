/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <opencv2/opencv.hpp>
#include <tesseract/baseapi.h>
#include <tesseract/resultiterator.h>
#include <memory>
#include <stdexcept>

namespace freescan {
struct OCRWord { std::string text; cv::Rect box; cv::Rect line; float confidence; };
struct OCRResult { std::string text; std::vector<OCRWord> words; };
inline OCRResult recognizeDocument(const cv::Mat &image,const std::string &dataPath,const std::string &languages) {
    if (image.empty() || image.depth()!=CV_8U || languages.empty()) throw std::runtime_error("Invalid OCR input or no active language packs.");
    tesseract::TessBaseAPI api;
    if (api.Init(dataPath.c_str(),languages.c_str(),tesseract::OEM_LSTM_ONLY)!=0)
        throw std::runtime_error("Could not initialize Tesseract with the selected language packs.");
    api.SetPageSegMode(tesseract::PSM_AUTO);
    api.SetImage(image.data,image.cols,image.rows,image.channels(),int(image.step));
    api.SetSourceResolution(300);
    if (api.Recognize(nullptr)!=0) throw std::runtime_error("Tesseract recognition failed.");
    OCRResult result;
    std::unique_ptr<char[]> full(api.GetUTF8Text());
    if (full) result.text=full.get();
    std::unique_ptr<tesseract::ResultIterator> iterator(api.GetIterator());
    if (!iterator) return result;
    do {
        std::unique_ptr<char[]> word(iterator->GetUTF8Text(tesseract::RIL_WORD));
        float confidence=iterator->Confidence(tesseract::RIL_WORD);
        if (!word || !word[0] || confidence<=50) continue;
        int left=0,top=0,right=0,bottom=0,l=0,t=0,r=0,b=0;
        if (!iterator->BoundingBox(tesseract::RIL_WORD,&left,&top,&right,&bottom)) continue;
        iterator->BoundingBox(tesseract::RIL_TEXTLINE,&l,&t,&r,&b);
        auto bounds=cv::Rect(left,top,std::max(0,right-left),std::max(0,bottom-top)) & cv::Rect(0,0,image.cols,image.rows);
        if (!bounds.empty()) result.words.push_back({word.get(),bounds,cv::Rect(l,t,std::max(0,r-l),std::max(0,b-t)),confidence});
    } while (iterator->Next(tesseract::RIL_WORD));
    return result;
}
} // namespace freescan
