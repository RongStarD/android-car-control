# 黄冠宇：用户端商品下单新增代码

`patches/user-order-app.patch` 是相对远端已提交人脸采集 App 的增量，包含商品目录、数量选择、姓名与 `person_id` 绑定校验、房间号、下单请求和订单回执界面。

`android-test/OrderFormLogicTest.kt` 是新增的表单与商品数量测试。

在干净基线仓库根目录检查补丁：

```powershell
git apply --check contributions/2026-07-15-new-features/huang-guanyu/patches/user-order-app.patch
```

该补丁所在 App 同时调用薛琛昕目录中的新增视频客户端；视频客户端仍单独归属薛琛昕，没有在本目录复制。

