# 两个最终 Android App 的 CI/CD

仓库只把以下两个目录视为最终交付 App：

- `face-enrollment-app`
- `rosmaster-control-app`

## CI

`Final Apps CI` 在推送到 `main`、相关 Pull Request 或手动触发时并行执行两个 App 的：

```text
testDebugUnitTest -> lintDebug -> assembleDebug
```

成功后分别上传 `face-enrollment-debug-apk` 和 `rosmaster-control-debug-apk`，保留 14 天。CI 使用明确标记为非生产用途的占位 API Key，只用于验证编译和测试，不会把现场凭据提交到仓库。

## CD 验证

在 Actions 页面手动运行 `Final Apps CD`，会重新测试并构建两个 Debug APK，随后打包为带 `SHA256SUMS.txt` 的 `final-apps-delivery` artifact。该模式无需发布密钥，可用于验证整条交付流水线。

## 正式签名发布

正式版本通过 `v` 开头的标签触发，例如 `v1.4.0`。标签发布会构建两个签名 Release APK，并创建同名 GitHub Release。

仓库管理员需要先在 **Settings → Secrets and variables → Actions** 配置：

| Secret | 用途 |
| --- | --- |
| `FACE_API_KEY` | 注入正式 APK 的小车服务凭据 |
| `ANDROID_KEYSTORE_BASE64` | Release keystore 的 Base64 文本 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

密钥缺失时，标签发布会在构建前明确失败，不会生成带占位凭据或临时签名的正式 APK。keystore 和密码必须离线备份，不能提交到 Git。

配置完成后发布：

```powershell
git tag -a v1.4.0 -m "Release v1.4.0"
git push origin v1.4.0
```
