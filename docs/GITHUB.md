# 发布与维护

- 源码仓库：[fanyinmo/yingxia](https://github.com/fanyinmo/yingxia)
- 安装包：[GitHub Releases](https://github.com/fanyinmo/yingxia/releases)
- 当前版本：影匣 **1.0.0**，`versionCode 10`。

源码、介绍和展示图片由本仓库维护，安装包通过 Releases 分发。每次发布后核验仓库页面、README 图片和安装包附件。

## 源码维护

提交应用源码、测试、Gradle Wrapper、构建脚本、使用文档与 README 展示图片。自制 Android 测试素材和 Gradle Wrapper 的 JAR 属于必要文件，应保留在仓库中。

`.gitignore` 排除本地工具、构建缓存、`local.properties`、`outputs/`、临时调试文件和签名密钥。JDK、SDK、个人截图、下载样本与 APK 不作为源码提交。

提交前检查实际变更和忽略文件：

```powershell
git status --short
git status --short --ignored
```

继续开发时按 [构建说明](BUILD.md) 配置环境。运行单元测试、解析脚本回归和 Lint；涉及设备行为的改动，再运行对应 Android 测试。更新功能时同步 [使用说明](INSTALL.md) 和 [验证记录](VALIDATION.md)。

## 发布安装包

版本标签使用 `v1.0.0`；后续发布标签与 `app/build.gradle.kts` 中的 `versionName` 保持一致，`versionCode` 递增。

把普通安装包 `outputs/apk/yingxia-1.0.0.apk` 作为 Release 附件，填写面向使用者的变更与已验证范围。不要上传独立测试 APK 或 `phonecheck` 变体。发布后核验附件可下载、版本信息正确，并记录 APK 的 SHA-256。

当前安装包沿用原开发签名，可覆盖先前测试版。后续更新需继续使用相同签名才能覆盖安装；换开发机器可能生成不同 debug 密钥。正式签名与备份见 [构建说明](BUILD.md)，私钥不上传仓库。

## 页面图片

README 使用 `docs/images/home.png`、`history.png` 和 `settings.png`，均为真实 App 页面。更新界面时使用相同天蓝主题和统一尺寸重新截图，保留清晰的操作文字，避免将个人分享文案、下载记录或系统通知放入仓库。

项目尚未选择开源许可证；仓库拥有者决定授权方式，本次不新增许可证。
