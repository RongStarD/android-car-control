# Rosmaster 独立控制桥

本目录把原桌面快捷指令 `app` 的底盘控制、寻迹开关和摄像头画面提供给新的 Android App。它直接调用主机 `Rosmaster_Lib`，不依赖 ROS、Docker 或容器，也不修改 `/home/jetson/Rosmaster-App/rosmaster`。

默认地址：

- WebSocket：`ws://<Jetson-IP>:9093`
- MJPEG：`http://<Jetson-IP>:9094/video.mjpg`
- 摄像头状态：`http://<Jetson-IP>:9094/healthz`

## 安全设计

- 单进程只创建一个 `Rosmaster`，使用进程锁和 Linux `TIOCEXCL` 独占串口。
- 启动前检查 `/dev/myserial`、厂商 `app.py`/桌面程序、测试程序及已知底盘驱动。发现占用只拒绝启动，绝不杀进程或抢占。实车使用前须人工关闭厂商 App，并停止容器底盘驱动。
- 手动运动和寻迹互斥，切换前先停车。
- 持续运动和寻迹租约均为 700 ms，Android 应每 200～300 ms 发送 `heartbeat`；500/1000 ms 定时运动到期自动停。
- 控制者连接断开立即停；最后客户端断开也停。紧急停止、硬件异常和服务退出均下发命令 7。
- 实车模式强制调用厂商 `license.checkLicense("icar")`，校验输出被屏蔽，不写授权内容；仅 `--mock` 跳过。

本协议无账号认证，只能用于受控局域网，不要将端口暴露到公网。

## 安装与运行

把 `jetson` 目录复制到 Jetson 的新目录（不要放入厂商源码目录）：

一键启动（首次运行会自动建立独立运行环境并安装依赖）：

```bash
cd ~/rosmaster-control-bridge
bash start_bridge.sh
```

如需分别执行安装、检查和启动，也可以使用：

```bash
bash scripts/install.sh
bash scripts/verify.sh
bash scripts/start.sh
```

停止：`bash scripts/stop.sh`。日志在 `run/bridge.log`。可通过环境变量配置：

```bash
CAMERA_DEVICE=/dev/video2 WS_PORT=9093 VIDEO_PORT=9094 bash scripts/start.sh
NO_CAMERA=1 bash scripts/start.sh
VENDOR_ROOT=/home/jetson/Rosmaster-App/rosmaster bash scripts/start.sh
```

安装脚本创建不依赖 `ensurepip` 的 `.venv`，复用 Jetson 已有 cv2/numpy，并把 Python 3.8 兼容的 `websockets==10.4` 和授权校验所需的 `rsa==4.9` 安装到项目自己的 `.deps` 目录。因此不需要 `sudo` 或 `python3.8-venv`，也不会修改系统包或厂商目录。

## Windows/Linux 模拟

模拟模式不打开串口、不校验授权、不打开摄像头；MJPEG 返回 503，但控制协议完整可用：

```bash
python -m venv .venv
# 安装 requirements.txt 后：
python bridge_server.py --mock --no-camera --host 127.0.0.1
```

Android 模拟器访问 Windows 宿主机通常使用 `10.0.2.2:9093`。

## WebSocket 协议 v1

连接后依次收到嵌套状态的 `hello` 和单独的顶层 `state`：

```json
{"op":"hello","protocol":1,"state":{"serial_ready":true,"camera_ready":false,"moving":false,"command":7,"speed":50,"duration_ms":0,"follow_line":false,"message":"底盘已就绪"}}
{"op":"state","serial_ready":true,"camera_ready":false,"moving":false,"command":7,"speed":50,"duration_ms":0,"follow_line":false,"message":"底盘已就绪"}
```

每个请求必须带 `id`，响应会原样返回。`id` 只用于单次请求/响应关联，不是租约编号；运动控制权绑定 WebSocket 连接，同一连接每次心跳可用新 `id`。

```json
{"op":"drive","id":"d-1","command":1,"speed":50,"duration_ms":0}
{"op":"heartbeat","id":"h-1"}
{"op":"stop","id":"s-1"}
{"op":"emergency_stop","id":"e-1"}
{"op":"follow_line","id":"f-1","enabled":true}
```

响应格式：

```json
{"op":"response","id":"d-1","ok":true,"message":"持续运动中，请保持心跳"}
```

`speed` 只能是 `30/50/70/100`；`duration_ms` 只能是 `0/500/1000`，0 表示持续。真实命令映射为：

| command | 动作 |
|---:|---|
| 1 | 前进 |
| 2 | 后退 |
| 3 | 左平移 |
| 4 | 右平移 |
| 5 | 左转 |
| 6 | 右转 |
| 7 | 停车（桥内部使用） |

开启寻迹也必须心跳续租，关闭寻迹会同时停车。状态变化会广播 `state`，Android 必须用 `serial_ready=true` 判断控制可用，不能只看 WebSocket 已连接。

## 文件说明

- `bridge_server.py`：入口、占用检测、启动与安全退出。
- `rosmaster_bridge/control.py`：互斥、租约、超时、断线和紧停。
- `rosmaster_bridge/hardware.py`：授权、单 Rosmaster、串口独占和 mock。
- `rosmaster_bridge/ws_server.py`：WebSocket JSON。
- `rosmaster_bridge/video.py`：摄像头和标准库 MJPEG。
- `tests/`：无需串口/摄像头/ROS 的纯逻辑测试。

项目不保存授权内容、SSH 密码或其他凭据。
