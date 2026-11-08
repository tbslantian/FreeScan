/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import AVFoundation
import ImageIO
import SwiftUI
import UIKit

enum CameraResolutionPreset: String, CaseIterable, Identifiable {
    case fullHD = "1080p"
    case twoK = "2K"
    case fourK = "4K"

    var id: String { rawValue }
    var maxDimension: Int {
        switch self {
        case .fullHD: 1920
        case .twoK: 2560
        case .fourK: 3840
        }
    }
}

enum CameraFlashMode: Int, CaseIterable, Identifiable {
    case auto, on, off
    var id: Int { rawValue }
    var title: String {
        switch self {
        case .auto: "闪光灯：自动"
        case .on: "闪光灯：开启"
        case .off: "闪光灯：关闭"
        }
    }
    var captureMode: AVCaptureDevice.FlashMode {
        switch self {
        case .auto: .auto
        case .on: .on
        case .off: .off
        }
    }
}

struct DocumentQuad: Codable, Equatable {
    /// Normalized coordinates with a top-left origin.
    var topLeft: CGPoint
    var topRight: CGPoint
    var bottomRight: CGPoint
    var bottomLeft: CGPoint

    var points: [CGPoint] { [topLeft, topRight, bottomRight, bottomLeft] }

    var isValid: Bool {
        let corners = points
        guard corners.allSatisfy({ (0...1).contains($0.x) && (0...1).contains($0.y) }) else { return false }
        var twiceArea: CGFloat = 0
        for i in corners.indices {
            let a = corners[i], b = corners[(i + 1) % 4], c = corners[(i + 2) % 4]
            let cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            guard cross > 0.0001 else { return false }
            twiceArea += a.x * b.y - b.x * a.y
        }
        return twiceArea > 0.005
    }

    func rotatedClockwise() -> DocumentQuad {
        func point(_ p: CGPoint) -> CGPoint { CGPoint(x: 1 - p.y, y: p.x) }
        return DocumentQuad(topLeft: point(bottomLeft), topRight: point(topLeft),
                            bottomRight: point(topRight), bottomLeft: point(bottomRight))
    }

    static func blend(_ old: DocumentQuad, _ new: DocumentQuad, amount: CGFloat) -> DocumentQuad {
        func point(_ lhs: CGPoint, _ rhs: CGPoint) -> CGPoint {
            CGPoint(x: lhs.x + (rhs.x - lhs.x) * amount,
                    y: lhs.y + (rhs.y - lhs.y) * amount)
        }
        return DocumentQuad(topLeft: point(old.topLeft, new.topLeft),
                            topRight: point(old.topRight, new.topRight),
                            bottomRight: point(old.bottomRight, new.bottomRight),
                            bottomLeft: point(old.bottomLeft, new.bottomLeft))
    }
}

final class CameraCaptureModel: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    @Published var capturedImage: UIImage?
    @Published var isReady = false
    @Published private(set) var isCapturing = false
    @Published var errorMessage: String?
    @Published private(set) var detectedDocument: DocumentQuad?
    @Published private(set) var hasTorch = false
    @Published private(set) var torchEnabled = false
    @Published var resolution: CameraResolutionPreset = CameraResolutionPreset(
        rawValue: UserDefaults.standard.string(forKey: "scanResolution") ?? "4K") ?? .fourK
    @Published var flashMode: CameraFlashMode = .auto
    @Published var multiFrameEnabled = UserDefaults.standard.bool(forKey: "cameraMultiFrame")

    private let output = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "org.freescan.ios.camera")
    private let detectionQueue = DispatchQueue(label: "org.freescan.ios.camera.detection", qos: .userInitiated)
    private var configured = false
    private var lastDetectionTime = CFAbsoluteTimeGetCurrent()
    private var previousRawRectangle: DocumentQuad?
    private var stableRectangleCount = 0
    private var detectionEnabled = true
    private var detectionRotation: CGFloat = 90
    private var torchIsOn = false
    private var captureInProgress = false
    private var framesRemaining = 0
    private var bestFrameScore = -Double.infinity
    private var bestFrame: UIImage?
    @MainActor var captureRotationAngle: CGFloat = 90

    func start() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self else { return }
            guard granted else {
                Task { @MainActor in self.errorMessage = "请在系统设置中允许 FreeScan 使用相机。" }
                return
            }
            self.queue.async { self.configureAndStartOnQueue() }
        }
    }

    private func configureAndStartOnQueue() {
        guard !configured else {
            if !session.isRunning { session.startRunning() }
            return
        }
        configured = true
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input), session.canAddOutput(output), session.canAddOutput(videoOutput) else {
            session.commitConfiguration()
            Task { @MainActor in self.errorMessage = "无法启动相机，请检查设备或相机权限。" }
            return
        }
        session.addInput(input)
        session.addOutput(output)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.setSampleBufferDelegate(self, queue: detectionQueue)
        session.addOutput(videoOutput)
        if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(0) {
            connection.videoRotationAngle = 0
        }
        output.isHighResolutionCaptureEnabled = true
        Task { @MainActor in self.hasTorch = device.hasTorch }
        session.commitConfiguration()
        session.startRunning()
        Task { @MainActor in self.isReady = true }
    }

    func stop() {
        queue.async {
            if self.torchIsOn { self.setTorchOnQueue(false) }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func setResolution(_ preset: CameraResolutionPreset) {
        UserDefaults.standard.set(preset.rawValue, forKey: "scanResolution")
    }

    func toggleTorch() {
        queue.async { self.setTorchOnQueue(!self.torchIsOn) }
    }

    private func setTorchOnQueue(_ enabled: Bool) {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = enabled ? .on : .off
            device.unlockForConfiguration()
            torchIsOn = enabled
            Task { @MainActor in self.torchEnabled = enabled }
        } catch {
            Task { @MainActor in self.errorMessage = "无法切换补光灯：\(error.localizedDescription)" }
        }
    }

    @MainActor func capture() {
        guard isReady, !isCapturing else { return }
        isCapturing = true
        let angle = captureRotationAngle
        queue.async {
            guard self.session.isRunning, !self.captureInProgress else {
                Task { @MainActor in self.isCapturing = false }
                return
            }
            if let connection = self.output.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
            self.captureInProgress = true
            self.framesRemaining = self.multiFrameEnabled ? 3 : 1
            self.bestFrameScore = -Double.infinity
            self.bestFrame = nil
            self.captureNextFrameOnQueue()
        }
    }

    @MainActor func updateRotation(_ angle: CGFloat) {
        guard captureRotationAngle != angle else { return }
        captureRotationAngle = angle
        detectionQueue.async {
            self.detectionRotation = angle
            self.resetDetection()
        }
    }

    @MainActor func setDetectionEnabled(_ enabled: Bool) {
        detectionQueue.async {
            self.detectionEnabled = enabled
            self.resetDetection()
        }
    }

    private func resetDetection() {
        previousRawRectangle = nil
        stableRectangleCount = 0
        Task { @MainActor in self.detectedDocument = nil }
    }

    func setMultiFrameEnabled(_ enabled: Bool) {
        multiFrameEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "cameraMultiFrame")
    }

    private func captureNextFrameOnQueue() {
        let settings = AVCapturePhotoSettings()
        settings.isHighResolutionPhotoEnabled = true
        if output.supportedFlashModes.contains(flashMode.captureMode) {
            settings.flashMode = flashMode.captureMode
        }
        output.capturePhoto(with: settings, delegate: self)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        let now = CFAbsoluteTimeGetCurrent()
        guard detectionEnabled, now - lastDetectionTime >= 0.22,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastDetectionTime = now

        let orientation: CGImagePropertyOrientation
        switch detectionRotation {
        case 90: orientation = .right
        case 180: orientation = .down
        case 270: orientation = .left
        default: orientation = .up
        }
        guard var detected = ImagePipeline.detectDocumentQuad(pixelBuffer, orientation: orientation) else {
            resetDetection()
            return
        }
        // AVCaptureVideoPreviewLayer expects points in the sensor's unrotated coordinates.
        for _ in 0..<(Int(360 - detectionRotation) % 360 / 90) {
            detected = detected.rotatedClockwise()
        }
        if let previous = previousRawRectangle, Self.maxCornerDistance(previous, detected) < 20 {
            stableRectangleCount = min(stableRectangleCount + 1, 3)
            detected = DocumentQuad.blend(previous, detected, amount: 0.45)
        } else {
            stableRectangleCount = 1
        }
        previousRawRectangle = detected
        let stable = stableRectangleCount >= 3 ? detected : nil
        Task { @MainActor in self.detectedDocument = stable }
    }

    private static func maxCornerDistance(_ lhs: DocumentQuad, _ rhs: DocumentQuad) -> CGFloat {
        let pairs = [(lhs.topLeft, rhs.topLeft), (lhs.topRight, rhs.topRight),
                     (lhs.bottomRight, rhs.bottomRight), (lhs.bottomLeft, rhs.bottomLeft)]
        return pairs.map { pair in
            hypot((pair.0.x - pair.1.x) * 640, (pair.0.y - pair.1.y) * 480)
        }.max() ?? .greatestFiniteMagnitude
    }

    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        let frame = error == nil
            ? photo.fileDataRepresentation().flatMap(UIImage.init(data:))
            : nil
        let score = frame.map(Self.sharpnessScore) ?? -Double.infinity
        queue.async {
            if let frame, score > self.bestFrameScore {
                self.bestFrameScore = score
                self.bestFrame = frame
            }
            self.framesRemaining -= 1
            if self.framesRemaining > 0 {
                self.captureNextFrameOnQueue()
            } else {
                self.captureInProgress = false
                let selected = self.bestFrame
                self.bestFrame = nil
                Task { @MainActor in
                    self.isCapturing = false
                    self.capturedImage = selected
                    if selected == nil { self.errorMessage = "拍照失败，请重试。" }
                }
            }
        }
    }

    private static func sharpnessScore(_ image: UIImage) -> Double {
        guard let cgImage = image.cgImage else { return 0 }
        let width = 80
        let height = 80
        guard let context = CGContext(data: nil, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: width,
                                      space: CGColorSpaceCreateDeviceGray(),
                                      bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return 0 }
        context.interpolationQuality = .medium
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let data = context.data else { return 0 }
        let pixels = data.assumingMemoryBound(to: UInt8.self)
        var score = 0.0
        var samples = 0
        for y in 1..<(height - 1) {
            for x in 1..<(width - 1) {
                let i = y * width + x
                let center = Int(pixels[i])
                let laplacian = 4 * center - Int(pixels[i - 1]) - Int(pixels[i + 1])
                    - Int(pixels[i - width]) - Int(pixels[i + width])
                score += Double(abs(laplacian))
                samples += 1
            }
        }
        return samples == 0 ? 0 : score / Double(samples)
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let detectedDocument: DocumentQuad?
    let onRotationChange: (CGFloat) -> Void

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
        uiView.onRotationChange = onRotationChange
        uiView.updateRotation()
        uiView.detectedDocument = detectedDocument
    }
}

final class CameraPreviewView: UIView {
    var detectedDocument: DocumentQuad? { didSet { updateDocumentOutline() } }
    var onRotationChange: ((CGFloat) -> Void)?
    private let documentOutline = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        documentOutline.fillColor = UIColor.clear.cgColor
        documentOutline.strokeColor = UIColor.systemGreen.cgColor
        documentOutline.lineWidth = 3
        documentOutline.lineJoin = .round
        previewLayer.addSublayer(documentOutline)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        documentOutline.fillColor = UIColor.clear.cgColor
        documentOutline.strokeColor = UIColor.systemGreen.cgColor
        documentOutline.lineWidth = 3
        documentOutline.lineJoin = .round
        previewLayer.addSublayer(documentOutline)
    }

    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateRotation()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateRotation()
        updateDocumentOutline()
    }

    func updateRotation() {
        guard let scene = window?.windowScene else { return }
        // This scanner uses the back camera, whose unrotated video is landscape right.
        let angle: CGFloat
        switch scene.interfaceOrientation {
        case .portrait: angle = 90
        case .portraitUpsideDown: angle = 270
        case .landscapeRight: angle = 0
        case .landscapeLeft: angle = 180
        default: return
        }
        if let connection = previewLayer.connection, connection.videoRotationAngle != angle,
           connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
        onRotationChange?(angle)
    }

    private func updateDocumentOutline() {
        guard let detectedDocument else {
            documentOutline.path = nil
            return
        }
        func layerPoint(_ point: CGPoint) -> CGPoint {
            previewLayer.layerPointConverted(fromCaptureDevicePoint: point)
        }
        let points = [detectedDocument.topLeft, detectedDocument.topRight,
                      detectedDocument.bottomRight, detectedDocument.bottomLeft].map(layerPoint)
        guard let first = points.first else { return }
        let path = UIBezierPath()
        path.move(to: first)
        points.dropFirst().forEach { path.addLine(to: $0) }
        path.close()
        documentOutline.path = path.cgPath
        documentOutline.frame = bounds
    }
}

