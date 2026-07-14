# 用户人脸采集 App

独立 Android 原生应用，用手机系统相机或相册采集人员照片，并上传到 Jetson 人脸服务。小车摄像头的就绪状态和最近识别结果可在 App 中刷新查看。

## 功能

- 默认服务：`http://10.39.132.165:9095`，可编辑并在手机本地保存。
- API Key 由 `local.properties` 在构建时注入 `BuildConfig`，通过 `X-API-Key` Header 自动发送；界面不显示，也不需要重复输入。
- `local.properties` 已被 Git 忽略，不会提交到仓库。测试 APK 中仍包含该凭据，仅适合受控设备和局域网测试。
- 状态轮询仅在 Activity 处于 `STARTED` 前台阶段运行，进入后台立即停止并取消在途状态请求。
- 使用系统相机 `TakePicture` + `FileProvider`，无需由 App 直接占用相机硬件。相机临时 JPEG 在处理完成或取消后立即删除，启动时也会清理一小时前遗留的 `face_capture_*.jpg`。
- 使用系统 Photo Picker 一次多选图片。
- 最多 10 张样本；每张图片先纠正 EXIF 方向，再缩放到最长边不超过 1600 px，并以 JPEG 质量 85 重编码；重编码后单张不得超过 5 MiB，全部 JPEG 合计不得超过 20 MiB。
- 仅提供人脸录入和替换同名样本，不显示人员名单，也不提供人员库刷新或删除功能；数据库管理统一放在小车控制 App。
- 每 5 秒轮询服务状态，也可手动刷新小车摄像头状态、数据库统计和最近识别结果。
- 最近识别列表仅显示服务端标记为 `state=recognized` 且姓名、相似度有效的结果；JSON `null` 不会显示成文字 `"null"`。
- 状态页解析并显示 `recognition.enabled/ready/error/last_success_at`，可区分摄像头故障与推理引擎故障。
- 网络连接超时为 5 秒，读写超时为 120 秒。录入 POST 不做自动传输重试，避免响应丢失时重复创建样本。

## 服务 API

- `GET /api/v1/status`
- `POST /api/v1/enroll`

## 构建

先在项目根目录的 `local.properties` 中配置与 Jetson `/etc/face-car.env` 相同的测试凭据：

```properties
FACE_API_KEY=<测试用 API Key>
```

也可以通过 Gradle 属性或同名环境变量 `FACE_API_KEY` 注入。

```powershell
cd D:\Junior\xxq\Project\face-enrollment-app
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
```

Debug APK：`app\build\outputs\apk\debug\app-debug.apk`

当前验证产物：

- 12 项 JVM 单元测试全部通过；
- Android Lint：`No issues found.`；
- APK 大小：10,152,751 bytes；
- SHA-256：`DEAAD31CE33C3C1A17FB8689CC96B0B87BADD667E4048CF3686018A832F65138`。

该 APK 使用 Debug 签名，适合现场安装验收；正式分发前应使用项目自己的 Release
keystore 签名，不要把 keystore 或密码提交到源码。
