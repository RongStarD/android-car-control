# 自动导航功能交接包

此目录只包含导航基础环境、DWA/TEB 启动、初始位姿、目标点、取消目标与停止导航；不包含手动 `/cmd_vel` 控制、建图或地图保存。

提交时只需执行：

```powershell
git add contributions/navigation
git commit -m "feat: add Nav2 navigation workflow"
```

Android 侧的 `NavigationCommandClient` 通过 `NavigationTransport` 对接现有 WebSocket 网关。Jetson 侧 `navigation_service.py` 提供对应 ROS 服务，以及 `/app/initial_pose`、`/app/goal_pose` 两个 `PoseStamped` 话题。使用前必须已有可用地图，且先完成初始位姿设置。
