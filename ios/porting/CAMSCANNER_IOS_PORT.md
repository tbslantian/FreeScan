# FreeScan iOS 扫描迁移

参考样本为扫描全能王 iOS 7.28.5（`7.28.5.2610302009`）和已验证的 Android ARM64 APK。提取器校验整个 IPA、APK 内相关库以及每份模型的 SHA-256，模型放在 Git 忽略的 `ios/.build/camscanner-models`。运行时使用从固定提交构建的开源 MNN 3.6.2 CPU 库。[IPA 分析](../../docs/camscanner-ios-scan-analysis.md) · [APK 分析](../../docs/camscanner-android-scan-analysis.md) · [MNN 3.6.2](https://github.com/alibaba/MNN/releases/tag/3.6.2)

| 环节 | 接入方式 |
| --- | --- |
| 自动找边 | 先按比例缩到长边至多 416 像素，再缩放到 240×240。BGR 按 255 归一化，取 MNN 输出通道 0，量化后按取景比例恢复边缘图。FreeScan 根据 EDLines 的线段增长、拟合与验证思路求四角。检测失败时使用 HED 和 Vision。 |
| 裁切与透视 | 自动文档裁边用曲边模型检查并细化候选四角。自动与手动裁边共用 `findHomography → warpPerspective`，目标尺寸按安卓版对边长度推算。透视采样使用原始扫描像素，最后按输出清晰度缩放。 |
| 文字方向 | 512×512 独立 MNN 模型给出方向，FreeScan 以 0.75 为置信阈值。对类别 1、2、3 执行逆时针 90°、180°、顺时针 90°。可在设置关闭，编辑器也保留小角度文字行校正。 |
| 曲面展平 | 透视拉正后使用展平模型 B，参照安卓版使用 39×39 平滑和 0.5 形变判定。通过位移与折叠检查后分条带重采样，再判断整页文字方向。单页书页入口也允许处理没有检测到四角的书页，双页需分别取景。 |
| 滤镜 | 智能高清使用安卓版的场景分类、纸面光照和像素校正模型。按场景选择两阶段校正或单模型校正，最后做颜色和锐度调整。预览、导出和打印共享滤镜。缺少模型或推理失败时使用本地滤镜。 |

文档与书页拍照后立即处理，完成后直接打开新页的编辑器。证件的正反面仍逐面确认。拍照、相册和编辑重裁共用透视和展平核心。手动重裁使用用户给出的四角。

四角拟合、曲边与位移的检查及智能高清像素合成是 FreeScan 的可移植实现。Android 原库对完整曲边轮廓的约束和增强结果的逐像素一致性仍待实机对照。

## 构建 iPhone/iPad 真机测试 IPA

在 macOS 的仓库根目录运行。需要 Xcode、CMake 和 Python 3。

```bash
bash ios/build-ocr.sh
bash ios/build-reference-scan.sh "/path/to/CamScanner-IPAOMTK.COM.ipa" "/path/to/FreeScan-1.0-arm64.apk"
xcodebuild -project ios/FreeScan.xcodeproj -scheme FreeScan \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath ios/.build/reference-device \
  -xcconfig ios/.build/camscanner-models/Enabled.xcconfig \
  CODE_SIGNING_ALLOWED=NO build
python3 ios/package_device_test.py \
  ios/.build/reference-device/Build/Products/Release-iphoneos/FreeScan.app \
  ios/.build/reference-ipa
```

产物在 `ios/.build/reference-ipa`，是待签名的 ARM64 真机测试 IPA。直接安装前需要用 Apple 账户签名。TestFlight 和 App Store 使用 Apple 分发证书和匹配的描述文件执行归档与导出，并在构建归档时沿用上述 `-xcconfig`。

## 用 GitHub Actions 编译，再在本机打包

手动运行 `iOS IPA`，选择 `reference-device-test`。该任务用 macOS 构建包含 MNN 运行时的 ARM64 真机包，输出 `FreeScan-iOS-Reference-Runtime-<构建号>`。解压下载的产物，再用下面的命令把经过校验的参考模型补进 IPA。这一步可在 Windows 执行。

```powershell
python ios/porting/finalize_reference_ipa.py "FreeScan-iOS-device-test-unsigned-<构建号>.ipa" "FreeScan-iOS-Reference-unsigned.ipa" --reference-ipa "C:/path/to/CamScanner-IPAOMTK.COM.ipa" --android-reference-apk "C:/path/to/FreeScan-1.0-arm64.apk"
```

签名时选择本机补齐模型后的 IPA。GitHub 运行时产物保留开源回退路径，模型和最终含权重的 IPA 留在本机。
