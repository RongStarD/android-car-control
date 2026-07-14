# Rosmaster Android 控制协议 v1

控制通道使用 `ws://<Jetson-IP>:9093`，UTF-8 JSON 文本帧。控制桥的原始 MJPEG 位于 Jetson 本机 `9094/video.mjpg`，人脸服务读取该单一源后，在 `http://<Jetson-IP>:9095/video_feed` 输出带识别框的视频供 Android App 使用。

## 服务端消息

WebSocket 建立后，服务端首先发送协议握手；`state` 可以嵌套在 `hello` 中，也可以随后单独发送：

```json
{
  "op": "hello",
  "protocol": 1,
  "message": "Rosmaster bridge ready",
  "state": {
    "serial_ready": true,
    "camera_ready": true,
    "moving": false,
    "command": 7,
    "speed": 50,
    "duration_ms": 0,
    "follow_line": false,
    "message": "底盘已就绪"
  }
}
```

独立状态帧：

```json
{
  "op": "state",
  "serial_ready": true,
  "camera_ready": false,
  "moving": true,
  "command": 1,
  "speed": 50,
  "duration_ms": 0,
  "follow_line": false,
  "message": "前进中"
}
```

请求响应：

```json
{"op":"response","id":12,"ok":true,"message":"命令已执行"}
```

## App 请求

运动。`command` 为 1～6；`speed` 只能是 30、50、70、100；`duration_ms` 只能是 0、500、1000：

```json
{"op":"drive","id":12,"command":1,"speed":50,"duration_ms":0}
```

持续运动或寻迹租约续期。控制租约绑定当前 WebSocket 连接；`id` 只用于关联本次请求与响应，因此每次心跳可以使用新的 `id`：

```json
{"op":"heartbeat","id":13}
```

停车。`id` 同样只用于关联本次请求与响应：

```json
{"op":"stop","id":12}
```

寻迹开关：

```json
{"op":"follow_line","id":13,"enabled":true}
```

全局急停：

```json
{"op":"emergency_stop","id":14}
```

## 服务端必须实现的保护

1. 连续运动或寻迹启动后，如果约 700 ms 未从持有控制权的 WebSocket 连接收到 `heartbeat`，立即停车并关闭对应模式；其他连接不能代为续租。
2. `duration_ms=500/1000` 必须由服务端计时，不能只依赖 Android 定时器。
3. 收到 `stop`、`emergency_stop` 或 WebSocket 断开时立即停车。
4. `serial_ready=false` 时拒绝 `drive` 和 `follow_line=true`，通过 `response.ok=false` 返回原因。
5. 所有输入必须再次在服务端校验；App 端校验不能替代实车端保护。
