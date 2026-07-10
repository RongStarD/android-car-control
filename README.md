# 智能小车 Android 控制端

这是现有 Web 控车项目的 Android 原生客户端，采用 Kotlin + Jetpack Compose。它不再需要 Node.js 充当手机和小车之间的 TCP 转发层。

## 已实现

- 课程参考项目兼容的 `TCP:6000` 控制帧：按钮、摇杆、四轮独立速度、拍照、录像、循迹。
- 现有 Jetson ROS Bridge 兼容的 `HTTP:8081` 控制：按钮、摇杆、紧急停止。
- 按下移动、松手停车；摇杆限频为 50 ms，松手会立刻发送停车。
- 应用退到后台时请求停车，连接参数保存在手机本地。
- 可选加载课程参考项目使用的视频页面：`http://IP:视频端口/index2`。

## 打开与构建

1. 安装 Android Studio，并在 SDK Manager 中安装 Android 15 (API 35) SDK Platform 与 Build-Tools。
2. 用 Android Studio 打开本目录：`android-car-control`。
3. 等待 Gradle Sync 完成，选择真机或模拟器后运行。

命令行环境已配置 Android SDK 时，可以运行：

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

## 连接模式

| 模式 | 地址 | 用途 |
| --- | --- | --- |
| Jetson ROS | `10.39.132.165:8081` | 当前建议路径。先按项目根目录的 `scripts/start-m3-web-bridge.ps1` 启动临时 Bridge。 |
| 课程 TCP | `小车 IP:6000` | 课程鸿蒙参考项目的原始协议。真车必须实际监听此端口。 |

`Jetson ROS` 模式会调用小车端 Bridge 的 `/api/button`、`/api/joystick` 和 `/api/stop`，最终发布到 `/cmd_vel`。它复刻当前 `m3` 键盘控制的速度配置，不支持课程 TCP 的拍照、录像、循迹和独立轮速。

## 无车验证

先在电脑运行模拟 TCP 小车，并让它监听局域网地址：

```powershell
cd D:\大三下\xxq\Project\web-car-control
$env:MOCK_HOST = "0.0.0.0"
npm run mock
```

手机和电脑接入同一 Wi-Fi 后，在 Android App 中选择“课程 TCP”，填电脑的局域网 IPv4 地址与端口 `6000`。可验证连接、方向按钮、摇杆和四轮速度帧。模拟器不能使用 `127.0.0.1` 连接 Windows 主机；物理手机也不能使用该地址。

## 安全测试顺序

1. 小车空闲、周围留出安全空间，首次测试建议架空车轮。
2. 连接后先点击“紧急停止”。
3. 只短按一个方向按钮，确认松手后立即停车。
4. 再测试摇杆与视频。断开连接或切换应用时，客户端会请求停车，但这不能替代小车端的安全限速与看门狗。
