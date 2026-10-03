# 构建与验证

影匣 1.0.0、versionCode 10。JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、compile/target SDK 35、min SDK 29。图标与默认主题使用天蓝色。

## 配置环境

推荐在 Android Studio 打开项目，安装 Android SDK Platform 35、Build Tools 35.0.0 与 Platform Tools，使用 JDK 17。SDK 路径保存在本机 `local.properties`，不提交到 Git。

Windows 命令行可用 Python 3.10 以上准备项目内工具。源码目录不保留 JDK/SDK，需要时重新生成：

```powershell
python scripts/bootstrap_tools.py
# 仅在已阅读并同意 Google Android SDK 许可后使用此参数
python scripts/bootstrap_android.py --accept-license
```

脚本从官方来源获取 JDK、Gradle Wrapper 和 SDK，并校验下载内容。SDK 许可：[Google Android SDK](https://developer.android.com/studio)。下载 Gradle 分发遇到连接问题时，可运行 `python scripts/cache_gradle.py`，再执行构建。

已有 JDK/SDK 的开发者无需运行准备脚本：设置 `JAVA_HOME` 为 JDK 17，配置 `local.properties` 中的 `sdk.dir` 即可。可设置 `GRADLE_HOME` 使用已安装的 Gradle 8.13，脚本也支持项目内工具或 Gradle Wrapper。本版构建使用项目外工具缓存，避免把工具放进源码目录。非 Windows 环境使用 `./gradlew`；Linux/macOS 首次可先 `chmod +x gradlew`。

## 构建命令

在仓库根目录运行：

```powershell
./scripts/build.ps1 -Test                       # Kotlin 单元测试
./scripts/build.ps1                             # 普通 debug APK
./scripts/build.ps1 -Validate -Instrumentation  # 测试、Lint、普通 APK 与独立测试 APK
node --test parser-lab/browser-script.test.cjs parser-lab/public-page-script.test.cjs
```

非 Windows 对应命令为 `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`。普通构建输出 `app/build/outputs/apk/debug/app-debug.apk`；PowerShell 脚本另复制到 `outputs/apk/yingxia-1.0.0.apk`。测试包位于 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

普通 APK 不包含 instrumentation 或图集测试素材。独立测试 APK 只用于开发设备。`phonecheck` 是开发测试变体，不能作为正式安装包发布。

## Android 运行测试

先用 `adb devices` 获取当前设备编号，显式指定你授权的测试设备，安装普通包和独立测试包。不要卸载主应用或清除数据来完成覆盖更新。

```powershell
$taskAdb = './.tools/android-sdk/platform-tools/adb.exe'
$taskDevice = '<当前测试设备编号>'
& $taskAdb -s $taskDevice install -r 'outputs/apk/yingxia-1.0.0.apk'
& $taskAdb -s $taskDevice install -r 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
& $taskAdb -s $taskDevice shell am instrument -w -e class 'com.local.douyinsaver.CustomColorPickerTest' 'com.local.douyinsaver.test/androidx.test.runner.AndroidJUnitRunner'
```

Android shell 返回码不能代替测试结果，检查输出中的 `OK` 或失败详情。调色盘测试用隔离引擎启动 MainActivity 并挂载纯调色盘，颜色回调仅更新测试状态；另用独立偏好验证外观保存，不修改用户偏好。其他状态测试也采用独立偏好命名空间。自制红蓝图片和短音频是运行测试需要的素材，应保留在源码中。

### 显式测试真实公开作品

`PhonePipelineTest` 默认跳过真实链接测试，运行全部 instrumentation 不会自动访问或下载第三方作品。需要测试时，显式传入 `run_public_pipeline=true`、你有权保存的 `shareUrl` 和对应作品的数字 `videoId`。只提供短链接时，也需要预先确认其作品 ID。

```powershell
$taskShareUrl = '<你用于测试的抖音 HTTPS 分享链接>'
$taskVideoId = '<对应作品的数字 ID>'
& $taskAdb -s $taskDevice shell am instrument -w -e class 'com.local.douyinsaver.PhonePipelineTest' -e run_public_pipeline true -e shareUrl $taskShareUrl -e videoId $taskVideoId -e download false 'com.local.douyinsaver.test/androidx.test.runner.AndroidJUnitRunner'
```

默认 `download=false`，只检查解析结果是否匹配该作品；需要验证保存和本地解码时，把命令中的 `-e download false` 改为 `-e download true`。下载测试会保留本次创建的文件和记录。该测试使用 App 的实际状态，请先结束当前任务，并在专用测试设备上运行；平台未返回公开作品数据时，测试可能失败。

`scripts/phone_smoke.py` 需要显式 `--serial`，外部 SDK 可用 `--adb` 指定可执行文件，触控前确认前台为本 App。它按需生成的 `work/` 和 `outputs/` 属于忽略的本地文件。

## 实现与签名

图集 MP4 使用 Media3 Transformer，在设备本地导出 H.264/AAC。媒体地址和各次重定向会验证来源，取消时只清理本次创建的文件。当前没有后台自动解析和断点续传。

debug 签名使用开发机器本地的 Android debug 密钥，不在项目内。整理目录不会删除该密钥，本次 APK 继续兼容之前的覆盖安装。换开发机器可能生成不同 debug 密钥。正式发布请在 Android Studio 中配置自己的发布密钥并备份；私钥由 `.gitignore` 排除。

Windows 中文目录使用 `android.overridePathCheck=true`。Java 17 测试启动参数保持系统默认字符集，源码为 UTF-8。脚本仅为当前进程读取无身份验证的 HTTP_PROXY/HTTPS_PROXY，不把代理配置写入仓库。
