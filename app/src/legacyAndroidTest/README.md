# 历史测试归档

这些 2025 年测试引用已经删除的 TransmissionController、快慢传输模式、旧 MainActivity 和私有 DataStore，默认设备测试因此无法编译。部分用例直接调用测试自己的回调，或吞掉断言后始终通过，不能验证当前功能。保留源码用于追溯，不将它们计为通过或当前覆盖。

2026-10-27 起，默认 `:app:assembleDebugAndroidTest` / `:app:connectedDebugAndroidTest` 编译和运行维护中的 `androidTest`、`uiRedesignAndroidTest`、`backgroundUploadAndroidTest`；不再需要指定筛选参数才能成功构建。

当前对应覆盖：

- 主界面与兼容性：真实 Compose 导航、授权撤回、字号/方向、Android 服务声明、SQLite、MMKV、压缩/加密设备回归。
- 检测/策略/生命周期：AnomalyDetectorUnitTest、AnomalyDetectorIntegrationTest、SmartTransmissionManagerTest、DataCollectionServiceTest、CollectionPauseStateTest。
- 网络/故障恢复：NetworkEnvironmentDetectorTest、UploadManagerTest、UploaderImpl 回归、真实受控 gRPC 服务、后台上传数据库用例。
- 版本：VersionManager 的维护用例与实际安装包 versionCode/targetSdk 验证。

12 小时/多天物理传感器、真实功耗、多厂商设备和模型准确率仍需专门硬件实验；旧占位测试不能替代这些验收。
