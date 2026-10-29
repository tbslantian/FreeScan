/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
import Foundation
import SwiftUI
import UIKit

enum LocalOCRError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

struct OCRModel: Codable, Identifiable {
    let code: String
    let size: Int
    let gitBlob: String
    var id: String { code }
    var name: String {
        let aliases = ["chi_sim": "zh-Hans", "chi_tra": "zh-Hant", "chi_sim_vert": "zh-Hans",
                       "chi_tra_vert": "zh-Hant", "jpn_vert": "ja", "kor_vert": "ko",
                       "aze_cyrl": "az-Cyrl", "uzb_cyrl": "uz-Cyrl", "srp_latn": "sr-Latn"]
        let name = Locale.current.localizedString(forIdentifier: aliases[code] ?? code) ?? code
        return name + (code.hasSuffix("_vert") ? "（竖排）" : "")
    }
}
struct OCRCatalog: Codable {
    let revision: String
    let models: [OCRModel]
    static let bundled: OCRCatalog = {
        guard let url = Bundle.main.url(forResource: "OCRLanguages", withExtension: "json"),
              let data = try? Data(contentsOf: url), let catalog = try? JSONDecoder().decode(OCRCatalog.self, from: data)
        else { return OCRCatalog(revision: "", models: []) }
        return catalog
    }()
}

@MainActor
final class OCRLanguageStore: ObservableObject {
    static let shared = OCRLanguageStore()
    @Published private(set) var installed = Set<String>()
    @Published private(set) var enabled = Set<String>()
    @Published private(set) var activeCode: String?
    @Published private(set) var progress = 0.0
    @Published var errorMessage: String?
    private var task: URLSessionDownloadTask?
    private var observation: NSKeyValueObservation?
    private var generation = UUID()
    let catalog = OCRCatalog.bundled
    let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("FreeScan/OCR/tessdata", isDirectory: true)

    private init() {
        enabled = Set(UserDefaults.standard.stringArray(forKey: "tesseractLanguages") ?? [])
        refresh()
    }
    func refresh() {
        installed = Set(catalog.models.filter { model in
            let url = directory.appendingPathComponent(model.code + ".traineddata")
            let values = try? url.resourceValues(forKeys: [.fileSizeKey])
            return values?.fileSize == model.size
        }.map(\.code))
        enabled.formIntersection(installed)
        persist()
    }
    func setEnabled(_ code: String, _ value: Bool) {
        guard installed.contains(code) else { return }
        if value { enabled.insert(code) } else { enabled.remove(code) }
        persist()
    }
    private func persist() { UserDefaults.standard.set(enabled.sorted(), forKey: "tesseractLanguages") }

    func configuration() throws -> (path: String, languages: String) {
        refresh()
        guard !enabled.isEmpty else {
            throw LocalOCRError.message("请先在设置 → OCR 语言包中下载并启用至少一种语言。")
        }
        return (directory.path, enabled.sorted().joined(separator: "+"))
    }
    func remove(_ code: String) {
        guard installed.contains(code), catalog.models.contains(where: { $0.code == code }) else { return }
        do {
            try FileManager.default.removeItem(at: directory.appendingPathComponent(code + ".traineddata"))
            enabled.remove(code); refresh()
        } catch { errorMessage = error.localizedDescription }
    }
    func cancelDownload() {
        generation = UUID(); task?.cancel(); task = nil
        observation?.invalidate(); observation = nil; activeCode = nil; progress = 0
    }
    func download(_ model: OCRModel) {
        guard activeCode == nil, catalog.models.contains(where: { $0.code == model.code }) else { return }
        let ticket = UUID(); generation = ticket; activeCode = model.code; progress = 0; errorMessage = nil
        let url = URL(string: "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/\(catalog.revision)/\(model.code).traineddata")!
        var request = URLRequest(url: url); request.timeoutInterval = 120
        let download = URLSession.shared.downloadTask(with: request) { [weak self] location, response, error in
            // Read before returning from URLSession's callback; its temporary URL
            // is removed when the callback returns.
            let result: Result<Data, Error> = Result {
                if let error { throw error }
                guard let response = response as? HTTPURLResponse, response.statusCode == 200, let location else {
                    throw LocalOCRError.message("下载失败，请检查网络后重试。")
                }
                return try Data(contentsOf: location)
            }
            Task { @MainActor in
                guard let self, self.generation == ticket else { return }
                defer {
                    self.task = nil; self.observation?.invalidate(); self.observation = nil
                    self.activeCode = nil; self.progress = 0
                }
                do {
                    let data = try result.get()
                    try OCRModelIntegrity.validate(data, expectedSize: model.size, gitBlob: model.gitBlob)
                    try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
                    try data.write(to: self.directory.appendingPathComponent(model.code + ".traineddata"), options: .atomic)
                    self.enabled.insert(model.code); self.refresh()
                } catch { self.errorMessage = error.localizedDescription }
            }
        }
        task = download
        observation = download.progress.observe(\.fractionCompleted, options: [.new]) { [weak self] value, _ in
            let fraction = value.fractionCompleted
            Task { @MainActor in
                guard let self, self.generation == ticket else { return }
                self.progress = fraction
            }
        }
        download.resume()
    }
}

struct LocalOCRResult { let text: String; let words: [RecognizedTextLine] }
actor TesseractOCRRunner {
    static let shared = TesseractOCRRunner()
    func recognize(_ image: UIImage, path: String, languages: String) throws -> LocalOCRResult {
        guard let result = FreeScanTesseract.recognize(image, dataPath: path, languages: languages) else {
            throw LocalOCRError.message("本地 Tesseract 识别失败，请检查语言包或重新下载。")
        }
        return LocalOCRResult(text: result.text, words: result.words.map { RecognizedTextLine(text: $0.text, bounds: $0.bounds) })
    }
}

struct OCRLanguagesView: View {
    @StateObject private var store = OCRLanguageStore.shared
    @State private var search = ""
    @State private var pendingRemoval: String?
    var body: some View {
        List {
            Section {
                Text("语言包来自 Tesseract 官方 tessdata_fast。下载并启用后可离线识别；可以同时启用多种语言。")
                    .font(.footnote).foregroundStyle(.secondary)
                if let code = store.activeCode {
                    ProgressView("正在下载 \(code)", value: store.progress)
                    Button("取消下载") { store.cancelDownload() }
                }
            }
            Section("已启用 \(store.enabled.count) 种语言") {
                ForEach(store.catalog.models.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || $0.code.localizedCaseInsensitiveContains(search) }) { model in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(model.name)
                            Text("\(model.code) · \(ByteCountFormatter.string(fromByteCount: Int64(model.size), countStyle: .file))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if store.installed.contains(model.code) {
                            Toggle("启用 \(model.name)", isOn: Binding(
                                get: { store.enabled.contains(model.code) },
                                set: { store.setEnabled(model.code, $0) })).labelsHidden()
                            Button(role: .destructive) { pendingRemoval = model.code } label: { Image(systemName: "trash") }
                                .buttonStyle(.borderless)
                        } else {
                            Button("下载") { store.download(model) }.buttonStyle(.bordered).disabled(store.activeCode != nil)
                        }
                    }
                }
            }
        }
        .navigationTitle("OCR 语言包")
        .searchable(text: $search, prompt: "搜索语言或代码")
        .onAppear { store.refresh() }
        .confirmationDialog("删除这个语言包？", isPresented: Binding(get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } })) {
            Button("删除", role: .destructive) { if let code = pendingRemoval { store.remove(code) }; pendingRemoval = nil }
        }
        .alert("语言包操作失败", isPresented: Binding(get: { store.errorMessage != nil }, set: { if !$0 { store.errorMessage = nil } })) {
            Button("好") { store.errorMessage = nil }
        } message: { Text(store.errorMessage ?? "") }
    }
}
