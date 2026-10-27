/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import AVFoundation
import PhotosUI
import PDFKit
import SwiftUI
import UniformTypeIdentifiers

@main
struct FreeScanApp: App {
    @StateObject private var library = ScanLibrary()
    var body: some Scene {
        WindowGroup { FreeScanTabs().environmentObject(library) }
    }
}

struct FreeScanTabs: View {
    var body: some View {
        TabView {
            HomeView().tabItem { Label("首页", systemImage: "doc.viewfinder") }
            DocumentsView().tabItem { Label("文档", systemImage: "doc.text") }
            SettingsView().tabItem { Label("设置", systemImage: "gearshape") }
        }.tint(Color(red: 0.08, green: 0.55, blue: 0.39))
    }
}

struct HomeView: View {
    @EnvironmentObject private var library: ScanLibrary
    @State private var showScanner = false
    @State private var showCertificates = false
    @State private var selectedPhotos: [PhotosPickerItem] = []
    @State private var showPDFImporter = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("FreeScan").font(.largeTitle.bold())
                        Text("本地扫描、整理并导出文档").foregroundStyle(.secondary)
                    }
                    Button { showScanner = true } label: {
                        Label("开始扫描", systemImage: "camera.fill").font(.title3.bold())
                            .frame(maxWidth: .infinity).padding(.vertical, 18)
                    }.buttonStyle(.borderedProminent)
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        PhotosPicker(selection: $selectedPhotos, maxSelectionCount: 20, matching: .images) {
                            FeatureCardLabel(title: "相册导入", subtitle: "扫描图片", icon: "photo")
                        }.buttonStyle(.plain)
                        Button { showCertificates = true } label: {
                            FeatureCardLabel(title: "证件扫描", subtitle: "九种实时取景模板", icon: "person.text.rectangle")
                        }.buttonStyle(.plain)
                        NavigationLink { WatermarkToolView() } label: {
                            FeatureCardLabel(title: "二维码/水印", subtitle: "本地检测与区域修复", icon: "qrcode")
                        }.buttonStyle(.plain)
                        NavigationLink { HandwritingToolView() } label: {
                            FeatureCardLabel(title: "试卷擦除", subtitle: "彩笔自动处理", icon: "pencil.tip.crop.circle")
                        }.buttonStyle(.plain)
                        NavigationLink { DocumentsView() } label: {
                            FeatureCardLabel(title: "PDF 编辑", subtitle: "导入、调整页面并导出", icon: "doc.richtext")
                        }.buttonStyle(.plain)
                        NavigationLink { ScanEditorView() } label: {
                            FeatureCardLabel(title: "当前扫描", subtitle: "\(library.pages.count) 页 · 裁边、滤镜、OCR", icon: "square.stack.3d.up")
                        }.buttonStyle(.plain)
                    }
                    Button { showPDFImporter = true } label: {
                        Label("从文件打开 PDF", systemImage: "folder").frame(maxWidth: .infinity, alignment: .leading).padding()
                    }.buttonStyle(.bordered)
                }.padding()
            }
            .navigationTitle("FreeScan").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showScanner) { ScanCameraView(mode: .document) }
            .sheet(isPresented: $showCertificates) { CertificateScanView() }
            .onChange(of: selectedPhotos) { _, items in
                guard !items.isEmpty else { return }
                selectedPhotos = []
                Task {
                    let autoCrop = UserDefaults.standard.object(forKey: "autoCropEnabled") as? Bool ?? true
                    let resolution = CameraResolutionPreset(
                        rawValue: UserDefaults.standard.string(forKey: "scanResolution") ?? "4K") ?? .fourK
                    for item in items {
                        guard let data = try? await item.loadTransferable(type: Data.self),
                              let image = UIImage(data: data) else { continue }
                        let processed = await Task.detached(priority: .userInitiated) {
                            let cropped = autoCrop ? ImagePipeline.autoCropDocument(image) : image
                            return ImagePipeline.resize(cropped, maxDimension: resolution.maxDimension)
                        }.value
                        library.add(processed)
                    }
                }
            }
            .fileImporter(isPresented: $showPDFImporter, allowedContentTypes: [.pdf]) { result in
                guard case .success(let url) = result else { return }
                importPDF(url)
            }
        }
    }
    private func importPDF(_ url: URL) {
        do {
            let localURL = try PDFServices.importIntoLibrary(from: url)
            library.add(contentsOf: try PDFServices.images(from: localURL))
        }
        catch { NSLog("FreeScan PDF import: %@", error.localizedDescription) }
    }
}

struct FeatureCardLabel: View {
    let title: String
    let subtitle: String
    let icon: String
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Image(systemName: icon).font(.title2).foregroundStyle(Color.accentColor)
            Text(title).font(.headline).foregroundStyle(.primary)
            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2)
        }
        .frame(maxWidth: .infinity, minHeight: 92, alignment: .leading).padding(14)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}

enum ScanCameraMode: Equatable { case document, certificate }

struct ScanCameraView: View {
    @EnvironmentObject private var library: ScanLibrary
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraCaptureModel()
    @AppStorage("autoCropEnabled") private var autoCropEnabled = true
    @State private var template = CertificateTemplate.all[0]
    @State private var isBackSide = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var capturedPreview: UIImage?
    let mode: ScanCameraMode

    var body: some View {
        NavigationStack {
            ZStack {
                CameraPreview(session: camera.session, detectedDocument: camera.detectedDocument).ignoresSafeArea()
                VStack(spacing: 16) {
                    HStack {
                        Menu {
                            Picker("相机分辨率", selection: $camera.resolution) {
                                ForEach(CameraResolutionPreset.allCases) { preset in
                                    Text(preset.rawValue).tag(preset)
                                }
                            }
                            Toggle("多帧拍摄并选取最清晰帧", isOn: Binding(
                                get: { camera.multiFrameEnabled },
                                set: { camera.setMultiFrameEnabled($0) }
                            ))
                        } label: {
                            Label(camera.resolution.rawValue, systemImage: "viewfinder")
                        }
                        .onChange(of: camera.resolution) { _, preset in camera.setResolution(preset) }
                        Spacer()
                        Menu {
                            Picker("闪光灯", selection: $camera.flashMode) {
                                ForEach(CameraFlashMode.allCases) { mode in
                                    Text(mode.title).tag(mode)
                                }
                            }
                        } label: {
                            Image(systemName: camera.flashMode == .off ? "bolt.slash" : "bolt")
                                .font(.title3)
                        }
                        if camera.hasTorch {
                            Button { camera.toggleTorch() } label: {
                                Image(systemName: camera.torchEnabled ? "flashlight.on.fill" : "flashlight.off.fill")
                                    .font(.title3)
                            }
                            .accessibilityLabel(camera.torchEnabled ? "关闭补光灯" : "开启补光灯")
                        }
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    if mode == .certificate {
                        Text(isBackSide ? "证件反面 · \(template.title)" : "证件正面 · \(template.title)")
                            .font(.headline).padding(10).background(.black.opacity(0.5), in: Capsule()).foregroundStyle(.white)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(CertificateTemplate.all) { item in
                                    Button(item.title) { template = item }.buttonStyle(.bordered)
                                        .tint(template == item ? .green : .white)
                                }
                            }.padding(.horizontal)
                        }
                    }
                    Spacer()
                    if mode == .certificate {
                        CertificateTemplateGuide(template: template).padding(.horizontal, 36)
                    } else {
                        GuideOverlay(aspect: 0.72).padding(.horizontal, 36)
                    }
                    Spacer()
                    HStack {
                        if mode == .certificate {
                            Button(isBackSide ? "正面" : "反面") { isBackSide.toggle() }.buttonStyle(.borderedProminent)
                        } else {
                            Toggle("自动裁边", isOn: $autoCropEnabled).labelsHidden().tint(.green)
                            Text("自动裁边").foregroundStyle(.white)
                        }
                        Spacer()
                        Button { camera.capture() } label: {
                            Circle().fill(.white).frame(width: 68, height: 68)
                                .overlay(Circle().stroke(.green, lineWidth: 5).padding(5))
                        }
                        Spacer()
                        PhotosPicker(selection: $photoItems, maxSelectionCount: 20, matching: .images) {
                            Image(systemName: "photo").font(.title2).foregroundStyle(.white)
                        }.accessibilityLabel("相册导入，可多选")
                    }.padding(.horizontal, 28).padding(.bottom, 24)
                }.padding(.top, 18)
                if let capturedPreview {
                    VStack(spacing: 12) {
                        Text(mode == .certificate ? "检查证件照片" : "检查扫描结果")
                            .font(.headline).foregroundStyle(.white)
                        Image(uiImage: capturedPreview)
                            .resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity)
                            .padding(.horizontal, 12)
                        HStack(spacing: 16) {
                            Button("重拍") { self.capturedPreview = nil }
                                .buttonStyle(.bordered).tint(.white)
                            Button(mode == .certificate ? "使用此面" : "添加页面") { acceptCapture() }
                                .buttonStyle(.borderedProminent).tint(.green)
                        }.padding(.bottom, 22)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black.ignoresSafeArea())
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("返回") { dismiss() }.foregroundStyle(.white) }
                ToolbarItem(placement: .topBarTrailing) {
                    if mode == .certificate {
                        HStack(spacing: 14) {
                            Toggle("自动裁边", isOn: $autoCropEnabled).labelsHidden().tint(.green)
                            Button("完成 (\(library.pages.count))") { dismiss() }.foregroundStyle(.white)
                        }
                    } else {
                        Button("完成 (\(library.pages.count))") { dismiss() }.foregroundStyle(.white)
                    }
                }
            }
            .toolbarBackground(.black.opacity(0.72), for: .navigationBar).toolbarBackground(.visible, for: .navigationBar)
            .onAppear { camera.start() }.onDisappear { camera.stop() }
            .onChange(of: camera.capturedImage) { _, image in
                guard let image else { return }
                capturedPreview = image
                camera.capturedImage = nil
            }
            .onChange(of: photoItems) { _, items in
                guard !items.isEmpty else { return }
                photoItems = []
                Task {
                    for item in items {
                        guard let data = try? await item.loadTransferable(type: Data.self),
                              let image = UIImage(data: data) else { continue }
                        await appendScannedImage(image)
                    }
                }
            }
            .alert("相机不可用", isPresented: Binding(get: { camera.errorMessage != nil }, set: { if !$0 { camera.errorMessage = nil } })) {
                Button("好") { camera.errorMessage = nil }
            } message: { Text(camera.errorMessage ?? "") }
        }
    }

    private func acceptCapture() {
        guard let image = capturedPreview else { return }
        capturedPreview = nil
        Task { await appendScannedImage(image) }
    }

    private func appendScannedImage(_ image: UIImage) async {
        let shouldCrop = autoCropEnabled
        let maxDimension = camera.resolution.maxDimension
        let processed = await Task.detached(priority: .userInitiated) {
            let cropped = shouldCrop ? ImagePipeline.autoCropDocument(image) : image
            return ImagePipeline.resize(cropped, maxDimension: maxDimension)
        }.value
        library.add(processed)
        if mode == .certificate { isBackSide.toggle() }
    }
}

struct GuideOverlay: View {
    let aspect: CGFloat
    var body: some View {
        GeometryReader { proxy in
            let width = min(proxy.size.width, proxy.size.height * aspect)
            let height = width / max(0.35, aspect)
            RoundedRectangle(cornerRadius: 18).stroke(.white, lineWidth: 2)
                .frame(width: width, height: height)
                .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
                .shadow(color: .black.opacity(0.4), radius: 2)
        }
    }
}

struct CertificateTemplateGuide: View {
    let template: CertificateTemplate

    var body: some View {
        GeometryReader { proxy in
            let availableHeight = max(100, proxy.size.height - 84)
            let frameWidth = min(proxy.size.width * 0.92,
                                 min(300, min(220 * template.aspect, availableHeight * template.aspect)))
            let frameHeight = frameWidth / template.aspect
            let frame = CGRect(x: (proxy.size.width - frameWidth) / 2,
                               y: (proxy.size.height - frameHeight) / 2,
                               width: frameWidth, height: frameHeight)
            Canvas { context, _ in
                drawCertificateArtwork(template, in: frame, context: &context)
            }
        }
        .frame(height: 340)
        .accessibilityLabel("\(template.title)证件取景框")
    }
}

private func drawCertificateArtwork(_ template: CertificateTemplate, in rect: CGRect,
                                    context: inout GraphicsContext) {
    var path = Path()
    let width = rect.width
    let height = rect.height
    let shortSide = min(width, height)
    let detailWidth = max(1.2, shortSide * 0.012)
    let cardRadius = shortSide * 0.055

    func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
        CGPoint(x: rect.minX + x * width, y: rect.minY + y * height)
    }
    func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) {
        path.move(to: point(x1, y1)); path.addLine(to: point(x2, y2))
    }
    func outline(_ left: CGFloat, _ top: CGFloat, _ right: CGFloat, _ bottom: CGFloat, radius: CGFloat = 0) {
        let shape = CGRect(x: rect.minX + left * width, y: rect.minY + top * height,
                           width: (right - left) * width, height: (bottom - top) * height)
        path.addRoundedRect(in: shape, cornerSize: CGSize(width: radius, height: radius))
    }
    func rows(_ left: CGFloat, _ top: CGFloat, _ right: CGFloat, _ count: Int, _ bottom: CGFloat) {
        guard count > 0 else { return }
        let gap = (bottom - top) / CGFloat(count)
        for index in 0..<count {
            let y = top + gap * (CGFloat(index) + 0.5)
            line(left, y, right, y)
        }
    }

    switch template.id {
    case "single-card":
        outline(0.07, 0.19, 0.30, 0.81, radius: cardRadius * 0.3)
        line(0.39, 0.29, 0.91, 0.29); rows(0.39, 0.37, 0.86, 4, 0.76)
    case "id-card":
        outline(0.09, 0.31, 0.25, 0.54, radius: shortSide * 0.012)
        outline(0.75, 0.20, 0.91, 0.80, radius: shortSide * 0.012)
        line(0.32, 0.27, 0.68, 0.27); rows(0.32, 0.34, 0.68, 4, 0.75)
    case "double-card":
        line(0.5, 0.06, 0.5, 0.94)
        outline(0.025, 0.10, 0.475, 0.90, radius: cardRadius * 0.55)
        outline(0.525, 0.10, 0.975, 0.90, radius: cardRadius * 0.55)
        outline(0.06, 0.27, 0.17, 0.73, radius: shortSide * 0.01)
        outline(0.56, 0.27, 0.67, 0.73, radius: shortSide * 0.01)
        rows(0.21, 0.30, 0.44, 4, 0.72); rows(0.71, 0.30, 0.94, 4, 0.72)
    case "household":
        line(0.12, 0.19, 0.88, 0.19); rows(0.12, 0.25, 0.88, 6, 0.84)
        line(0.38, 0.35, 0.38, 0.84); line(0.67, 0.35, 0.67, 0.84)
    case "household-double":
        line(0.5, 0.07, 0.5, 0.93); line(0.08, 0.20, 0.92, 0.20)
        rows(0.08, 0.26, 0.46, 5, 0.88); rows(0.54, 0.26, 0.92, 5, 0.88)
        line(0.26, 0.36, 0.26, 0.88); line(0.74, 0.36, 0.74, 0.88)
    case "business-license":
        line(0.17, 0.13, 0.83, 0.13); line(0.25, 0.20, 0.75, 0.20)
        rows(0.15, 0.27, 0.84, 6, 0.73); outline(0.60, 0.75, 0.83, 0.92, radius: shortSide * 0.06)
    case "passport":
        line(0.5, 0.08, 0.5, 0.92); outline(0.08, 0.18, 0.38, 0.72, radius: shortSide * 0.015)
        rows(0.58, 0.20, 0.91, 5, 0.63); line(0.08, 0.78, 0.92, 0.78)
        line(0.08, 0.84, 0.92, 0.84); line(0.08, 0.90, 0.82, 0.90)
    case "driver-license":
        outline(0.08, 0.20, 0.30, 0.80, radius: shortSide * 0.015)
        line(0.38, 0.24, 0.89, 0.24); rows(0.38, 0.31, 0.88, 5, 0.77)
        line(0.61, 0.43, 0.61, 0.77)
    case "vehicle-license":
        line(0.09, 0.20, 0.91, 0.20); line(0.09, 0.36, 0.91, 0.36)
        line(0.09, 0.56, 0.91, 0.56); line(0.09, 0.77, 0.91, 0.77)
        line(0.34, 0.20, 0.34, 0.77); line(0.66, 0.20, 0.66, 0.77)
    default:
        outline(0.06, 0.06, 0.94, 0.94, radius: cardRadius)
    }
    context.stroke(path, with: .color(.white.opacity(0.82)), lineWidth: detailWidth)
}

struct CertificateScanView: View {
    var body: some View { ScanCameraView(mode: .certificate) }
}

struct ScanEditorView: View {
    @EnvironmentObject private var library: ScanLibrary
    @State private var selectedID: UUID?
    @State private var filter = ScanFilter.automatic
    @State private var showCrop = false
    @State private var showOCR = false
    @State private var recognizedText = ""
    @State private var shareFile: ShareFile?
    @State private var errorText: String?
    @State private var exportFilename = ""
    @AppStorage("defaultFilter") private var defaultFilterRaw = ScanFilter.color.rawValue
    @AppStorage("removeShadowsEnabled") private var removeShadowsEnabled = true
    @AppStorage("exportFormat") private var exportFormat = "PDF"
    @State private var previewImage: UIImage?
    private var current: ScanPage? { library.pages.first(where: { $0.id == selectedID }) ?? library.pages.first }
    private var previewTaskID: String {
        guard let current else { return "none" }
        return "\(current.id)-\(ObjectIdentifier(current.image).hashValue)-\(filter.rawValue)-\(removeShadowsEnabled)"
    }

    var body: some View {
        NavigationStack {
            Group {
                if library.pages.isEmpty {
                    ContentUnavailableView("还没有页面", systemImage: "doc.viewfinder", description: Text("先拍摄或导入图片。"))
                } else if let current {
                    VStack(spacing: 12) {
                        Group {
                            if let previewImage {
                                Image(uiImage: previewImage).resizable().scaledToFit()
                            } else {
                                ProgressView("正在处理预览…")
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color(uiColor: .secondarySystemBackground))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(Array(library.pages.enumerated()), id: \.element.id) { index, page in
                                    Button {
                                        selectedID = page.id
                                        filter = page.filter
                                    } label: {
                                        VStack {
                                            Image(uiImage: page.image).resizable().scaledToFill().frame(width: 70, height: 84).clipped()
                                            Text("\(index + 1)").font(.caption)
                                        }.padding(5).background(selectedID == page.id ? Color.accentColor.opacity(0.2) : .clear,
                                                               in: RoundedRectangle(cornerRadius: 10))
                                    }.buttonStyle(.plain)
                                }
                            }.padding(.horizontal)
                        }
                        HStack {
                            Button { moveCurrent(by: -1) } label: { Image(systemName: "chevron.left") }
                                .disabled(currentIndex == 0)
                            Text("第 \(currentIndex + 1) / \(library.pages.count) 页").font(.caption)
                            Button { moveCurrent(by: 1) } label: { Image(systemName: "chevron.right") }
                                .disabled(currentIndex >= library.pages.count - 1)
                            Spacer()
                            Button(role: .destructive) { removeCurrent() } label: {
                                Label("删除本页", systemImage: "trash")
                            }
                        }.buttonStyle(.bordered).padding(.horizontal)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(ScanFilter.allCases) { mode in
                                    Button(mode.rawValue) { filter = mode }
                                        .buttonStyle(.bordered)
                                        .tint(filter == mode ? .green : .secondary)
                                }
                            }.padding(.horizontal)
                        }
                        Toggle("去阴影", isOn: $removeShadowsEnabled).padding(.horizontal)
                        HStack {
                            Button { showCrop = true } label: { Label("手动裁边", systemImage: "crop.rotate") }
                            Spacer()
                            Button { rotate(current) } label: { Label("旋转", systemImage: "rotate.right") }
                            Spacer()
                            Button { Task { await runOCR(current) } } label: { Label("OCR", systemImage: "text.viewfinder") }
                        }.buttonStyle(.bordered).padding(.horizontal)
                        HStack {
                            TextField("导出文件名（不含扩展名）", text: $exportFilename)
                                .textFieldStyle(.roundedBorder)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .padding(.horizontal)
                        }
                        HStack {
                            Button("导出 \(exportFormat)") {
                                if exportFormat == "JPEG" { exportJPEG() } else { exportPDF() }
                            }.buttonStyle(.borderedProminent)
                            Button("打印") { printPDF() }.buttonStyle(.bordered)
                        }.padding(.bottom, 8)
                    }.padding(.top, 8)
                }
            }
            .navigationTitle("页面编辑").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("清空") { library.clear(); selectedID = nil } } }
            .onAppear {
                filter = current?.filter ?? ScanFilter(rawValue: defaultFilterRaw) ?? .automatic
            }
            .onChange(of: selectedID) { _, _ in
                if let current { filter = current.filter }
            }
            .onChange(of: filter) { _, selectedFilter in
                guard let current, current.filter != selectedFilter else { return }
                library.update(current.id) { $0.filter = selectedFilter }
            }
            .task(id: previewTaskID) {
                guard let current else { previewImage = nil; return }
                let source = current.image
                let selectedFilter = filter
                let shouldRemoveShadows = removeShadowsEnabled
                let result = await Task.detached(priority: .userInitiated) {
                    let filtered = ImagePipeline.filtered(source, mode: selectedFilter)
                    return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                }.value
                previewImage = result
            }
            .sheet(isPresented: $showCrop) { if let current { ManualCropView(page: current) } }
            .sheet(isPresented: $showOCR) {
                NavigationStack {
                    ScrollView { Text(recognizedText).frame(maxWidth: .infinity, alignment: .leading).padding() }
                        .navigationTitle("文字识别")
                        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { showOCR = false } } }
                }
            }
            .sheet(item: $shareFile) { ShareSheet(urls: $0.urls) }
            .alert("处理失败", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("好") { errorText = nil }
            } message: { Text(errorText ?? "") }
        }
    }
    private var currentIndex: Int {
        guard let selectedID, let index = library.pages.firstIndex(where: { $0.id == selectedID }) else { return 0 }
        return index
    }
    private func moveCurrent(by offset: Int) {
        let from = currentIndex
        let to = from + offset
        guard library.pages.indices.contains(from), library.pages.indices.contains(to) else { return }
        let page = library.pages.remove(at: from)
        library.pages.insert(page, at: to)
        selectedID = page.id
    }
    private func removeCurrent() {
        guard library.pages.indices.contains(currentIndex) else { return }
        let page = library.pages[currentIndex]
        library.remove(page.id)
        selectedID = library.pages.indices.contains(currentIndex) ? library.pages[currentIndex].id : nil
    }
    private func rotate(_ page: ScanPage) {
        library.update(page.id) { $0.image = ImagePipeline.rotated($0.image, clockwiseDegrees: 90); $0.original = $0.image }
    }
    private func runOCR(_ page: ScanPage) async {
        do {
            let language = UserDefaults.standard.string(forKey: "ocrLanguage") ?? "zh-Hans"
            recognizedText = try await ImagePipeline.recognizeText(page.image, language: language)
            showOCR = true
        } catch { errorText = error.localizedDescription }
    }
    private func exportPDF() {
        let source = library.pages.map { ($0.image, $0.filter) }
        let shouldRemoveShadows = removeShadowsEnabled
        let language = UserDefaults.standard.string(forKey: "ocrLanguage") ?? "zh-Hans"
        let filename = exportFilename.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : exportFilename
        Task {
            do {
                let processed = await Task.detached(priority: .userInitiated) {
                    source.map { page in
                        let filtered = ImagePipeline.filtered(page.0, mode: page.1)
                        return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                    }
                }.value
                var recognizedPages = [[RecognizedTextLine]]()
                for image in processed {
                    do {
                        recognizedPages.append(try await ImagePipeline.recognizeTextLines(image, language: language))
                    } catch {
                        NSLog("FreeScan export OCR failed for a page: %@", error.localizedDescription as NSString)
                        recognizedPages.append([])
                    }
                }
                shareFile = ShareFile(url: try PDFServices.makePDF(
                    from: processed, searchableText: recognizedPages, filename: filename))
            } catch { errorText = error.localizedDescription }
        }
    }
    private func exportJPEG() {
        let source = library.pages.map { ($0.image, $0.filter) }
        let shouldRemoveShadows = removeShadowsEnabled
        let filename = exportFilename.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : exportFilename
        Task {
            do {
                let processed = await Task.detached(priority: .userInitiated) {
                    source.map { page in
                        let filtered = ImagePipeline.filtered(page.0, mode: page.1)
                        return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                    }
                }.value
                shareFile = ShareFile(urls: try PDFServices.makeJPEGs(from: processed, baseName: filename))
            } catch { errorText = error.localizedDescription }
        }
    }
    private func printPDF() {
        let source = library.pages.map { ($0.image, $0.filter) }
        let shouldRemoveShadows = removeShadowsEnabled
        let filename = exportFilename.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : exportFilename
        Task {
            do {
                let processed = await Task.detached(priority: .userInitiated) {
                    source.map { page in
                        let filtered = ImagePipeline.filtered(page.0, mode: page.1)
                        return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                    }
                }.value
                PDFServices.printPDF(at: try PDFServices.makePDF(from: processed, filename: filename))
            } catch { errorText = error.localizedDescription }
        }
    }
}

struct ShareFile: Identifiable {
    let urls: [URL]
    init(url: URL) { urls = [url] }
    init(urls: [URL]) { self.urls = urls }
    var id: String { urls.map(\.absoluteString).joined(separator: "|") }
}

struct ShareSheet: UIViewControllerRepresentable {
    let urls: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: urls, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ManualCropView: View {
    @EnvironmentObject private var library: ScanLibrary
    @Environment(\.dismiss) private var dismiss
    let page: ScanPage
    @State private var corners = [
        CGPoint(x: 0.03, y: 0.03), CGPoint(x: 0.97, y: 0.03),
        CGPoint(x: 0.97, y: 0.97), CGPoint(x: 0.03, y: 0.97),
    ]
    @State private var activeCorner: Int?
    @State private var dragStart: CGPoint?
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text("拖动四个绿色控制点，对齐纸张四角")
                    .font(.footnote).foregroundStyle(.secondary)
                GeometryReader { proxy in
                    let fitted = AVMakeRect(aspectRatio: page.image.size,
                                            insideRect: CGRect(origin: .zero, size: proxy.size))
                    ZStack {
                        Image(uiImage: page.image).resizable().scaledToFit()
                        Canvas { context, _ in
                            guard corners.count == 4, let first = corners.first else { return }
                            func screenPoint(_ point: CGPoint) -> CGPoint {
                                CGPoint(x: fitted.minX + point.x * fitted.width,
                                        y: fitted.minY + point.y * fitted.height)
                            }
                            var path = Path()
                            path.move(to: screenPoint(first))
                            corners.dropFirst().forEach { path.addLine(to: screenPoint($0)) }
                            path.closeSubpath()
                            context.stroke(path, with: .color(.green), lineWidth: 3)
                        }
                        ForEach(corners.indices, id: \.self) { index in
                            Circle()
                                .fill(Color.green)
                                .overlay(Circle().stroke(.white, lineWidth: 2))
                                .frame(width: 28, height: 28)
                                .position(x: fitted.minX + corners[index].x * fitted.width,
                                          y: fitted.minY + corners[index].y * fitted.height)
                                .gesture(DragGesture(minimumDistance: 0)
                                    .onChanged { value in
                                        if activeCorner != index {
                                            activeCorner = index
                                            dragStart = corners[index]
                                        }
                                        guard let dragStart else { return }
                                        corners[index] = CGPoint(
                                            x: min(1, max(0, dragStart.x + value.translation.width / max(1, fitted.width))),
                                            y: min(1, max(0, dragStart.y + value.translation.height / max(1, fitted.height)))
                                        )
                                    }
                                    .onEnded { _ in
                                        activeCorner = nil
                                        dragStart = nil
                                    })
                        }
                    }
                }.frame(maxHeight: 360)
                Spacer()
            }.padding().navigationTitle("手动裁边")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("应用") {
                            let quad = DocumentQuad(topLeft: corners[0], topRight: corners[1],
                                                    bottomRight: corners[2], bottomLeft: corners[3])
                            let cropped = ImagePipeline.perspectiveCrop(page.image, quad: quad)
                            library.update(page.id) { $0.image = cropped; $0.original = cropped }
                            dismiss()
                        }
                    }
                }
        }
    }
}

/// Drag a box over a mark, then repair only the selected normalized image region.
struct ManualRepairView: View {
    @Environment(\.dismiss) private var dismiss
    let image: UIImage
    let onApply: (UIImage) -> Void
    @State private var regions: [CGRect] = []
    @State private var currentRegion: CGRect?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text("在要修复的笔迹或水印上拖动框选")
                    .font(.footnote).foregroundStyle(.secondary)
                GeometryReader { proxy in
                    let fitted = AVMakeRect(aspectRatio: image.size, insideRect: CGRect(origin: .zero, size: proxy.size))
                    ZStack(alignment: .topLeading) {
                        Color.black
                        Image(uiImage: image).resizable().scaledToFit()
                        ForEach(regions.indices, id: \.self) { index in
                            selectionBorder(regions[index], fitted: fitted)
                        }
                        if let currentRegion {
                            selectionBorder(currentRegion, fitted: fitted)
                        }
                    }
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 4)
                        .onChanged { value in
                            let start = normalized(value.startLocation, inside: fitted)
                            let end = normalized(value.location, inside: fitted)
                            currentRegion = CGRect(
                                x: min(start.x, end.x), y: min(start.y, end.y),
                                width: abs(end.x - start.x), height: abs(end.y - start.y)
                            )
                        }
                        .onEnded { _ in
                            if let currentRegion, currentRegion.width > 0.002, currentRegion.height > 0.002 {
                                regions.append(currentRegion)
                            }
                            currentRegion = nil
                        })
                }
                .frame(maxHeight: .infinity)
                HStack {
                    Button("撤销框选") { if !regions.isEmpty { regions.removeLast() } }
                        .disabled(regions.isEmpty)
                    Text("已选 \(regions.count) 个区域").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("清空") { regions.removeAll() }.disabled(regions.isEmpty)
                }
            }
            .padding()
            .navigationTitle("手动框选修复")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("修复") {
                        guard !regions.isEmpty else { return }
                        onApply(ImagePipeline.repair(image, regions: regions))
                        dismiss()
                    }.disabled(regions.isEmpty)
                }
            }
        }
    }

    private func normalized(_ point: CGPoint, inside rect: CGRect) -> CGPoint {
        CGPoint(
            x: min(1, max(0, (point.x - rect.minX) / max(1, rect.width))),
            y: min(1, max(0, (point.y - rect.minY) / max(1, rect.height)))
        )
    }

    private func selectionBorder(_ region: CGRect, fitted: CGRect) -> some View {
        Rectangle().stroke(.green, lineWidth: 3)
            .frame(width: fitted.width * region.width, height: fitted.height * region.height)
            .offset(x: fitted.minX + fitted.width * region.minX,
                    y: fitted.minY + fitted.height * region.minY)
    }
}

struct BrushEraserView: View {
    @Environment(\.dismiss) private var dismiss
    let image: UIImage
    let onApply: (UIImage) -> Void
    @State private var strokes: [LocalRepairStroke] = []
    @State private var redoStrokes: [LocalRepairStroke] = []
    @State private var activePoints: [CGPoint] = []
    @State private var brushWidth: CGFloat = 0.022
    @State private var isWorking = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                GeometryReader { proxy in
                    let fitted = AVMakeRect(aspectRatio: image.size,
                                            insideRect: CGRect(origin: .zero, size: proxy.size))
                    ZStack {
                        Image(uiImage: image).resizable().scaledToFit()
                        Canvas { context, _ in
                            for stroke in strokes + [LocalRepairStroke(points: activePoints, width: brushWidth)] {
                                guard let first = stroke.points.first else { continue }
                                var path = Path()
                                let point = CGPoint(x: fitted.minX + first.x * fitted.width,
                                                    y: fitted.minY + first.y * fitted.height)
                                path.move(to: point)
                                if stroke.points.count == 1 {
                                    path.addLine(to: CGPoint(x: point.x + 0.1, y: point.y + 0.1))
                                } else {
                                    for normalized in stroke.points.dropFirst() {
                                        path.addLine(to: CGPoint(x: fitted.minX + normalized.x * fitted.width,
                                                                 y: fitted.minY + normalized.y * fitted.height))
                                    }
                                }
                                let lineWidth = max(3, stroke.width * min(fitted.width, fitted.height))
                                context.stroke(path, with: .color(.red.opacity(0.8)),
                                               style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                            }
                        }
                    }
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .local)
                            .onChanged { value in
                                guard let point = normalized(value.location, in: proxy.size) else { return }
                                if activePoints.isEmpty || hypot(activePoints.last!.x - point.x,
                                                                  activePoints.last!.y - point.y) > 0.001 {
                                    activePoints.append(point)
                                }
                            }
                            .onEnded { _ in
                                if !activePoints.isEmpty {
                                    strokes.append(LocalRepairStroke(points: activePoints, width: brushWidth))
                                    redoStrokes.removeAll()
                                    activePoints.removeAll()
                                }
                            }
                    )
                }
                Text("涂抹需要修复的笔迹；修复使用本地纸张邻域估色。")
                    .font(.footnote).foregroundStyle(.secondary)
                HStack {
                    Text("笔刷")
                    Slider(value: $brushWidth, in: 0.008...0.065)
                    Text("\(Int(brushWidth * 1000))‰").monospacedDigit().frame(width: 52, alignment: .trailing)
                }
                HStack {
                    Button("撤销", systemImage: "arrow.uturn.backward") {
                        if let stroke = strokes.popLast() { redoStrokes.append(stroke) }
                    }.disabled(strokes.isEmpty || isWorking)
                    Button("重做", systemImage: "arrow.uturn.forward") {
                        if let stroke = redoStrokes.popLast() { strokes.append(stroke) }
                    }.disabled(redoStrokes.isEmpty || isWorking)
                    Button("清除", systemImage: "trash") {
                        strokes.removeAll(); redoStrokes.removeAll(); activePoints.removeAll()
                    }.disabled(strokes.isEmpty && activePoints.isEmpty || isWorking)
                    Spacer()
                    Button(isWorking ? "修复中…" : "应用修复") { applyRepair() }
                        .buttonStyle(.borderedProminent).disabled(strokes.isEmpty || isWorking)
                }
                .buttonStyle(.bordered)
            }
            .padding()
            .navigationTitle("手动涂抹修复")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("取消") { dismiss() } } }
        }
    }

    private func normalized(_ point: CGPoint, in size: CGSize) -> CGPoint? {
        let fitted = AVMakeRect(aspectRatio: image.size, insideRect: CGRect(origin: .zero, size: size))
        guard fitted.contains(point), fitted.width > 0, fitted.height > 0 else { return nil }
        return CGPoint(x: min(1, max(0, (point.x - fitted.minX) / fitted.width)),
                       y: min(1, max(0, (point.y - fitted.minY) / fitted.height)))
    }

    private func applyRepair() {
        guard !strokes.isEmpty else { return }
        isWorking = true
        let source = image
        let selectedStrokes = strokes
        Task {
            let repaired = await Task.detached(priority: .userInitiated) {
                ImagePipeline.repair(source, strokes: selectedStrokes)
            }.value
            onApply(repaired)
            isWorking = false
            dismiss()
        }
    }
}

struct DocumentsView: View {
    @EnvironmentObject private var library: ScanLibrary
    @State private var showImporter = false
    @State private var showEditor = false
    @State private var searchText = ""
    @State private var errorText: String?
    @State private var shareFile: ShareFile?
    @State private var pdfPendingDeletion: URL?
    @State private var showDeleteConfirmation = false
    @State private var pdfPendingRename: URL?
    @State private var renameText = ""
    @State private var showRenameDialog = false
    @State private var libraryRevision = 0
    private var savedPDFs: [URL] {
        _ = libraryRevision
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?
            .filter { $0.pathExtension.lowercased() == "pdf" }
            .sorted { lhs, rhs in
                let keys: Set<URLResourceKey> = [.contentModificationDateKey]
                let lhsDate = (try? lhs.resourceValues(forKeys: keys).contentModificationDate) ?? .distantPast
                let rhsDate = (try? rhs.resourceValues(forKeys: keys).contentModificationDate) ?? .distantPast
                if lhsDate != rhsDate { return lhsDate > rhsDate }
                return lhs.lastPathComponent.localizedStandardCompare(rhs.lastPathComponent) == .orderedAscending
            } ?? []
    }
    private func metadataText(for url: URL) -> String {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
        let values = try? url.resourceValues(forKeys: keys)
        let size = Int64(values?.fileSize ?? 0)
        let date = values?.contentModificationDate?.formatted(date: .abbreviated, time: .shortened) ?? "日期未知"
        return "\(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)) · \(date)"
    }
    private var visiblePDFs: [URL] {
        guard !searchText.isEmpty else { return savedPDFs }
        return savedPDFs.filter { $0.deletingPathExtension().lastPathComponent.localizedCaseInsensitiveContains(searchText) }
    }
    var body: some View {
        NavigationStack {
            List {
                Section("当前扫描") {
                    NavigationLink(destination: ScanEditorView()) {
                        Label("编辑当前扫描 · \(library.pages.count) 页", systemImage: "square.stack.3d.up")
                    }
                }
                Section("最近导出 PDF") {
                    if visiblePDFs.isEmpty { Text(searchText.isEmpty ? "导出的文件会显示在这里。" : "没有匹配的 PDF。")
                        .foregroundStyle(.secondary) }
                    ForEach(visiblePDFs, id: \.absoluteString) { url in
                        HStack {
                            Image(systemName: "doc.richtext")
                            VStack(alignment: .leading, spacing: 3) {
                                Text(url.lastPathComponent).lineLimit(1)
                                Text(metadataText(for: url)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            Button("打开") { open(url) }
                            Menu {
                                Button("打印", systemImage: "printer") { PDFServices.printPDF(at: url) }
                                Button("共享", systemImage: "square.and.arrow.up") { shareFile = ShareFile(url: url) }
                                Button("重命名", systemImage: "pencil") {
                                    pdfPendingRename = url
                                    renameText = url.deletingPathExtension().lastPathComponent
                                    showRenameDialog = true
                                }
                                Button("删除", systemImage: "trash", role: .destructive) {
                                    pdfPendingDeletion = url
                                    showDeleteConfirmation = true
                                }
                            } label: { Image(systemName: "ellipsis.circle") }
                        }
                    }
                }
                Section { Button { showImporter = true } label: { Label("导入 PDF 到文档库", systemImage: "folder.badge.plus") } }
            }
            .navigationTitle("文档")
            .searchable(text: $searchText, prompt: "搜索 PDF")
            .navigationDestination(isPresented: $showEditor) { ScanEditorView() }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.pdf], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                do {
                    let imported = try urls.map { try PDFServices.importIntoLibrary(from: $0) }
                    libraryRevision += 1
                    if let first = imported.first { open(first) }
                } catch { errorText = error.localizedDescription }
            }
            .alert("无法打开文件", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("好") { errorText = nil }
            } message: { Text(errorText ?? "") }
            .confirmationDialog("删除这份 PDF？", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
                Button("删除", role: .destructive) { deletePendingPDF() }
                Button("取消", role: .cancel) { pdfPendingDeletion = nil }
            }
            .alert("重命名 PDF", isPresented: $showRenameDialog) {
                TextField("文件名", text: $renameText)
                Button("保存") { renamePendingPDF() }
                Button("取消", role: .cancel) { pdfPendingRename = nil }
            }
            .sheet(item: $shareFile) { ShareSheet(urls: $0.urls) }
        }
    }
    private func open(_ url: URL) {
        do {
            let pages = try PDFServices.images(from: url)
            guard !pages.isEmpty else { throw NSError(domain: "FreeScan.PDF", code: 3,
                userInfo: [NSLocalizedDescriptionKey: "PDF 中没有可编辑页面。"]) }
            library.clear()
            library.add(contentsOf: pages)
            showEditor = true
        }
        catch { errorText = error.localizedDescription }
    }
    private func renamePendingPDF() {
        guard let source = pdfPendingRename else { return }
        let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !name.contains("/"), !name.contains("\\") else {
            errorText = "请输入有效的文件名。"
            return
        }
        let target = source.deletingLastPathComponent().appendingPathComponent(name).appendingPathExtension("pdf")
        guard target != source else { pdfPendingRename = nil; return }
        guard !FileManager.default.fileExists(atPath: target.path) else {
            errorText = "已有同名文件，请换一个名称。"
            return
        }
        do { try FileManager.default.moveItem(at: source, to: target) }
        catch { errorText = error.localizedDescription }
        pdfPendingRename = nil
    }
    private func deletePendingPDF() {
        guard let url = pdfPendingDeletion else { return }
        do { try FileManager.default.removeItem(at: url) }
        catch { errorText = error.localizedDescription }
        pdfPendingDeletion = nil
    }
}

struct WatermarkToolView: View {
    @State private var item: PhotosPickerItem?
    @State private var images: [UIImage] = []
    @State private var selectedIndex = 0
    @State private var message: String?
    @State private var shareFile: ShareFile?
    @State private var showImporter = false
    @State private var showManualRepair = false
    @State private var showReview = false
    @State private var errorText: String?
    @AppStorage("exportFormat") private var exportFormat = "PDF"
    private var image: UIImage? { images.indices.contains(selectedIndex) ? images[selectedIndex] : nil }

    var body: some View {
        VStack(spacing: 12) {
            Text("二维码/水印修复").font(.title2.bold())
            Text("QR 自动检测、手动框选和 PDF 批量处理均在设备本地运行。")
                .font(.footnote).foregroundStyle(.secondary)
            if let image {
                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 420)
                if images.count > 1 {
                    HStack {
                        Button("上一页") { selectedIndex = max(0, selectedIndex - 1) }.disabled(selectedIndex == 0)
                        Text("\(selectedIndex + 1) / \(images.count)").font(.caption)
                        Button("下一页") { selectedIndex = min(images.count - 1, selectedIndex + 1) }
                            .disabled(selectedIndex == images.count - 1)
                    }
                }
            } else {
                ContentUnavailableView("选择图片或 PDF", systemImage: "qrcode.viewfinder")
            }
            HStack {
                PhotosPicker("选择图片", selection: $item, matching: .images).buttonStyle(.bordered)
                Button("导入 PDF/图片") { showImporter = true }.buttonStyle(.bordered)
            }
            if image != nil {
                Button("自动识别并修复 QR（全部页面）", action: repairQRs)
                    .buttonStyle(.borderedProminent)
                Button("复核并选择区域（全部页面）") { showReview = true }
                    .buttonStyle(.bordered)
                Button("手动框选修复") { showManualRepair = true }.buttonStyle(.bordered)
                HStack {
                    Button("导出 \(exportFormat)") {
                        if exportFormat == "JPEG" { exportJPEG() } else { exportPDF() }
                    }.buttonStyle(.bordered)
                }
            }
            if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            Spacer()
        }
        .padding()
        .navigationTitle("二维码/水印")
        .onChange(of: item) { _, value in
            guard let value else { return }
            Task {
                if let data = try? await value.loadTransferable(type: Data.self), let loaded = UIImage(data: data) {
                    images = [loaded]; selectedIndex = 0; message = nil
                }
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.pdf, .image]) { result in
            guard case .success(let url) = result else { return }
            load(url)
        }
        .sheet(isPresented: $showManualRepair) {
            if let image {
                ManualRepairView(image: image) { repaired in
                    images[selectedIndex] = repaired
                    message = "已修复当前页选区。"
                }
            }
        }
        .sheet(isPresented: $showReview) {
            WatermarkReviewView(images: $images) { repairedCount in
                selectedIndex = 0
                message = "复核完成：修复了 \(repairedCount) 个区域。"
            }
        }
        .sheet(item: $shareFile) { ShareSheet(urls: $0.urls) }
        .alert("处理失败", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
            Button("好") { errorText = nil }
        } message: { Text(errorText ?? "") }
    }

    private func load(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            if url.pathExtension.lowercased() == "pdf" {
                images = try PDFServices.images(from: url)
            } else {
                guard let loaded = UIImage(contentsOfFile: url.path) else {
                    throw NSError(domain: "FreeScan.Image", code: 2,
                                  userInfo: [NSLocalizedDescriptionKey: "无法读取所选图片。"])
                }
                images = [loaded]
            }
            selectedIndex = 0
            message = nil
        } catch { errorText = error.localizedDescription }
    }

    private func repairQRs() {
        var repaired = 0
        images = images.map { page in
            let regions = ImagePipeline.qrRectangles(in: page)
            repaired += regions.count
            return regions.isEmpty ? page : ImagePipeline.repair(page, regions: regions)
        }
        message = repaired == 0 ? "没有检测到 QR 码，可改用手动框选。" : "已在 \(images.count) 页中修复 \(repaired) 个 QR 区域。"
    }

    private func exportPDF() {
        do { shareFile = ShareFile(url: try PDFServices.makePDF(from: images, title: "FreeScan QR 修复")) }
        catch { errorText = error.localizedDescription }
    }

    private func exportJPEG() {
        do { shareFile = ShareFile(urls: try PDFServices.makeJPEGs(from: images)) }
        catch { errorText = error.localizedDescription }
    }
}

private struct WatermarkReviewView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var images: [UIImage]
    let onComplete: (Int) -> Void

    @State private var candidates: [[QRDetectionCandidate]] = []
    @State private var selected = Set<UUID>()
    @State private var selectedIndex = 0
    @State private var isAddingManualRegion = false
    @State private var dragStart: CGPoint?
    @State private var dragEnd: CGPoint?
    @State private var isDetecting = true
    @State private var isRepairing = false

    private var page: UIImage? { images.indices.contains(selectedIndex) ? images[selectedIndex] : nil }
    private var currentCandidates: [QRDetectionCandidate] {
        candidates.indices.contains(selectedIndex) ? candidates[selectedIndex] : []
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                if let page {
                    Text("第 \(selectedIndex + 1) / \(images.count) 页 · 已选 \(selected.count) 个区域")
                        .font(.subheadline)
                    GeometryReader { geometry in
                        let fitted = fittedRect(imageSize: page.size, container: geometry.size)
                        ZStack(alignment: .topLeading) {
                            Color(uiColor: .secondarySystemBackground)
                            Image(uiImage: page)
                                .resizable().scaledToFit()
                                .frame(width: geometry.size.width, height: geometry.size.height)
                            ForEach(currentCandidates) { candidate in
                                let rect = screenRect(candidate.bounds, in: fitted)
                                RoundedRectangle(cornerRadius: 3)
                                    .stroke(selected.contains(candidate.id) ? Color.green : Color.orange,
                                            style: StrokeStyle(lineWidth: 2, dash: selected.contains(candidate.id) ? [] : [6, 4]))
                                    .frame(width: max(2, rect.width), height: max(2, rect.height))
                                    .position(x: rect.midX, y: rect.midY)
                            }
                            if let start = dragStart, let end = dragEnd {
                                let rect = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
                                                  width: abs(end.x - start.x), height: abs(end.y - start.y))
                                RoundedRectangle(cornerRadius: 3)
                                    .stroke(Color.blue, style: StrokeStyle(lineWidth: 2, dash: [5, 3]))
                                    .frame(width: max(2, rect.width), height: max(2, rect.height))
                                    .position(x: rect.midX, y: rect.midY)
                            }
                        }
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: isAddingManualRegion ? 6 : 0)
                            .onChanged { value in
                                guard isAddingManualRegion else { return }
                                let point = normalizedPoint(value.location, in: fitted)
                                if dragStart == nil { dragStart = point }
                                dragEnd = point
                            }
                            .onEnded { value in
                                defer { dragStart = nil; dragEnd = nil }
                                if isAddingManualRegion {
                                    let start = dragStart ?? normalizedPoint(value.startLocation, in: fitted)
                                    let end = normalizedPoint(value.location, in: fitted)
                                    let rect = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
                                                      width: abs(end.x - start.x), height: abs(end.y - start.y))
                                    guard rect.width * page.size.width >= 8,
                                          rect.height * page.size.height >= 8 else { return }
                                    let candidate = QRDetectionCandidate(bounds: rect, kind: .manual,
                                                                         automaticallyRepairable: false)
                                    candidates[selectedIndex].append(candidate)
                                    selected.insert(candidate.id)
                                    isAddingManualRegion = false
                                } else {
                                    let point = normalizedPoint(value.location, in: fitted)
                                    if let candidate = currentCandidates.reversed().first(where: { $0.bounds.contains(point) }) {
                                        if selected.contains(candidate.id) { selected.remove(candidate.id) }
                                        else { selected.insert(candidate.id) }
                                    }
                                }
                            })
                    }
                    .overlay(alignment: .bottom) {
                        Text(isAddingManualRegion ? "拖动框出手动修复区域" : "绿色区域将修复；橙色候选点按后纳入修复")
                            .font(.caption).padding(8)
                            .background(.regularMaterial, in: Capsule()).padding(8)
                    }

                    if isDetecting {
                        ProgressView("正在逐页识别二维码…")
                    } else if currentCandidates.isEmpty {
                        Text("本页没有自动候选；可添加手动选区或跳过。")
                            .font(.caption).foregroundStyle(.secondary)
                    }

                    HStack {
                        Button("全选本页") { selected.formUnion(currentCandidates.map(\.id)) }
                        Button("清空本页") { selected.subtract(currentCandidates.map(\.id)) }
                        Button(isAddingManualRegion ? "取消手动框选" : "添加手动选区") {
                            isAddingManualRegion.toggle()
                        }
                    }.buttonStyle(.bordered).disabled(isDetecting || isRepairing)

                    HStack {
                        Button("上一页") { selectedIndex = max(0, selectedIndex - 1) }
                            .disabled(selectedIndex == 0 || isRepairing)
                        Spacer()
                        Button(selectedIndex == images.count - 1 ? "修复所选区域" : "下一页") {
                            if selectedIndex < images.count - 1 {
                                selectedIndex += 1
                                isAddingManualRegion = false
                            } else {
                                applySelection()
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isDetecting || isRepairing)
                    }
                } else {
                    ContentUnavailableView("没有可复核页面", systemImage: "qrcode")
                }
            }
            .padding()
            .navigationTitle("复核二维码/水印")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("取消") { dismiss() } } }
            .task { await detectCandidates() }
        }
    }

    private func detectCandidates() async {
        let snapshot = images
        let found = await Task.detached(priority: .userInitiated) {
            snapshot.map { ImagePipeline.qrCandidates(in: $0) }
        }.value
        candidates = found
        selected = Set(found.flatMap { $0 }.filter(\.automaticallyRepairable).map(\.id))
        isDetecting = false
    }

    private func applySelection() {
        let snapshot = images
        let regions = snapshot.indices.map { index in
            candidates.indices.contains(index)
                ? candidates[index].filter { selected.contains($0.id) }.map(\.bounds)
                : []
        }
        let count = regions.reduce(0) { $0 + $1.count }
        isRepairing = true
        Task {
            let repaired = await Task.detached(priority: .userInitiated) {
                snapshot.enumerated().map { index, image in
                    regions[index].isEmpty ? image : ImagePipeline.repair(image, regions: regions[index])
                }
            }.value
            images = repaired
            onComplete(count)
            dismiss()
        }
    }

    private func fittedRect(imageSize: CGSize, container: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return .zero }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        return CGRect(x: (container.width - size.width) / 2, y: (container.height - size.height) / 2,
                      width: size.width, height: size.height)
    }

    private func screenRect(_ rect: CGRect, in imageRect: CGRect) -> CGRect {
        CGRect(x: imageRect.minX + rect.minX * imageRect.width,
               y: imageRect.minY + rect.minY * imageRect.height,
               width: rect.width * imageRect.width,
               height: rect.height * imageRect.height)
    }

    private func normalizedPoint(_ point: CGPoint, in imageRect: CGRect) -> CGPoint {
        CGPoint(x: ((point.x - imageRect.minX) / max(1, imageRect.width)).clamped(to: 0...1),
                y: ((point.y - imageRect.minY) / max(1, imageRect.height)).clamped(to: 0...1))
    }
}

private extension CGFloat {
    func clamped(to range: ClosedRange<CGFloat>) -> CGFloat { min(max(self, range.lowerBound), range.upperBound) }
}

struct HandwritingToolView: View {
    @State private var item: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var original: UIImage?
    @State private var shareFile: ShareFile?
    @State private var showManualRepair = false
    @State private var showingOriginal = false
    var body: some View {
        VStack(spacing: 14) {
            Text("试卷擦除").font(.title2.bold())
            Text("本地彩色笔迹自动处理；残留的彩笔或黑色笔迹可手动涂抹修复。")
                .font(.footnote).foregroundStyle(.secondary)
            if let image {
                Image(uiImage: showingOriginal ? (original ?? image) : image)
                    .resizable().scaledToFit().frame(maxHeight: 460)
                Button(showingOriginal ? "查看修复结果" : "对比原图") { showingOriginal.toggle() }
                    .buttonStyle(.bordered)
            }
            else { ContentUnavailableView("选择试卷图片", systemImage: "pencil.tip.crop.circle") }
            PhotosPicker("选择图片", selection: $item, matching: .images).buttonStyle(.bordered)
            if let original {
                Button("自动擦除彩色笔迹") { image = ImagePipeline.removeColoredInk(original); showingOriginal = false }
                    .buttonStyle(.borderedProminent)
                Button("手动涂抹修复") { showManualRepair = true }.buttonStyle(.bordered)
                Button("恢复原图") { image = original; showingOriginal = false }
                Button("导出修复结果") {
                    if let image, let url = try? PDFServices.makeJPEGs(from: [image]).first {
                        shareFile = ShareFile(url: url)
                    }
                }
                    .buttonStyle(.bordered)
            }
            Spacer()
        }.padding().navigationTitle("试卷擦除")
            .onChange(of: item) { _, value in
                guard let value else { return }
                Task {
                    if let data = try? await value.loadTransferable(type: Data.self), let loaded = UIImage(data: data) {
                        original = loaded; image = loaded; showingOriginal = false
                    }
                }
            }
            .sheet(item: $shareFile) { ShareSheet(urls: $0.urls) }
            .sheet(isPresented: $showManualRepair) {
                if let image {
                    BrushEraserView(image: image) { repaired in self.image = repaired; showingOriginal = false }
                }
            }
    }
}

struct SettingsView: View {
    @AppStorage("autoCropEnabled") private var autoCropEnabled = true
    @AppStorage("removeShadowsEnabled") private var removeShadowsEnabled = true
    @AppStorage("defaultFilter") private var defaultFilter = ScanFilter.automatic.rawValue
    @AppStorage("ocrLanguage") private var ocrLanguage = "zh-Hans"
    @AppStorage("exportQuality") private var exportQuality = 0.92
    @AppStorage("scanResolution") private var scanResolution = CameraResolutionPreset.fourK.rawValue
    @AppStorage("exportFormat") private var exportFormat = "PDF"
    @AppStorage("exportFolderDisplayName") private var exportFolderDisplayName = ""
    @State private var showExportFolderPicker = false
    @State private var exportFolderError: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("扫描") {
                    Toggle("默认自动裁边", isOn: $autoCropEnabled)
                    Toggle("默认去阴影", isOn: $removeShadowsEnabled)
                    Picker("默认滤镜", selection: $defaultFilter) {
                        ForEach(ScanFilter.allCases) { Text($0.rawValue).tag($0.rawValue) }
                    }
                    Picker("扫描分辨率", selection: $scanResolution) {
                        ForEach(CameraResolutionPreset.allCases) { preset in
                            Text(preset.rawValue).tag(preset.rawValue)
                        }
                    }
                }
                Section("导出") {
                    Button {
                        showExportFolderPicker = true
                    } label: {
                        LabeledContent("导出目录", value: exportFolderDisplayName.isEmpty ? "应用内文档" : exportFolderDisplayName)
                    }
                    if !exportFolderDisplayName.isEmpty {
                        Button("恢复为应用内目录", role: .destructive) {
                            PDFServices.clearExportDirectory()
                            exportFolderDisplayName = ""
                        }
                    }
                    Text("导出文件会额外保存到此文件夹；应用内副本用于文档库和分享。")
                        .font(.caption).foregroundStyle(.secondary)
                    Picker("JPEG 质量", selection: $exportQuality) {
                        Text("高").tag(0.96); Text("平衡").tag(0.92); Text("小文件").tag(0.82)
                    }
                    Picker("默认格式", selection: $exportFormat) {
                        Text("PDF").tag("PDF")
                        Text("JPEG").tag("JPEG")
                    }
                }
                Section("文字识别") {
                    Picker("识别语言", selection: $ocrLanguage) {
                        Text("自动识别").tag("auto")
                        ForEach(ImagePipeline.supportedOCRLanguages(), id: \.self) { language in
                            Text(Locale.current.localizedString(forIdentifier: language) ?? language)
                                .tag(language)
                        }
                    }
                    Text("Vision OCR 在设备上运行，不会上传图像。").font(.caption).foregroundStyle(.secondary)
                }
                Section("关于") {
                    LabeledContent("FreeScan iOS", value: "1.0")
                    Text("本地处理，不含云端 AI。基于 FairScan，GPL-3.0-or-later。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }.navigationTitle("设置")
                .fileImporter(isPresented: $showExportFolderPicker, allowedContentTypes: [.folder]) { result in
                    guard case .success(let url) = result else { return }
                    do {
                        try PDFServices.setExportDirectory(url)
                        exportFolderDisplayName = url.lastPathComponent
                    } catch { exportFolderError = error.localizedDescription }
                }
                .alert("无法设置导出目录", isPresented: Binding(
                    get: { exportFolderError != nil },
                    set: { if !$0 { exportFolderError = nil } }
                )) {
                    Button("好") { exportFolderError = nil }
                } message: { Text(exportFolderError ?? "") }
        }
    }
}
