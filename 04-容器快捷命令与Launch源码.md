# 04｜导航容器快捷命令与 Launch 源码

状态：**已结合用户提供源码、课程手册、m1 启动截图和 9092 桥接源码完成建图与导航链路校正**  
记录时间：2026-07-13  
适用设备：当前唯一一台 Yahboom/iCar 实体小车  
适用容器：容器 1（名称：nifty_dirac；ID：81c99e0c3f98；镜像：yahboomtechnology/ros-foxy:5.0.1）

> 本文记录容器内 m1–m4、n1–n4 与 ROS 2 Python Launch 文件的对应关系。本文不记录登录密码。容器 2（sharp_maxwell）的避障、跟随和视觉功能按当前计划暂不纳入。

源码按用户提供内容整理，统一了空格、换行和部分过长语句；除文中明确说明的 `__init__` 复原外，不改变运行逻辑。本文是便于开发查阅的整理记录，不是容器文件的逐字节备份。

## 1. 快捷命令总表

| 快捷命令 | 对应文件或程序 | 主要职责 | 是否产生运动控制 |
| --- | --- | --- | --- |
| m1 | map_gmapping_launch.py | 当前课程镜像中启动 X3 底盘、IMU/EKF、Joy、A1 雷达、TF 与 GMapping 整套建图基础节点 | 建立运动链路；不要与 n1 并行 |
| m2 | display_map_launch.py | 启动 RViz2 并加载地图显示配置；Android 由 App 原生地图替代 | 否 |
| m3 | yahboom_keyboard | 键盘发布 cmd_vel；Android 由 App 10 Hz 摇杆替代 | **是** |
| m4 | save_map_launch.py | 使用 Nav2 map_saver_cli 覆盖保存固定名称地图 | 否 |
| n1 | laser_bringup_launch.py | 启动 X3 底盘、A1 雷达和静态 TF | 底盘节点就绪，但本文件本身不下发速度 |
| n2 | display_nav_launch.py | 启动 RViz2 并加载导航显示配置 | 否 |
| n3 | navigation_dwa_launch.py | 加载地图并使用 DWA 参数启动 Nav2 | **Nav2 可产生运动控制** |
| n4 | navigation_teb_launch.py | 加载地图并使用 TEB 参数启动 Nav2 | **Nav2 可产生运动控制** |

## 2. 功能链路与互斥关系

### 2.1 建图链路

当前课程镜像的建图链路为：

~~~text
m1 整套建图 bringup
  → Android App 原生地图显示（替代 m2）
  → Android App 10 Hz 摇杆（替代 m3）
  → 停车后 m4 覆盖保存地图
  → 保存请求完成后再停止 m1
~~~

- **建图时不要另启 n1。** 课程手册的 m1 启动截图显示，它已经带起 X3 底盘、IMU/EKF、Joy、A1 雷达、静态 TF 和 GMapping；重复启动 n1 可能争用串口、雷达设备或产生同名节点/TF 冲突。
- m2 只负责 RViz 桌面显示，不是建图算法运行的必要条件。Android 使用 9092 二进制地图、位姿和雷达数据原生绘制，不在手机中启动 RViz2。
- m3 与 App 摇杆都是 `/cmd_vel` 发布者，二者不能同时运行。App 摇杆偏离中心期间每 100 ms 发送一次，松手、回中、切页、后台、断线或停止建图时先发送零速度。
- m4 默认覆盖保存为 yahboomcar 地图，生成的 YAML/PGM 文件供 n3 或 n4 加载。必须保持 m1 与 `/map` 运行到 m4 请求发出之后。

### 2.2 导航链路

课程/RViz 操作顺序为：

~~~text
n1 底盘/雷达基础
  → n2 RViz 导航显示（可选）
  → n3 DWA 导航 或 n4 TEB 导航（二选一）
  → 设置初始位姿
  → 下发导航目标
~~~

Android V2 不启动 n2/RViz，其对应顺序为：

~~~text
连接 AppBridge / 打开 App 原生地图
  → load_saved_map 读取 maps/yahboomcar.yaml
  → start_navigation_base 启动 n1
  → 等待 navigation_base/ready
  → start_navigation_dwa（n3）或 start_navigation_teb（n4，二选一）
  → 在地图上设置并发布初始位姿
  → 等待 navigation/ready 后下发单点或多航点目标
~~~

- n3 与 n4 都启动 Nav2，只是加载的参数文件不同，二者必须互斥。
- n2 只是课程/RViz 桌面显示步骤；Android 通过 AppBridge 接收保存地图、代价地图和路径并原生绘制，所以不启动 n2。
- Android 在 n1 之前先读取保存地图，是为了在小车不动、Nav2 尚未运行时核对地图并选择后续初始位姿；n3/n4 启动时会由 Nav2 的 map_server 接管 `/map`。
- 自动导航期间，m3 或 App 遥控不能与 Nav2 同时向 cmd_vel 输出。
- 人工接管必须先取消导航目标、发布零速度、确认 Nav2 不再输出，再启用 App 独立遥控。

### 2.3 App 独立遥控链路

V2 App 当前使用容器 1 的 WebSocket 桥接服务，连接地址为 Jetson 当前 IP 的 9092 端口。独立遥控直接复用 m3 的 Twist 控制方式：

~~~text
连接 AppBridge
  → start_navigation_base/n1 准备独立遥控底盘
  → runtime=navigation_base/ready 后由用户启用遥控
  → 按住期间 10 Hz 发布 /cmd_vel
  → 松手发布零速度
~~~

独立遥控只要求实车底盘驱动订阅 `/cmd_vel`，不应为了移动而启动 m1。当前状态下“启动 m1 后摇杆才可用”，是因为课程镜像 m1 同时启动了底盘驱动；独立遥控模式应由 `start_navigation_base`/n1 提供底盘基础，建图模式则只启动 m1、不要再启动 n1。视频画面本阶段暂不接入。

### 2.4 9092 建图数据

9092 同时承载 JSON 控制/状态文本帧和大端 `ICAR` 二进制帧。Android 的 m2 替代页面使用：

| 类型 | 二进制布局 | 频率上限 | 内容 |
| --- | --- | --- | --- |
| 1 | `!4sBIIfffII` + zlib cells | 1 Hz | `/map` 的 width、height、resolution、origin x/y 与占据栅格 |
| 2 | `!4sBfff` | 10 Hz | map 坐标系 x、y、yaw |
| 3 | `!4sBHff` + `count × float32` | 5 Hz | `/scan` 的采样数、angle_min、降采样后 angle_increment 与距离，最多 180 点 |

共同前缀为 ASCII `ICAR` 加 1 字节包类型。类型 1 解压后每格为 ROS 有符号占据值，传输字节 255 代表 `-1` 未知；Android 绘图时需翻转 Y 轴。完整偏移、校验和协议限制见 [03-实车接口盘点.md](03-实车接口盘点.md)。

文本握手已经过实车观察；二进制布局来自当前 AppBridge 源码，仍需在 m1 实车运行时抓包确认部署版本，并完成地图方向、位姿和雷达叠加验收。

## 3. m1｜map_gmapping_launch.py

用途：读取环境变量 RPLIDAR_TYPE，并按 a1、s2 或 4ROS 选择实际建图子 Launch。当前实车为 a1，因此进入 `map_gmapping_a1_launch.py`。本节所列顶层文件只是分发入口，不能据此得出“m1 只启动 GMapping”的结论。

课程手册 6.1 的单次 m1 启动截图已确认，当前课程镜像的 a1 子 Launch 同时拉起以下建图基础组件：

- `joint_state_publisher`、`robot_state_publisher`
- X3 底盘驱动与底盘基础节点
- `imu_filter_madgwick_node`、EKF
- `yahboom_joy_X3`
- `sllidar_node`
- `static_transform_publisher`
- `slam_gmapping`

因此，用户只有启动 m1 后 App 摇杆才使小车移动，是因为此时底盘驱动才开始订阅 `/cmd_vel`；不是 GMapping 本身负责驱动电机。

~~~python
from launch import LaunchDescription
from launch_ros.actions import Node
import os
from launch.actions import IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from ament_index_python.packages import get_package_share_directory
from launch.conditions import LaunchConfigurationEquals
from launch.actions import DeclareLaunchArgument


def generate_launch_description():
    RPLIDAR_TYPE = os.getenv('RPLIDAR_TYPE')
    rplidar_type_arg = DeclareLaunchArgument(
        name='rplidar_type',
        default_value=RPLIDAR_TYPE,
        choices=['a1', 's2', '4ROS'],
        description='The type of robot'
    )

    gmapping_4ros_launch = IncludeLaunchDescription(
        PythonLaunchDescriptionSource([
            os.path.join(
                get_package_share_directory('yahboomcar_nav'),
                'launch'
            ),
            '/map_gmapping_4ros_s2_launch.py'
        ]),
        condition=LaunchConfigurationEquals('rplidar_type', '4ROS')
    )
    gmapping_s2_launch = IncludeLaunchDescription(
        PythonLaunchDescriptionSource([
            os.path.join(
                get_package_share_directory('yahboomcar_nav'),
                'launch'
            ),
            '/map_gmapping_4ros_s2_launch.py'
        ]),
        condition=LaunchConfigurationEquals('rplidar_type', 's2')
    )
    gmapping_a1_launch = IncludeLaunchDescription(
        PythonLaunchDescriptionSource([
            os.path.join(
                get_package_share_directory('yahboomcar_nav'),
                'launch'
            ),
            '/map_gmapping_a1_launch.py'
        ]),
        condition=LaunchConfigurationEquals('rplidar_type', 'a1')
    )

    return LaunchDescription([
        rplidar_type_arg,
        gmapping_4ros_launch,
        gmapping_s2_launch,
        gmapping_a1_launch
    ])
~~~

注意：

- 4ROS 和 s2 当前都引用 map_gmapping_4ros_s2_launch.py，这是源代码的既有设计。
- 导入的 Node 在本文件中未使用，不影响 Launch 执行。
- 如果 RPLIDAR_TYPE 未配置，默认值可能为空；容器环境应保证该变量存在。
- 建图模式只启动 m1，不要再启动 n1。两者都可能启动底盘、雷达和 TF，重复运行存在设备占用与节点冲突风险。
- `start_mapping` 的成功响应只表示 m1 进程已创建。App 必须等待 runtime 依次检查 `slam_gmapping`、`/scan`、`base_link→laser` TF 和 m1 启动后的新 `/map`，最终进入 `mapping/ready` 才开放建图摇杆。

## 4. m2｜display_map_launch.py

用途：启动 RViz2，并加载 yahboomcar_nav/rviz/map.rviz。

~~~python
from ament_index_python.packages import get_package_share_path

from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition, UnlessCondition
from launch.substitutions import Command, LaunchConfiguration

from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue


def generate_launch_description():
    package_path = get_package_share_path('yahboomcar_nav')
    default_rviz_config_path = package_path / 'rviz/map.rviz'
    rviz_arg = DeclareLaunchArgument(
        name='rvizconfig',
        default_value=str(default_rviz_config_path),
        description='Absolute path to rviz config file'
    )

    rviz_node = Node(
        package='rviz2',
        executable='rviz2',
        name='rviz2',
        output='screen',
        arguments=['-d', LaunchConfiguration('rvizconfig')],
    )

    return LaunchDescription([
        rviz_arg,
        rviz_node
    ])
~~~

注意：

- IfCondition、UnlessCondition、Command 和 ParameterValue 在当前文件中未使用，可在未来整理源码时删除，但本文暂按原功能记录。
- m2 是 Jetson/虚拟机桌面诊断工具，不是 m1 的启动依赖。课程材料建议在已配置 ROS 多机通信的虚拟机执行。
- Android App 不启动或嵌入 RViz2，而是通过 9092 直接接收 `/map`、map 坐标系位姿和 `/scan`，原生绘制占据栅格、小车位置/朝向与雷达点；这构成对 m2 的功能替代。

## 5. m3｜yahboom_keyboard

用途：从终端读取按键并向相对话题 cmd_vel 发布 geometry_msgs/msg/Twist。

默认参数：

| 参数 | 默认值 |
| --- | --- |
| 线速度 speed | 0.2 |
| 角速度 turn | 1.0 |
| linear_speed_limit | 1.0 |
| angular_speed_limit | 5.0 |
| 发布队列深度 | 1 |

按键映射：

~~~text
u  i  o      左前 / 前进 / 右前
j  k  l      左转 / 停止 / 右转
m  ,  .      左后 / 后退 / 右后

q/z：同时提高/降低线速度和角速度 10%
w/x：只提高/降低线速度 10%
e/c：只提高/降低角速度 10%
t/T：切换 linear.x 与 linear.y
s/S：切换键盘控制暂停状态
空格或 k：强制停止
Ctrl+C：退出并发送零速度
~~~

> 用户粘贴内容中的双下划线被 Markdown 显示为粗体标记，本文已将构造函数复原为 Python 正确写法 __init__，并统一了缩进；功能逻辑不变。

~~~python
#!/usr/bin/env python
# encoding: utf-8

from geometry_msgs.msg import Twist
import sys
import select
import termios
import tty

import rclpy
from rclpy.node import Node
from geometry_msgs.msg import Twist


msg = """
Control Your SLAM-Bot!
---------------------------
Moving around:
   u    i    o
   j    k    l
   m    ,    .

q/z : increase/decrease max speeds by 10%
w/x : increase/decrease only linear speed by 10%
e/c : increase/decrease only angular speed by 10%
t/T : x and y speed switch
s/S : stop keyboard control
space key, k : force stop
anything else : stop smoothly

CTRL-C to quit
"""

moveBindings = {
    'i': (1, 0),
    'o': (1, -1),
    'j': (0, 1),
    'l': (0, -1),
    'u': (1, 1),
    ',': (-1, 0),
    '.': (-1, 1),
    'm': (-1, -1),
    'I': (1, 0),
    'O': (1, -1),
    'J': (0, 1),
    'L': (0, -1),
    'U': (1, 1),
    'M': (-1, -1),
}

speedBindings = {
    'Q': (1.1, 1.1),
    'Z': (.9, .9),
    'W': (1.1, 1),
    'X': (.9, 1),
    'E': (1, 1.1),
    'C': (1, .9),
    'q': (1.1, 1.1),
    'z': (.9, .9),
    'w': (1.1, 1),
    'x': (.9, 1),
    'e': (1, 1.1),
    'c': (1, .9),
}


class Yahboom_Keybord(Node):
    def __init__(self, name):
        super().__init__(name)
        self.pub = self.create_publisher(Twist, 'cmd_vel', 1)
        self.declare_parameter("linear_speed_limit", 1.0)
        self.declare_parameter("angular_speed_limit", 5.0)
        self.linenar_speed_limit = (
            self.get_parameter("linear_speed_limit")
            .get_parameter_value()
            .double_value
        )
        self.angular_speed_limit = (
            self.get_parameter("angular_speed_limit")
            .get_parameter_value()
            .double_value
        )
        self.settings = termios.tcgetattr(sys.stdin)

    def getKey(self):
        tty.setraw(sys.stdin.fileno())
        rlist, _, _ = select.select([sys.stdin], [], [], 0.1)
        if rlist:
            key = sys.stdin.read(1)
        else:
            key = ''
        termios.tcsetattr(
            sys.stdin,
            termios.TCSADRAIN,
            self.settings
        )
        return key

    def vels(self, speed, turn):
        return "currently:\tspeed %s\tturn %s " % (speed, turn)


def main():
    rclpy.init()
    yahboom_keyboard = Yahboom_Keybord("yahboom_keyboard_ctrl")
    xspeed_switch = True
    (speed, turn) = (0.2, 1.0)
    (x, th) = (0, 0)
    status = 0
    stop = False
    count = 0
    twist = Twist()

    try:
        print(msg)
        print(yahboom_keyboard.vels(speed, turn))
        while True:
            key = yahboom_keyboard.getKey()
            if key == "t" or key == "T":
                xspeed_switch = not xspeed_switch
            elif key == "s" or key == "S":
                print("stop keyboard control: {}".format(not stop))
                stop = not stop

            if key in moveBindings.keys():
                x = moveBindings[key][0]
                th = moveBindings[key][1]
                count = 0
            elif key in speedBindings.keys():
                speed = speed * speedBindings[key][0]
                turn = turn * speedBindings[key][1]
                count = 0
                if speed > yahboom_keyboard.linenar_speed_limit:
                    speed = yahboom_keyboard.linenar_speed_limit
                    print("Linear speed limit reached!")
                if turn > yahboom_keyboard.angular_speed_limit:
                    turn = yahboom_keyboard.angular_speed_limit
                    print("Angular speed limit reached!")
                print(yahboom_keyboard.vels(speed, turn))
                if status == 14:
                    print(msg)
                status = (status + 1) % 15
            elif key == ' ':
                (x, th) = (0, 0)
            else:
                count = count + 1
                if count > 4:
                    (x, th) = (0, 0)
                if key == '\x03':
                    break

            if xspeed_switch:
                twist.linear.x = speed * x
            else:
                twist.linear.y = speed * x
            twist.angular.z = turn * th

            if not stop:
                yahboom_keyboard.pub.publish(twist)
            if stop:
                yahboom_keyboard.pub.publish(Twist())
    except Exception as e:
        print(e)
    finally:
        yahboom_keyboard.pub.publish(Twist())

    termios.tcsetattr(
        sys.stdin,
        termios.TCSADRAIN,
        yahboom_keyboard.settings
    )
    yahboom_keyboard.destroy_node()
    rclpy.shutdown()
~~~

安全注意：

- m3 是明确的速度发布者，运行前必须确认 Nav2、App 摇杆和实体 Joy/`joy_ctrl` 未启用。
- m3 退出、暂停或异常时应发送零速度。
- 源码变量 linenar_speed_limit 存在拼写错误，但其声明和使用保持一致，不影响当前逻辑。
- geometry_msgs.msg.Twist 被重复导入一次，不影响执行。

Android 建图模式不启动该终端程序，而是复用相同 Twist 语义：

~~~text
摇杆 Y 轴 → linear.x（前进为正，后退为负，范围 -0.20～0.20）
摇杆 X 轴 → angular.z（逆时针为正，顺时针为负，范围 -1.0～1.0）
摇杆偏离中心 → 每 100 ms 发送一次
摇杆回中/松手 → 立即发送 Twist(0,0,0) 并停止非零循环
~~~

页面退出、App 进入后台、WebSocket 失败/关闭、急停、保存前和 `stop_mapping` 前都必须先执行同一零速度动作。当前 Jetson 的 `stop_mapping` 服务不会代替 Android 发布零速度。

## 6. m4｜save_map_launch.py

用途：调用 nav2_map_server/map_saver_cli，将地图保存到 yahboomcar_nav 源码目录下的 maps/yahboomcar。

~~~python
from ament_index_python.packages import get_package_share_path

from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition, UnlessCondition
from launch.substitutions import Command, LaunchConfiguration

from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue
import os


def generate_launch_description():
    package_share_path = str(
        get_package_share_path('yahboomcar_nav')
    )
    # 获取 yahboomcar_nav 目录的路径
    package_path = os.path.abspath(os.path.join(
        package_share_path,
        "../../../../src/yahboomcar_nav"
    ))
    map_name = "yahboomcar"
    default_map_path = os.path.join(
        package_path,
        'maps',
        map_name
    )

    map_arg = DeclareLaunchArgument(
        name='map_path',
        default_value=str(default_map_path),
        description='The path of the map'
    )

    map_saver_node = Node(
        package='nav2_map_server',
        executable='map_saver_cli',
        arguments=[
            '-f',
            LaunchConfiguration('map_path'),
            '--ros-args',
            '-p',
            'save_map_timeout:=10000'
        ],
    )

    return LaunchDescription([
        map_arg,
        map_saver_node
    ])
~~~

注意：

- 默认地图名固定为 yahboomcar，重复保存按当前设计覆盖同名地图文件。App 必须在调用前显示覆盖确认。
- 默认路径通过 install/share 目录反向定位 src/yahboomcar_nav，依赖当前工作空间目录结构。
- IfCondition、UnlessCondition、Command 和 ParameterValue 在当前文件中未使用。

当前课程镜像的默认输出为：

~~~text
/root/yahboomcar_ros2_ws/yahboomcar_ws/src/yahboomcar_nav/maps/yahboomcar.pgm
/root/yahboomcar_ros2_ws/yahboomcar_ws/src/yahboomcar_nav/maps/yahboomcar.yaml
~~~

课程手册示例 YAML 使用 `mode: trinary`、`resolution: 0.05`、`origin: [-10, -10, 0]`、`negate: 0`、`occupied_thresh: 0.65`、`free_thresh: 0.25`。实际 App 显示应以 9092 地图帧的 width、height、resolution、origin x/y 和栅格数据为准，不硬编码这些示例值。

### 6.1 App 调用语义

当前 `/app/save_map` 实现只检查 `slam_gmapping` 是否存在，然后异步启动 `save_map_launch.py` 并立即响应：

~~~text
已启动 m4：覆盖保存 maps/yahboomcar.{pgm,yaml}
~~~

因此 `op=response`、`ok=true` 只证明保存命令已被受理，不证明 `map_saver_cli` 已退出、两个文件已写完或文件可用于导航。现阶段 UI 文案必须是“保存命令已受理”，不得写成“地图保存成功”。若要自动给出“保存成功”，Jetson 端后续需要增加完成信号，并核验进程退出码、PGM/YAML 同时存在、文件非空且更新时间属于本次请求。

### 6.2 保存顺序与验收

~~~text
摇杆回中并发送零速度
  → 保持 m1、/map 继续运行
  → 用户确认覆盖
  → 调用 save_map/m4
  → 收到“命令已受理”
  → 人工或未来完成信号核验 PGM/YAML
  → 再发一次零速度
  → stop_mapping
~~~

验收至少包括：保存时小车不动；固定路径下 PGM 与 YAML 都可读取；文件修改时间更新；YAML 的 `image` 指向有效 PGM；图片尺寸和地图元数据合理；重新加载后地图方向、比例和原点与保存前一致。m4 失败或尚未确认完成时不得先停止 m1，以便用户重试。

## 7. n1｜laser_bringup_launch.py

用途：读取 ROBOT_TYPE 和 RPLIDAR_TYPE；当前启用 X3 底盘、A1 雷达，并发布 base_link 到 laser 的静态变换。

~~~python
from launch import LaunchDescription
from launch_ros.actions import Node
import os
from launch.actions import IncludeLaunchDescription
from launch.conditions import LaunchConfigurationEquals
from launch.launch_description_sources import PythonLaunchDescriptionSource
from ament_index_python.packages import get_package_share_directory
from launch.actions import DeclareLaunchArgument


def generate_launch_description():
    ROBOT_TYPE = os.getenv('ROBOT_TYPE')
    RPLIDAR_TYPE = os.getenv('RPLIDAR_TYPE')
    print(
        "\n-------- robot_type = {}, rplidar_type = {} --------\n"
        .format(ROBOT_TYPE, RPLIDAR_TYPE)
    )

    robot_type_arg = DeclareLaunchArgument(
        name='robot_type',
        default_value=ROBOT_TYPE,
        choices=['x1', 'x3', 'r2'],
        description='The type of robot'
    )
    rplidar_type_arg = DeclareLaunchArgument(
        name='rplidar_type',
        default_value=RPLIDAR_TYPE,
        choices=['a1', 's2', '4ROS'],
        description='The type of robot'
    )

    # X1 与 R2 的启动项在当前源码中被注释。
    bringup_x3_launch = IncludeLaunchDescription(
        PythonLaunchDescriptionSource([
            os.path.join(
                get_package_share_directory('yahboomcar_bringup'),
                'launch'
            ),
            '/yahboomcar_bringup_X3_launch.py'
        ]),
        condition=LaunchConfigurationEquals('robot_type', 'x3')
    )

    lidar_a1_launch = IncludeLaunchDescription(
        PythonLaunchDescriptionSource([
            os.path.join(
                get_package_share_directory('sllidar_ros2'),
                'launch'
            ),
            '/sllidar_launch.py'
        ]),
        condition=LaunchConfigurationEquals('rplidar_type', 'a1')
    )

    # S2 与 4ROS 的启动项在当前源码中被注释。
    tf_base_link_to_laser = Node(
        package='tf2_ros',
        executable='static_transform_publisher',
        arguments=[
            '-0.0455',
            '5.258E-05',
            '0.3059',
            '3.14',
            '0',
            '0',
            'base_link',
            'laser'
        ]
    )

    return LaunchDescription([
        robot_type_arg,
        bringup_x3_launch,
        rplidar_type_arg,
        lidar_a1_launch,
        tf_base_link_to_laser
    ])
~~~

注意：

- 当前有效组合是 ROBOT_TYPE=x3、RPLIDAR_TYPE=a1，与实车盘点结果一致。
- X1、R2、S2 和 4ROS 的 IncludeLaunchDescription 在用户提供源码中被注释，本文用注释摘要代替整段注释代码。
- 独立遥控只要求 X3 底盘驱动订阅 /cmd_vel；雷达和完整 n1 并非遥控本身的必要条件。
- n1 是导航/独立遥控基础链路，不是当前课程镜像建图 m1 的前置条件。建图时不得把 n1 和 m1 叠加启动。

## 8. n2｜display_nav_launch.py

用途：启动 RViz2，并加载 yahboomcar_nav/rviz/nav.rviz。

~~~python
from ament_index_python.packages import get_package_share_path

from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition, UnlessCondition
from launch.substitutions import Command, LaunchConfiguration

from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue


def generate_launch_description():
    package_path = get_package_share_path('yahboomcar_nav')
    default_rviz_config_path = package_path / 'rviz/nav.rviz'
    rviz_arg = DeclareLaunchArgument(
        name='rvizconfig',
        default_value=str(default_rviz_config_path),
        description='Absolute path to rviz config file'
    )

    rviz_node = Node(
        package='rviz2',
        executable='rviz2',
        name='rviz2',
        output='screen',
        arguments=['-d', LaunchConfiguration('rvizconfig')],
    )

    return LaunchDescription([
        rviz_arg,
        rviz_node
    ])
~~~

注意：n2 仅负责可视化，不启动 Nav2，也不加载地图服务器。

## 9. n3｜navigation_dwa_launch.py

用途：加载 maps/yahboomcar.yaml 和 params/dwa_nav_params.yaml，通过 nav2_bringup/bringup_launch.py 启动 Nav2。

~~~python
import os
from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.actions import IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration


def generate_launch_description():
    package_path = get_package_share_directory('yahboomcar_nav')
    nav2_bringup_dir = get_package_share_directory('nav2_bringup')

    use_sim_time = LaunchConfiguration(
        'use_sim_time',
        default='false'
    )
    map_yaml_path = LaunchConfiguration(
        'map',
        default=os.path.join(
            package_path,
            'maps',
            'yahboomcar.yaml'
        )
    )
    nav2_param_path = LaunchConfiguration(
        'params_file',
        default=os.path.join(
            package_path,
            'params',
            'dwa_nav_params.yaml'
        )
    )

    return LaunchDescription([
        DeclareLaunchArgument(
            'use_sim_time',
            default_value=use_sim_time,
            description='Use simulation (Gazebo) clock if true'
        ),
        DeclareLaunchArgument(
            'map',
            default_value=map_yaml_path,
            description='Full path to map file to load'
        ),
        DeclareLaunchArgument(
            'params_file',
            default_value=nav2_param_path,
            description='Full path to param file to load'
        ),
        IncludeLaunchDescription(
            PythonLaunchDescriptionSource([
                nav2_bringup_dir,
                '/launch',
                '/bringup_launch.py'
            ]),
            launch_arguments={
                'map': map_yaml_path,
                'use_sim_time': use_sim_time,
                'params_file': nav2_param_path
            }.items(),
        ),
    ])
~~~

## 10. n4｜navigation_teb_launch.py

用途：加载 maps/yahboomcar.yaml 和 params/teb_nav_params.yaml，通过 nav2_bringup/bringup_launch.py 启动 Nav2。

~~~python
import os
from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.actions import IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration


def generate_launch_description():
    package_path = get_package_share_directory('yahboomcar_nav')
    nav2_bringup_dir = get_package_share_directory('nav2_bringup')

    use_sim_time = LaunchConfiguration(
        'use_sim_time',
        default='false'
    )
    map_yaml_path = LaunchConfiguration(
        'map',
        default=os.path.join(
            package_path,
            'maps',
            'yahboomcar.yaml'
        )
    )
    nav2_param_path = LaunchConfiguration(
        'params_file',
        default=os.path.join(
            package_path,
            'params',
            'teb_nav_params.yaml'
        )
    )

    return LaunchDescription([
        DeclareLaunchArgument(
            'use_sim_time',
            default_value=use_sim_time,
            description='Use simulation (Gazebo) clock if true'
        ),
        DeclareLaunchArgument(
            'map',
            default_value=map_yaml_path,
            description='Full path to map file to load'
        ),
        DeclareLaunchArgument(
            'params_file',
            default_value=nav2_param_path,
            description='Full path to param file to load'
        ),
        IncludeLaunchDescription(
            PythonLaunchDescriptionSource([
                nav2_bringup_dir,
                '/launch',
                '/bringup_launch.py'
            ]),
            launch_arguments={
                'map': map_yaml_path,
                'use_sim_time': use_sim_time,
                'params_file': nav2_param_path
            }.items(),
        ),
    ])
~~~

## 11. DWA 与 TEB 的差异点

| 项目 | n3（DWA） | n4（TEB） |
| --- | --- | --- |
| 地图 | maps/yahboomcar.yaml | maps/yahboomcar.yaml |
| Nav2 入口 | nav2_bringup/bringup_launch.py | nav2_bringup/bringup_launch.py |
| 参数文件 | params/dwa_nav_params.yaml | params/teb_nav_params.yaml |
| use_sim_time | false | false |
| App 操作 | 启动 DWA 导航 | 启动 TEB 导航 |

两者的实际路径规划和控制差异主要由各自参数文件及所配置的插件决定。本文尚未记录 dwa_nav_params.yaml 和 teb_nav_params.yaml 的完整内容，后续收到源码后可追加。

## 12. 已知命令与仍待实车核验项

已确认 m1 的完整命令是：

~~~bash
ros2 launch yahboomcar_nav map_gmapping_launch.py
~~~

以下内容仍待实车核验，不能自行推测：

1. m2–m4、n1–n4 快捷 alias 在实车 `/root/.bashrc` 中的逐字定义尚未独立核验；本文对应命令来自用户材料与课程手册。
2. 每个快捷命令对应的标准停止命令或进程回收方式。
3. dwa_nav_params.yaml 与 teb_nav_params.yaml 的完整配置。
4. m4 的异步完成通知、退出码和地图文件落盘校验；当前 9092 响应只表示命令受理。
5. 容器 2 的避障、跟随、警卫和视觉功能命令。

后续补充时，应继续以“快捷命令 → 文件 → ROS 节点/话题/服务 → App 功能”的顺序记录。
