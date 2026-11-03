# Track · HabitTrack

[源码](https://github.com/jelly2187/track-android) · [下载安装包](https://github.com/jelly2187/track-android-releases/releases/latest) · [MIT License](LICENSE)

一款离线安卓习惯打卡应用。原生 Kotlin / Compose Material 3，Room 数据库，AlarmManager 提醒，Glance 桌面小组件。

## 使用

1. 安装 `artifacts/Track-v0.1.1.apk`，创建习惯。可以选完成型或计数型，自定义颜色分组、打卡星期、提醒星期和多个提醒时间。
2. 在「设置」允许通知与准时提醒，发送测试通知。未确认默认每 15 分钟提醒，23:00—07:00 静音；达标或确认「今日未完成」后停止当天提醒。
3. 长按桌面空白处 → 小组件 → Track，添加「今日习惯」或「坚持日历」，分别选择分组或习惯。列表支持直接打卡和累计，点击名称打开详情。
4. 点击习惯查看统计、修改记录或补记。休息日不打断连续记录，今天尚未结束时不会提前计为失败。
5. 「设置」可导出 JSON 完整备份，恢复会替换当前数据。卸载前请自行导出备份；应用没有云同步，也不使用系统自动云备份。

## OPPO Find X9 Pro / ColorOS 16、17

- 在应用信息中允许通知，检查「习惯打卡提醒」通知渠道、锁屏显示及声音权限。
- 允许「闹钟和提醒」精确调度；未授权时使用系统非精确提醒，可能延迟。
- 检查应用电池管理、后台运行及自启动设置。不同 ColorOS 版本的菜单名称可能不同；应用内提供系统设置入口，可在最近任务中锁定应用辅助保留。
- Android 14 起，普通常驻通知仍可能被单独划掉。Track 会保留待办，并在下一次提醒重新显示，不能承诺绝对不可划掉。
- 强行停止、关闭通知、勿扰模式和系统后台限制会影响提醒。强行停止后需重新打开应用；重启后会恢复调度，首次解锁后可用。
- ColorOS 17 升级后重新检查权限、熄屏提醒、小组件与覆盖安装保留数据；应用不会自动升级手机系统。

## 构建

构建基线：JDK 17、Gradle 9.3.1、AGP 9.1.1、compile/target SDK 37、min SDK 26。官方 SDK 仓库的平台包名为 `platforms;android-37.0`。依赖已固定；KSP 2.2.10 需在 AGP 9 中使用 `android.disallowKotlinSourceSets=false` 兼容开关。

已交付环境可运行：

```powershell
./scripts/Build.ps1
# 指定任务
./scripts/Build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'
# 仅在连接测试设备/模拟器后执行
./scripts/Build.ps1 -Tasks ':app:connectedDebugAndroidTest'
```

在其他电脑上安装 JDK 17 与 Android SDK，用 Android Studio 打开项目并配置 SDK 路径，或设置 `JAVA_HOME`、`ANDROID_HOME`。首次构建需要访问 Google Maven、Maven Central 和 Gradle 下载服务器。

发布构建需本地 `signing.properties` 和 `personal-signing.jks`。本项目交付保留同一密钥用于后续覆盖升级；不要重新生成或提交这些文件。新项目首次可运行 `scripts/NewSigningKey.ps1` 生成，密钥存在时脚本拒绝覆盖。

## 代码与数据

- `data`：Room 实体、版本化打卡计划、事务写入、事件去重、备份校验。
- `reminder`：每天多时刻与重复提醒合并，常驻汇总通知、直接操作、重启恢复。
- `widget`：独立实例配置、列表与28天日历、直接打卡。
- `ui`：今日列表、统计详情、编辑器、权限诊断、备份与恢复。

每习惯每天唯一一条记录。应用、通知和小组件使用同一写入入口，外部操作绑定日期并去重；过期通知不会修改今天。目标与打卡计划按生效日期保存，编辑不追溯改变过去统计。跨午夜不追提醒前一天。打卡数据仍保存在本机；应用从 0.1.1 起增加网络权限，仅用于版本检查与 APK 下载，不上传习惯数据。

Git 使用本地 `main`，按功能提交，验收后标记 `v0.1.0`；构建环境、安装包、个人数据和签名材料不进入版本管理。测试记录见 `docs/VALIDATION.md`。

## 自动更新（从 0.1.1 起）

每次打开应用都会在后台检查 GitHub Releases；发现更高版本时显示更新说明，点击下载后校验大小、SHA-256、包名、版本及签名，再调起系统安装器。第一次需允许 Track 安装应用；安装最后一步由你确认。失败或断网不会影响打卡，可在设置手动重试或关闭启动检查。

当前 0.1.0 尚无更新功能，需要先手动覆盖安装 0.1.1。包名、签名和数据库版本保持一致，不卸载即可保留记录；原备份仍可恢复。

公开发布仓库仅含安装包与说明：[Track Releases](https://github.com/jelly2187/track-android-releases/releases)。更新地址为 `https://github.com/jelly2187/track-android-releases/releases/latest/download/latest.json`，也可在设置替换为自己的 HTTPS 地址。GitHub 无法连接时会保留当前版本，不影响本机打卡。

后续发布：递增 `versionCode` / `versionName`，写更新说明，保留原签名并构建，再执行：

```powershell
./scripts/PrepareRelease.ps1 -NotesFile 'docs/releases/0.1.1.md'
./scripts/PublishRelease.ps1 -Version '0.1.1'
```

脚本从签名 APK 读取真实版本、生成 `latest.json`，只上传白名单内的 APK、SHA-256、更新描述及说明；先创建草稿，重新下载核验后再公开。它不会推送本地源码或签名材料，已存在的版本拒绝覆盖。脚本需要 GitHub CLI 登录，默认使用 APK 专用仓库 `jelly2187/track-android-releases`。

## 开源与构建

本项目以 [MIT License](LICENSE) 开源，第三方说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。公开仓库包含应用源码、测试、数据库结构、构建与发布脚本、文档、提交历史和版本标签。

签名密钥、密码、本机 SDK/JDK、构建缓存、手机备份和个人数据不上传。官方版本继续由 APK 专用发布仓库提供，自动更新地址保持一致。

```bash
git clone https://github.com/jelly2187/track-android.git
cd track-android
# 安装 JDK 17 和 Android SDK，配置 ANDROID_HOME 或 local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`；PowerShell 可使用现有 `scripts/Build.ps1`。调试构建无需官方签名密钥；自行签名的构建不能覆盖官方 APK，若需与官方版本并存，请为个人构建修改 applicationId。官方升级密钥不会公开。
