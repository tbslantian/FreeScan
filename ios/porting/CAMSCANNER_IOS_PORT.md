# FreeScan iOS 扫描迁移

参考样本为扫描全能王 iOS 7.28.5（`7.28.5.2610302009`）。从用户提供的 IPA 主程序中按已验证偏移提取四份模型。提取器校验整个 IPA 和每份模型的 SHA-256，模型放在 Git 忽略的 `ios/.build/camscanner-models`。运行时使用从固定提交构建的开源 MNN 3.6.2 CPU 库。[IPA 分析](../../docs/camscanner-ios-scan-analysis.md) · [MNN 3.6.2](https://github.com/alibaba/MNN/releases/tag/3.6.2)

| 环节 | 接入方式 |
| --- | --- |
| 自动找边 | 240×240 BGR 输入按 255 归一化，取 MNN 输出通道 0。FreeScan 在边缘图上用 EDLines 的线段增长、拟合与验证思路求四角。iOS 原应用的线段提取函数尚未独立确认。低置信度时使用现有 HED 和 Vision。 |
| 裁切与透视 | 自动与手动裁边共用 OpenCV `findHomography → warpPerspective`。 |
| 文字方向 | 512×512 独立 MNN 模型给出方向，FreeScan 以 0.75 为置信阈值。对类别 1、2、3 执行逆时针 90°、180°、顺时针 90°。可在设置关闭，编辑器也保留小角度文字行校正。 |
| 曲面展平 | 取展平模型 B 的 XY 逆位移，使用 iOS 路径的 9×9 平滑和 0.5 形变判定。通过边缘和折叠检查后用 `remap` 重采样。文档在透视拉正后尝试展平。单页书页入口也允许处理没有检测到四角的书页，双页需分别取景。 |
| 滤镜 | 原图、去阴影、增亮、增强锐化、智能高清、灰度、黑白、省墨、反色、去手写、去水印。去阴影使用 IPA 的光照模型，智能高清使用该模型配合本地增强。其余采用恢复的算子或 FreeScan 本地实现。去手写处理彩色笔迹。去水印处理已验证的页边二维码和夸克页脚。 |

文档与书页拍照后立即处理，完成后直接打开新页的编辑器。证件的正反面仍逐面确认。拍照、相册和编辑重裁均走同一几何核心，滤镜用于页面预览、导出和打印。

## 构建 iPhone/iPad 真机测试 IPA

在 macOS 的仓库根目录运行。需要 Xcode、CMake 和 Python 3。

```bash
bash ios/build-ocr.sh
bash ios/build-reference-scan.sh "/path/to/CamScanner-IPAOMTK.COM.ipa"
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

手动运行 `iOS IPA`，选择 `reference-device-test`。该任务用 macOS 构建包含 MNN 运行时的 ARM64 真机包，输出 `FreeScan-iOS-Reference-Runtime-<构建号>`。解压下载的产物，再用下面的命令把四个经过校验的参考模型补进 IPA。这一步可在 Windows 执行。

```powershell
python ios/porting/finalize_reference_ipa.py "FreeScan-iOS-device-test-unsigned-<构建号>.ipa" "FreeScan-iOS-Reference-unsigned.ipa" --reference-ipa "C:/path/to/CamScanner-IPAOMTK.COM.ipa"
```

签名时选择本机补齐模型后的 IPA。GitHub 运行时产物保留开源回退路径，模型和最终含权重的 IPA 留在本机。
