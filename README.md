# Animius TV版

> 本仓库是 [lanlinju/Animius](https://github.com/lanlinju/Animius)（v1.3.5，GPL v3）的 TV 设备定制版，
> 经原作者同意开源，并允许使用「Animius TV版」名称（原作者 2026-10-07 回信许可）。

**相对上游的主要改动**（详见提交历史）：

- TV 端全局焦点体系（主题色描边）与遥控器适配、播放器"直控/焦点"双模式、长按 2 秒上下文操作
- 失效数据源清理（域名易主/停摆/SPA 化的 5 个源）
- HTTPS 证书链补全（TlsChainFix，公共 CA 严格校验）
- 弹幕：加载失败原因可见（鉴权失败/未匹配/网络错误分类提示）、集数匹配增强
- 封面加载：统一 ImageLoader（共享证书补全 + UA + 磁盘缓存）

**关于弹幕凭证**：源码不含 AppId/AppSecret。需要弹幕功能请自行在弹弹play 开放平台（dev.dandanplay.com）申请，在仓库根目录 `local.properties`（不进 git）中加两行：`dandanplayAppId=你的AppId` / `dandanplayAppSecret=你的AppSecret`，然后构建。

**自行构建**：JDK 17 + Android SDK，`./gradlew :app:assembleDebug`。本项目仅供学习交流，弹幕功能免费，无任何商业用途。

---

（以下为上游原 README）

# Animius

一个简洁的播放动漫的App，支持下载，弹幕，多数据源等功能，使用[Jetpack Compose](https://developer.android.com/jetpack?hl=zh-cn)
进行开发

## 如何下载安装

点击此链接[下载地址](https://github.com/Lanlinju/Anime/releases/latest)
前往下载页面，然后选择下载以`.apk`结尾的文件。Android
TV或者系统版本低于安卓8.0的，请点击查看[这里](https://github.com/lanlinju/Anime/releases/tag/v1.2.1)

## 应用截图

<table>
  <tr>
    <td><img src="./image/week.jpg" alt="week"/></td>
    <td><img src="./image/home.jpg" alt="home"/></td>
    <td><img src="./image/favourite.jpg" alt="favourite"/></td>
  <tr>
  <tr>
    <td><img src="./image/detail.jpg" alt="detail"/></td>
    <td><img src="./image/history.jpg" alt="history"/></td>
    <td><img src="./image/download_episode.jpg" alt="download episode"/></td>
  </tr>
  <tr>
    <td colspan="3"><img src="./image/player.jpg" alt="player"/></td>
  </tr>
</table>

## 相关功能


- [x] 首页推荐
- [x] 番剧搜索
- [x] 番剧时间表
- [x] 多数据源支持
- [x] 历史记录
- [x] 番剧下载
- [x] 番剧收藏
- [x] 动态主题颜色
- [x] 视频播放器
- [x] 倍速播放
- [x] 外部播放器播放
- [ ] 选择下载目录
- [x] 弹幕功能
- [ ] BT资源下载
- [ ] 多平台 (Compose Multiplatform)

## Architecture

使用的是[Google应用架构指南](https://developer.android.com/topic/architecture), MVVM 和 Clean
Architecture

## 参考来源

视频弹幕源来自于[弹弹play](https://www.dandanplay.com)开放API

- [SakuraAnime](https://github.com/670848654/SakuraAnime)：樱花动漫网站数据解析参考实现来源
- [Animite](https://github.com/imashnake0/Animite)：应用UI设计参考实现来源
- [compose-video-player](https://github.com/imherrera/compose-video-player)：Exoplayer视频播放器封装参考实现来源
- [FreeToPlay](https://github.com/qababadr/FreeToPlay)：应用MVVM架构参考实现来源
- [DownloadX](https://github.com/ssseasonnn/DownloadX)：视频文件下载功能参考实现来源
- [Animeko](https://github.com/open-ani/animeko)：视频弹幕功能参考实现来源