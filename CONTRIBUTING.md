# 参与贡献

欢迎 issue 与 PR！提交前请花一分钟了解以下约定。

## 提 issue

- 使用 [issue 模板](https://github.com/NEO-CC-OSS/Animius-TV/issues/new/choose)，按表单填写设备信息与重现步骤
- 使用疑问与交流请去 [Discussions](https://github.com/NEO-CC-OSS/Animius-TV/discussions)

## 提 PR

1. 基于 `source-danmaku` 分支（默认分支）开发，一个 PR 聚焦一件事
2. 提交前本地构建通过：`./gradlew :app:assembleDebug`
3. commit message 简要说明改了什么、为什么

## 代码约定（本项目特有的三条红线）

- **手机 / TV 一套数据与流程**：元数据、选源、搜索等逻辑不按设备分叉；只有焦点样式、按键映射、尺寸走 `isAndroidTV`
- **弹幕渲染模块（`danmaku/`）保持稳定**：如需相关改动，请在 issue 中先沟通
- **发布版本号必须是 `x.y.z-tv.N`**：App 的更新检查只认这种格式（裸 `1.4.0` 会被当成「不是本项目的版本」直接忽略）；tag 与 versionName 成对、versionCode 递增

## 开发环境

- JDK 17 + Android SDK（compileSdk 36）
- 构建：`./gradlew :app:assembleDebug`
- 弹幕凭证：本地构建需在 `local.properties` 配置 `dandanplayAppId` / `dandanplayAppSecret`（参照 `app/build.gradle.kts` 顶部说明；不配置也能构建，仅弹幕功能不可用）
