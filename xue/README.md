# 薛琛昕：人脸视频与测试新增代码

本目录只保留旧提交之后新增、且能独立归属到视频链路的文件：

- `android/MjpegFrameReader.kt`：从 multipart MJPEG 响应中安全拆分 JPEG 帧。
- `android/MjpegStreamClient.kt`：连接 9095 视频流、携带 API Key、重连并释放资源。
- `android-test/MjpegFrameReaderTest.kt`：视频拆帧、长度限制和异常输入测试。
- `testing/yolo_smoke_test.py`：YOLO 模型本机冒烟测试。

这些文件来自当前人脸采集 App 的新增部分；已提交过的小车视频实现没有重复放入。Android 文件放回原包路径后，由用户端 App 的集成补丁调用。

