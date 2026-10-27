/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

import Foundation
import SwiftUI
import UIKit

enum ScanFilter: String, CaseIterable, Identifiable {
    case color = "彩色"
    case grayscale = "灰度"
    case blackAndWhite = "黑白"
    case brighten = "提亮"
    case enhance = "文档增强"

    var id: String { rawValue }
}

struct ScanPage: Identifiable {
    let id: UUID
    var original: UIImage
    var image: UIImage
    var filter: ScanFilter = .color
    var rotation: Int = 0
    var recognizedText: String?

    init(image: UIImage) {
        id = UUID()
        original = image
        self.image = image
    }
}

@MainActor
final class ScanLibrary: ObservableObject {
    @Published var pages: [ScanPage] = []
    @Published var recentExports: [URL] = []

    func add(_ image: UIImage) {
        pages.append(ScanPage(image: ImagePipeline.upright(image)))
    }

    func add(contentsOf images: [UIImage]) {
        pages.append(contentsOf: images.map { ScanPage(image: ImagePipeline.upright($0)) })
    }

    func update(_ id: UUID, _ change: (inout ScanPage) -> Void) {
        guard let index = pages.firstIndex(where: { $0.id == id }) else { return }
        change(&pages[index])
    }

    func remove(_ id: UUID) {
        pages.removeAll { $0.id == id }
    }

    func clear() {
        pages.removeAll()
    }
}

struct CertificateTemplate: Identifiable, Hashable {
    let id: String
    let title: String
    /// Width divided by height in portrait camera preview.
    let aspect: CGFloat

    static let all: [CertificateTemplate] = [
        .init(id: "single-card", title: "单卡证", aspect: 1.586),
        .init(id: "id-card", title: "身份证", aspect: 1.586),
        .init(id: "double-card", title: "卡证(双拼)", aspect: 3.172),
        .init(id: "household", title: "户口本", aspect: 1.28),
        .init(id: "household-double", title: "户口本(双拼)", aspect: 1.82),
        .init(id: "business-license", title: "营业执照", aspect: 0.707),
        .init(id: "passport", title: "护照", aspect: 1.42),
        .init(id: "driver-license", title: "驾驶证", aspect: 1.586),
        .init(id: "vehicle-license", title: "行驶证", aspect: 1.586),
    ]
}

