/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import AVFoundation
import SwiftUI
import UIKit

final class CameraCaptureModel: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    @Published var capturedImage: UIImage?
    @Published var isReady = false
    @Published var errorMessage: String?

    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "org.freescan.ios.camera")
    private var configured = false

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
              session.canAddInput(input), session.canAddOutput(output) else {
            session.commitConfiguration()
            Task { @MainActor in self.errorMessage = "无法启动相机，请检查设备或相机权限。" }
            return
        }
        session.addInput(input)
        session.addOutput(output)
        output.isHighResolutionCaptureEnabled = true
        session.commitConfiguration()
        session.startRunning()
        Task { @MainActor in self.isReady = true }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    func capture() {
        queue.async {
            guard self.session.isRunning else { return }
            let settings = AVCapturePhotoSettings()
            settings.isHighResolutionPhotoEnabled = true
            self.output.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation(), let image = UIImage(data: data) else { return }
        Task { @MainActor in self.capturedImage = image }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
    }
}

final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

