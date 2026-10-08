# 1.2.0-rc12 电脑验证历史快照

日期：2026-10-07。本页保留普通候选 **1.2.0-rc12 / code 32 / APK摘要9ef** 的实际构建与测试结果，是该包的历史快照。新的候选正在构建、验证，进展另见 [rc13电脑验证记录](EMULATOR_RC13.md)；本页通过项不能直接迁移给新APK。**下列具体检查通过，仍不表示全部功能验收完成。**正式第三版 1.2.0 尚未发布。用户当前无法连接手机，电脑结果不记入手机历史台账，也不代替红米原生相册和聊天客户端验收。

## 本轮修复

1. 移动页明确标为LIVE却暂缺片段时，只在每张封面可信、其余素材完整的条件下允许自动补读同作品。保留原LIVE类型；没有片段时禁止以静态封面冒充实况保存。
2. 完整桌面候选虽提供了片段，却仅标DYNAMIC时，只依据同作品、两数组各唯一相同的非空图片标识，补回原先明确的LIVE/ANIMATED类型。保留候选顺序、地址、片段及BGM，不按位置猜，也不覆盖候选已有明确类型。
3. 桌面读取生命周期测试改用正式包现有MainActivity承载受控WebView，不向普通APK加入专用测试页面；原5项严格断言保留。
4. 有自身非空动态片段的未知DYNAMIC增加独立整行“用动态片段生成实况”，单项预览同入口。只在用户点击时用首个真实解码帧和时间戳生成新封面，去音轨并封装；原三种未知格式不变，STATIC／LIVE不显示此按钮。新增2项JVM与1项UI回归已在下列完整构建及界面组中通过。

rc12构建截点的源码索引为 **379个JVM方法、209个Android方法**，仅表示该候选的测试声明。Android声明分为196个受控方法、3个目录方法、8个公网入口、1个已确认公开素材本地比较入口、1个网络诊断采集方法。网络采集的正常终态仅记 `DIAGNOSTIC_COMPLETED`，不能计为网络或解析功能通过；209不等于209通过，也不等于原手册246条通过。

## 本轮构建与执行登记

| 项目 | rc12 实际结果 | 证据与范围 |
| --- | --- | --- |
| 普通 APK | 完整构建成功，code32，40,352,290字节 | `outputs/apk/yingxia-1.2.0-rc12.apk`；SHA-256为 `9ef44c3585722dccc869d8880deff97b253a50b9510e504a59ce0fbc910a5a95`；本历史截点运行绑定此普通包 |
| 签名与普通包范围 | 原证书保持；普通包未加入专用诊断宿主 | 证书SHA-256 `25710e8795821f8ac24872b626641737c54f16b56b74f6a3b2d38808b3ec0a53`；普通manifest不含phonecheck专用Probe／PhoneCheck Activity |
| 独立测试 APK | 已构建并按轮次覆盖安装 | 最初完整构建测试包为 `9188b792a72ad94e2ef01f1213f9af02bbf6186c7022ccbcddbca042bab4ac28`；首次夹具复测为 `847f9af8bfaeb06bc361d7a3a0b43ef5d4066423da5aad5a392eb0a2653a84d4`；后续修正夹具及网络诊断包为 `c31ddfc47f200824fbff0174d1c150fbb9d0645adcd52466964ebfaca6b1619d`。普通包均为同一9ef摘要，每轮测试包分别登记 |
| JVM | 379项通过，0失败／错误／跳过 | 本次完整构建，含7项分类保持和2项新增生成入口；不是继承rc11计数 |
| 解析 JavaScript | 126项通过，0失败／跳过 | 本次 `outputs/reports/parser-rc12-oct07.log` |
| Lint与完整构建 | 0错误、53警告、3提示；完整构建2分8秒成功 | `outputs/reports/build-rc12-final-oct07.log`、实际JUnit XML、`app/build/reports/lint-results-debug.xml`；警告没有当作错误或忽略成零 |
| 覆盖安装与启动 | 普通包实际覆盖安装、MainActivity运行 | 各 `api36-host-rc12-*.json` 记录安装普通／测试包摘要；原AVD数据保留，没有卸载或清空。安装成功不替代逐项原数据回核 |
| Android已完成日志截点 | **199个受控及目录方法最新状态199通过、0失败／跳过** | 原2项SAF条件跳过在真实系统选择器授权后分别复测通过，普通9ef／测试c31；修正安全区后的10方法也完整通过。原失败和跳过仍保留，不重复累加方法数 |
| 真实S21普通界面流程 | 普通下载与拒绝通知后的下载，两次均通过 | `api36-host-rc12-s21-host-dns-normal-ui-video.json`／`api36-host-rc12-s21-notification-denied-download.json`，均普通9ef／测试c31；两次是同一方法的不同条件，不计两个unique方法。网络为本轮自有AVD的主机DNS择优、TLS不解密代理；原3次失败保留 |
| S21完整媒体 | 新保存9,883,742 B，完整1,831视频帧及AAC解码通过 | `rc12-s21-media-independent-oct07.md`及对应绑定／完整视频／完整音频JSON；1280×720、30fps、容器61.034秒，不是只读头部或采样帧 |
| 真实素材GIF质量 | 本地已确认片段全段转换通过，两档文件全部帧独立解码 | `api36-host-rc12-real-gif-quality.json`、`rc12-real-gif-independent-oct07.md/.json`；清晰20,263,842 B、分享1,871,200 B，均2.950秒。不是本轮自动公网解析成功，也不是QQ／微信播放验收 |
| 真实系统背景与通知操作 | 背景导入、来源移除后冷启动、移除背景及通知拒绝／允许操作有实界面证据 | `rc12-actual-background-import-oct07.md/.json`、`manual-notification-*`；通知拒绝后普通下载成功，但尚不证明后台通知送达或通知点击入口 |
| 独立网络诊断 | 早期报告采集完成；未辅助AVD路由下官方页超时 | `example.org`原生HTTPS200且挂载WebView正常加载；`www.iesdouyin.com`原生HTTPS10秒、挂载WebView20秒均超时，无页面回调。未调用生产解析器，诊断正常结束不算来源成功；后续路由条件和真实成功另列 |

所有原始证据保存在 Git 忽略的 `outputs/reports/`，每轮登记准确日志／JSON／媒体路径。只有完整日志和实际产物身份确认后才写通过；主机超时、设备环境故障、未到业务断言或条件跳过分别记录。rc12构建摘要为 `outputs/reports/emulator-rc11/rc12-final-build.json`，签名与manifest为同目录 `rc12-final-signature.txt`／`rc12-final-manifest.txt`。目录名称沿用历史路径，但本页只纳入普通APK为9ef摘要的rc12原始证据，不迁移rc11通过项。下文不带完整目录的原始证据路径均相对 `outputs/reports/emulator-rc11/`。

生成按钮加入前的初始 rc12 APK（摘要前缀 `982a…`）已归档，未执行设备回归；它不含最终按钮，不能用于本轮验收。最终生产包9ef与后续仅修改测试夹具的包分别保留摘要，不能混写成一个测试APK。

## 逐组执行与失败复测

原始日志／JSON均位于忽略目录 `outputs/reports/emulator-rc11/`。以下计数按方法及rc12普通9ef包去重，复测不累计新增通过数；条件跳过不算通过。

| 完成的运行标签 | 实际结果 | 覆盖与限度 |
| --- | --- | --- |
| `api36-host-rc12-engine` | 82/82通过 | 动态资源引擎36、队列26、桌面生命周期5、首页输入7、动态记录6、水印历史2；LIVE缺片段恢复和唯一身份分类保持包含在内 |
| `api36-host-rc12-original-formats` | 首轮14通过、1SAF条件跳过 | 默认目录发布／回读、原动画字节／MIME、单JPEG实况内嵌完整片段、无声轨保存；当时无真实SAF grant，后来该分项在下述实际授权复测通过，初次跳过保留 |
| `api36-host-rc12-ui-main` | 36/36通过 | 图集入口12、历史5、预览控制5、动态预览3、静图时长设置3及UI1、首页2、水印入口3、调色盘2；包括新增未知DYNAMIC生成按钮与窄屏／大字体完整边框 |
| `api36-host-rc12-converters` | 25/25通过 | 视频GIF10、原生动画转视频10、图集GIF4、无声remux归属1；16.1秒清晰GIF全段转换已完成，不沿用旧候选未完成结果 |
| `api36-host-rc12-exports` | 首轮30通过、1SAF条件跳过 | 合成5、GIF回滚2、GIF下载3、四类型混合2、逐项导出15、冷CDN4；当时缺grant的分项后来在下述实际系统选择器授权复测通过，初次跳过保留 |
| `api36-host-rc12-feature-ui`／`api36-host-rc12-batch-ui` | 首轮分别2通过1失败、4通过3失败 | 失败在测试夹具的可见范围、旧相册按钮选择及确认弹窗等待；日志保留，未把失败计通过 |
| `api36-host-rc12-feature-batch-ui-retest` | 首次合并复测8通过、2夹具失败 | 队列确认清空、真实超出初始视口的持续拖动及相册保存回调通过。外观整按钮横向不在夹具安全区、旧固定左滑终点越界两项仍失败；不以“文字可见”放松整按钮边界 |
| `api36-host-rc12-feature-batch-safe-content-retest` | 10/10通过，0失败／跳过 | 只修改测试宿主安全内容Insets及按实际视口计算左右滑动距离；保留完整48dp／前台／系统边界／身份顺序及真实动作断言。普通包9ef未改，测试包c31；完整原始terminal与摘要已核对 |
| `api36-host-rc12-saf-after-real-picker` | 2/2通过，0失败／跳过 | 原跳过的 `AlbumCompositionTest#savesOriginalImagesToUserSelectedTestFolder`、`DynamicAlbumSaveTest#preservesGifAndLivePairInExplicitIsolatedSafFolder` 实际重测；系统目录选择器授权后，静图／原GIF／实况单JPEG向明确隔离SAF目录写入回读及本轮准确URI清理通过。普通9ef／测试c31 |

199通过是上述分组按方法去重后的最新状态，含真实SAF复测替换原2次条件跳过，不含网络诊断的正常结束，也不含公网失败样例。History组件5方法主要验证搜索、排序、移除记录及取消删除；引擎已有实际单文件删除回归，但完整图集“确认删除文件”、部分provider失败和系统授权仍须专项证据。外观夹具通过与下列实际PhotoPicker证据分开登记；服务离开Activity500毫秒测试不等于长后台或通知入口通过。

真实SAF操作证据包括 `manual-saf-open-picker`、`manual-saf-use-folder`、`manual-saf-allow-dialog`、`manual-saf-allow-folder`、`manual-saf-app-selected` 及停止／重新打开后的 `manual-saf-after-reopen-proof`。目录为本轮明确的 `Screenshots/DouyinValidation030`，不是复用手机URI；实际系统授权和重开仍显示目录是方法执行前提。2项方法完整terminal／JSON位于 `api36-host-rc12-saf-after-real-picker.log/.json`，不把最初无grant时的ASSUME改写成当时通过。

## 公开S21、网络条件与未关闭的图集失败

新增报错作品[小咕嘎成为大学宿管](https://v.douyin.com/NbROyrItgEM/)的作品ID为 `7684581692060830976`。本轮三次尝试保留为失败：

| 标签 | 观察到的结果 |
| --- | --- |
| `api36-host-rc12-s21-full-video` | 隔离headless生产引擎未取得分享页作品数据；详情接口403，没有完整视频 |
| `api36-host-rc12-s21-normal-ui-video` | 普通MainActivity实界面未等到READY；分享映射较慢，页面仍为空，没有完整输出 |
| `api36-host-rc12-s21-direct-normal-ui-video` | 仅清除自建模拟器启动进程的代理环境变量并保留数据后，普通UI仍失败；页面未提供可下载数据，详情403 |

这三次直接路由尝试均未取得完整输出。随后 `api36-host-rc12-connectivity-probe` 在实际挂载的普通Activity中独立比较原生HTTPS和WebView，`example.org`两者均成功，抖音官方作品页两者均超时，WebView没有 `page_started`。诊断JSON明确 `productionParserUsed=false`、`testCompletionIsNetworkAcceptance=false`，没有读取Cookie或改变TLS校验／权限。它只证明该次AVD路由访问官方域名存在卡点，不能当作解析功能通过。

主机逐地址验证 `host-direct-connectivity-safe-20261007.json` 观察到部分官方解析地址连接超时，而另一地址可在默认TLS校验下返回移动页200。后续只对自有AVD使用主机DNS择优的 **TLS不解密CONNECT代理**（`OWN_AVD_HOST_DNS_FAILOVER_TLS_OPAQUE_PROXY`），保留Android正常TLS；未给生产包固定IP、未替换证书校验、未解密Cookie／媒体，也未改变电脑全局代理。进程与条件见 `owned-avd-proxy-r3-process.json`、`owned-avd-connect-proxy.py`，同条件下取得下列实际S21结果：

| 新的S21运行 | 实际结果与身份 |
| --- | --- |
| `api36-host-rc12-s21-host-dns-normal-ui-video` | 正常MainActivity解析、预览及完整下载通过，22.226秒，普通9ef／测试c31；保存到MediaStore并有实际记录／文件 |
| `api36-host-rc12-s21-notification-denied-download` | 在真实系统通知弹窗点“Don’t allow”后，正常MainActivity解析及下载仍通过，15.351秒，普通9ef／测试c31 |

两次执行为同一 `PhonePipelineTest#currentFailedSample` 方法在不同条件下的复测，不累加成两个unique方法。它们证明这条样例在上述路由条件下的rc12正常流程可用，**不证明未经辅助的AVD路由、红米网络、其他视频或“移动候选全403→桌面回退”路径均已通过**。早期三次失败仍保留。

网络诊断实际报告位于 `rc12-direct-webview-probe-private/cache/webview_connectivity_8050f2fb-206a-428d-9614-447ee890c690/connectivity.json`；原始签名地址、Cookie及私有媒体只留忽略目录，公共文档仅记录公开短链、文件摘要和脱敏结论。

三条问题图集的自动移动分享页流程在rc12仍有失败，不能被S21的视频成功覆盖：

| 样例与准确标签 | rc12结果 |
| --- | --- |
| S15“双子星” `api36-host-rc12-s15-auto-motion-original-scoped` | 失败，移动页未返回这条作品可下载数据，普通9ef／测试c31；此前未按隔离参数执行的旧尝试也保留 |
| S17“苹果动态循环” `api36-host-rc12-s17-auto-motion-original-scoped` | 失败，同一移动页数据缺失阶段，普通9ef／测试c31 |
| S18“命数如织” `api36-host-rc12-s18-auto-motion-original`／`api36-host-rc12-s18-public-dns-auto-motion` | 两次自动流程均失败，移动页未返回可下载数据；DNS路由变化没有令自动桌面补读完整通过 |
| S18显式桌面 `api36-host-rc12-s18-direct-desktop-source` | `sourceVerified=true`，同作品逐图运动片段取得并完整解码；**整个方法仍FAIL**：cleanup时原断言将Chromium访问统计运行缓存变化计入既有偏好变化，`protectionVerified=false`，普通9ef／测试c31。不能改写为自动解析、下载或保护整体通过 |

S18桌面片段的来源与媒体独立证据为 `rc12-s18-desktop-binding-independent-oct07.json`、`rc12-s18-desktop-media-independent-oct07.json`，以及 `rc12-s18-direct-desktop-source-private/cache/headless_desktop_source_0be57c5f-5d91-4c16-9288-6207b601f9db/7689306999973377371.json`。片段1,861,995 B、960×720、88个完整解码帧且88个不同画面，与已确认素材摘要相同；来源保持未知DYNAMIC，不由导出格式反推原生LIVE。原清理FAIL和所有日志未覆盖删除，测试隔离调整及自动流程修复需在新候选单独验证。

## 完整视频与真实GIF文件核验

S21新保存文件为 **9,883,742 B**，SHA-256 `0a7ad74ab009b68d77b1bcf49fd692491893732a999a8ef8dc01389d96508f46`，绑定正常UI下载记录及上述9ef／c31通过运行。独立读取完整MP4结构，1280×720、30fps、容器61.034秒；FFmpeg完整解码 **1,831帧**，时间戳严格递增，1,818种RGB画面、1,817次相邻变化，未报解码错误。AAC为44.1kHz双声道，完整解码每声道2,692,096个PCM采样并有非零信号；解码尾部包含AAC填充，不改称另一容器时长。证据为 `rc12-s21-media-independent-oct07.md`、`rc12-s21-artifact-binding-independent-oct07.json`、`rc12-s21-full-video-independent-oct07.json`、`rc12-s21-full-audio-independent-oct07.json`。三张实际PNG未发现可见平台水印，仅支持这三帧观察，不宣称全片每时刻无水印或主观音质验收。

真实GIF比较以**此前确认、此次按准确SHA重新读取的本地“命数如织”片段**为输入，不是一次新的自动公网解析成功。输入1,861,995 B、960×720、30fps、容器2.949秒，完整88帧均可解码且有真实变化；生产9ef／测试c31的 `api36-host-rc12-real-gif-quality` 方法通过。新输出经Pillow逐帧与完整结构独立检查，源视频经FFmpeg全段解码，逐GIF帧按实际开始时间对照最近源画面：

| 文件级检查 | 清晰档 | 分享档 |
| --- | --- | --- |
| 画布 | 960×720 | 480×360 |
| 完整解码GIF帧／不同画面 | 89／87 | 30／30 |
| 全部时长 | 2.950秒（选定2.949秒，量化差+1ms） | 2.950秒（同样+1ms） |
| 无限循环、完整尾标记、每帧正延迟 | 全部确认 | 全部确认 |
| 文件字节 | **20,263,842**（十进制20.26MB） | **1,871,200**（十进制1.87MB） |
| 逐帧源RGB平均误差（0–255尺度） | 1.8625 | 2.0653 |

分享档比清晰档小90.77%，两者保留4:3比例；清晰档保留源尺寸，分享档主动降低尺寸／帧密度。清晰GIF仍比MP4大，不能承诺所有GIF与MP4同体积；89张编码图像也不等于89个独立源帧。每帧误差包含解码／调色板／时间量化／缩放，不是无损或通用质量阈值。准确新运行目录 `rc12-real-gif-private/cache/emulator_real_gif_8f1e805e-c438-44d5-8257-dd1118e986bd`，汇总和逐帧SHA证据为 `rc12-real-gif-independent-oct07.md/.json`。QQ／微信发送接收和自动播放仍待实际客户端验收。

## 真实系统照片选择器与通知权限

实际背景证据 `rc12-actual-background-import-oct07.md/.json` 只使用本轮新建RGB几何图。系统PhotoPicker取消后回到空背景基线；真实点选横900×600、竖600×900、大2400×1600三图，私有JPEG分别900×600、600×900、1600×1067。三张原图每次导入后SHA均不变。仅删除清单中的3个exact测试源文件再冷启动，私有大图SHA仍为 `69ae012c4b64b26a0fc57f81331c3eeb20932b5ac8ba195667b035119d331fe2`、135,664 B，首页／下载记录／设置有同一背景截图。实际完整显示出现顶部／居中／底部，铺满裁剪隐藏三方位；实际移除后背景revision为0、UI返回“选择图片”、私有副本不存在。

报告保留误选大图、异步导入检查过早、缺文件exec-out状态码三种辅助脚本缺陷及纠正重测，未将其计为App失败或删证据。仅有上述子项通过：已有非空背景下取消、坏文件提示、三方位逐个切换与位置保留、所有滑块极值仍不能由本轮截图推导通过；原手册UI-H06／H07／H08／H10／REG-014／REG-042均按报告限定范围。

通知采用正常设置入口“开启通知”，真实系统PermissionController弹窗及动作位于 `manual-notification-prompt-open`、`manual-notification-prompt-observed`、`manual-notification-deny-once-observed`。2026-10-07 08:09:14 UTC实际点“Don’t allow”，随后同普通包的 `api36-host-rc12-s21-notification-denied-download.log/.json` 完整下载通过；再次从设置请求，`manual-notification-second-request`、`manual-notification-second-prompt-observed`、`manual-notification-allow`记录08:10:31 UTC实际点“Allow”。这些证据支持**拒绝权限不阻断普通下载及正常重新请求路径**，不证明后台进度通知、完成通知、点击返回或长后台电源管理已验收。

## 环境与历史对照

本轮官方模拟器：serial `emulator-5556`，Android16/API36、WebView133.0.6943.137、Google APIs x86_64 r7、Emulator37.2.12、WHPX、2核／官方最低2560MiB、720×1600/dpi320、NVIDIA RTX3060 GPU host。实际启动登记在 `api36-rc12-start.json`；直接连接启动登记在 `api36-host-direct-startup.json`，只对该启动进程移除代理变量、保留AVD数据，没有改变电脑全局代理或MuMu状态。测试普通包身份及WebView版本另在逐轮实际报告中核对。只对自建测试模拟器保持插电唤醒，无root、未清空应用或用户媒体。

MuMu存储卡顿及官方初次SystemUI无响应、恢复后的rc11结果保存在[rc11历史电脑记录](EMULATOR_RC11.md)。这些结果用于定位问题，**不挪算rc12已通过**。所有中断、失败、条件跳过与后续复测分别保留，原始私有证据位于Git忽略的 `outputs/reports/emulator-rc11/`。

## 验收范围

本候选的验收范围涉及解析/自动动态识别/队列、逐项与混合保存、动图无声MP4、单JPEG实况封装及显式动图转实况、GIF两档与完整时间范围、BGM合成、0.1秒时长、预览/外观/历史操作，以及原分享链接。各项实际结果以上述证据为准，不表示范围内全部分项通过。补充要求见[新增验收清单](RC11_ACCEPTANCE_ADDENDUM.md)，原246条见[测试手册](../TEST_MANUAL.md)。

| 重点回归 | 检查范围与尚需区分的条件 |
| --- | --- |
| 自动动态检查与分类保持 | 单条／批量不需额外检查；LIVE缺片段恢复／失败／取消；唯一图片身份类型保持及7项边界；新增引擎流程与迟到回调隔离 |
| 桌面生命周期 | 普通包现有宿主挂载、完成／取消／销毁与Cookie保护；旧宿主失败保留，按新测试APK复测 |
| 原格式与转换 | GIF／WebP／APNG 原字节、无声MP4视频轨；实况真实帧／时间戳配对及内嵌完整文件；显式转实况去音轨；不匹配／不支持编码拒绝 |
| 未知片段生成实况 | DYNAMIC有非空片段时才显示独立整行与单项按钮；原3选项保持；明确新封面／非原生类型／设备支持说明；CONVERT_TO_LIVE真实首个解码帧和无音轨，STATIC／LIVE不显示按钮 |
| 逐项与四类型混合 | F19 同作品身份和顺序；预览单项下载不读邻居；STATIC、ANIMATED、LIVE、DYNAMIC 各自主要操作正确 |
| GIF与合成 | 分享／清晰两档、完整选择时间、0.1秒输入、各动态原时长／静图默认／统一覆盖、短截／长循环确认、BGM真实动态画面 |
| 界面与数据 | 首页清空、队列排序／滑删／清空、预览／设置／圆形滑块、历史搜索排序与删除确认、原偏好／背景／路径／历史保留 |
| 存储与恢复 | 默认Pictures／Movies及明确隔离SAF目录发布回读／MIME／记录／清理；权限失效、失败与取消只处理准确本轮URI |
| 真实来源 | S15／S17／S18自动识别；S16普通视频GIF；RC11-S21完整视频与403来源；单条和批量分别登记 |

同一最终 APK 完成受影响回归后，再对照原手册核查仍未关闭的条目。受控素材与真实公开来源分开，文件级检查与系统界面分开；一项必要分项缺失不能整体判通过。

## 素材与外部验收缺口

| 待核查项目 | 完成条件与边界 |
| --- | --- |
| 真实已知 LIVE／ANIMATED | 逐项官方字段或原嵌入文件证据；S15／S17／S18仍按已观察的未知DYNAMIC登记，实况输出选择不反推原生类型；S20仍需App逐项确认 |
| 真实原生 GIF／WebP／APNG、多动态／混合图集 | 分别确认真实多帧容器、图片身份及顺序；自制F01–F19不能填作公网样例通过 |
| 原生动画／内嵌实况输入前置 | 没有动画类型且无逐图片段的原生GIF，解析可能仍为STATIC，需下载实际解码后确认。明确LIVE但仅内嵌JPEG、无外部motion的输入可能被前置检查拦截；暂无真实公开样例证明整条入口，不能由下载层字节保留推断全部输入支持 |
| 同作品BGM | 实际取得并解码音频，混合合成需同作品来源；只有bgm=true或地址不能证明配乐可用 |
| 桌面视频403回退公网路径 | 需实际发生移动候选全部403并进入同ID桌面回退；rc11小咕嘎移动备用成功不替代这条路径 |
| 真实登录／验证码 | 真正门槛由用户手动完成，常驻登录按钮和受控页面不替代公网验证 |
| 红米原生实况相册 | 正常保存与显式动图转换分别检查实况标记、长按／重开播放、声音及分享／接收后动态保留；普通Android模拟器无法代替HyperOS相册 |
| QQ／微信GIF发送接收 | 记录具体文件、大小、时长、客户端版本和发送入口，实际接收端播放；本地完整解码、体积缩小或静图0.1秒样例不证明其他GIF自动播放，实际发送由用户操作 |
| 红米运行与升级 | 锁屏／后台电源管理、Android16存储／编解码、已有偏好／背景／文件／历史覆盖升级回核；模拟器结果不迁移 |

当前 JPEG Motion Photo 封装及 App 播放不等于厂商相册认可，GIF保留选择总时长也不等于每个源帧保留。手机不可连接时继续电脑能够完成的工作，但上述原生相册与聊天验收仍保持待验证。不上传GitHub、创建正式标签或Release来代替未完成验收。
