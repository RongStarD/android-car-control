# 戴善嵘：人脸识别与连接基础设施新增代码

本目录是本分支唯一允许提交和推送的成员目录，内容均来自当前未提交的新文件，不包含其他三人的订单或视频代码。

## 人脸录入与识别

`face/face_identity.py` 是独立的人脸录入、识别、名单管理和摄像头识别工具，使用 YOLO 检测、YuNet 五点对齐和 SFace 特征比对。模型和 `face_data` 样本不提交；运行时从项目本地 `models` 目录提供模型。

## ROSBridge / SSH 连接

- `rosbridge/android/RosBridgeGateway.kt`：Android WebSocket 连接、ROS 话题订阅、`/cmd_vel`、地图、定位和导航服务调用。
- `rosbridge/jetson/robot_app_api.py`：Jetson ROS2 服务入口。
- `rosbridge/jetson/app_bridge.py`：二进制 AppBridge WebSocket 桥。
- `rosbridge/jetson/inspect_navigation_topics.sh`：导航话题检查。
- `rosbridge/scripts/*.ps1`：通过 SSH/SCP 启停桥接并检查 Jetson。

`start-rosbridge-slam.ps1` 是原文件名；当前实现会通过 SSH 部署 `robot_app_api.py` 和二进制 `app_bridge.py`，并停止旧的 `rosbridge_websocket`，README 以实际代码行为为准。

## 已提交内容不重复

CI/CD 已在远端提交 `7967fd6`（`ci: add Android release workflow`），按“之前已经提交过的不用管”要求，本目录不复制该工作流。

基础检查：

```powershell
python -m py_compile face/face_identity.py rosbridge/jetson/robot_app_api.py rosbridge/jetson/app_bridge.py
```
