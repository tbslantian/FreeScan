/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
import CryptoKit
import Foundation

enum OCRModelIntegrity {
    static func validate(_ data: Data, expectedSize: Int, gitBlob: String) throws {
        guard data.count == expectedSize else {
            throw NSError(domain: "FreeScan.OCRModel", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "语言包大小校验失败，请重试。"])
        }
        var hash = Insecure.SHA1()
        hash.update(data: Data("blob \(data.count)\0".utf8)); hash.update(data: data)
        let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard digest == gitBlob else {
            throw NSError(domain: "FreeScan.OCRModel", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "语言包内容校验失败，请重试。"])
        }
    }
}
