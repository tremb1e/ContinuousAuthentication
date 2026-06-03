
---

# **Continuous Authentication - Android 持续认证数据采集器**
> **版本**：Lite v1.0  
> **最后更新日期**：2026-06-03
> **状态**：功能规格说明书  

---

## **0. 当前实现说明**

本文保留早期功能规划内容；与当前代码冲突时，以 `2025-11-18-技术方案.md`、`2025-11-18-需求规格.md` 和当前 app/server proto 为准。当前实现的关键差异如下：

- 主链路为 gRPC `SensorDataService.StreamSensorData`；App 默认公网入口为 `https://ca.macrz.com:443`，server 内部 gRPC 端口 `10500` 仅由 OpenResty 反代访问。
- 上传体为 Protobuf `DataPacket`，`encrypted_sensor_payload` 内部是 LZ4 压缩后的 `SerializedSensorBatch`，再经 AES-GCM 加密为 `IV(12)|TAG(16)|ciphertext`。
- 不再生成、保存或上传用户 ID；`SerializedSensorBatch` 只包含 `samples` 与 `session_id`。
- “上传标识 (HMAC)”由硬件稳定材料派生，普通卸载重装后不依赖 App 私有随机值。
- 前台应用字段为 `foreground_app_name`，内容为当前前台应用的明文包名，并随加密 payload 上传。
- 持续认证 UI 显示的模型、分数、阈值、窗口、EMA / y-of-x 文本均来自 server 响应。

## **1. 项目概述**

### **1.1 项目简介**
Android 持续认证数据采集器是一个专门用于收集移动设备传感器数据的应用程序，通过分析用户的行为模式实现连续身份验证。  
系统采用**端到端加密**确保数据安全，支持**实时数据采集**、**本地缓存**和**云端传输**。  

---

## **2. 系统架构**

### **2.1 架构模式**
采用 **MVVM (Model-View-ViewModel)** + **Repository Pattern** 架构模型：  
```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Presentation  │ ←→ │    Domain Layer   │ ←→ │   Data Layer    │
│   (UI/ViewModel)│    │   (Use Cases)     │    │ (Repository)    │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                                         ↓
                    ┌──────────────────┬─────────────────────────────────┐
                    │   Local Storage  │        Remote Data Source       │
                    │  (Room Database) │      (Network API Client)       │
                    └──────────────────┴─────────────────────────────────┘
```

### **2.2 核心模块**
- **SensorModule**：传感器数据采集与预处理  
- **CryptoModule**：数据加密与解密  
- **NetworkModule**：网络传输与同步管理  
- **UIModule**：用户界面与交互  

---

## **3. 分阶段开发流程**

### **Phase 1: 核心数据链路与持久化**
**目标**：实现从数据采集 → 序列化 → 压缩（LZ4） → 加密（封装）→ 传输的端到端数据链路。  

#### **Epic 1.1: 传感器数据采集**  
**状态**：已实现，需测试验证  

##### **Task 1.1.1：设计 `SensorCollector` 模块**
技术实现细节：
- 使用 `SensorManager` 注册的核心传感器：
  - 加速度计 `TYPE_ACCELEROMETER`
  - 陀螺仪 `TYPE_GYROSCOPE`
  - 磁力计 `TYPE_MAGNETIC_FIELD`

##### **Task 1.1.2：采样率与硬件适配**
- **目标采样率**：100Hz  
- **实际采样率检测**：记录设备的最大支持采样率，超出设备能力时自动降级。  
- **FIFO 缓冲优化**：
```kotlin
val fifoMaxEventCount = sensor.fifoMaxEventCount
val maxReportLatencyUs = if (fifoMaxEventCount > 0) {
    (fifoMaxEventCount * 1_000_000L) / targetSamplingRate
} else 0L
```

##### **Task 1.1.3：数据窗口化与打包**
- **窗口大小**：1 秒（1000ms）  
- **重叠策略**：无重叠，连续窗口  
- **数据对齐**：保留原始时间戳，由服务端进行对齐  

---

#### **Epic 1.2: 时间戳记录与校准**
- **NTP 同步策略**：应用启动时同步一次，每整点重新同步  
- **时间参考**：
  - `event.timestamp`：`SystemClock.elapsedRealtimeNanos()`（纳秒）  
  - `wall_clock_base`：启动时 UTC 毫秒时间戳  
  - `elapsed_base`：启动时的 `elapsedRealtimeNanos()`  


---

#### **Epic 1.3: 端侧加密模块**
- **算法**：AES-256-GCM  
- **密钥管理**：  
  - 暂时使用**固定对称密钥**（开发测试用），密钥为“Continuous_Authentication” .

---

### **Phase 2: 网络传输与数据持久化**

#### **Epic 2.1: HTTP 传输层**
- **协议**：HTTPS（TLS 1.2+）  
- **请求方法**：`POST`
- **数据格式**：`application/octet-stream` 二进制加密包  

---

### **Phase 3: UI、用户体验与国际化**

#### **Epic 3.1: 主界面设计**

##### **Task 3.1.2：实时数据可视化**
- 使用 `Canvas` 绘制实时波形图  
- 支持多传感器叠加显示  
- 支持缩放时间轴和数值轴  
- 限制帧率 ≤ 30fps 优化性能  

---

#### **Epic 3.2: 权限与合规管理**
**状态**：已实现，需测试  

##### **Task 3.2.1：权限申请流程**
必需权限：
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions"/>
```
权限引导：
1. 首次启动显示权限说明  
2. 按需逐项申请  
3. 引导关闭电池优化  
4. 引导授予使用统计权限  

##### **Task 3.2.2：隐私协议与用户同意**
- 收集范围：传感器数据、设备状态、应用使用统计  
- 用途说明：持续身份认证研究  
- 安全措施：端到端加密，最小化存储  

---

#### **Epic 3.3: 多语言国际化**
- 默认语言：英文  
- 已支持：简体中文  


**关键字符串示例**：
```xml
<!-- values/strings.xml -->
<string name="app_name">Continuous Authentication</string>
<string name="collection_start">Start Collection</string>
<string name="collection_stop">Stop Collection</string>
<string name="privacy_policy_title">Privacy Policy</string>

<!-- values-zh/strings.xml -->
<string name="app_name">持续认证</string>
<string name="collection_start">开始采集</string>
<string name="collection_stop">停止采集</string>
<string name="privacy_policy_title">隐私协议</string>
```

---

### **Phase 4: 系统状态与资源监控**
**状态**：已实现，需测试  

#### **Epic 4.1: 后台服务管理**
- 前台服务运行，使用通知栏提示数据采集进行中，采用 `START_STICKY` 保证异常退出重启。  

#### **Epic 4.2: 设备状态监控**
- 屏幕状态：`ON`, `OFF`, `DIMMED`  
- 系统指标：电池、温度、CPU、内存、存储等  

##### **屏幕状态监听**：
```kotlin
class ScreenStateMonitor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> resumeCollection()
            Intent.ACTION_SCREEN_OFF -> pauseCollection()
        }
    }

    private fun pauseCollection() {
        // 暂停传感器监听，保留会话状态
        sensorCollector.pause()
    }

    private fun resumeCollection() {
        // 恢复传感器监听
        sensorCollector.resume()
    }
}
```

---

#### **传感器数据包模型**（参考）：
```kotlin
@Serializable
data class SensorDataPacket(
    @SerialName("session_id")
    val sessionId: String,                    // 采集会话ID

    @SerialName("packet_seq_no")
    val packetSeqNo: Long,                   // 包序列号

    @SerialName("timestamp_ms")
    val timestampMs: Long,                   // UTC时间戳(毫秒)

    @SerialName("window_start_ms")
    val windowStartMs: Long,                 // 数据窗口开始时间

    @SerialName("window_end_ms")
    val windowEndMs: Long,                   // 数据窗口结束时间

    @SerialName("device_id_hash")
    val deviceIdHash: String,                // 设备ID哈希

    @SerialName("dek_key_id")
    val dekKeyId: String,                    // 数据加密密钥ID

    @SerialName("sensor_readings")
    val sensorReadings: List<SensorReading>, // 传感器读数列表

    @SerialName("system_stats")
    val systemStats: SystemStats,            // 系统状态信息

    @SerialName("context_info")
    val contextInfo: ContextInfo             // 上下文信息
)

@Serializable
data class SensorReading(
    @SerialName("sensor_name")
    val sensorName: String,                  // accelerometer/gyroscope/magnetometer

    @SerialName("sensor_type")
    val sensorType: Int,                     // Android sensor type constant

    @SerialName("timestamp_ns")
    val timestampNs: Long,                   // 传感器事件时间戳(纳秒)

    @SerialName("values")
    val values: SensorValues,                // 传感器数值

    @SerialName("accuracy")
    val accuracy: Int                        // 精度等级 (0-3)
)

@Serializable
data class SensorValues(
    @SerialName("x") val x: Float,
    @SerialName("y") val y: Float,
    @SerialName("z") val z: Float,
    @SerialName("scalar") val scalar: Float? = null  // 用于标量传感器
)

@Serializable
data class SystemStats(
    @SerialName("battery_level")
    val batteryLevel: Int,                   // 电池电量百分比

    @SerialName("temperature")
    val temperature: Float,                  // 设备温度(摄氏度)

    @SerialName("is_charging")
    val isCharging: Boolean,                 // 是否正在充电

    @SerialName("cpu_usage")
    val cpuUsage: Float,                     // CPU使用率(0-1)

    @SerialName("memory_usage")
    val memoryUsage: Long,                   // 内存使用量(字节)

    @SerialName("network_type")
    val networkType: String                  // WIFI/CELLULAR/NONE
)

@Serializable
data class ContextInfo(
    @SerialName("foreground_app_name")
    val foregroundAppName: String,           // 前台应用包名

    @SerialName("screen_state")
    val screenState: String,                 // ON/OFF/DIMMED

    @SerialName("recent_apps")
    val recentApps: List<String>,           // 最近使用的应用列表
)
```

### **5.3 API接口规格（历史参考）**

当前 App 不再按下列 JSON body 直接上传。现行网络包是 gRPC `DataPacket`；该小节仅作为早期 HTTP Lite 设计参考。

#### **数据上传接口（需补全必要内容）**：
```http
POST /api/v1/sensor-data
Content-Type: application/octet-stream
X-Device-ID-Hash: <device_id_hash>
X-Session-ID: <session_id>
X-Packet-Sequence: <packet_seq_no>
<binary_encrypted_envelope>
```

##### **binary_encrypted_envelope 历史参考**：
```json
{
  "device_id_hash": 123456789,
  "session_id": 123456789,
  "packet_seq_no": 123456789,
  "timestamp_ms": 1643723400000,
  "window_start_ms": 1643723400000,
  "window_end_ms": 1643723401000,
  "type": "sensor",
  "foreground_app_name": "com.example.app",
  "sensor_data": [
    {
      "sensor_name": "accelerometer",
      "sensor_type": 1,
      "timestamp_ns": 123456789,
      "values": { "x": 0.1, "y": 0.2, "z": 9.8 },
      "accuracy": 3
    }
  ]
}
```
