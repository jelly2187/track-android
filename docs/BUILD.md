# 开发与构建

[返回 Track 首页](../README.md)

## 环境

- JDK 17
- Gradle Wrapper 9.3.1、Android Gradle Plugin 9.1.1
- Android SDK API 37，官方平台包名 `platforms;android-37.0`
- SDK Build Tools 36.0.0；应用最低 API 26

设置 `JAVA_HOME`、`ANDROID_HOME`，或在 Android Studio 中配置 SDK。依赖版本固定；KSP 2.2.10 与 AGP 9 配合使用 `android.disallowKotlinSourceSets=false` 兼容开关。

## 获取与调试

```bash
git clone https://github.com/jelly2187/track-android.git
cd track-android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。调试构建无需官方签名密钥；自行签名的构建不能覆盖官方 APK，若需并存，请为个人构建修改 `applicationId`。

交付的本地环境支持 PowerShell：

```powershell
./scripts/Build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'
# 已连接专用测试手机或模拟器后
./scripts/Build.ps1 -Tasks ':app:connectedDebugAndroidTest'
```

脚本优先使用本地 `.toolchain/java` 与 `.toolchain/sdk`，这些目录不进入 Git。首次构建需访问依赖下载服务器。

## 代码结构

- `data`：Room、打卡计划历史、事务、事件去重与备份校验。
- `reminder`：提醒调度、常驻汇总通知、直接操作与重启恢复。
- `widget`：列表和日历小组件、独立配置与桌面打卡。
- `ui`：今日列表、详情、编辑器、设置及更新界面。
- `update`：HTTPS 版本检查、应用内下载、文件和签名校验、用户确认的系统安装入口；弹窗显示独立于下载状态。

每习惯每天唯一记录。应用、通知和小组件共用写入入口，外部操作绑定日期并去重。目标和计划按生效日期保存，修改不重算过去统计。

## 签名

发布构建使用本地 `signing.properties` 和 `personal-signing.jks`。官方升级密钥不公开、不提交 Git；请保留原密钥以支持覆盖安装。

个人分支首次可以运行 `scripts/NewSigningKey.ps1` 生成自己的密钥。脚本拒绝覆盖现有密钥。修改项目版本时应同时递增 `versionCode` 和 `versionName`。

## 发布

源码、安装包和更新描述统一使用 `jelly2187/track-android`。默认更新地址：

```text
https://github.com/jelly2187/track-android/releases/latest/download/latest.json
```

先构建、完成测试，并把源码提交到目标仓库，然后：

```powershell
./scripts/Build.ps1
./scripts/PrepareRelease.ps1 -NotesFile 'docs/releases/0.1.6.md'
./scripts/PublishRelease.ps1 -Version '0.1.6'
```

准备脚本从签名 APK 读取版本，生成包含下载地址、大小和 SHA-256 的 `latest.json`。发布脚本要求 GitHub CLI 已登录，并校验目标仓库与源码 `origin` 一致；上传 APK、校验文件、更新描述、说明及包结构审计资料。先创建草稿、重新下载核验，再正式发布；拒绝覆盖已有版本。

`PrepareRelease.ps1` 会先执行 `AuditRelease.ps1`，从编译后的 APK 核对权限、调试标记、签名、明文网络设置及内嵌安装包。报告与厂商复核资料位于 `artifacts/reports/版本号/`。该审计不是杀毒引擎检测，也不表示厂商已批准安装。

签名材料、缓存、SDK、构建产物和个人记录均被排除在版本管理之外。测试结果与尚待执行的实机场景见 [验证记录](VALIDATION.md)。

## 截图

README 截图由 `ShowcaseTest` 从实际应用页面渲染生成，使用合成的演示习惯数据。截图文件位于 `artifacts/showcase`，人工检查后复制到 `docs/images`。不使用个人手机记录作为公开演示素材。
