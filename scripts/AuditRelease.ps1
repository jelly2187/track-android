param(
    [string]$ApkPath = 'app/build/outputs/apk/release/app-release.apk',
    [string]$OutputDirectory = 'artifacts/reports/0.1.6'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $env:JAVA_HOME = (Get-ChildItem '.toolchain/java' -Directory | Select-Object -First 1).FullName
    $buildTools = Join-Path $projectRoot '.toolchain/sdk/build-tools/36.0.0'
    $apk = (Resolve-Path -LiteralPath $ApkPath).Path
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $permissionsOutput = & (Join-Path $buildTools 'aapt2.exe') dump permissions $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK permissions' }
    $permissions = @($permissionsOutput | ForEach-Object {
        if ($_ -match "^uses-permission: name='([^']+)'") { $Matches[1] }
    })
    $allowed = @('android.permission.POST_NOTIFICATIONS', 'android.permission.SCHEDULE_EXACT_ALARM',
        'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.INTERNET',
        'android.permission.WAKE_LOCK', 'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.REQUEST_INSTALL_PACKAGES',
        'com.jelly.habittrack.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
    $unexpected = @($permissions | Where-Object { $_ -notin $allowed })
    if ($unexpected.Count) { throw "Unexpected release permission: $($unexpected -join ', ')" }
    foreach ($required in $allowed[0..3]) {
        if ($required -notin $permissions) { throw "Missing expected permission: $required" }
    }
    if ('android.permission.REQUEST_INSTALL_PACKAGES' -notin $permissions) { throw 'The in-app updater is missing its user-confirmed install permission' }
    $badging = & (Join-Path $buildTools 'aapt2.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK metadata' }
    $packageLine = $badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
    if ($packageLine -notmatch "^package: name='com\.jelly\.habittrack' versionCode='(\d+)' versionName='([^']+)'") { throw 'Unexpected package identity' }
    $versionCode = [long]$Matches[1]; $version = $Matches[2]
    if ($badging -match '^application-debuggable') { throw 'Release APK is debuggable' }
    $signature = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    $signature | Set-Content (Join-Path $OutputDirectory 'apk-signature.txt') -Encoding utf8NoBOM
    $permissionsOutput | Set-Content (Join-Path $OutputDirectory 'apk-permissions.txt') -Encoding utf8NoBOM
    $tree = & (Join-Path $buildTools 'aapt2.exe') dump xmltree $apk --file AndroidManifest.xml
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read packaged manifest' }
    $tree | Set-Content (Join-Path $OutputDirectory 'apk-manifest.txt') -Encoding utf8NoBOM
    if (-not ($tree -match 'usesCleartextTraffic\([^)]*\)=false\s*$')) { throw 'Cleartext traffic is not explicitly disabled' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $embeddedPackages = @($zip.Entries | Where-Object { $_.FullName -match '(?i)\.(apk|apks|xapk|jar)$' } | ForEach-Object FullName)
        $nativeLibraries = @($zip.Entries | Where-Object { $_.FullName -match '\.so$' } | ForEach-Object FullName)
    } finally { $zip.Dispose() }
    if ($embeddedPackages.Count) { throw "Embedded package payload found: $($embeddedPackages -join ', ')" }
    $report = [ordered]@{
        checkedAt = (Get-Date).ToString('o'); packageName = 'com.jelly.habittrack'
        versionName = $version; versionCode = $versionCode
        sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        md5 = (Get-FileHash -LiteralPath $apk -Algorithm MD5).Hash.ToLowerInvariant()
        sizeBytes = (Get-Item -LiteralPath $apk).Length
        permissions = $permissions; embeddedPackages = $embeddedPackages; nativeLibraries = $nativeLibraries
        signatureVerified = $true; debuggable = $false; cleartextTrafficAllowed = $false
        updateMode = 'In-app HTTPS download; size/hash/package/version/signature checks; user-confirmed system installer'
        scope = 'Packaging and permission audit only; not an antivirus verdict or vendor approval.'
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDirectory 'apk-audit.json') -Encoding utf8NoBOM
    $review = @'
# Track 安装风险复核资料

- 应用名：Track
- 包名：com.jelly.habittrack
- 版本：__VERSION__（versionCode __CODE__）
- APK SHA-256：__SHA256__
- APK MD5：__MD5__
- APK： https://github.com/jelly2187/track-android/releases/download/v__VERSION__/Track-v__VERSION__.apk
- 源码与检查说明： https://github.com/jelly2187/track-android/blob/main/docs/INSTALLATION_SAFETY.md
- 签名信息：见同目录 apk-signature.txt，与既有公开版本保持一致。

本版本提供应用内更新，声明 REQUEST_INSTALL_PACKAGES，仅安装用户确认的同包名、同签名新版。
下载和安装前核对大小、SHA-256、包名、版本及签名；通过受限的非导出 FileProvider 只读分享更新文件给系统安装器。
不携带其他 APK，不自动下载，不在后台自动安装；最终安装需用户在系统界面确认。
仅按用户主动启用的习惯安排提醒，未开启提醒或通知权限关闭时不安排提醒唤醒。
删除旧的每 30 分钟全局轮询，静音期间停止重复唤醒，可选择不重复提醒。
无短信、系统设置写入、悬浮窗、无障碍或全部文件访问权限。

请结合具体风险名称、引擎及行为证据复核。附上手机实际提示截图、系统版本、安装来源和复现步骤。
以上是代码与包结构说明，不代替厂商安全检测。未将自动测试通过作为查杀通过的证明。
'@
    $review.Replace('__VERSION__', $version).Replace('__CODE__', $versionCode.ToString()).Replace('__SHA256__', $report.sha256).Replace('__MD5__', $report.md5) |
        Set-Content (Join-Path $OutputDirectory 'VENDOR_REVIEW.md') -Encoding utf8NoBOM
    Write-Output "Packaging audit passed for Track $version; no antivirus verdict is implied."
    $report | ConvertTo-Json -Depth 5
} finally { Pop-Location }
