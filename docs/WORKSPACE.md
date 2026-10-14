# 工作区目录

本次正式交付为 1.2.1 / code 47。

| 目录 | 用途 |
| --- | --- |
| app/ | Android 源码及测试 |
| parser-lab/ | 解析脚本回归 |
| scripts/、gradle/ | 构建与发布工具 |
| docs/ | 当前使用说明、版本变更和公开验证摘要 |
| outputs/latest/ | 1.2.1 APK、源码 ZIP、说明及 SHA256 |
| outputs/apk/ | 当前正式包构建副本 |
| outputs/reports/RELEASE_1_2_1/ | 本次本地构建与整理证据 |
| outputs/archives/1.2.1-development/ | rc1–rc4 安装包、候选说明、原始测试材料 |
| outputs/archives/formal-1.2.0-before-1.2.1/ | 原 1.2.0 正式交付 |
| outputs/archives/ | 以前历史及本次哈希移动清单 |

本次归档 17 个目标，逐文件校验移动前后 SHA256，不永久删除。编译缓存与环境配置保持原位并由 .gitignore 排除。聊天截图、来源素材、签名密钥、SDK、登录状态不提交 GitHub。归档内旧证据的原路径由 `outputs/archives/cleanup-1.2.1.json` 映射至新路径；当前说明与交付路径已同步。
