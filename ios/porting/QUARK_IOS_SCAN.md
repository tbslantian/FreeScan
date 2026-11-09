# 夸克扫描王 iOS 7.2.1 扫描几何处理

分析对象是用户提供的 `com.quark.scanking` 7.2.1（7.2.1.2302）。IPA SHA-256 为 `8a6ae79587440db66ac66c952699f56c5d81bdd861af57d6f75dea3e63d9f78f`。原生主程序 `Scanking` 的 SHA-256 为 `8432c7ecf18137ae20b2d4a2c0b3be9e4182ce307ec9e70a19cff30d5327ff2f`。

REA 提取 IPA 的证据为 `ev_78f71605faa54ca836a3fe390911bb6ad416adc01ef9180edcba9769271e7f0f`，共 4169 个文件。嵌套的 `quarkCamera.zip` 由 REA 单独提取，证据为 `ev_596ba96fd9442025a0fb3ef75e5245f2ef4c4dbd0a5ab1071b3dc86d87b2a412`。Windows 上的 REA 原生提供器不支持 ARM64 Mach-O 深度分析，下表地址来自提取后解析的 Objective-C 元数据和 ARM64 静态指令，未运行夸克。

| 环节 | 主程序中的调用和数据 | 提取状态 |
| --- | --- | --- |
| 实时取景 | `CameraSceneScanDoc.initWithDelegate:` `0x103d513a4` 建立 QStream 的 `docdet` 分支，也建立任务名为 `realtime_docdet` 的 `QKCameraRealtimeFrameBufferManager`。后者在 `0x103be1f68` 提交帧、格式、填充参数、预览尺寸及缩放比。QStream 的 `QKCameraDocumentDetector` 在 `0x103cd1c74` 调用推理，回调 `0x103cd1f00` 解析 `edge_points`。 | 已确认实时路径的任务和部分接口，取景模型和阈值尚未恢复。 |
| 拍照后的找边 | `ScanResultModel.init` `0x103941cb0` 安装 `QKWalleFileDetectOperator`。`correctSync` `0x10394cec8` 复用有效点位，缺失时调用文件检测。该算子在 `0x103da08c0` 绑定 `common_multi_detect`，传入 `_image`。`0x103da0c50` 解析响应，点位工具读取 `points` 和 `mode`，识别 `four_points`、`top_bottom`、`left_right`。 | 已提取调用协议，未找到任务图或权重。 |
| 裁切和透视校正 | `ScanResultModel.syncCorrectImage:points:autorotate:` `0x103946638` 先验证点位，再调用 `QKCameraWalleWarpPespectiveOperator`。初始化 `0x103e03984` 绑定 `baizheng`。入口 `0x103e03a64` 整理四点或六点，提交 `input_image`、`input_points` 和 `task_type`（此路径为 1），回调 `0x103946f24` 读取 `output_image`。 | 已提取调用协议。实际变换、尺寸选择和插值规则尚未恢复。 |
| 文字方向校正 | 启用 `autorotate` 时，透视结果进入 `QKCameraDetectOrientationOperator`，其初始化 `0x1039f7e48` 绑定 `image_orientation_pred`。回调 `0x103946ff8` 读取 `output_orientation`，为正时把角度换算为弧度并反向旋转图像，同时更新裁切点。 | 已确认整页方向旋转。模型、支持的角度集合及独立的小角度文字行纠偏尚未确认。 |
| 弯曲页面展平 | 已确认多点校正的接口和多种边界模式。尚无指令或任务图能确认六点模式采用的具体变换，亦未确认弧面网格重映射算法。 | 尚未提取曲面展平实现。 |

## 运行时资源

`WalleManager.walleTaskNameList`（`0x1006855f4`）注册了上述任务。其配置更新处理 `0x100686154` 可触发预取，随后 `prefetchTask` `0x100685ed8` 调用 `downloadTask:`。`CameraWalleTask` 通过 `MNNCVExecutor` 把任务交给 `MRTJobManager`，并有独立的仅下载入口。MRT 的模型、资源和 Python 库目录名分别是 `MNNRTModels`、`MNNRTResources`、`MNNRTLib`（目录构造代码位于 `0x102794d88`、`0x102794e50`、`0x102794f18`）。

在 IPA 及其中 8 个 ZIP 的文件名和常见模型文件头中，未定位到上述三个任务的模型文件。已检查 `hardcode_cd_app.pb` 中的任务标识，相关命中是 `camera_enable_scandoc_edge_snapping` 功能开关。`qa_edge` 是另一路 `QKEdgeDetectOperator` 的模型名，它使用 `qa_edge.mnn`、`input_1` 和 `bw_out/Tanh`，关联 `webARJsHandler`，不能用它指代普通扫描的 `common_multi_detect`。本次分析未联网运行样本，也未观察到某台设备实际下载的资源。

## 真机资源定位

目录构造调用的是 `NSSearchPathForDirectoriesInDomains`，模型、资源和 Python 库分别在应用的 `Documents/MNNRTModels`、`Documents/MNNRTResources`、`Documents/MNNRTLib`。`WalleManager.setupECConfigIfNeed` `0x10068666c` 和 `registerECModelsIfNeed` `0x10068690c` 都从 `NSUserDefaults` 读取 `MRTConfigCache`。后者解析其中的 `models` 数组，`MRTCodeDescription.initWithTaskJSONModel:` `0x10279ebb4` 读取任务名、资源 URL 和摘要字段。设备导出时应保存原始目录结构，若能导出偏好设置，只需 `MRTConfigCache` 对应的值。

本机项目、下载、文档和桌面目录中只找到原始 IPA。要按夸克原方案继续移植，需要同版本已下载的 Walle 任务配置和资源，重点是 `realtime_docdet`、`common_multi_detect`、`baizheng`、`image_orientation_pred`。FreeScan 的扫描实现尚未切换。
