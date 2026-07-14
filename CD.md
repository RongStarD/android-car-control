# Android CD 发布说明

仓库的 `Android CD` workflow 会在推送以版本号开头的 `v` 标签（例如 `v0.1.0`）时：

1. 运行单元测试和 Release lint；
2. 使用 GitHub Actions Secrets 中的密钥构建签名 APK；
3. 生成 `SHA256SUMS.txt`；
4. 上传 Actions artifact；
5. 创建同名 GitHub Release，并附加 APK 和校验文件。

## 首次配置

### 1. 准备签名文件

如果还没有正式发布用的 keystore，可在 PowerShell 中执行：

```powershell
keytool -genkeypair -v `
  -keystore release.jks `
  -alias android-car-control `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

请妥善离线备份 keystore 和密码。丢失后将无法使用同一签名升级已经安装的应用。不要把 `release.jks` 提交到 Git 仓库。

### 2. 添加 GitHub Actions Secrets

先把 keystore 转成单行 Base64 文本：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks"))
```

打开 GitHub 仓库的 **Settings → Secrets and variables → Actions → New repository secret**，添加：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 上一步得到的完整 Base64 文本 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名，例如 `android-car-control` |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

也可在已登录 GitHub CLI 的 PowerShell 中配置；命令会交互式读取值，不要把密码直接写进命令历史：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks")) |
  gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_STORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

### 3. 检查 Actions 权限

workflow 已声明 `contents: write`，用于创建 GitHub Release。若仓库或组织策略限制了工作流权限，请在 **Settings → Actions → General → Workflow permissions** 中确认允许该 workflow 获得写权限。

## 手动验证

将 workflow 合并到默认分支后，可在仓库的 **Actions → Android CD → Run workflow** 手动运行。手动运行会构建并上传签名 APK artifact，但不会创建 GitHub Release。

## 正式发布

1. 在 `app/build.gradle.kts` 中递增 `versionCode`，并把 `versionName` 改为本次版本，例如 `0.2.0`；
2. 提交并推送版本修改，等待常规 CI 通过；
3. 创建与 `versionName` 对应的标签并推送：

```powershell
git tag -a v0.2.0 -m "Release v0.2.0"
git push origin v0.2.0
```

推送标签后，`Android CD` 会自动构建并创建 `v0.2.0` Release。

## 常见问题

- `Missing required GitHub Actions secret`：对应 Secret 未添加、名称不一致或值为空。
- `Keystore was tampered with, or password was incorrect`：`ANDROID_STORE_PASSWORD` 不正确，或 Base64 内容不是对应的 keystore。
- `No key with alias ...`：`ANDROID_KEY_ALIAS` 与 keystore 中的别名不一致，可用 `keytool -list -v -keystore release.jks` 检查。
- Release 创建失败并提示资源不可访问：检查 `contents: write` 是否被仓库或组织的 Actions 策略禁止。
