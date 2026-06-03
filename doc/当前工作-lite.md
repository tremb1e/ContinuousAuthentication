# 当前工作说明

更新日期：2026-06-03

## 已完成

- App 工作区已回到“修复问题”版本，并合并“清理冗余前端”改动，跳过“整合 imsiimei 代码”。
- 运行时代码已移除用户 ID 管理：
  - 删除旧用户标识管理器；
  - 使用 `SessionManager` 仅管理当前 session；
  - `SerializedSensorBatch` 不再包含用户标识字段；
  - 服务器配置页不再显示用户 ID。
- 上传标识改为设备级稳定 HMAC，普通卸载重装后不依赖本地随机安装实例。
- App proto 与 server `protos/sensor_data.proto` 对齐：
  - `SerializedSensorBatch.samples = 1`；
  - `SerializedSensorBatch.session_id = 3`；
  - `SensorSample.foreground_app_name = 8`。
- 加密上传数据格式与 server 接收链路一致：
  - 明文为 `SerializedSensorBatch`；
  - 默认 LZ4 压缩；
  - AES-GCM 输出 `IV(12)|TAG(16)|ciphertext`。
- 持续认证页面展示 server 返回的模型、判定、分数、阈值、窗口和判定消息。
- “近期判定”上方已增加“认证结果分析”，支持手动开始/停止统计运行时间、通过次数、不通过次数和通过率。
- “近期判定”下方已改为“认证结果分析日志”，记录最近 10 段从开始到停止的分析结果。
- 锁屏后前台服务会暂停传感器采集和上传循环；解锁后若用户此前已启动采集/上传，会自动恢复。

## 当前默认联调配置

- App 默认 host：`ca.macrz.com`
- App 默认 scheme：`https`
- App 默认 port：`443`
- Server 内部 gRPC 端口：`10500`，由 OpenResty 反代到公网入口
- 公网联调入口：`https://ca.macrz.com:443`
- 共享加密 secret：`Continuous_Authentication`

用户输入 `ca.macrz.com:80`、`http://ca.macrz.com:80` 或旧后端地址 `ty.macrz.com:10500` 时，App 会归一化为 `https://ca.macrz.com:443`；公网 443 入口连接失败时不会回退到明文 h2c 或其它端口。

## 构建产物

Debug APK：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 注意事项

- Room `1.json` 是历史 schema，保留旧字段只表示旧数据库版本记录；当前运行时代码和 `2.json` 已移除该字段。
- 服务端内部训练/认证代码仍可能保留历史变量命名，但 gRPC 接收入口实际传入的是 `device_id_hash`。这属于 server 内部历史命名，不是 App 上传协议字段。
