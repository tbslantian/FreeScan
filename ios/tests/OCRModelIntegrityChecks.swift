/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
import Foundation

@main
struct OCRModelIntegrityChecks {
    static func main() throws {
        let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
        let models = catalog["models"] as! [[String: Any]]
        precondition(models.count == 120, "Android language catalog is incomplete")
        let english = models.first { $0["code"] as? String == "eng" }!
        let size = english["size"] as! Int, digest = english["gitBlob"] as! String
        let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
        try OCRModelIntegrity.validate(data, expectedSize: size, gitBlob: digest)
        var damaged = data
        damaged[0] ^= 1
        var rejected = false
        do { try OCRModelIntegrity.validate(damaged, expectedSize: size, gitBlob: digest) } catch { rejected = true }
        precondition(rejected, "Same-size corrupted model was accepted")
        rejected = false
        do { try OCRModelIntegrity.validate(Data(data.dropLast()), expectedSize: size, gitBlob: digest) } catch { rejected = true }
        precondition(rejected, "Truncated model was accepted")
        print("All OCR language model integrity checks passed")
    }
}
