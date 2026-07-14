# 用户人脸采集 App

独立 Android 原生应用，包含“人脸采集”和“商品下单”两个用户页面。用户可用系统相机或相册采集人员照片并上传到 Jetson，也可观看小车摄像头、发起一次 5 秒按需识别，并用已录入姓名填写房间号和商品订单。

## 功能

- 默认服务：`http://10.39.132.165:9095`，可编辑并在手机本地保存。
- 顶部提供两个真正独立的页面：切到“商品下单”时会释放 MJPEG 画面连接；切回“人脸采集”后再按页面生命周期恢复视频。
- 下单页只显示当前填写内容和本次成功提交的订单回执，不读取人员库、其他用户或全部订单。
- 最近一次成功录入返回的姓名与 `person_id` 会一起保存在 App 私有配置中，并自动带入下单页。订单同时发送 `person_id` 和姓名，避免同名人员错配；用户修改姓名后，必须先用该姓名重新完成人脸录入才能下单。
- 商品列表通过服务端目录加载，只显示 `enabled=true` 的商品；支持多选以及 1–99 数量增减。姓名、房间号和至少一件商品均通过校验后才能提交。
- 下单成功会显示订单号、房间号、商品摘要和“待完成”状态；订单随后由小车控制 App 的管理端继续处理。
- API Key 由 `local.properties` 在构建时注入 `BuildConfig`，通过 `X-API-Key` Header 自动发送；界面不显示，也不需要重复输入。
- `local.properties` 已被 Git 忽略，不会提交到仓库。测试 APK 中仍包含该凭据，仅适合受控设备和局域网测试。
- 状态轮询仅在 Activity 处于 `STARTED` 前台阶段运行，进入后台立即停止并取消在途状态请求。
- 实时画面始终读取 Jetson 的 `GET /api/v1/video.mjpg`；空闲时是原始流，按需识别期间由服务端切换为带框流。视频请求自动携带内置 API Key，App 不会为了观看视频占用手机摄像头。
- 点击“开始识别”创建一次 5 秒会话，界面显示倒计时和已处理帧数；可提前结束，完成后显示识别姓名、相似度和出现次数，或明确显示“不确定 / 未知 / 未检测到人脸”。
- 视频连接与识别会话轮询仅在 Activity 前台运行，离开页面立即关闭 MJPEG 连接并取消在途轮询。
- 使用系统相机 `TakePicture` + `FileProvider`，无需由 App 直接占用相机硬件。相机临时 JPEG 在处理完成或取消后立即删除，启动时也会清理一小时前遗留的 `face_capture_*.jpg`。
- 使用系统 Photo Picker 一次多选图片。
- 最多 10 张样本；每张图片先纠正 EXIF 方向，再缩放到最长边不超过 1600 px，并以 JPEG 质量 85 重编码；重编码后单张不得超过 5 MiB，全部 JPEG 合计不得超过 20 MiB。
- 仅提供人脸录入和替换同名样本，不显示人员名单，也不提供人员库刷新或删除功能；数据库管理统一放在小车控制 App。
- 每 5 秒轮询服务状态，也可手动刷新小车摄像头状态、数据库统计和最近识别结果。
- 最近识别列表仅显示服务端标记为 `state=recognized` 且姓名、相似度有效的结果；JSON `null` 不会显示成文字 `"null"`。
- 状态页解析并显示 `recognition.enabled/ready/error/last_success_at`，可区分摄像头故障与推理引擎故障。
- 网络连接超时为 5 秒，读写超时为 120 秒。录入 POST 不做自动传输重试，避免响应丢失时重复创建样本。
- 商品目录请求在 App 退到后台时会立即取消；订单 POST 同样不做自动重试，并由 `ViewModel` 持有，不引用 Activity，避免前后台切换造成重复提交或界面回调泄漏。退出并销毁任务时会取消全部在途请求。

## 服务 API

- `GET /api/v1/status`
- `POST /api/v1/enroll`
- `GET /api/v1/video.mjpg`
- `POST /api/v1/recognition/session`（启动 5 秒识别）
- `GET /api/v1/recognition/session`（读取倒计时和结果）
- `DELETE /api/v1/recognition/session`（提前结束）
- `GET /api/v1/catalog`（读取可下单商品）
- `POST /api/v1/orders`（提交姓名、房间号和商品数量，返回本次订单）

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

- App 版本：`1.4.0`（versionCode 5）；
- 28 项 JVM 单元测试全部通过；
- Android Lint：0 errors、0 warnings；
- APK 大小：10,218,345 bytes；
- SHA-256：`DED425E25155DCBEAF7DF6A16C19C59493C9051AE6D3DE865995466BE19BF76B`。

该 APK 使用 Debug 签名，适合现场安装验收；正式分发前应使用项目自己的 Release
keystore 签名，不要把 keystore 或密码提交到源码。
