/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import AVFoundation
import SwiftUI
import UIKit
import Vision

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

struct DocumentQuad: Equatable {
    /// Normalized coordinates with a top-left origin.
    var topLeft: CGPoint
    var topRight: CGPoint
    var bottomRight: CGPoint
    var bottomLeft: CGPoint

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
    private var stableRectangle: DocumentQuad?
    private var missedDetectionFrames = 0
    private var torchIsOn = false
    private var captureInProgress = false
    private var framesRemaining = 0
    private var bestFrameScore = -Double.infinity
    private var bestFrame: UIImage?

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

    func capture() {
        queue.async {
            guard self.session.isRunning, !self.captureInProgress else { return }
            self.captureInProgress = true
            self.framesRemaining = self.multiFrameEnabled ? 3 : 1
            self.bestFrameScore = -Double.infinity
            self.bestFrame = nil
            self.captureNextFrameOnQueue()
        }
    }

    func setMultiFrameEnabled(_ enabled: Bool) {
        multiFrameEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "cameraMultiFrame")
    }

    private func captureNextFrameOnQueue() {
        let settings = AVCapturePhotoSettings()
        settings.isHighResolutionPhotoEnabled = true
        if output.supportedFlashModes.contains(NSNumber(value: flashMode.captureMode.rawValue)) {
            settings.flashMode = flashMode.captureMode
        }
        output.capturePhoto(with: settings, delegate: self)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        let now = CFAbsoluteTimeGetCurrent()
        guard now - lastDetectionTime >= 0.22,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastDetectionTime = now

        let request = VNDetectRectanglesRequest()
        request.maximumObservations = 1
        request.minimumConfidence = 0.55
        request.minimumAspectRatio = 0.18
        request.maximumAspectRatio = 3.0
        request.quadratureTolerance = 25
        do {
            try VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .right).perform([request])
            guard let rectangle = request.results?.first else {
                missedDetectionFrames += 1
                if missedDetectionFrames >= 4 {
                    stableRectangle = nil
                    Task { @MainActor in self.detectedDocument = nil }
                }
                return
            }
            missedDetectionFrames = 0
            func topLeftOrigin(_ point: CGPoint) -> CGPoint {
                CGPoint(x: point.x, y: 1 - point.y)
            }
            let detected = DocumentQuad(
                topLeft: topLeftOrigin(rectangle.topLeft),
                topRight: topLeftOrigin(rectangle.topRight),
                bottomRight: topLeftOrigin(rectangle.bottomRight),
                bottomLeft: topLeftOrigin(rectangle.bottomLeft)
            )
            if let previous = stableRectangle,
               hypot(previous.topLeft.x - detected.topLeft.x,
                     previous.topLeft.y - detected.topLeft.y) < 0.10 {
                stableRectangle = .blend(previous, detected, amount: 0.35)
            } else {
                stableRectangle = detected
            }
            let stable = stableRectangle
            Task { @MainActor in self.detectedDocument = stable }
        } catch {
            // Keep the camera preview responsive if Vision temporarily rejects a frame.
        }
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
                Task { @MainActor in self.capturedImage = selected }
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

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.detectedDocument = detectedDocument
        return view
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
        uiView.detectedDocument = detectedDocument
    }
}

final class CameraPreviewView: UIView {
    var detectedDocument: DocumentQuad? { didSet { updateDocumentOutline() } }
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

    override func layoutSubviews() {
        super.layoutSubviews()
        updateDocumentOutline()
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

