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
- 原生解析 `http://Jetson-IP:9095/video_feed` 的带框 MJPEG 视频并用 Compose 显示；视频请求自动携带 `X-API-Key`，支持自动重连、手动重载和后台释放。
- 从 9095 读取人脸数据库人数、样本数和版本，显示人员列表，并提供带二次确认的删除操作；新增和替换样本仍由人脸采集 App 完成。
- “控制与画面”主页面集中显示连接、设备状态、实时视频和遥控面板；人脸数据库位于独立管理页面。
- 人脸状态和数据库每 5 秒独立刷新，不依赖 9093 控制连接；9095 未手动启动时，底盘遥控仍可单独使用。

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
- 从控制页面切换到人脸数据库页面时，App 会自动关闭寻迹并停车。
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
| `app/src/main/java/cn/edu/xxq/rosmastercontrol/network/FaceServiceClient.kt` | 9095 状态、人员列表和删除 API |

## Jetson 手动启动

厂商 `app.py`、桌面快捷程序、容器底盘驱动和本项目都会访问串口/摄像头，不能同时运行。先在对应终端按 `Ctrl+C` 关闭厂商程序，再执行：

```bash
cd ~/rosmaster-control-bridge
bash start_bridge.sh
sudo face-control start
```

控制桥的 `9094/video.mjpg` 是 Jetson 本机内部原始视频源；手机只连接 9095 的识别后视频。这样 `/dev/video*` 只由控制桥读取一次，人脸服务不会再次打开摄像头设备。

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

当前验证产物（版本 `1.2.0`）：

- 16 项 JVM 单元测试全部通过；
- Android Lint：0 个问题；
- 模拟器直接覆盖安装和启动成功，已确认“控制与画面”包含实时视频和遥控面板，“人脸数据库”独立显示统计与人员管理；
- APK 大小：10,235,523 bytes；
- SHA-256：`A657B615EA3D4F0437A04D7778E6056107174488572801AC5AB9D7F8313E07FC`。
