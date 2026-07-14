# Rosmaster 小车安卓遥控

这是一个全新的、独立的 Android 原生项目，只复现 Jetson 桌面快捷指令 `app` 的遥控能力，不包含 ROS、SLAM、建图或导航代码，也不依赖旧 Android 项目。

## 功能

- Kotlin + Jetpack Compose，固定竖屏，蓝色界面。
- 默认连接 `10.39.132.165:9093` 的 WebSocket 控制桥。
- 显示网络、底盘串口、摄像头、人脸识别和运动状态。
- 速度档：`30 / 50 / 70 / 100`。
- 持续时间：`持续 / 0.5s / 1s`。
- 前进、后退、左/右平移、左/右转、普通停止、全局紧急停止。
- 寻迹开关；寻迹开启时锁定手动方向控制。
- 原生解析 `http://Jetson-IP:9095/api/v1/video.mjpg` 的 MJPEG 视频并用 Compose 显示；客户端解码上限为 20 FPS，视频请求自动携带 `X-API-Key`，支持自动重连、手动重载和后台释放。
- 默认只显示流畅原始画面且不持续运行人脸推理；点击“开始识别（5 秒）”后临时显示带框画面、倒计时和已处理帧数，结束后展示姓名/相似度结果并自动恢复原始画面，也可提前结束。
- 从 9095 读取人脸数据库人数、样本数和版本，显示人员列表，并提供带二次确认的删除操作；新增和替换样本仍由人脸采集 App 完成。
- 独立“订单管理”页面按待完成、配送中、已完成、已取消筛选订单，显示用户、房间、商品和下单时间，并支持开始配送及二次确认取消；服务端全局只允许一个配送中订单。
- 开始配送后自动返回“控制与画面”，顶部显示当前收货人、房间和商品；点击“识别收货人（5 秒）”会把订单编号绑定到识别会话，人员匹配时由服务端原子完成订单，不匹配或无人脸时保持配送中。
- 下单时间按手机本地时区显示；订单列表尚未成功加载、刷新失败或正在执行状态变更时，识别按钮会锁定并提示先刷新，避免遗漏服务端已有配送任务后误发通用识别。
- App 重启后会从 9095 恢复配送中订单；订单和人脸状态空闲时每 5 秒刷新，识别会话运行时约每 500 ms 刷新进度。这些请求不依赖 9093 控制连接，9095 未手动启动时底盘遥控仍可单独使用。

## 实车命令映射

映射依据 Jetson 当前实际加载的 `app_sim.c/.so`，不是目录中可能过时的 `app_sim.py`：

| command | 功能 |
|---:|---|
| 1 | 前进 |
| 2 | 后退 |
| 3 | 左平移 |
| 4 | 右平移 |
| 5 | 左转/左旋 |
| 6 | 右转/右旋 |
| 7 | 停止（桥接协议使用独立的 `stop` 操作） |

## 安全规则

- 持续模式在按下方向按钮时发送 `drive`，每约 200 ms 发送一次 `heartbeat`，松手立即发送 `stop`。
- 定时模式发送 `duration_ms=500/1000`，服务端和 App 均应在到期后停车。
- 寻迹开启期间同样每约 200 ms 续租；关闭寻迹、断开连接或 App 进入后台时停止续租并停车。
- 从控制页面切换到订单管理或人脸数据库页面时，App 会自动关闭寻迹并停车。
- 切换到后台、销毁 Activity、主动断开和 ViewModel 清理都会先发送停车命令。
- 网络异常时客户端无法保证最后一帧送达，因此 Jetson 控制桥必须实现约 700 ms 的租约/看门狗超时停车，并在 WebSocket 断开时停车。
- 桌面 `app` 与 Jetson 控制桥都要访问底盘串口，不应同时运行，避免串口冲突。

完整报文见 [CONTROL_PROTOCOL.md](CONTROL_PROTOCOL.md)。

## 主要源码

| 文件 | 作用 |
|---|---|
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/MainActivity.kt` | Compose 界面、按住/松手手势、MJPEG 展示、生命周期停车 |
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/RosmasterControlViewModel.kt` | 控制状态、定时停车、手动与寻迹续租、安全联锁 |
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/model/ControlProtocol.kt` | JSON 协议、实车命令编号、状态模型与输入校验 |
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/network/WebSocketControlClient.kt` | WebSocket 连接、收发与断线处理 |
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/network/MjpegStreamClient.kt` | MJPEG 网络连接、JPEG 解码、重连与资源释放 |
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/network/MjpegFrameReader.kt` | multipart 拆帧、长度限制与 JPEG 完整性校验 |
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/network/FaceServiceClient.kt` | 9095 状态、按需识别、订单管理、人员列表和删除 API |

按需识别使用以下 9095 接口，视频始终保持同一个连接，无需在原始画面和带框画面之间切换 URL：

```text
POST   /api/v1/recognition/session   启动 5 秒识别
GET    /api/v1/recognition/session   查询进度和结果
DELETE /api/v1/recognition/session   提前结束
GET    /api/v1/video.mjpg            原始/带框动态视频流
GET    /api/v1/admin/orders?status=all              读取全部订单
POST   /api/v1/admin/orders/{order_id}/start        开始配送
POST   /api/v1/admin/orders/{order_id}/cancel       取消订单/配送
```

有配送中订单时，启动识别的请求体会附带 `order_id`。服务端只在识别出的 `person_id` 与该订单绑定人员一致时将订单更新为已完成；姓名仅用于界面显示，不作为身份关联键。

## Jetson 手动启动

厂商 `app.py`、桌面快捷程序、容器底盘驱动和本项目都会访问底盘串口，不能同时运行。先在对应终端按 `Ctrl+C` 关闭厂商程序，再执行：

```bash
cd ~/rosmaster-control-bridge
bash start_bridge.sh
sudo face-control start
```

`start_bridge.sh` 默认启用 `NO_VIDEO=1`，只启动 9093，不打开 `/dev/video*`，也不监听 9094。摄像头由 9095 人脸服务独占，手机只连接 9095 的识别后视频。

停止服务：

```bash
bash scripts/stop.sh
sudo face-control stop
```

控制桥启动脚本首次运行会在项目目录内安装依赖。人脸服务已取消开机自启，只有执行 `sudo face-control start` 时才会打开 9095；停止后 9095 会立即关闭。

## 构建

先在项目根目录的 `local.properties` 中配置与 Jetson `/etc/face-car.env` 相同的测试凭据：

```properties
FACE_API_KEY=<测试用 API Key>
```

该字段在构建时注入 App，不会显示在界面中。

项目根目录执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain "-Pandroid.injected.testOnly=false"
```

生成 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前验证产物（版本 `1.4.0`）：

- 31 项 JVM 单元测试全部通过（包含订单可靠状态门控、JSON null 清理、本地时区、中文错误映射、开始/取消配送、禁止状态变更请求自动重放和绑定订单的识别会话契约）；
- Android Lint：0 个问题；
- APK 大小：10,184,345 bytes；
- SHA-256：`45519FCC1B3C49F062F84750DA2EC613823653EB686DAE4847AECF289CEB6DAC`。
