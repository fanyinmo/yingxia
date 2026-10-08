# 构建与验证

本版已移除实况照片保存和动图转实况。保留静图、原格式动图、无声 MP4、GIF、单项保存及 BGM 合成。动态卡片只提供保留动态素材和 GIF；批量保存默认保留已有格式，未分类片段保存为无声动图。旧记录与旧文件保留；读取内嵌动态片段只用于兼容预览或 GIF／BGM 输入，不再生成实况照片。

当前正式版为 **1.2.0 / code 42**，本地安装包与源码位于 `outputs/latest/`，采用用户确认的 rc21 功能。本轮模拟器实际结果及限制见 [电脑验证](testing/EMULATOR_RC21.md) 和 [最新版说明](CURRENT_VERSION.md)。

当前正式构建为影匣 **1.2.0 / versionCode 42**，采用用户已确认的 rc21 功能，正式包单独构建并校验版本、签名与摘要。首版基线为 **1.0.0 / code 10**，第二版为 **1.1.0 / code 20**；相对变更见 [CHANGELOG](CHANGELOG.md)。

环境为 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、compile/target SDK 35、min SDK 29。图标与默认主题使用天蓝色。

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
node --test parser-lab/browser-script.test.cjs parser-lab/public-page-script.test.cjs parser-lab/desktop-album-script.test.cjs
```

非 Windows 对应命令为 `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`。普通构建输出 `app/build/outputs/apk/debug/app-debug.apk`；PowerShell 脚本按 `versionName` 复制到 `outputs/apk/yingxia-1.2.0.apk`。测试包位于 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

普通 APK 不包含 instrumentation 或图集测试素材。独立测试 APK 只用于开发设备。`phonecheck` 是开发测试变体，不能作为正式安装包发布。

## Android 运行测试

先用 `adb devices` 获取当前设备编号，显式指定你授权的测试设备，安装普通包和独立测试包。不要卸载主应用或清除数据来完成覆盖更新。

```powershell
$taskAdb = './.tools/android-sdk/platform-tools/adb.exe'
$taskDevice = '<当前测试设备编号>'
& $taskAdb -s $taskDevice install -r 'outputs/apk/yingxia-1.2.0.apk'
& $taskAdb -s $taskDevice install -r 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
& $taskAdb -s $taskDevice shell am instrument -w -e class 'com.local.douyinsaver.CustomColorPickerTest' 'com.local.douyinsaver.test/androidx.test.runner.AndroidJUnitRunner'
```

示例中的 `$taskAdb` 使用项目内 SDK 路径；若采用 Android Studio 或项目外 SDK，请替换为本机实际的 `platform-tools/adb.exe` 路径。

Android shell 返回码不能代替测试结果。检查逐方法的 instrumentation status 与完整失败详情：`statusCode=0` 才计实际成功，`-4` 为前提不满足而跳过，`-3` 为忽略；JUnit `OK` 汇总也可能包含这些未执行断言的项目。调色盘测试用隔离引擎启动 MainActivity 并挂载纯调色盘，颜色回调仅更新测试状态；另用独立偏好验证外观保存，不修改用户偏好。其他状态测试也采用独立偏好命名空间。自制红蓝图片、短音频及附 SHA-256 清单的固定测试视频是运行测试需要的素材，应保留在测试源码中，普通 APK 不包含它们。

### 显式测试真实公开作品

`PhonePipelineTest` 默认跳过真实链接测试，运行全部 instrumentation 不会自动访问或下载第三方作品。需要测试时，显式传入 `run_public_pipeline=true`、你有权保存的 `shareUrl` 和对应作品的数字 `videoId`。只提供短链接时，也需要预先确认其作品 ID。

```powershell
$taskShareUrl = '<你用于测试的抖音 HTTPS 分享链接>'
$taskVideoId = '<对应作品的数字 ID>'
& $taskAdb -s $taskDevice shell am instrument -w -e class 'com.local.douyinsaver.PhonePipelineTest' -e run_public_pipeline true -e shareUrl $taskShareUrl -e videoId $taskVideoId -e download false 'com.local.douyinsaver.test/androidx.test.runner.AndroidJUnitRunner'
```

默认 `download=false`，只检查解析结果是否匹配该作品；需要验证保存和本地解码时，把命令中的 `-e download false` 改为 `-e download true`。下载测试会保留本次创建的文件和记录。该测试使用 App 的实际状态，请先结束当前任务，并在专用测试设备上运行；平台未返回公开作品数据时，测试可能失败。

`scripts/phone_smoke.py` 需要显式 `--serial`，外部 SDK 可用 `--adb` 指定可执行文件，触控前确认前台为本 App。它按需生成的 `work/` 和 `outputs/` 属于忽略的本地文件。

### 显式验证桌面逐图动态来源

`DesktopAlbumPublicSampleTest` 默认跳过真实网络请求，需明确传入 `run_desktop_album=true`、分享链接及准确作品 `id`。它调用生产桌面 resolver、来源策略、传输、MP4 验证与 GIF 转换，只使用本次 App 私有缓存，不写 MediaStore、用户记录或偏好。

```powershell
& $taskAdb -s $taskDevice shell am instrument -w -e class 'com.local.douyinsaver.DesktopAlbumPublicSampleTest' -e run_desktop_album true -e shareUrl $taskShareUrl -e id $taskVideoId 'com.local.douyinsaver.test/androidx.test.runner.AndroidJUnitRunner'
```

JUnit 返回 `OK` 只表明探针执行完成；实际成功必须核对本次 `desktop-album-result-<id>.json` 的 `status=VERIFIED`、`success=true` 和逐图 MP4／GIF 运动证据。报告及缓存媒体按需复制到被 Git 忽略的 `outputs/reports/desktop-motion-poc/`，不提交签名媒体 URL、Cookie、原网页或用户状态。受控生命周期及引擎测试与真实来源探针分别记录，产品按钮、相册发布、记录、单条与批量的完整流程仍需独立检查。

### 验证资源保存与实际 App 操作


真实作品的 `AlbumMotionAppPipelineTest` 需显式 `run_album_motion_app=true`、准确 `id` 与 `shareUrl`，`mode` 或 `export_mode` 可为 `MOTION_VIDEOS` 或 `GIF`（实况照片导出已移除）；`batch=true` 检查批量卡片。可选的 `seed_cover_from_desktop=true` 仅为移动页被拒设备建立测试卡片，报告保留 `DESKTOP_COVER_TEST_SETUP`，不得计为初始移动解析成功。


## 实现与签名

素材与 BGM 合成使用 Media3 Transformer，本地导出 H.264/AAC，动态项使用实际短片/动画，静态项使用图片；统一 30fps 画布按比例留边。默认采用动态实际时长与静图设置时长，用户可用 0.1 秒精度覆盖每项时长，截取/循环前确认，BGM 循环至结束。它是重新编码的衍生视频，与原素材保存区分。


此前失败的视频由 `v5-coldx.douyinvod.com` 跳转到 `bdcgslb.com` 的动态调度子域，已支持该区域：域名前缀必须是单层、1–63 字符的有效 DNS 标签，根域、多层子域及伪后缀均拒绝。通常的媒体地址要求 HTTPS、默认或 443 端口，禁止嵌入凭据，不绕过 TLS；探测、预览、下载与每次重定向沿用同一门禁。rc4 根据用户官方 CDN 跳转诊断，单独允许 `https://<8位数字>.ydycdn.com:58001`；该例外不适用于根域、多层子域、其他端口、HTTP 或其他媒体域名。

rc4 实测上述数字冷节点完整 GET 会提前结束响应，新增限定节点的 1 MiB 分段原字节下载。每个 206 响应核验 Content-Range、Content-Length、总长度及强 ETag，后续请求发送 If-Range；每段最多两次重试，总期限十分钟。忽略 Range 的 200 响应只能从零完整重写，不能追加到已有片段。缺少强 ETag 时退回既有完整 GET。rc5 在共享传输中使用这条受限兼容路径，普通视频、GIF 来源和逐图片段经过同一门禁；不是把所有媒体地址都改成分段。完整媒体验证后才发布，失败或取消移除本次临时文件，不发布部分文件。

已有的 HTTP 重定向兼容路径同样适用于合法调度子域：可信来源的 Location 使用 HTTP、无嵌入凭据且端口为默认或 80 时，先转换为同域、同原始路径与签名参数的 HTTPS 地址，再重新验证并请求。初始 HTTP 地址仍拒绝，不发送明文 HTTP；未知域名和任意非标准端口不会因此获得支持。

跳转诊断在拒绝前记录目标 `host`、`scheme`、`port` 和是否允许；长 DNS 主机名不再被误当作不透明秘密，完整签名 URL、查询参数、Cookie 和凭据仍脱敏。手机诊断已确认上述被拒域，来源规则已补充。用户随后确认候选版手机测试正常；该反馈与自动测试结果分别记录，不能仅凭域名核验或构建成功推断所有真实作品均可用。

debug 签名使用开发机器本地的 Android debug 密钥，不在项目内。整理目录不会删除该密钥，本次 APK 继续兼容之前的覆盖安装。换开发机器可能生成不同 debug 密钥。正式发布请在 Android Studio 中配置自己的发布密钥并备份；私钥由 `.gitignore` 排除。

Windows 中文目录使用 `android.overridePathCheck=true`。Java 17 测试启动参数保持系统默认字符集，源码为 UTF-8。脚本仅为当前进程读取无身份验证的 HTTP_PROXY/HTTPS_PROXY，不把代理配置写入仓库。

## 下载来源验证

默认测试不请求真实作品。可给 `PhonePipelineTest` 显式传入已有公共链接与作品 ID，并设置 `run_public_pipeline=true`。`download=true` 启用保存；下载固定为 `CLEAN`，可省略 `watermarkMode`，如显式提供仅接受 `watermarkMode=CLEAN`，其他值会被拒绝。`inspectSources=true` 将来源报告保存在目标 App 私有缓存，`captureFrames=true` 仅对新保存文件捕获开头、中段和末段画面。来源报告可能包含临时签名 URL，不应上传仓库。

所有下载与预览固定使用同一作品的 `CLEAN` 来源。旧版本偏好不再读取或写入，但保留原偏好、文件和记录；历史里的其他版本或未分类文件不能作为 CLEAN 重复文件。保存整套图集需要每项都有可用来源；逐项保存只选择目标图片，未选择的邻居不会被下载。不以其他来源替代，来源补充只能凭明确图片身份合并。

图集的 `displayUrls` 必须来自已确认的当前作品结构字段；不同清晰度地址只按同一图片 URI 配对，不按列表位置猜测。排除显式水印变换与无法判断的水印开关后，官方展示地址可作为 CLEAN 来源，不再要求同时存在明确的 WATERMARKED 下载变体。若展示与下载地址相同，或仅 fragment 不同，归类为 ORIGINAL 与 CLEAN，避免同一真实请求被两种来源标签冲突拒绝；仅有未分类下载字段或未标明角色的扁平 URL 不能自动升为 CLEAN，明确的来源标记仍按原规则判定。

这里的 CLEAN 表示选择官方展示来源并排除已知的水印派生地址，不代表图像像素经过检测或编辑，作者嵌入的 Logo、文字等不会被移除。所有图片必须完整保留顺序，BGM 仍绑定同一作品，单条与批量使用共享来源策略。


实际验证／登录门槛允许用户主动打开官方 WebView 手动完成后继续；常驻登录按钮不会触发。当前这条手动路径仅在受控页面验证，尚未实测真实网络验证码。诊断只输出有限计数、字段存在标志、受限枚举与主机名，不转储 RSC 内容、图片键、媒体 URL 或 Cookie。

图文诊断已经确认 `albums=1`、`images=1`、`bgm=true`，拒绝触发点是旧的图片来源分类，修复后用户已确认候选版手机测试正常。日志中的音频 CDN 子资源网络错误不是这次解析拒绝的触发点，本次来源修复不代表所有音频网络错误均已解决。图片保存不依赖 BGM 成功；合成仍需独立下载并验证实际音频，不能仅凭解析到 BGM 地址认定声音可用。用户没有提供逐项测试日志，不将一般正常反馈扩写为每条链接、每种保存模式都已验证。

`WatermarkInteractionTest` 与 `WatermarkRecordsTest` 使用隔离偏好和测试元数据，验证普通下载按钮、版本选项移除、未分类或有水印来源不可下载，以及旧历史保留与严格重复判断；不下载真实作品。`QueueEngineTest` 验证旧设置和接口不能改变单次或批量的 CLEAN 下载选项、重复批量保存会跳过已有文件、删除历史或文件后可再保存，以及不完整图集失败后队列继续。

`ColdCdnPipelineTest` 使用自制图片与 BGM 合成 MP4，通过受控双 302 响应验证平台入口、冷 CDN 与动态调度子域的生产 MediaProbe、CLEAN 选择和共享 MediaTransfer，随后比较文件字节并用 Android 解码实际帧；另验证未知域与水印来源拒绝、失败文件清理。它只用随机私有缓存目录，不操作用户偏好或历史，也不代表真实网络 VideoDownloader 或远程播放器测试。

## 预览播放器验证

预览使用 Media3 ExoPlayer/PlayerView，与图集合成模块统一保持 1.8.0。TextureView 使用空效果 GL 管线处理旋转纹理，视图保持显示比例；VideoSize 未发布时使用实际视频格式的尺寸、旋转和像素比例。`PreviewPlaybackPolicyTest` 验证时间格式、跳转边界和画面比例；`PreviewInteractionTest` 用自制图片与短音频生成本地视频，验证真实播放、暂停、拖动、静音、重播、后台暂停、暂停帧恢复和释放。截图还会与解码源帧的方形、横条比较，检查是否旋转或拉伸。测试结束只删除自己的临时文件与隔离偏好。

运行 `PreviewInteractionTest` 时传入 `capture_preview=true`，可在目标 App 的缓存目录取得 `preview_portrait_validation.png` 和 `preview_landscape_validation.png`。截图使用天蓝色测试主题，不修改用户外观；测试临时调整自己 Activity 的方向并在结束时恢复。

## 自动队列与来源回退验证

`QueueEngineTest` 通过隔离引擎和受限测试入口验证连续解析、失败后继续、暂停及恢复、过期回调隔离、批量保存、历史保留、单链接结果恢复和一键清空。测试入口只能在非空测试偏好命名空间中启用，普通 App 使用真实解析和下载。

`PreviewPreferences` 独立保存前进与后退秒数。设置界面使用两个独立数字输入框，下方一行 5／10／15／30／60 秒快捷值同时更新两侧。快捷按钮保留边缘空间，防止末端描边被裁切；普通宽度尽量显示全部值，窄屏或大字模式横向滚动。`PreviewInteractionTest` 使用隔离偏好验证数字输入、快捷值同步、无效输入保护、持久化、按钮标签与实际播放器跳转，`capture_preview=true` 可保存设置与播放器截图到 App 私有缓存。

所有输入框采用与首页一致的 `RoundedCornerShape(12.dp)`，覆盖分享输入、批量添加、文件名、搜索和预览秒数。设备验证应检查不同宽度和字体尺寸下的圆角、快捷值可达性与描边完整性。

外观滑块保留 Material 的触摸、键盘和无障碍行为，绘制圆形滑钮和细轨道，不再显示独立效果预览板块。背景仅在完整显示时提供位置选项；铺满裁剪始终居中，保留完整显示的原位置设置。`AppearanceOptionsTest` 验证参数规范化与旧外观迁移；界面变化还需在真实设置页面检查。

`BatchQueueInteractionTest` 操作本 App 的真实任务卡片，验证上下移、长按拖动、边缘自动滚动、左右滑动移除及处理时锁定。传入 `capture_queue=true` 会使用虚构作品和天蓝主题生成完整首页截图，位于 App 外部私有目录 `files/runtime-test-captures/queue-home-*.png`，不改用户主题或下载真实作品。

`MediaProbeTest` 使用可控 HTTP 响应验证首个有效 CLEAN 媒体确认后停止、过期地址刷新、失败后尝试同类备用来源、响应内容校验和取消。`VideoSourceFallbackTest` 验证传输失败时切换 CLEAN 备用地址，以及取消、文件写入和发布阶段禁止重试。测试证明代码路径的行为，不代表真实网络下的固定提速比例；平台拒绝返回公开作品数据时，仍须对真实作品单独验证。

实际下载与每次重定向也会检查来源分类；`MediaTransferTest` 验证图片跳转到已知有水印地址时拒绝请求、可信地址刷新、BGM 不受图片水印规则影响，以及取消清理。`FeatureRuntimeTest` 验证圆形滑块的真实拖动与持久化、原生范围语义、48dp 操作区域、背景位置按钮的条件显示和用户外观数据保留。

## 发布前保留与清理

保留 `app/src` 中的生产代码、单元与 Android 测试、自制测试素材，以及 Gradle Wrapper、构建脚本、文档、品牌资源和 README 展示图。新增测试与源文件应纳入提交，不因尚未跟踪就当作临时文件删除。

本机 `local.properties`、签名密钥和正在使用的状态备份继续保留在本机；个人诊断、手机备份、截图、下载样本、旧 APK 与构建缓存不进入 Git。正式普通 APK 通过 Releases 分发。完成验证并保留新 APK 与最新证据后，才清理旧产物；有价值的故障报告可归档到工程外。详细发布步骤见 [发布与维护](GITHUB.md)。

当前正式版 **1.2.0 / versionCode 42** 已按用户确认的 rc21 功能发布至 GitHub，通过 `v1.2.0` Release 分发。发布前已核对版本、原签名与 SHA-256，发布后已核验标签及 APK 附件摘要。本轮没有重跑全量测试；历史候选模拟器结果与界面测试限制仍如实保留，不等同于正式包手机全量验收。历次记录见 [验证记录](VALIDATION.md) 与 [正式包校验](testing/RELEASE_1_2_0.md)。

## 发布已确认的安装包

`.github/workflows/publish-approved-release.yml`只在main的`docs/releases/publish.json`更新时运行。清单中的Git对象是已批准APK的传输分段，不是应用源码树里的APK；发布任务逐段校验后重组相同字节，创建正式标签与Release，上传APK和摘要，不重新构建另一个签名包。清单无签名密钥和账号凭据；授权来自GitHub内置令牌，权限限定contents.write。已发布且摘要一致的版本不会再次上传；同标签不同包拒绝覆盖。传输对象仅用于当次发布，后续重新发布新版本应准备新清单，不依赖旧未引用对象长期存在。
