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
    @State private var selectedPhoto: PhotosPickerItem?
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
                        PhotosPicker(selection: $selectedPhoto, matching: .images) {
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
            .onChange(of: selectedPhoto) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        let autoCrop = UserDefaults.standard.object(forKey: "autoCropEnabled") as? Bool ?? true
                        library.add(autoCrop ? ImagePipeline.autoCropDocument(image) : image)
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
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do { library.add(contentsOf: try PDFServices.images(from: url)) }
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
    @State private var photoItem: PhotosPickerItem?
    let mode: ScanCameraMode

    var body: some View {
        NavigationStack {
            ZStack {
                CameraPreview(session: camera.session).ignoresSafeArea()
                VStack(spacing: 16) {
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
                    GuideOverlay(aspect: mode == .certificate ? template.aspect : 0.72).padding(.horizontal, 36)
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
                        PhotosPicker(selection: $photoItem, matching: .images) {
                            Image(systemName: "photo").font(.title2).foregroundStyle(.white)
                        }.accessibilityLabel("相册导入")
                    }.padding(.horizontal, 28).padding(.bottom, 24)
                }.padding(.top, 18)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("返回") { dismiss() }.foregroundStyle(.white) }
                ToolbarItem(placement: .topBarTrailing) {
                    if mode == .certificate {
                        HStack(spacing: 14) {
                            Toggle("自动裁边", isOn: $autoCropEnabled).labelsHidden().tint(.green)
                            Button("完成") { dismiss() }.foregroundStyle(.white)
                        }
                    }
                }
            }
            .toolbarBackground(.black.opacity(0.72), for: .navigationBar).toolbarBackground(.visible, for: .navigationBar)
            .onAppear { camera.start() }.onDisappear { camera.stop() }
            .onChange(of: camera.capturedImage) { _, image in
                guard let image else { return }
                library.add(autoCropEnabled ? ImagePipeline.autoCropDocument(image) : image)
                camera.capturedImage = nil
                if mode == .certificate {
                    isBackSide = true
                } else {
                    dismiss()
                }
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        library.add(autoCropEnabled ? ImagePipeline.autoCropDocument(image) : image)
                        if mode == .certificate {
                            isBackSide = true
                        } else {
                            dismiss()
                        }
                    }
                }
            }
            .alert("相机不可用", isPresented: Binding(get: { camera.errorMessage != nil }, set: { if !$0 { camera.errorMessage = nil } })) {
                Button("好") { camera.errorMessage = nil }
            } message: { Text(camera.errorMessage ?? "") }
        }
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

struct CertificateScanView: View {
    var body: some View { ScanCameraView(mode: .certificate) }
}

struct ScanEditorView: View {
    @EnvironmentObject private var library: ScanLibrary
    @State private var selectedID: UUID?
    @State private var filter = ScanFilter.color
    @State private var showCrop = false
    @State private var showOCR = false
    @State private var recognizedText = ""
    @State private var shareURL: URL?
    @State private var errorText: String?
    @AppStorage("defaultFilter") private var defaultFilterRaw = ScanFilter.color.rawValue
    @AppStorage("removeShadowsEnabled") private var removeShadowsEnabled = true
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
                                    Button { selectedID = page.id } label: {
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
                        Picker("滤镜", selection: $filter) {
                            ForEach(ScanFilter.allCases) { Text($0.rawValue).tag($0) }
                        }.pickerStyle(.segmented).padding(.horizontal)
                        Toggle("去阴影", isOn: $removeShadowsEnabled).padding(.horizontal)
                        HStack {
                            Button { showCrop = true } label: { Label("手动裁边", systemImage: "crop.rotate") }
                            Spacer()
                            Button { rotate(current) } label: { Label("旋转", systemImage: "rotate.right") }
                            Spacer()
                            Button { Task { await runOCR(current) } } label: { Label("OCR", systemImage: "text.viewfinder") }
                        }.buttonStyle(.bordered).padding(.horizontal)
                        HStack {
                            Button("导出 PDF") { exportPDF() }.buttonStyle(.borderedProminent)
                            Button("导出 JPEG") { exportJPEG() }.buttonStyle(.bordered)
                            Button("打印") { printPDF() }.buttonStyle(.bordered)
                        }.padding(.bottom, 8)
                    }.padding(.top, 8)
                }
            }
            .navigationTitle("页面编辑").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("清空") { library.clear(); selectedID = nil } } }
            .onAppear { filter = ScanFilter(rawValue: defaultFilterRaw) ?? .color }
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
            .sheet(item: Binding(get: { shareURL.map(ShareFile.init(url:)) }, set: { shareURL = $0?.url })) {
                ShareSheet(url: $0.url)
            }
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
        let source = library.pages.map(\.image)
        let selectedFilter = filter
        let shouldRemoveShadows = removeShadowsEnabled
        Task {
            do {
                let processed = await Task.detached(priority: .userInitiated) {
                    source.map { image in
                        let filtered = ImagePipeline.filtered(image, mode: selectedFilter)
                        return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                    }
                }.value
                shareURL = try PDFServices.makePDF(from: processed)
            } catch { errorText = error.localizedDescription }
        }
    }
    private func exportJPEG() {
        let source = library.pages.map(\.image)
        let selectedFilter = filter
        let shouldRemoveShadows = removeShadowsEnabled
        Task {
            do {
                let processed = await Task.detached(priority: .userInitiated) {
                    source.map { image in
                        let filtered = ImagePipeline.filtered(image, mode: selectedFilter)
                        return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                    }
                }.value
                shareURL = try PDFServices.makeJPEGs(from: processed).first
            } catch { errorText = error.localizedDescription }
        }
    }
    private func printPDF() {
        let source = library.pages.map(\.image)
        let selectedFilter = filter
        let shouldRemoveShadows = removeShadowsEnabled
        Task {
            do {
                let processed = await Task.detached(priority: .userInitiated) {
                    source.map { image in
                        let filtered = ImagePipeline.filtered(image, mode: selectedFilter)
                        return shouldRemoveShadows ? ImagePipeline.removeShadows(filtered) : filtered
                    }
                }.value
                PDFServices.printPDF(at: try PDFServices.makePDF(from: processed))
            } catch { errorText = error.localizedDescription }
        }
    }
}

struct ShareFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ManualCropView: View {
    @EnvironmentObject private var library: ScanLibrary
    @Environment(\.dismiss) private var dismiss
    let page: ScanPage
    @State private var left = 0.02
    @State private var top = 0.02
    @State private var right = 0.98
    @State private var bottom = 0.98
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                GeometryReader { proxy in
                    let fitted = AVMakeRect(aspectRatio: page.image.size, insideRect: proxy.frame(in: .local))
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: page.image).resizable().scaledToFit()
                        Rectangle().stroke(.green, lineWidth: 3)
                            .frame(width: fitted.width * (right - left), height: fitted.height * (bottom - top))
                            .position(x: fitted.minX + fitted.width * (left + right) / 2,
                                      y: fitted.minY + fitted.height * (top + bottom) / 2)
                    }
                }.frame(maxHeight: 360)
                cropSlider("左", value: $left, in: 0...max(0.1, right - 0.08))
                cropSlider("上", value: $top, in: 0...max(0.1, bottom - 0.08))
                cropSlider("右", value: $right, in: min(0.9, left + 0.08)...1)
                cropSlider("下", value: $bottom, in: min(0.9, top + 0.08)...1)
                Spacer()
            }.padding().navigationTitle("手动裁边")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("应用") {
                            let rect = CGRect(x: left, y: top, width: right - left, height: bottom - top)
                            let cropped = ImagePipeline.crop(page.image, normalizedRect: rect)
                            library.update(page.id) { $0.image = cropped; $0.original = cropped }
                            dismiss()
                        }
                    }
                }
        }
    }
    private func cropSlider(_ title: String, value: Binding<Double>, in range: ClosedRange<Double>) -> some View {
        HStack {
            Text(title).frame(width: 28)
            Slider(value: value, in: range)
            Text("\(Int(value.wrappedValue * 100))%").font(.caption).frame(width: 38)
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

struct DocumentsView: View {
    @EnvironmentObject private var library: ScanLibrary
    @State private var showImporter = false
    @State private var errorText: String?
    @State private var shareURL: URL?
    @State private var pdfPendingDeletion: URL?
    @State private var showDeleteConfirmation = false
    private var savedPDFs: [URL] {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?
            .filter { $0.pathExtension.lowercased() == "pdf" }.sorted { $0.lastPathComponent > $1.lastPathComponent } ?? []
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
                    if savedPDFs.isEmpty { Text("导出的文件会显示在这里。").foregroundStyle(.secondary) }
                    ForEach(savedPDFs, id: \.absoluteString) { url in
                        HStack {
                            Image(systemName: "doc.richtext")
                            Text(url.lastPathComponent).lineLimit(1)
                            Spacer()
                            Button("打开") { open(url) }
                            Menu {
                                Button("打印", systemImage: "printer") { PDFServices.printPDF(at: url) }
                                Button("共享", systemImage: "square.and.arrow.up") { shareURL = url }
                                Button("删除", systemImage: "trash", role: .destructive) {
                                    pdfPendingDeletion = url
                                    showDeleteConfirmation = true
                                }
                            } label: { Image(systemName: "ellipsis.circle") }
                        }
                    }
                }
                Section { Button { showImporter = true } label: { Label("导入 PDF 进行编辑", systemImage: "folder.badge.plus") } }
            }
            .navigationTitle("文档")
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.pdf]) { result in
                guard case .success(let url) = result else { return }
                open(url)
            }
            .alert("无法打开文件", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("好") { errorText = nil }
            } message: { Text(errorText ?? "") }
            .confirmationDialog("删除这份 PDF？", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
                Button("删除", role: .destructive) { deletePendingPDF() }
                Button("取消", role: .cancel) { pdfPendingDeletion = nil }
            }
            .sheet(item: Binding(get: { shareURL.map(ShareFile.init(url:)) }, set: { shareURL = $0?.url })) {
                ShareSheet(url: $0.url)
            }
        }
    }
    private func open(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do { library.clear(); library.add(contentsOf: try PDFServices.images(from: url)) }
        catch { errorText = error.localizedDescription }
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
    @State private var shareURL: URL?
    @State private var showImporter = false
    @State private var showManualRepair = false
    @State private var errorText: String?
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
                Button("手动框选修复") { showManualRepair = true }.buttonStyle(.bordered)
                HStack {
                    Button("导出 PDF") { exportPDF() }.buttonStyle(.bordered)
                    Button("导出 JPEG") { exportJPEG() }.buttonStyle(.bordered)
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
        .sheet(item: Binding(get: { shareURL.map(ShareFile.init(url:)) }, set: { shareURL = $0?.url })) {
            ShareSheet(url: $0.url)
        }
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
        do { shareURL = try PDFServices.makePDF(from: images, title: "FreeScan QR 修复") }
        catch { errorText = error.localizedDescription }
    }

    private func exportJPEG() {
        do { shareURL = try PDFServices.makeJPEGs(from: images).first }
        catch { errorText = error.localizedDescription }
    }
}

struct HandwritingToolView: View {
    @State private var item: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var original: UIImage?
    @State private var shareURL: URL?
    @State private var showManualRepair = false
    var body: some View {
        VStack(spacing: 14) {
            Text("试卷擦除").font(.title2.bold())
            Text("本地彩色笔迹自动处理；残留的彩笔或黑色笔迹可手动框选修复。")
                .font(.footnote).foregroundStyle(.secondary)
            if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 460) }
            else { ContentUnavailableView("选择试卷图片", systemImage: "pencil.tip.crop.circle") }
            PhotosPicker("选择图片", selection: $item, matching: .images).buttonStyle(.bordered)
            if let original {
                Button("自动擦除彩色笔迹") { image = ImagePipeline.removeColoredInk(original) }
                    .buttonStyle(.borderedProminent)
                Button("手动框选修复") { showManualRepair = true }.buttonStyle(.bordered)
                Button("恢复原图") { image = original }
                Button("导出修复结果") { if let image { shareURL = try? PDFServices.makeJPEGs(from: [image]).first } }
                    .buttonStyle(.bordered)
            }
            Spacer()
        }.padding().navigationTitle("试卷擦除")
            .onChange(of: item) { _, value in
                guard let value else { return }
                Task {
                    if let data = try? await value.loadTransferable(type: Data.self), let loaded = UIImage(data: data) {
                        original = loaded; image = loaded
                    }
                }
            }
            .sheet(item: Binding(get: { shareURL.map(ShareFile.init(url:)) }, set: { shareURL = $0?.url })) {
                ShareSheet(url: $0.url)
            }
            .sheet(isPresented: $showManualRepair) {
                if let image {
                    ManualRepairView(image: image) { repaired in self.image = repaired }
                }
            }
    }
}

struct SettingsView: View {
    @AppStorage("autoCropEnabled") private var autoCropEnabled = true
    @AppStorage("removeShadowsEnabled") private var removeShadowsEnabled = true
    @AppStorage("defaultFilter") private var defaultFilter = ScanFilter.color.rawValue
    @AppStorage("ocrLanguage") private var ocrLanguage = "zh-Hans"
    @AppStorage("exportQuality") private var exportQuality = 0.92
    var body: some View {
        NavigationStack {
            Form {
                Section("扫描") {
                    Toggle("默认自动裁边", isOn: $autoCropEnabled)
                    Toggle("默认去阴影", isOn: $removeShadowsEnabled)
                    Picker("默认滤镜", selection: $defaultFilter) {
                        ForEach(ScanFilter.allCases) { Text($0.rawValue).tag($0.rawValue) }
                    }
                    LabeledContent("分辨率", value: "相机原始分辨率")
                }
                Section("导出") {
                    Picker("JPEG 质量", selection: $exportQuality) {
                        Text("高").tag(0.96); Text("平衡").tag(0.92); Text("小文件").tag(0.82)
                    }
                    LabeledContent("格式", value: "PDF / JPEG")
                }
                Section("文字识别") {
                    Picker("识别语言", selection: $ocrLanguage) {
                        Text("简体中文").tag("zh-Hans")
                        Text("繁体中文").tag("zh-Hant")
                        Text("英语").tag("en-US")
                    }
                    Text("Vision OCR 在设备上运行，不会上传图像。").font(.caption).foregroundStyle(.secondary)
                }
                Section("关于") {
                    LabeledContent("FreeScan iOS", value: "1.0")
                    Text("本地处理，不含云端 AI。基于 FairScan，GPL-3.0-or-later。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }.navigationTitle("设置")
        }
    }
}
