<div align="center">

<img src="./image/icon-tv.png" width="120" alt="Animius TV版"/>

# Animius TV版

Android TV 动漫播放器，基于 [Animius](https://github.com/lanlinju/Animius) 的 TV 定制分支。

遥控器操作 · 弹幕 · 多数据源 · 约 4.6 MB

[![Release](https://img.shields.io/github/v/release/NEO-CC-OSS/Animius?style=flat-square)](https://github.com/NEO-CC-OSS/Animius/releases/latest)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange?style=flat-square)](#致谢)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square)](#安装)
[![CI](https://github.com/NEO-CC-OSS/Animius/actions/workflows/android_ci.yml/badge.svg?branch=source-danmaku)](https://github.com/NEO-CC-OSS/Animius/actions/workflows/android_ci.yml)

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

## 功能

- 遥控器焦点：方向键移动、焦点描边、焦点记忆
- 播放器直控：双击返回、快进快退气泡提示
- 弹幕：弹弹play 开放弹幕网络，集数匹配，失败原因提示
- 多数据源：内置多个数据源，可随时切换
- 每周时间表、收藏、历史、断点续播
- 离线下载（进度与入口仍在完善）
- 应用内检查更新

## 安装

1. 从 [Releases](https://github.com/NEO-CC-OSS/Animius/releases/latest) 下载 APK
2. 用 U 盘、局域网或电视浏览器传到设备
3. 按提示允许安装未知来源应用

要求 Android 8.0 及以上，不需要 Google 服务框架或 Leanback 认证。APK 包含 arm64-v8a、armeabi-v7a、x86、x86_64，无需挑架构。

直接覆盖安装可保留数据；提示签名冲突时，先卸载旧版本。

## 常见问题

**弹幕不显示**

弹幕来自弹弹play，需要联网。冷门剧集可能未收录，会提示未匹配；网络或鉴权问题会单独提示。

**播放失败**

换个数据源或稍后重试。

**遥控器没反应**

看屏幕上的焦点框；部分电视的鼠标模式会干扰焦点操作。

**桌面找不到图标**

部分 Android TV 桌面只显示 Leanback 应用，可从系统应用列表启动。

**手机能用吗**

可以，交互以电视遥控器为准。

## 构建

JDK 17 + Android SDK。

```bash
./gradlew :app:assembleDebug
```

弹幕功能需要在 `local.properties` 配置 `dandanplayAppId` 和 `dandanplayAppSecret`，该文件不会入库。

## 致谢

上游 [lanlinju/Animius](https://github.com/lanlinju/Animius)（GPL-3.0），经原作者同意维护的 TV 分支。弹幕数据来自 [弹弹play](https://www.dandanplay.com)。

本项目不存储、不分发影视内容，仅供学习交流。
