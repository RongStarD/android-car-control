# 小车移动功能交接包

此目录只包含手动移动和紧急停止：前进、后退、横移、旋转、摇杆速度换算与 `/cmd_vel` 发布；不包含建图、地图保存、初始位姿、目标点或 Nav2。

提交时只需执行：

```powershell
git add contributions/movement
git commit -m "feat: add manual vehicle movement controls"
```

Android 侧的 `MovementCommandClient` 通过 `MovementTransport` 与现有 WebSocket 网关衔接；Jetson 侧 `movement_bridge.py` 是只发布 `/cmd_vel` 的独立 WebSocket 服务，默认端口为 `9093`。首次实车测试必须架空车轮或留出安全距离。
