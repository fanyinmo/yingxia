# 1.2.0-rc14 电脑验收

日期：2026-10-07。普通候选 **1.2.0-rc14 / code 34**；正式第三版 1.2.0 未发布。手机暂不可连接，本轮在保留数据的独立官方 Android 16 / API 36 模拟器上测试。本报告冻结为 rc14 的历史证据，不引用后续 rc15 或新增实况测试的运行结果。

本包完成 **219 个不同的受控／目录方法**，以及普通首页静态 GIF、公网来源和导出的专项验证。原 20 条来源中 **19 条 READY、S08 失败**；选出的九条完整导出检查中 **8 条通过、S08 无导出**。来源可解析、导出完整与普通界面保存分别记录，不能据此填写原 246 条手册全部通过。

## 本轮修复

- 移动分享页和详情接口都没有作品数据时，受限读取一次同作品官方完整网页，补齐普通视频、静态图集和动态素材。视频必须通过真实媒体检查；静态数组等待页面完整、8 秒水合窗口及两次稳定观测。默认动态读取仍不接受静态数组或配乐冒充动态资源。
- 无声动图保留原压缩帧、PTS、编码、尺寸和旋转，并用视频轨自身时长保留末帧展示区间；不跟随更长的音轨或容器时长。
- 公开来源测试等待自动动态检查收尾后才保存，检测未启动的保存操作并立即失败；报告版本取实际安装包，编译时版本另列。

## 构建身份

| 检查 | 实际结果 |
| --- | --- |
| 普通 APK | `outputs/apk/yingxia-1.2.0-rc14.apk`，38,008,347 字节 |
| APK SHA-256 | `f3e755dccf6ae2a2e0f49a723726712601088c9be09605f14357d1106fb5ad59` |
| 初始测试组件 SHA-256 | `7e63a4a1a9254cee80509082529a559d5ed00232f49ef6d3d6bb4df492e4f3a2` |
| 更新测试组件 SHA-256 | `bde2a73bdebf187a85cb9b3ce6c55f6ac0c524267ac49ed5c1d660e1dc5a6e68`；增加真实静图 GIF 和整组删除回归，主 APK 未改变 |
| 签名 | 原证书 `25710e8795821f8ac24872b626641737c54f16b56b74f6a3b2d38808b3ec0a53` |
| 普通包 Manifest | rc14/code34；不含 Probe／PhoneCheck Activity 或 testOnly |
| JVM | 379 项通过；失败、错误、跳过均 0 |
| JavaScript 解析脚本 | 131 项通过；失败、取消、跳过均 0 |
| Lint | 0 错误、53 警告、3 提示 |
| 构建时 Android 源码索引 | 228 方法；仅为源码清单，不是通过数 |

原始构建日志、签名、Manifest、每轮安装包及测试组件摘要保留在本地忽略目录 `outputs/reports/`。下方原始报告链接只用于本地复核，不包含在 Git 仓库中。不能把 [rc12](EMULATOR_RC12.md) 的 199 个通过方法或 [rc13](EMULATOR_RC13.md) 的 15 条来源成功移算为本包通过；228 是最初构建的源码方法索引，也不是本轮通过数。

## 受控方法与实际目录

| 组 | 不同方法数 | 绑定测试组件 | 实际结果与本地证据 |
| --- | ---: | --- | --- |
| 引擎与页面生命周期 | 99 | 初始 `7e63…` | [99 PASS](../../outputs/reports/emulator-rc11/api36-host-rc14-engine99.json) |
| 媒体转换 | 27 | 初始 `7e63…` | [27 PASS](../../outputs/reports/emulator-rc11/api36-host-rc14-converters27.json) |
| 动态格式与目录 | 15 | 初始 `7e63…` | [15 PASS](../../outputs/reports/emulator-rc11/api36-host-rc14-formats15.json) |
| 组合／GIF／下载与发布 | 31 | 初始 `7e63…` | [31 PASS](../../outputs/reports/emulator-rc11/api36-host-rc14-exports31.json) |
| 界面与历史控制 | 47 | 更新 `bde…` | [47 PASS](../../outputs/reports/emulator-rc11/api36-host-rc14-ui47.json) |

逐个 `class#method` 去重后为 **219**，五组没有重复方法；失败、条件跳过、忽略、主机超时均为 0，全部方法都有完整终端 PASS，包装 JSON 的日志 SHA 与实际日志一致。99 项包括 12 个共享备用读取方法、同作品及身份拒绝、晚到动态、稳定静态、真实视频字节探测、HTTP403／HTML200拒绝、取消与队列继续。其余组覆盖实际编解码／MediaStore／隔离目录等断言，仍是受控回归，不能替代所有公网来源、红米相册或通知真机验收。

新增整组删除回归实际创建 A 图集两张 PNG URI 与邻居 B 一张 PNG。界面取消后全部文件／记录不变，确认后 A 两个 URI 和记录均删除，B 完整字节 SHA、记录及其他夹具保持；运行清理仅处理本轮 URI 与 namespace。对应原 **UI-G06 子场景 PASS，完整用例仍 PARTIAL**：尚未覆盖同时选择多个图集或原图／GIF／实况 JPEG／MP4 的混合组。截图和严格断言的边界见[独立审核](../../outputs/reports/emulator-rc11/rc14-static-gif-and-delete-ui-independent-oct07.md)。

## 原 20 条公网来源与八条完整导出

原 [测试手册](../TEST_MANUAL.md) 中 S01–S20 的同一分享链接与作品编号逐条运行：S01–S07、S09–S20 共 **19 条 READY**；**S08 为 FAIL**。保存或来源模式均绑定初始 `7e63…` 测试组件和 f3e 主包；S08 的短链接与 canonical 重试仍失败，重试不增加样例总数。READY 只表示取得当前来源，不能理解为十九条都做了完整下载或普通首页操作。

本轮九条导出检查中的八条成功文件都做了独立完整解码：

| 样例／保存方式 | 实际输出 | 独立结果 |
| --- | --- | --- |
| S02／视频 | 1280×720 MP4，20,304 视频帧；视频轨 676800 ms | 全部视频帧与 AAC 音频解码通过 |
| S04／视频 | 1280×720 MP4，6,103 视频帧；视频轨约 203433.333 ms | 全部视频帧与 AAC 音频解码通过 |
| S07／图片 | 1 张 1440×1440 静态 WebP | 完整 RGB 与 RIFF／尾部检查通过 |
| S14／图片 | 1 张 1079×1200 静态 WebP | 完整 RGB 与 RIFF／尾部检查通过 |
| S20／图片 | 10 张 1440×1920 静态 WebP | 十张全部完整解码、顺序／格式记录一致 |
| S15／无声动图 | 1270×720 MP4，117 帧／3900 ms | 原压缩包、RGB 帧、PTS、尺寸与旋转一致；无音轨 |
| S17／无声动图 | 720×720 MP4，59 帧／1967 ms | 原压缩包、RGB 帧、PTS、尺寸与旋转一致；无音轨 |
| S18／无声动图 | 960×720 MP4，88 帧／2934 ms | 原压缩包、RGB 帧、PTS、尺寸与旋转一致；无音轨 |

三条动图从同图静态封面自动补齐为 DYNAMIC，明确作品／imageKey／图片槽位保持一致；报告 `awaitAutomaticMotion=true / checkMotion=false`。S17 原少约一帧的尾时长在本包实际输出中消失；三条视频轨尾差均小于 1 ms。重封装会改变容器包装，不能声称整个 MP4 文件所有字节都相同。S18 的动态源本身为 960×720，而封面为 1440×1080；这里只确认保留动态源画质，没有取得与封面同尺寸的新视频。

这些八条导出采用隔离 headless 引擎，保存调用和发布走生产路径，清理与原用户业务数据保护通过；它们不能替代普通界面点击或前台服务。详细绑定、43 份输入指纹与逐文件完整解码见[九路独立审核](../../outputs/reports/emulator-rc11/rc14-nine-public-independent-oct07.md)和[JSON](../../outputs/reports/emulator-rc11/rc14-nine-public-independent-oct07.json)。

S20 标题含 LivePhoto，但十张实际 WebP 都只有一帧、无 XMP／MP4 尾段。另行原样请求同槽位明确 CLEAN 的 JPEG 候选，已取得的主候选 9 张及 q80 候选 4 张也没有运动尾段；sourceIndex=2 的 JPEG 抓取和重试失败，不能称该槽已核验。因此 S20 是已验证的静态图集，不能算原生实况来源通过，也没有“已取得实况 JPEG 被 WebP 选择丢掉”的证据。

## 普通界面、GIF 与系统背景

**静态图集 GIF（UI-E01）：PASS。** 公网 S20 经普通 MainActivity 首页实际“解析作品”按钮，等待自动识别结束后确认十项全部 STATIC／无 motion；实际“统一播放时长”输入 **0.1**，点击“图片序列合成 GIF”，经生产下载／合成／MediaStore 保存一个 GIF。[本轮终端结果](../../outputs/reports/emulator-rc11/api36-host-rc14-static-gif-normal-ui.json)为 1 PASS、0 跳过，耗时约 45.98 秒，绑定 f3e／bde。

输出 810,452 字节、360×480，十帧各 100 ms，总长 1000 ms、无限循环 loop=0；全部帧完整解码。独立以同作品十个唯一 imageKey 摘要与原序号绑定先前十张原 WebP，再做十帧×十图共 **100 组**完整 RGB／实际调色板误差比较，全部唯一最近匹配为原顺序 0–9，十个完整像素哈希也与 Android 解码结果相同。原图 1440×1920 与输出的比例均为 0.75；分享画质缩小尺寸并使用 64 色，不能称原像素无损。完整矩阵、截图和数据保护边界见[静态 GIF 独立审核](../../outputs/reports/emulator-rc11/rc14-static-gif-and-delete-ui-independent-oct07.md)。这次 namespace 隔离跳过前台服务，未验收通知。

**真实动态源的两档 GIF：PASS。** [本轮实际转换](../../outputs/reports/emulator-rc11/api36-host-rc14-real-gif-both-qualities.json)使用先前确认的真实动态片段完整原文件，SHA `587934480ce0bab56ac19b4e81ad0af25252b25f81fb0735d85ee77c31b1b27d`；不是本次重新解析的公开链接。清晰模式为 960×720／20,263,842 字节，分享模式为 480×360／1,871,200 字节，真实动画解码／文件结尾／源文件未改断言通过。此项使用实际转换器，不是聊天发送接收或普通首页保存验收；不能因本项通过声称 QQ／微信会自动播放。

**S21 正常视频与完成通知：PASS，限定当前模拟器。** [普通 App 下载流程](../../outputs/reports/emulator-rc11/api36-host-rc14-s21-normal-video-export.json)从用户补充的“小咕嘎成为大学宿管”链接解析并完成保存，完整终端 1 PASS，约 19.25 秒，绑定历史 f3e／bde。后来只读收集该次精确 URI226 文件为 9,883,742 字节，独立重新解码全部 **1831 视频帧**与完整 **AAC 双声道音轨**通过；1280×720／30 fps／容器 61034 ms，1818 个不同 RGB 帧确认实际变化。收据 URI／owner／大小／完整文件名与历史页面一致。收集时已经安装后续 rc15，身份来自历史 wrapper，不称其为 rc15 生成；历史 PID 日志已丢失，不用当前 PID 日志或其他版本的解码结果补证。系统通知实际显示“保存完成”，tap 坐标落在对应文本与通知卡片内，随后 fresh MainActivity 下载记录页显示同文件名、约 9.4 MB 和共 3 个作品。详见[新的完整文件与 UI 独立审核](../../outputs/reports/emulator-rc11/rc14-s21-normal-pipeline-independent-oct07.md)。此处没有操作前全部旧历史 URI／记录／SHA 快照，不称三条历史文件逐字节保护通过；也未验收红米相册或聊天播放。

**背景 UI-H07／REG-014：PASS，限定已测步骤。** 普通 App 使用真实 PhotoPicker 导入一张自制 900×600 图；完整显示实际点击顶部／居中／底部，裁剪时隐藏三个方位按钮，切回完整恢复底部，冷启动保留。已有背景重新选择图片后实际 Cancel，所有 appearance 设置及私有 JPEG SHA 不变；精确移除本轮自制原图后再冷启动，私有背景仍显示。十份有效私有副本、控件祖先选中语义与图像位置交叉一致，见[独立审核](../../outputs/reports/emulator-rc11/rc14-background-position-cancel-oct07.md)。只测试这一张横图，不泛称全部背景输入通过。

背景首次 null-root 捕获没有有效截图，保留为 `HARNESS_CAPTURE_FAILURE`。一次旧 helper 在 null-root 后误读残留设置 XML，PNG／XML 不匹配，保留为 `HARNESS_STALE_XML_INVALID`；两者排除验收，后续 fresh Home／Settings 证据有效。最后移除测试背景恢复无背景状态，但 FIT/BOTTOM 设置仍保留，不称全部原外观偏好恢复。

## 保留失败与尚未验收

- **S08 来源仍 FAIL。** 移动分享页未给可下载作品数据、详情接口 HTTP403、桌面回退未取得有效来源，无文件输出。[原样来源失败](../../outputs/reports/emulator-rc11/api36-host-rc14-s08-real-video.json)与 canonical 重试都保留。后续安全 shape 诊断虽然 `collectorCompleted=true`，但完整方法因已观察 cookie 对改变而 **FAIL**，`protectionChecksPassed=false`；桌面扫描也明确 `truncated=true`。它不能算来源修复、完整网页穷尽分析或 cookie 保护通过，见[该次诊断终端](../../outputs/reports/emulator-rc11/api36-host-rc14-s08-safe-shape-diagnostic.json)。没有自动操作登录或验证码。
- **S18 普通界面动图测试仍是 HARNESS FAIL。** [f3e／bde 的实际运行](../../outputs/reports/emulator-rc11/api36-host-rc14-s18-motion-normal-ui.json)耗时约 **13.30 秒**，完整方法终端 AssertionError；报告停在 fast_parse、记录 READY、无输出 URI。后续修正夹具将在 rc15 另跑，不能将本包 headless S18 导出成功写成这项普通 UI 已通过，也不能覆盖删除这个失败。
- 新增 pending **LIVE／null motion** 的实际文件验证和 UI→生产下载器回归不属于上述 rc14 219；后续编译或执行必须单独绑定主／测试包，不能提前写 PASS。
- 红米系统相册对实况照片的标记、长按播放、分享后动态保留，及 QQ／微信发送接收、原生实况来源和未遇到的真实网页验证，均未验收。受控 JPEG+MP4 格式或“动图转实况”成功不证明用户手机相册识别，也不证明来源本来是实况。

## 环境与验收边界

MuMu 媒体发布曾出现提供方等待问题，因此使用独立官方 AVD 继续测试，没有清除 MuMu 或手机数据。网络诊断发现公开 DNS 结果中部分 TCP 地址不可达，本轮使用仅在自建 AVD 生效的不解密 TLS 连接隧道；Android 仍校验证书与主机名。此条件明确限定本轮网络结果，不能扩写为红米实际网络通过。

来源成功、文件结构、全部帧解码、普通 UI 和 App 内播放分别记录。原用户业务设置／历史／背景保护、精确 owned URI 清理与运行条件按各报告断言；S08 cookie 保护失败不因其他数据保护通过而忽略。保留上述缺口，原 246 条手册的真机模板不预填通过，本报告不宣称正式第三版已获验收或上传发布。
