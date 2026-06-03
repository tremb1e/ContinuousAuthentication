# Continuous Authentication - Android 持续认证数据采集器  (功能规范)

> 版本：v1.0
> 更新日期：2026-06-01

> 当前状态：历史规划参考。当前可执行实现以 `2025-11-18-技术方案.md`、`2025-11-18-需求规格.md` 和 app/server `sensor_data.proto` 为准。

---

## 1. 系统架构概述

### 1.1 整体架构设计

采用Clean Architecture分层架构，确保各层职责明确、依赖关系清晰：

```
┌─────────────────────────────────────────────────┐
│          Presentation Layer (UI/ViewModel)       │
├─────────────────────────────────────────────────┤
│      Domain Layer (UseCase/Repository Interface) │
├─────────────────────────────────────────────────┤
│       Data Layer (Repository实现/DataSource)     │
├─────────────────────────────────────────────────┤
│      Framework Layer (传感器/网络/存储)          │
└─────────────────────────────────────────────────┘
```

### 1.2 数据流架构

```
传感器硬件 → 事件采集 → 批处理缓冲 → 序列化 → 压缩 → 加密 → 
→ 内存队列 → [可选:磁盘持久化] → gRPC传输 → 服务器
                    ↑                      ↓
              加密状态存储            ACK确认/策略更新
```

### 1.3 模块边界定义

- **采集模块**：负责传感器数据获取和初步处理
- **加密模块**：独立的安全层，处理所有加密操作
- **传输模块**：网络通信和连接管理
- **存储模块**：数据持久化和队列管理
- **监控模块**：性能监控和调试支持

## 2. 项目概述与目标

**目标**：开发一款用于持续性身份认证研究的 Android 数据采集应用 `Continuous Authentication`，高保真采集加速度计、陀螺仪、磁力计，在设备端进行加密封包并通过 gRPC 双向流可靠上传至服务器，服务器对数据进行解密，存储与后续处理。

**当前实现要点（更新/强调）**：
- **加密方案**：当前采用共享密钥派生 AES-256-GCM，密文格式为 `IV(12)|TAG(16)|ciphertext`。旧版 Tink Envelope DEK / HybridEncrypt 设计暂不作为当前联调协议。
- **压缩与加密顺序**：**先序列化 -> 先压缩（LZ4） -> 再加密（AES-GCM）。**
- **传输策略**：支持 `仅 Wi-Fi 上传` 与 `不限制` 两种选项（默认不限制，前端给出相应button供用户选择）。
- **流式加密**：历史规划保留；当前 App 使用单包 AES-GCM，与 server 解密链路一致。
- **文件队列与写入原子性**：写入磁盘采用写 `.tmp` -> fsync -> rename 的原子化流程，并在 DB（Room）中保存路径与校验值（SHA256/CRC32）。
- **抗重放与顺序保障**：加 `packet_seq_no`（全局递增）与 `creation_server_ts`（ACK 回填）用于端/服务器校验顺序和重放防护。
- **AAD 隐私保护**：当前 server 未校验 AAD，App 不设置 AAD，避免解密不一致。网络中不放明文设备 ID；前台应用包名按 `foreground_app_name` 作为样本上下文上传。
- **Key Rotation 与兼容性**：当前 `dek_key_id` 为 `STATIC_KEY_V1`，保留字段用于后续扩展。
- **首次密钥发布与 pinning**：历史规划保留；当前联调使用固定共享密钥和可配置 TLS/证书检查。
- **上传标识**：不使用 IMEI，不生成用户 ID，不使用卸载后丢失的随机安装实例。当前 `device_id_hash` 由 Widevine 设备唯一材料摘要优先、`ANDROID_ID` 次之、硬件 `Build.*` 字段兜底的稳定材料 HMAC 得到。
- **错误处理架构**：统一的错误处理机制，包括本地恢复策略、服务端错误码处理、降级方案

---

## 2. 通用开发规范
- 语言：Kotlin、Coroutines
- 依赖注入：Hilt
- 目标 API：Android 11+ (API 30+)
- 核心库：gRPC、Protobuf、OkHttp、Google Tink、Room、DataStore、Android Keystore
- 测试：JUnit、Robolectric、Espresso、Mockito
- 代码注释要求：中文注释
- 提交规范：每个 Task 完成后使用英文 Conventional Commit 格式的 commit message

---

## 3. 总体分阶段开发流程

### Phase 1: 核心数据链路与持久化
目标：实现采集 -> 序列化 -> 压缩 -> 加密（Envelope）-> 内存/磁盘队列 -> 传输封装

#### Epic 1.1: 传感器数据采集
- **Task 1.1.1**: 设计 `SensorCollector` 模块。使用 `SensorManager` 注册 `ACCELEROMETER`, `GYROSCOPE`, `MAGNETOMETER`。使用专用 Coroutine Dispatcher（`Dispatchers.IO.limitedParallelism(1)` 或自定义）处理事件并将原始 SensorEvent 入环形缓冲/Channel。。
- **Task 1.1.2**: 预设加速度计/陀螺仪 200 Hz、磁力计 100 Hz（如设备支持）。记录硬件实际可用最大采样率并在 UI/日志中展示
- **Task 1.1.3**: 批处理硬件优化：使用 `maxReportLatencyUs` 基于 `sensor.getFifoMaxEventCount()` 动态设置，并充分利用硬件 FIFO 来降低唤醒频次。
- **Task 1.1.4**: app对传感器数据不做传感器时间戳对齐工作，将采样源与时间戳一起序列化并上传；在后端做后续的重采样 / 同步 / 特征提取。采取窗口化策略（1s 窗口），在窗口内将各传感器原始样本按时间排序并打包为一个DataPacket。

#### Epic 1.2: 时间戳记录与校准
1.程序启动时，自动同步NTP一次。并每隔1h同步一次NTP。（服务端同样）
2.采用 `event.timestamp`（纳秒，基于 `SystemClock.elapsedRealtimeNanos`）及 `base_wall_ms` / `base_elapsed_ns` 的组合。


#### Epic 1.3: 端侧加密模块
**当前策略**：采用共享密钥派生 AES-256-GCM，与 server 解密链路一致。以下 Tink Envelope 任务为历史规划，不代表当前联调实现。

- **Task 1.3.1**: 设计 `CryptoBox` 模块基于 Google Tink：使用 `StreamingAead` 优先；fallback 到 AEAD (AES256_GCM) 以兼容小 payload。
- **Task 1.3.2**: 优化的密钥层级：
  - KEK（Key Encryption Key）由 `AndroidKeysetManager` 管理并保存在 Android Keystore（优先 StrongBox）。
  - 使用会话级DEK策略：每个会话（1小时）生成一个 `DEK`，减少密钥生成开销。DEK 用 `StreamingAead` 或 AEAD 加密传感器数据。
  - 使用服务器的公钥（HybridEncrypt via Tink）对 DEK 加密，生成 `encrypted_dek`。在 `DataPacket` 中附 `dek_key_id`/`key_version` 供服务器选择私钥解密。
- **Task 1.3.3**: **严禁自行管理 IV/Nonce**，依赖 Tink 自动生成与管理。
- **Task 1.3.4**: 历史规划中 AAD 构建器包含不可泄露的摘要值，并包含 `packet_id`, `packet_seq_no`, `dek_key_id` 等用于防篡改的非机密标识。当前 server 未校验 AAD，App 加密时不设置 AAD。

  ```kotlin
  // 客户端必须严格按照以下顺序构建 AAD（与服务端保持完全一致）
  fun buildAAD(packet: DataPacket): ByteArray {
    val aad = ByteArrayOutputStream()
    // 顺序必须与服务端一致
    aad.write(packet.packetId.toByteArray())
    aad.write(packet.deviceIdHash.toByteArray())
    aad.write(ByteBuffer.allocate(8).putLong(packet.packetSeqNo).array())
    aad.write(packet.dekKeyId.toByteArray())
    // 元数据关键字段
    aad.write(packet.metadata.keyVersion.toByteArray())
    return aad.toByteArray()
}
  ```
- **Task 1.3.5**: `KeyRotationManager`：支持每月1日/按策略自动轮换 KEK 与服务端 DEK 版本（调用 `keysetManager.rotate()`），并在 `DataPacket.metadata` 中携带 `key_version`和dek_key_id。确保向后兼容——服务器保留全部历史私钥用于解密旧包。
- **Task 1.3.6**: 会话级DEK管理实现：
  ```kotlin
  class OptimizedKeyManager {
      private var sessionDEK: SecretKey? = null
      private var dekExpiryTime: Long = 0
      private val DEK_LIFETIME = 3600_000L // 1小时
      
      fun getOrCreateDEK(): SecretKey {
          val now = System.currentTimeMillis()
          if (sessionDEK == null || now > dekExpiryTime) {
              sessionDEK = generateNewDEK()
              dekExpiryTime = now + DEK_LIFETIME
          }
          return sessionDEK!!
      }
  }
  ```

#### Epic 1.4: 内存缓冲与传输封装（含压缩与分片）
- **Task 1.4.1**: 设计 `InMemoryBuffer`：线程安全的队列（`kotlinx.coroutines.channels.Channel` 或自定义 ring buffer），优先存放**已加密完整 DataPacket（或已序列化的压缩块）。
- **Task 1.4.2**: 默认 每 1s 聚合并封装为一个 DataPacket（window-based）。可通过策略调整窗口长度与最大 payload 上限。策略由服务端程序下发，保留相关接口。
- **Task 1.4.3**: **先序列化 -> 压缩 -> 再加密**。建议压缩选项：LZ4/，压缩后再交给 `StreamingAead`（流式加密）。使用 Tink StreamingAead + gRPC 流式发送（避免将整个密文同时加载到内存）。配置 gRPC 与 OkHttp 的最大消息大小/流式窗口与流控（参见下文工程注意事项）。
- **Task 1.4.5**: 数据结构与 Protobuf（见下文更新）采用 `encrypted_dek`, `dek_key_id`, `packet_seq_no`, 等以支持 Envelope 与重放保护。

#### Epic 1.5: 内存与性能优化
- **Task 1.5.1**: `SensorEventPool` 对象池实现，复用包装对象，减少 GC：
  ```kotlin
  object SensorEventPool {
      private val pool = Pools.SimplePool<SensorEventWrapper>(100)
      
      fun obtain(): SensorEventWrapper {
          return pool.acquire() ?: SensorEventWrapper()
      }
      
      fun recycle(event: SensorEventWrapper) {
          event.reset()
          pool.release(event)
      }
  }
  ```


#### Epic 1.6: 数据持久化混合方案（文件队列可靠化）
- **Task 1.6.1**: 仅允许**已加密的数据**临时落盘（不允许原始明文落盘）。
- **Task 1.6.2**: `FileQueueManager`：写入加密后的 `DataPacket` 到 `context.cacheDir` 的队列文件夹。
  - **写入原子性**：先写入 `file.tmp` -> `file.getFD().sync()` (或 `fsync`) -> **rename** 到最终文件名。保证写入原子性并避免 partial file。
  - 写入同时计算并保存 SHA256（或 CRC32）到 Room `BatchMetadata`。
- **Task 1.6.3**: 使用 Room 管理 `BatchMetadata`（`packet_id`, `file_path`, `status`（PENDING/UPLOADING/UPLOADED/CORRUPT）, `sha256`, `created_at`）。读盘时校验 sha256；若校验失败标记为 `CORRUPT` 并按策略丢弃并上报。

---

### Phase 2: 网络传输与健壮性

#### Epic 2.1: gRPC 双向流通信（增强连接管理）
- **Task 2.1.1**: Protobuf 编译生成 Kotlin 代码。
- **Task 2.1.2**: 增强的 `Uploader` 模块基于 gRPC 双向流：
  - 客户端发送：独立的`DataPacket`（含 `encrypted_dek`, `dek_key_id` 等）。每个 DataPacket 独立可解密。
  - 服务器发送：ServerDirective（Ack / PolicyUpdate / KeyRotationNotice / EmergencyStop）。
  - ACK 机制：收到 `Ack` 更新 Room 中对应批次为 `UPLOADED` 并删除本地文件。
  - **连接管理**：自动重连机制、指数退避策略、连接状态监控
  - **降级方案**：当gRPC不可用时，自动切换到HTTP/REST备用通道
- **Task 2.1.3**: `PolicyUpdate` 支持动态配置采集 batch_interval_ms、max_payload_size_bytes 等参数。
- **Task 2.1.4**: 连接池和重连管理：
  ```kotlin
  class ConnectionManager {
      private var retryCount = 0
      private val maxRetries = 10
      private val baseDelay = 1000L // 1秒
      
      suspend fun connectWithRetry() {
          while (retryCount < maxRetries) {
              try {
                  connect()
                  retryCount = 0
                  break
              } catch (e: Exception) {
                  val delay = calculateBackoff(retryCount)
                  delay(delay)
                  retryCount++
              }
          }
      }
      
      private fun calculateBackoff(attempt: Int): Long {
          return min(baseDelay * (1 shl attempt), 60_000L) // 最大60秒
      }
  }
  ```

补充（gRPC 工程要点）：
- 明确配置 gRPC/OkHttp 的 maxInboundMessageSize 与客户端 maxOutboundMessageSize
- 流式传输优先用于大密文（>1MB），确保服务器端的流控/并发限制
- 配置合理的 keepalive 参数防止连接超时


#### Epic 2.2: 安全传输层
- **Task 2.2.1**: 强制 TLS 1.3。开启 OkHttp + gRPC TLS 配置。
- **Task 2.2.2**: 配置 SPKI Pinning 并设计 Pin 轮换机制（服务器发布新公钥 -> 客户端轮换策略）。客户端首次注册需验证服务器公钥指纹并保留本地副本用于 pinning 验证。

#### Epic 2.3: 安全密钥协商与 Envelope
- **Task 2.3.1**: 设备注册与密钥交换流程：
  ```kotlin
  // 客户端注册流程
  class DeviceRegistration {
      suspend fun register(): RegistrationResult {
          // 1. 生成设备标识
          val deviceInstanceId = UUID.randomUUID().toString()
          
          // 2. 获取服务器公钥（HTTPS）
          val publicKeyResponse = api.getPublicKey()
          
          // 3. 验证公钥指纹（可选：硬编码预期指纹）
          val expectedFingerprint = BuildConfig.SERVER_KEY_FINGERPRINT
          if (publicKeyResponse.fingerprint != expectedFingerprint) {
              throw SecurityException("Public key fingerprint mismatch")
          }
          
          // 4. 保存公钥用于后续加密
          keyManager.saveServerPublicKey(
              publicKeyResponse.publicKey,
              publicKeyResponse.keyId
          )
          
          // 5. 获取并保存 HMAC 密钥
          val hmacKeyResponse = api.getHmacKey(deviceInstanceId)
          encryptedPrefs.edit()
              .putString("hmac_key_id", hmacKeyResponse.hmacKeyId)
              .putString("hmac_key", hmacKeyResponse.hmacKey)
              .apply()
          
          // 6. 完成注册
          val deviceIdHash = generateHMAC(hmacKeyResponse.hmacKeyId, deviceInstanceId)
          return api.registerDevice(deviceIdHash)
      }
  }
  ```
- **Task 2.3.2**: HMAC 密钥管理（历史规划，当前运行时代码已由 `EnvelopeCryptoBox` 统一处理）：
  ```kotlin
  class StableUploadIdentity(context: Context) {
      fun generateDeviceHash(stableDeviceMaterial: String): String {
          // 当前实现：EnvelopeCryptoBox.getDeviceIdHash()
          return hmacSha256UrlSafe("ca-device-upload-id-v1:$stableDeviceMaterial")
      }
  }
  ```

#### Epic 2.4: 网络健壮性与断点续传
- **Task 2.4.1**: `NetworkEnvironmentDetector` 检测 WIFI / 优质蜂窝 / 劣质蜂窝。支持用户选择：`WiFiOnly` 或 `Unrestricted`（默认）。
- **Task 2.4.2**: `ErrorHandler`：根据 gRPC 错误码采用指数退避、立即重试或放弃策略。放弃后立刻清空内存和暂时落盘的所有数据，释放资源。
  ```kotlin
  class ErrorHandler {
      fun handleServerError(errorCode: String, packet: DataPacket) {
          when (errorCode) {
              "ERR_DECRYPT_DEK_FAILED" -> {
                  // 重新获取服务器公钥
                  refreshServerPublicKey()
                  retryWithNewKey(packet)
              }
              "ERR_REPLAY_DETECTED" -> {
                  // 重置序列号并重新开始
                  resetSequenceNumber()
                  clearPendingQueue()
              }
              "ERR_RATE_LIMIT_EXCEEDED" -> {
                  // 应用退避策略
                  val retryAfter = getRetryAfter(errorResponse)
                  scheduleRetry(packet, retryAfter)
              }
              "ERR_DEVICE_SUSPENDED" -> {
                  // 停止采集并通知用户
                  stopCollection()
                  notifyUserDeviceSuspended()
              }
              "ERR_KEY_VERSION_NOT_FOUND" -> {
                  // 获取新密钥版本
                  updateKeyVersion()
                  retryWithUpdatedKey(packet)
              }
          }
      }
  }
  ```
- **Task 2.4.3**: `ResumableUploader`：启动/网络恢复时按 Room 中 PENDING 顺序重试上传；。

- **Task 2.4.5**: 心跳机制实现：
  ```kotlin
  class ConnectionManager {
      private val heartbeatInterval = 30_000L // 30秒
      
      fun startHeartbeat() {
          scope.launch {
              while (isActive) {
                  delay(heartbeatInterval)
                  sendHeartbeat()
                  
                  // 等待心跳响应，超时则重连
                  withTimeoutOrNull(5000L) {
                      waitForHeartbeatAck()
                  } ?: reconnect()
              }
          }
      }
  }
  ```

---

### Phase 3: UI、用户体验与国际化

#### Epic 3.1: 主界面与状态监控
- **Task 3.1.1**: 主界面包含：开始/停止、当前采集状态、session_id、已采集时长、上传策略（Wi-Fi only / Unrestricted），隐私与撤回同意入口。
- **Task 3.1.2**: 数据可视化图表（可按需开启），UI 与数据采集解耦。

#### Epic 3.2: 权限与合规
- **Task 3.2.1**: 首次启动显示隐私协议并引导忽略电池优化。
- **Task 3.2.2**: 隐私协议明确说明：采集类型、用途、加密与缓存策略（加密数据可临时缓存于本地）、撤回同意流程与数据删除机制（包括服务器删除请求）。
- **Task 3.2.3**: 引导用户授权 `PACKAGE_USAGE_STATS`。注意 manifest 的 queries & usage stats 权限说明，以及采集最近10个使用的程序包名相关权限。

#### Epic 3.3: 国际化
- **Task 3.3.1**: 所有 UI 文本、组件文本 和 提示文本 以及 隐私协议 等等所有在前端显示的内容全部使用 `@string`，支持英文与中文资源（根据系统语言切换，默认英文，系统语言为中文时显示中文）。

---

### Phase 4: 智能传输与可观测性

#### Epic 4.1: 性能监控与优化
- **Task 4.1.1**: 实现完整的性能监控体系：
  ```kotlin
  class PerformanceMonitor {
      private val metrics = mutableMapOf<String, Metric>()
      
      fun trackLatency(operation: String, duration: Long) {
          metrics[operation]?.addSample(duration)
      }
      
      fun trackMemoryUsage() {
          val runtime = Runtime.getRuntime()
          val usedMemory = runtime.totalMemory() - runtime.freeMemory()
          val maxMemory = runtime.maxMemory()
          metrics["memory_usage"]?.setValue(usedMemory.toFloat() / maxMemory)
      }
      
      fun trackBatteryLevel(level: Int) {
          metrics["battery_level"]?.setValue(level.toFloat())
      }
      
      fun generateReport(): PerformanceReport {
          return PerformanceReport(
              avgLatency = metrics["upload_latency"]?.average() ?: 0f,
              memoryUsage = metrics["memory_usage"]?.current() ?: 0f,
              batteryDrain = calculateBatteryDrain()
          )
      }
  }
  ```

- **Task 4.1.2**: 动态性能调优：
  ```kotlin
  class DynamicOptimizer {
      fun optimizeBasedOnPerformance(report: PerformanceReport) {
          when {
              report.memoryUsage > 0.8f -> {
                  // 内存压力大，减少缓冲区
                  reduceBufferSize()
                  increaseCompressionLevel()
              }
              report.batteryDrain > 5f -> {
                  // 电池消耗过快
                  reduceSamplingRate()
                  enableBatchMode()
              }
              report.avgLatency > 1000f -> {
                  // 延迟过高
                  switchToWiFiOnly()
                  enableLocalCaching()
              }
          }
      }
  }
  ```

#### Epic 4.2: 调试 & 可观测性
增强开发者面板显示：
- 传输状态、gRPC 连接信息、内存样本数、磁盘队列大小、已发送/已丢弃数、传感器硬件信息、Keystore/StrongBox 支持信息、服务器策略显示
- 关键 metrics（batches.processed, uploads.success, upload.latency_ms, memory.usage, battery.drain_rate）
- 提供手动触发、清空队列、导出 pending（加密）包、强制 key rotation、导出 debug log 的功能（注意脱敏）
- 实时性能图表展示（本应用所占用的CPU、内存、网络、电池等资源）

#### Epic 4.3: 可观测性基础设施
- MetricsCollector、PerformanceMonitor 等模块，确保敏感数据不出现在遥测中（只上报聚合指标）
- 添加 Trace ID 支持，便于端到端调试

---

### Phase 5: CI、测试与部署（重新生成）
单元测试、Robolectric、Espresso、集成测试（包含大 payload / 并发上传 / key rotation /断网恢复场景）。

CI：包含 R8/ProGuard 混淆规则验证（特别是 Tink/gRPC/protobuf）。

发布：分阶段灰度，注意密钥管理与回滚策略。


---

## 4. 协议与 Protobuf（更新）
**说明**：下列 Protobuf 增强了 Envelope Encryption、分片、校验、压缩与元数据字段。

```protobuf
syntax = "proto3";
package ca.auth;

message DataPacket {
  string packet_id = 1;                  // 批次唯一标识, e.g., UUID
  string device_id_hash = 2;             // HMAC(key_id, device_instance_id) - 非明文
  int64 base_wall_ms = 3;                // 批次创建时的 wall clock (UTC, NTP 校准后)
  int64 device_uptime_ns = 4;            // 批次创建时的 elapsed monotonic time
  optional int64 ntp_offset_ms = 5;      // 客户端计算的 NTP 时钟偏移量

  // Envelope 加密字段
  bytes encrypted_sensor_payload = 6;    // Tink StreamingAead 或 AEAD 的密文（通常包含一个完整窗口/批次的数据）
  bytes encrypted_dek = 7;               // DEK 用服务器公钥加密后的密文（HybridEncrypt）
  string dek_key_id = 8;                 // 服务器用于解密的公钥版本标识

  Metadata metadata = 9;

  // 抗重放与序号
  int64 packet_seq_no = 10;              // 全局递增序号
  bytes sha256 = 11;                     // payload 或文件的 SHA256 校验（用于磁盘队列）
}

message Metadata {
  string app_version = 1;
  int32 android_api_level = 2;
  string schema_version = 3;
  string transmission_profile = 4;       // "WIFI_ONLY" / "UNRESTRICTED" —— 客户端上传偏好（非必需）
  string compression = 5;                // "lz4"/"none"
  string encryption_scheme = 6;          // "Envelope-StreamingAead-AES256GCM" 等
  string key_version = 7;                // 本次使用的 keyset/version
  int32 uncompressed_size_bytes = 8;     // 未压缩数据大小
  int64 client_processing_time_ms = 9;   // 客户端处理时间
}

message SerializedSensorBatch {
  repeated SensorSample samples = 1;
  string session_id = 3;
}

message SensorSample {
  enum SensorType { ACCELEROMETER = 0; GYROSCOPE = 1; MAGNETOMETER = 2; }
  SensorType type = 1;
  int64 event_timestamp_ns = 2;          // 相对时间戳 (elapsedRealtimeNanos)
  float x = 3;
  float y = 4;
  float z = 5;
  int32 accuracy = 6;
  int64 seq_no = 7;                      // 自增序号，保障一致性/防重放
  string foreground_app_name = 8;        // 当前前台应用的明文包名
}

// 服务端下发的指令消息
message ServerDirective {
  oneof directive {
    Ack ack = 1;
    PolicyUpdate policy = 2;
    KeyRotationNotice key_rotation = 3;
    EmergencyStop emergency = 4;
    AuthResult auth_result = 5;
  }
}

message Ack {
  string packet_id = 1;
  int64 creation_server_ts = 2;           // 服务器接收时间戳（必需）
  bool success = 3;
  string error_code = 4;                  // 错误码，用于客户端错误处理
  int32 retry_after_ms = 5;               // 建议重试时间
}

message PolicyUpdate {
  string policy_id = 1;
  string policy_version = 2;

  // 传输/批处理 配置（移除 fast/slow 特殊模式）
  int32 batch_interval_ms = 3;            // 建议的客户端批次窗口，单位 ms
  int32 max_payload_size_bytes = 4;       // 单个 DataPacket 最大字节数
  float upload_rate_limit = 5;            // 每秒允许上传的包数量（或字节率）

  // 传输配置
  string transmission_profile = 6;        // "WIFI_ONLY" / "UNRESTRICTED"
  string compression_algorithm = 7;       // "lz4"
  int32 batch_size_threshold = 8;         // 样本或时间阈值触发上传

  // 采集配置
  map<string, int32> sensor_sampling_rates = 9;  // 传感器采样率
  repeated string enabled_sensors = 10;

  // 用户特定配置
  map<string, string> user_config = 11;

  // 异常检测配置
  AnomalyConfig anomaly_config = 12;
}

message AnomalyConfig {
  bool enabled = 1;
  float threshold_multiplier = 2;         // 异常阈值倍数
  int32 window_size_sec = 3;              // 检测窗口大小
  int32 cooldown_period_sec = 4;          // 冷却期
}

message KeyRotationNotice {
  string new_key_id = 1;
  string public_key_fingerprint = 2;
  int64 effective_from_ts = 3;
  int64 deprecate_old_after_ts = 4;
}

message EmergencyStop {
  string reason = 1;
  int32 stop_duration_sec = 2;
  bool clear_local_cache = 3;
}

// 心跳消息定义
message Heartbeat {
  int64 client_timestamp = 1;
  int32 pending_packets = 2;              // 待发送数据包数
  int64 last_packet_seq_no = 3;
}

message HeartbeatAck {
  int64 server_timestamp = 1;
  int64 client_timestamp_echo = 2;        // 回显客户端时间戳
}

```

---

## 5. 安全/隐私/合规要点
- **标识处理**：不在网络中放明文设备 ID 或用户 ID；当前运行时不生成用户 ID。上传标识为稳定设备材料的 HMAC 摘要，前台应用包名按 `foreground_app_name` 作为样本上下文上传。
- **撤回同意**：用户可撤回同意，撤回后客户端删除本地缓存并向服务器发起删除请求（服务器需支持删除 API 并记录删除结果）。
- **法律合规**：准备并审查 GDPR / PDPA 风控文档（数据用途、最小化、访问、删除、数据传输、第三方依赖等）。

---

## 6. Key 发布、Pinning 与轮换流程
1. **首次注册**：客户端向注册端点获取服务器公钥及其指纹，验证后保存本地（用于 pinning）。
2. **上传时**：客户端在 `DataPacket` 中附 `dek_key_id`（指示使用哪把服务器公钥加密 DEK）。
3. **轮换**：服务器发布新的公钥版本并维护旧公钥的解密能力；客户端在PolicyUpdate/KeyRotationNotice下更新本地公钥指纹并切换默认 `dek_key_id`。
4. **兼容性**：服务器必须保留全部旧私钥，覆盖历史数据解密窗口，或提供按需解密服务。 

---

## 7. 上传标识与存储建议
- 当前实现不再使用安装时随机 UUID 作为上传标识材料。
- 上传标识由 Widevine 设备唯一材料摘要优先、`ANDROID_ID` 次之、硬件 `Build.*` 字段兜底的稳定材料 HMAC 得到。
- 该标识不依赖 App 私有存储中的随机值，普通卸载重装后保持稳定。
- Room / EncryptedSharedPreferences 可缓存中间状态，但缓存不是标识稳定性的来源。

---

## 8. 实施要点与工程注意事项

### 8.1 性能优化要点
- **AES-GCM**：当前使用共享密钥派生 AES-256-GCM 单包加密，与 server 解密格式一致。
- **单包大小限制**：server gRPC 默认最大消息大小 4MB，HTTP 管理接口最大请求由 server 配置控制。
- **压缩**：先序列化再压缩；推荐 LZ4 
- **Atomic file write**：写磁盘文件流程为 `write tmp -> fsync -> rename`
- **校验**：在 DB (Room) 中保存 `sha256` 并在读取时校验。校验失败标 `CORRUPT`
- **线程管理**：避免在主线程做序列化、压缩或加密操作；利用 IO Dispatcher/专用线程池

### 8.2 错误处理与恢复
- **统一错误处理器**：
  ```kotlin
  class UnifiedErrorHandler {
      fun handleError(error: AppError) {
          when (error) {
              is NetworkError -> handleNetworkError(error)
              is CryptoError -> handleCryptoError(error)
              is SensorError -> handleSensorError(error)
              is StorageError -> handleStorageError(error)
          }
      }
      
      private fun handleNetworkError(error: NetworkError) {
          when (error.code) {
              NetworkError.NO_CONNECTION -> enableOfflineMode()
              NetworkError.TIMEOUT -> retryWithBackoff()
              NetworkError.SERVER_ERROR -> switchToBackupServer()
          }
      }
  }
  ```

### 8.3 gRPC 配置优化
- **Keepalive**：30秒心跳，5秒超时
- **Max message size**：默认 4MB（可在 server 配置中调整）
- **线程池**：根据CPU核心数动态配置
- **流控策略**：动态窗口调整

### 8.4 ProGuard/R8 配置
- 包含 gRPC/protobuf 以及现有依赖的官方混淆规则
- CI 中验证混淆后功能完整性
- 保留关键调试信息用于崩溃分析

---

## 9. 术语表（简短）
- DEK：Data Encryption Key（历史规划术语，当前联调不发送有效 DEK）
- KEK：Key Encryption Key（历史规划术语）
- StreamingAead：历史规划中的流式 AEAD 接口，当前联调未使用
- Envelope Encryption：历史规划中的信封加密方案，当前联调未使用
- FIFO：First In First Out（硬件传感器缓冲区）
- AAD：Additional Authenticated Data（附加认证数据）

---

