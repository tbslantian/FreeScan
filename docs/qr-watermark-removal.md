# 一键去除水印二维码

## 操作方式

- 首页入口仍为“一键去除水印二维码”。点击卡片、选择 PDF 或图片后，自动检测、擦除并另存，不要求逐页确认。
- 外部应用分享进来的文件继续走自动流程，多文件按顺序处理。
- 卡片内的“选择区域”为可选操作。选择文件后逐页显示检测框：绿色表示将擦除；黄色为未通过解码校验的候选，默认不擦，可手动点击加入。支持整页选择、仅页边、手动补框、撤销补框、修复预览及查看原图。
- 手动框可包含二维码附近的文字或标志。某页全部取消后可跳过，该页保持原样。
- 处理时显示当前页和阶段，可取消当前任务及待处理队列。取消或失败时清理当前尚未发布的输出；此前已完成的文件保留。
- 输出位于 Download/Freescan，名称为原文件名加 `_cleaned`。原文件不修改，重名输出不会覆盖已有文件。

## 代码与算法

| 文件 | 实现 |
| --- | --- |
| `platform/QrWatermarkDetector.kt` | OpenCV `QRCodeDetector`、`QRCodeDetectorAruco` 定位；只有成功解码且尺寸合理的标准二维码自动擦除；未验证候选保留在可选预览；全页概览与重叠图块补检；坐标归一化、重复区域合并 |
| `platform/QrRegionRepair.kt` | 完整多边形掩码、适度扩边；从周围采样估计纸张 RGB 平面；复杂背景使用 `INPAINT_TELEA` |
| `data/QuarkWatermarkProcessor.kt` | 图片/PDF 输入、逐页处理、预览回调、取消与输出；PDF 原页尺寸与旋转映射 |
| `ui/screens/library/WatermarkDialogs.kt` | 可选区域编辑、修复预览、页进度 |
| `MainActivity.kt` | 自动/选择两种任务、分享队列、协程与取消 |

检测概览最长边为 1600 像素，原处理图块为 1280 像素、步长 1024 像素。未能验证二维码时依次尝试局部对比度增强、反色和自适应二值化。增强仅用于检测副本，不修改原页面颜色。若候选边长超过页面短边的 30%，也不进入自动擦除，避免把表格等大块正文当成水印。

图像修复只将估计出的背景写入选区及小幅扩边。背景计算用最长边不超过 1024 像素的局部副本；选区以外的处理图像像素不重新滤镜处理。

### 夸克兼容路径

保留既有右下角蓝色标识判定和对应页脚区域，以继续自动处理旧版夸克导出标志、文字及二维码，即使通用二维码检测失败也可生效。通用检测已找到并被页脚覆盖的二维码会去重，整个夸克页脚按一处标记计数。

这个兼容判定仍是启发式规则：右下角相似蓝色图案可能触发。需要保留该区域时，可从“选择区域”取消对应框。通用二维码使用实际四角位置，不使用固定右下角区域。

### PDF 保真范围

- 使用 Android `PdfRenderer` 渲染检测副本，使用已集成的 PDFBox 修改文档。
- 未修改页保持原内容流、文字和资源，不重新栅格化。
- 修改页以修复后的无损图像替换内容，保留 MediaBox、CropBox、Rotate 和 UserUnit；按旋转方向将图像映射回原页坐标，避免将像素尺寸当成 PDF 点单位。
- 修改页原来的内容流会转为图片，因此原有可搜索文字可能丢失；保留该页的注释与表单对象，避免清空已填写字段及签名。若二维码本身作为注释叠加在页面上，注释可能继续显示，应单独处理该对象。
- 每页处理后释放位图，PDFBox 超过 16 MiB 的工作缓存使用应用缓存目录。渲染最长边上限 3600 像素、倍率上限 3。

## 适用边界

- 支持目标是标准 QR 二维码，不保证识别圆形小程序码、严重缺损码及所有艺术码。漏检时可手动补框。
- 自动流程会移除整页检测到的二维码，包括非水印用途的二维码；需要保留部分二维码时使用“选择区域”。
- 背景修复无法还原被二维码遮住的原始文字、图案。复杂纹理可能出现平滑痕迹。
- 图片沿用内存保护限制：最长边不超过 5000 像素，最多 1800 万像素；超限按采样倍率解码。Android 8 的图片读取补充 EXIF 方向校正。
- 没有新增模型、下载步骤、网络请求或依赖包。
- 本次仅完成源码修改和静态阅读，尚未运行编译或实机测试。多品牌二维码、旋转 PDF 及实际耗时仍需实机确认。

## 技术来源

- OpenCV 4.12 Java：<https://docs.opencv.org/4.12.0/javadoc/org/opencv/objdetect/GraphicalCodeDetector.html>
- OpenCV QRCodeDetector：<https://docs.opencv.org/4.12.0/javadoc/org/opencv/objdetect/QRCodeDetector.html>
- OpenCV QRCodeDetectorAruco：<https://docs.opencv.org/4.12.0/javadoc/org/opencv/objdetect/QRCodeDetectorAruco.html>
- OpenCV TELEA 局部修复：<https://docs.opencv.org/4.x/d7/d8b/group__photo__inpaint.html>
- PDFBox Android：<https://github.com/TomRoush/PdfBox-Android>
- Android PDF 页面单位：<https://developer.android.com/reference/android/graphics/pdf/PdfDocument.PageInfo.Builder>
- 多尺度拼接、夸克兼容规则、周围纸面采样与 RGB 平面拟合：FreeScan 项目实现。

## 构建

```powershell
Set-Location M:\1\Freescan
.\gradlew.bat :app:assembleRelease
```
