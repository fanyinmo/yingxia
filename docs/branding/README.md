# 影匣品牌

名称：**影匣**。版本：**1.0.0 第一版**。

![影匣图标](yingxia-icon.png)

图标沿用匣子与播放符号的形状，采用天蓝渐变、白色匣身和浅蓝匣盖。播放符号是真实镂空，避免单色图标失去内部标记。

| 资源 | 用途 |
| --- | --- |
| yingxia-icon.svg | 可编辑的图标母版 |
| yingxia-icon.png | 512 像素品牌预览 |
| app/src/main/res/drawable/ic_launcher_background.xml | Android 渐变背景层 |
| app/src/main/res/drawable/ic_launcher_foreground.xml | 自适应图标前景 |
| app/src/main/res/drawable/ic_launcher_monochrome.xml | Android 13 单色图标 |

前景放在 Android 自适应图标安全区内，适应圆形与圆角形状。名称集中到 `app/src/main/res/values/strings.xml` 的 `app_name`，桌面、首页、关于页和下载通知一致使用。

天蓝主题用于新安装的默认外观；覆盖更新保留已有的用户外观选择。包名和安装签名延续之前测试版，安装内部编号递增为 10，允许覆盖 versionCode 9。
