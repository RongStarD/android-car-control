# V2 Jetson 导航后端补丁

本目录只服务 `car-app-v2`。它不会修改冻结的旧 Android 项目，也不会自动启动 m1、n1、DWA 或 TEB。补丁基线是当前容器 1 中使用的 `/tmp/app_bridge.py` 与 `/tmp/robot_app_api.py`。

## 修复内容

| 文件 | 修复 |
|---|---|
| `app_bridge.py` | 占据栅格二进制头使用传入的 `packet_type`，因此局部代价地图为类型 4、全局代价地图为类型 5，不再都伪装成主地图类型 1 |
| `app_bridge.py` | 最后一个 9092 客户端断开时先发全零 `/cmd_vel`，再调用 `/app/emergency_stop`，由 API 停止 n3/n4 并保留 n1 |
| `app_bridge.py` | `/initialpose` 使用 ROS 零时间戳（最新 TF），避免实车上数十毫秒时差造成 AMCL “extrapolation into the future” |
| `app_bridge.py` | 类型 7 的 `/local_plan` 在 Jetson 从 `odom` 转为 `map` 后编码；类型 4 局部代价地图保持原始 `odom` 网格，并通过状态字段明确标注坐标系 |
| `app_bridge.py` | 离开导航模式或开始新的 n3/n4 会清除缓存的类型 4–7 数据和局部坐标系状态，后连接的 App 不会收到上一轮导航覆盖物 |
| `robot_app_api.py` | 显式跟踪 `send_goal_async` 尚未返回的目标；即使在该窗口取消/急停，goal handle 返回后也会立即取消 |
| `robot_app_api.py` | 普通取消/接管期间以 50 Hz 持续发布零速度，直到同一目标进入终态；无目标时也保留 1 秒安全窗口 |
| `robot_app_api.py` | 紧急停止在请求取消后直接结束 n3/n4，保留 n1 底盘与雷达，并把运行态切回 `navigation_base/checking`；不停止容器、不触发蜂鸣器 |
| `robot_app_api.py` | 发布带序号的结构化目标状态，只有 NavigateToPose 最终结果后才报告 succeeded/canceled/aborted 并解除目标保护 |
| `robot_app_api.py` | Nav2 就绪必须发现 AMCL 的 `/initialpose` 订阅；监督节点自己的订阅不再导致假就绪 |
| `robot_app_api.py` | 发布初始位姿后必须收到更新的 `/amcl_pose` 才继续检查代价地图；45 秒仍未收到会明确提示重新发布初始位姿 |
| `robot_app_api.py` | n1 就绪同时要求新鲜 `/odom`、`/scan`、`odom→base_footprint` 动态 TF 和 `base_link→laser` 静态 TF；启动/停止环境会清除旧健康证据并重新读取静态 TF |
| `robot_app_api.py` | DWA/TEB 每次启动和停止都清除旧代价地图时间；只有晚于本次 `/amcl_pose` 的局部/全局代价地图才可使导航就绪 |
| `robot_app_api.py` | 启动导航前停止手工 m3 `yahboom_keyboard`，但保留 n1 内只有 Joy 输入时才发布的 `joy_ctrl` |
| `restart_v2_runtime.sh` | 固定 ROS Domain 30；等待旧进程退出，必要时升级到 SIGTERM，残留时拒绝启动重复实例；启动后要求 API/Bridge 各恰好一个 |

## WebSocket 状态协议

`op=state` 同时提供兼容嵌套对象和平铺字段：

```json
{
  "navigation_goal": {"sequence": 12, "state": "cancel_requested", "result_status": null},
  "navigation_goal_sequence": 12,
  "navigation_goal_state": "cancel_requested",
  "navigation_result_status": null,
  "local_overlay_frame": "odom",
  "local_plan_frame": "map"
}
```

目标状态为 `idle`、`pending_accept`、`active`、`cancel_requested` 以及终态 `succeeded`、`canceled`、`aborted`、`finished`、`failed`、`rejected`、`stopped`。真实 Action 结果的 `result_status` 保留 Nav2 状态码（成功 4、取消 5、失败 6）；本地 stop/reject/failed 为 `null`。同一个目标从发送到终态保持相同 `sequence`。

类型 4 协议没有 frame id 和栅格原点旋转，不能把滚动的 `odom` 局部代价地图无损伪装成 `map`；Android 必须在 `local_overlay_frame != map` 时隐藏它或明确提示。类型 7 已由 Bridge 转成 `map`，TF 尚不可用时不会发送伪坐标路径。

## 文件说明

- `patches/*.patch`：可审阅、可反向应用的最小补丁。
- `deploy_navigation_v2.sh`：先检查补丁上下文，再备份、应用并执行 Python 语法与安全不变量校验；默认不重启。
- `rollback_navigation_v2.sh`：恢复部署脚本记录的最近备份；默认不重启。
- `restart_v2_runtime.sh`：先发零速度，再只重启 API 与 9092 Bridge；不会启动小车功能进程。
- `verify_navigation_v2.sh`：默认只做静态校验；`--live` 只检查已运行服务；额外参数可验证断开零速。
- `verify_navigation_v2_static.py`：检查补丁必须具备的目标竞态、零速 guard、坐标系、Domain 30 与单实例约束。

## 从 Windows 部署到容器 1

以下命令在 PowerShell 执行。示例使用 Jetson `10.224.104.165`、容器名 `nifty_dirac`，没有在文件中保存密码。

先生成一个不会与旧上传目录冲突的名称并上传：

```powershell
$Tag = "car-app-v2-jetson-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$Local = "D:\Junior\xxq\Project\car-app-v2\jetson"
scp -r $Local ("jetson@10.224.104.165:/tmp/" + $Tag)
ssh jetson@10.224.104.165 ("docker cp /tmp/" + $Tag + " nifty_dirac:/tmp/" + $Tag)
```

只应用补丁并保留当前运行进程，便于先审查：

```powershell
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/deploy_navigation_v2.sh --target /tmp'")
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/verify_navigation_v2.sh --target /tmp'")
```

审查通过后安全重启 API 与 Bridge：

```powershell
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/restart_v2_runtime.sh --target /tmp --port 9092'")
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/verify_navigation_v2.sh --target /tmp --port 9092 --live'")
```

部署和重启也可合并为一次：

```powershell
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/deploy_navigation_v2.sh --target /tmp --restart'")
```

## 回滚

部署脚本会把修改前的两个文件保存在：

```text
/tmp/car-app-v2-backups/<UTC时间>/
```

同时把最近一次备份路径写入 `/tmp/.car-app-v2-navigation-backup`。恢复并安全重启：

```powershell
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/rollback_navigation_v2.sh --target /tmp --restart'")
```

若要选择历史备份，显式传入目录：

```powershell
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/rollback_navigation_v2.sh --target /tmp --backup /tmp/car-app-v2-backups/20260713T120000Z --restart'")
```

## 安全校验

查看进程、服务和日志，不发送目标也不启动底盘：

```powershell
ssh -t jetson@10.224.104.165 "docker exec nifty_dirac bash -ic 'pgrep -af [r]obot_app_api.py; pgrep -af [a]pp_bridge.py; ros2 service list | grep ^/app/; tail -n 80 /tmp/robot-app-api.log; tail -n 80 /tmp/app-bridge.log'"
```

验证“最后客户端断开即安全取消”前，必须先让手机 App 断开 9092；脚本发现已有客户端会拒绝测试。该测试既核对零速度帧，也从 Bridge 日志确认 `/app/emergency_stop` 已受理，不发送任何非零速度：

```powershell
ssh -t jetson@10.224.104.165 ("docker exec nifty_dirac bash -ic 'bash /tmp/" + $Tag + "/verify_navigation_v2.sh --target /tmp --port 9092 --disconnect-stop-test'")
```

导航启动后，可用以下命令核对 AMCL，而不发布初始位姿或目标：

```powershell
ssh -t jetson@10.224.104.165 "docker exec nifty_dirac bash -ic 'ros2 node list | grep -x /amcl; ros2 topic info -v /initialpose || ros2 topic info /initialpose'"
```

验收时应看到 `/initialpose` 的订阅端包含 `amcl`。只有 `robot_app_api` 时，运行状态必须停留在 `waiting_localizer`，不能显示 Nav2 已就绪。
