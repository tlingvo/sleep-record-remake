# 睡时记录重制版

睡时记录是一款 Android 应用，按自定义作息时段统计手机使用情况，把夜间使用、锁屏事件和任务加分汇总成每日与周期得分。它依据系统使用情况记录计分，不测量实际入睡时间。

[下载 v3.0.0 APK](https://github.com/tlingvo/sleep-record-remake/releases/download/v3.0.0/sleep-record-3.0.0-debug.apk) · [版本说明](https://github.com/tlingvo/sleep-record-remake/releases/tag/v3.0.0) · 支持 Android 7.0（API 24）及以上

## v3.0.0 · WebView 界面重写

统计、对话、任务和设置均已重写为 Android WebView 中的页面。HTML、CSS、JavaScript 和字体随 APK 打包，界面可以离线加载。计分、使用情况采集、备份、音频播放和 AI 请求由 Android 原生 Kotlin 代码处理，页面通过异步桥接调用。

## 功能

- **统计与时间轴**：可调整每日起始时间、睡眠时段和周期长度。按小时查看应用使用与锁屏明细，支持导出时间轴。同一应用间隔不超过 1 秒的相邻片段会在界面上合并显示。
- **任务与休息**：每日打卡、短休息、长休息、早睡挑战和问答任务，内置可离线播放的睡前音频。
- **对话**：支持离线自我对话、角色扮演和 AI 任务会话。可在聊天页打开侧边会话列表、切换角色和设置头像。AI 生成的回复按流式片段显示。
- **界面**：主页面支持底栏与横向手势导航，支持浅色、深色和跟随系统主题。详情、对话和问答页面适配 Android 返回手势、系统栏安全区和软键盘。

## 安装与迁移

1. 首次打开重制版，阅读须知并在系统设置中允许「使用情况访问」，以读取应用使用和锁屏记录。通知权限可按需开启。
2. 从旧版迁移时，先在旧版导出 JSON 备份，再到重制版选择「设置 → 本地数据 → 导入旧版备份」。旧版 `sleepy.night.record` 与重制版 `sleep.record` 可以并存，无需先卸载旧版。
3. 重制版已有数据时，导入前建议先用「导出备份」存一份副本。导入会合并小时记录与用量明细；备份中存在的会话、挑战和打卡等数据段会覆盖对应的本机数据。

发布页提供的是调试签名 APK。如果手机已装有同包名但不同签名的重制版，Android 无法覆盖安装；先导出备份，再卸载已安装的重制版。备份可能包含使用记录、聊天历史和明文 API 密钥，请勿上传到 GitHub 或公开分享。

## AI 对话

应用预设了共享 API 基址 `https://botcf.com/v1` 和用户提供的低额度测试密钥。API 基址、模型及密钥可在「设置 → 开发者选项」修改。共享密钥额度有限，可能因其他用户使用而耗尽；发起 AI 请求时，对话内容会发送给所配置的服务商。统计、打卡、休息、离线自我对话和本地音频不需要 API 密钥。

## 构建与测试

使用 JDK 21、Android SDK Platform 36.1 和仓库自带的 Gradle Wrapper。前端源码位于 [`app/src/main/assets/web`](app/src/main/assets/web)，由 Gradle 直接打包。在项目根目录执行以下命令。

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux 将 `.\gradlew.bat` 换成 `./gradlew`。安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

连接测试设备或启动模拟器，并确保 `adb` 可用，然后运行兼容性测试：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w sleep.record.test/sleep.record.ContractRunner
```

v3.0.0 已通过 24 项 Android 自动验收，覆盖计分与日期归属、周期断点、旧版 JSON 导入、挑战与休息结算、角色提示词与备份、AI 流式响应解析，以及时间轴片段合并。

## 项目文档

- [重制说明](重制说明.md)：计分规则、功能行为、题库和验收用例。
- [旧版数据导入兼容格式说明](旧版数据导入兼容格式说明.md)：备份字段、导入规则和测试样例。
