<div align="center">

<img src="./image/icon-tv.png" width="120" alt="Animius TV版"/>

# Animius TV版

**轻量、无广告、遥控器优先的 Android TV 动漫播放器**

约 4.8 MB · Android 7.0+ · 支持弹幕

[![Release](https://img.shields.io/github/v/release/NEO-CC-OSS/Animius-TV?style=flat-square)](https://github.com/NEO-CC-OSS/Animius-TV/releases/latest)
[![APK](https://img.shields.io/badge/APK-4.8%20MB-blue?style=flat-square)](https://github.com/NEO-CC-OSS/Animius-TV/releases/latest)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange?style=flat-square)](#-致谢与声明)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-green?style=flat-square)](#-兼容性)
[![No Ads](https://img.shields.io/badge/Ads-none-brightgreen?style=flat-square)](#-项目定位)
[![CI](https://github.com/NEO-CC-OSS/Animius-TV/actions/workflows/android_ci.yml/badge.svg?branch=source-danmaku)](https://github.com/NEO-CC-OSS/Animius-TV/actions/workflows/android_ci.yml)

<table>
  <tr>
    <td><img src="./image/tv/tv-home.jpg" alt="推荐页"/></td>
    <td><img src="./image/tv/tv-schedule.jpg" alt="时间表"/></td>
  </tr>
  <tr>
    <td><img src="./image/tv/tv-detail.jpg" alt="详情页"/></td>
    <td><img src="./image/tv/tv-player.jpg" alt="播放与弹幕"/></td>
  </tr>
</table>

</div>

## 🎯 项目定位

| 原则 | 具体做法 |
|---|---|
| **轻量** | 安装包约 4.8 MB；无广告 SDK、无后台常驻服务、不依赖 Google 服务框架。 |
| **兼容** | Android 7.0 起可安装；不要求 Google TV（Leanback）认证，普通 Android 电视、盒子、投影仪都能用。 |
| **TV 大屏** | 布局、字号、焦点按远距离观看设计，不把手机界面直接放大。 |
| **遥控器优先** | 方向键焦点、焦点描边、焦点记忆、长按操作、播放器直控；不设计必须触摸才能用的功能。 |
| **弹幕** | 接入[弹弹play 开放弹幕网络](https://www.dandanplay.com)，集数智能匹配；加载失败时说明原因，而不是静默消失。 |

## ✨ 功能特性

- 📺 **遥控器焦点体系**：方向键移动、主题色焦点描边，离开再回来仍在原处
- 🎮 **播放器直控**：播放中双击返回、快进快退带中央气泡提示，不唤起控制栏也能顺手操作
- 💬 **弹幕**：弹弹play 开放弹幕网络，集数智能匹配；失败原因分类提示（网络错误 / 未匹配 / 鉴权问题）
- 🗂 **多数据源**：内置多个动漫数据源，一键切换，某个源波动时快速换源
- 🗓 **每周时间表**：按周整理新番更新，追番节奏一目了然
- ⭐ **收藏与历史**：追番收藏、断点续播
- ⬇️ **离线下载**：支持缓存到本机（下载进度与入口仍在完善）
- 🔄 **应用内更新**：检查更新、下载进度、失败原因与重试
- 🎨 **动态主题**：界面取色自当前番剧封面，随番换色

## 📥 下载安装

1. 打开 [Releases Latest](https://github.com/NEO-CC-OSS/Animius-TV/releases/latest) 下载最新 `.apk`
   （单包包含 arm64-v8a / armeabi-v7a / x86 / x86_64，无需挑架构）
2. 用 U 盘、局域网或电视浏览器把 APK 传到设备
3. 按系统提示允许「安装未知来源应用」

- **系统要求**：Android 7.0（API 24）及以上；不需要 Google 服务框架
- **覆盖升级**：直接安装新版本即可保留数据；若提示签名冲突，说明设备上装过其他来源的旧版本，需先卸载再安装

## 📱 兼容性

| 设备 / 环境 | 支持情况 |
|---|---|
| Android TV / Google TV | ✅ 支持遥控器操作 |
| 普通 Android 电视、盒子、投影仪（7.0+） | ✅ 不要求 Leanback 认证 |
| 手机 / 平板 | ✅ 可用，交互以 TV 为准 |
| Android 6.x 及以下 | ❌ 不支持（当前基线 Android 7.0） |
| Google 服务框架（GMS） | 不需要 |

## ❓ 常见问题

**弹幕不显示？**
弹幕来自弹弹play 开放平台，需要设备能正常联网。部分冷门剧集可能未被弹幕库收录，此时会提示「未匹配」；网络或鉴权异常也会分别提示原因。

**播放加载失败？**
数据来自公共网络，个别线路会有波动。可切换数据源或稍后重试，播放器已内置网络异常自动重试。

**遥控器按了没反应？**
留意屏幕上的焦点框位置；个别系统的「鼠标模式」会干扰焦点操作，可尝试关闭。

**手机能用吗？**
可以安装使用，但本项目主要面向 TV 大屏优化，交互体验以电视遥控器为准。

## 🔧 自行构建

- 环境：JDK 17 + Android SDK（用 Android Studio 打开工程最省事）
- 构建：`./gradlew :app:assembleDebug`（正式版用 `assembleRelease`）
- 弹幕功能：需在 [弹弹play 开放平台](https://www.dandanplay.com)申请开发者凭证，并在工程根目录的 `local.properties` 中配置 `dandanplayAppId` / `dandanplayAppSecret`（该文件不会入库）
- 本项目仅供学习交流，弹幕功能免费，无任何商业用途

## 📄 许可与修改声明

- 本项目按 **GPL-3.0** 发布，完整条款见 [LICENSE](./LICENSE)。
- 本仓库基于上游 [lanlinju/Animius](https://github.com/lanlinju/Animius) **v1.3.5**（GPL-3.0）修改而来，具体修改内容与日期见 git 提交历史。
- 版本号 `2.0.0-tv.N` 是本项目的独立编号，与上游版本号无关。
- 「仅供学习交流、无任何商业用途」是本项目的定位描述，**不构成对 GPL-3.0 的额外限制**。

## 🙏 致谢与声明

- 上游项目：[lanlinju/Animius](https://github.com/lanlinju/Animius)（GPL-3.0）。本仓库是经原作者同意独立维护的 TV 定制版，并沿用「Animius TV版」名称，感谢原作者 [Lanlinju](https://github.com/lanlinju) 的开源工作。上游的应用架构说明与参考来源详见[上游仓库 README](https://github.com/lanlinju/Animius#readme)。
- 元数据来源：[Bangumi](https://bangumi.tv)，非官方；仅用于番剧信息与封面展示。
- 弹幕数据：[弹弹play 开放弹幕网络](https://www.dandanplay.com)
- 本项目不存储、不分发任何影视内容，所有数据均来自公共网络；仅供学习交流，请于下载后 24 小时内删除，如认可相关作品请支持正版。
