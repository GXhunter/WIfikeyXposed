# GXhunter/WIfikeyXposed 发布指南

推送版本 tag 后，[Release 工作流](workflows/release.yml)会构建签名 APK，核对证书，并把 APK 和 SHA-256 校验文件上传到 Release 草稿。工作流只在 `GXhunter/WIfikeyXposed` 运行。

## 1. 准备 fork 自己的签名密钥

如果此 fork 尚未发布过 APK，可以在仓库外生成专用密钥。如果已经发布过，就继续使用此 fork 以前的密钥。离线备份签名文件和密码，后续覆盖升级要保持签名一致。

```powershell
$keystore = Join-Path $HOME 'WIfikeyXposed-release.p12'
# 仅首次创建。使用高强度 ASCII 密码，在 keytool 的提示符输入。
keytool -genkeypair -v -storetype PKCS12 -keystore "$keystore" -alias wifikeyxposed -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=GXhunter WIfikeyXposed"
# 查看公开证书的 SHA-256 指纹：
keytool -list -v -storetype PKCS12 -keystore "$keystore" -alias wifikeyxposed
```

上游与此 fork 若使用相同包名但不同签名，普通 Android 设备无法直接覆盖安装。切换到此 fork 前先备份模块配置，再卸载旧版；此 fork 后续版本继续使用自己的同一把密钥。

## 2. 在自己的 GitHub 仓库配置签名

在 **GXhunter/WIfikeyXposed** 的 `Settings → Secrets and variables → Actions` 配置：

| 类型 | 名称 | 值 |
| --- | --- | --- |
| Secret | `KEYSTORE_BASE64` | 签名文件的 Base64 内容 |
| Secret | `KEYSTORE_PASSWORD` | 签名库密码 |
| Secret | `KEY_ALIAS` | 上述新密钥使用 `wifikeyxposed` |
| Secret | `KEY_PASSWORD` | 上述 PKCS12 命令创建的密钥与签名库使用同一密码 |
| Variable | `RELEASE_CERT_SHA256` | `keytool -list -v` 输出的证书 SHA-256 指纹，可保留冒号 |

使用已登录的 GitHub CLI，可以直接通过标准输入保存签名文件，避免写出 Base64 文件或打印内容：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) |
    gh secret set KEYSTORE_BASE64 --repo GXhunter/WIfikeyXposed
```

不要把密钥、密码、Base64 或签名配置提交到 Git。签名文件和配置只在 CI runner 上重建；证书指纹缺失或与最终 APK 不匹配时，工作流会停止上传。

## 3. 创建和发布 Release

1. 如果此 fork 的 Actions 尚未启用，先在自己的仓库启用。限制谁可以创建版本 tag。
2. 此 fork 首版使用 `versionName = "1.0.0"`、`versionCode = 1`，不继承上游编号。后续每次发版递增 `versionCode`，tag 与 `versionName` 一致，并在 README 添加对应的更新日志。
3. 审核工作流和源码，合入 `main`；在已确认的提交上打 tag（将 `COMMIT_SHA` 换成实际提交）：

```powershell
git tag -a v1.0.0 COMMIT_SHA -m "Release v1.0.0"
git push origin refs/tags/v1.0.0
```

4. 在 Release 草稿核对 APK、`.sha256`、版本和验收结果，再点 **Publish release**。公开下载入口为 [本 fork 的 Releases](https://github.com/GXhunter/WIfikeyXposed/releases/latest)。

工作流当前在 Linux runner 上安装 Android SDK 37，并移除构建目录中的本机 SOCKS 配置。正式打 tag 前请先在干净 runner 验证构建和 Lint；测试 Release APK 时分别记录接口解析、Hook 注册、回调触发和真机效果。
