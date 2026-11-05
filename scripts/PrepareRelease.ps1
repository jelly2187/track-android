param(
    [string]$Repository = 'jelly2187/track-android',
    [string]$ApkPath = 'app/build/outputs/apk/release/app-release.apk',
    [string]$NotesFile = 'docs/releases/0.1.6.md'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw '无效的 GitHub 仓库名称' }
    $env:JAVA_HOME = (Get-ChildItem '.toolchain/java' -Directory | Select-Object -First 1).FullName
    $buildTools = Join-Path $projectRoot '.toolchain/sdk/build-tools/36.0.0'
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose $ApkPath
    if ($LASTEXITCODE -ne 0) { throw '发布包签名校验失败' }
    $metadata = & (Join-Path $buildTools 'aapt2.exe') dump badging $ApkPath
    if ($LASTEXITCODE -ne 0) { throw '无法读取发布包信息' }
    $packageLine = $metadata | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
    if ($packageLine -notmatch "^package: name='com\.jelly\.habittrack' versionCode='(\d+)' versionName='([A-Za-z0-9._-]+)'") { throw '安装包标识与预期不匹配' }
    $versionCode = [long]$Matches[1]; $versionName = $Matches[2]
    & (Join-Path $PSScriptRoot 'AuditRelease.ps1') -ApkPath $ApkPath -OutputDirectory "artifacts/reports/$versionName" | Out-Null
    $minimumLine = $metadata | Where-Object { $_ -match '^(minSdkVersion|sdkVersion):' } | Select-Object -First 1
    if ($minimumLine -notmatch "'(\d+)'") { throw '无法读取最低安卓版本' }
    $minSdk = [int]$Matches[1]
    $output = Join-Path $projectRoot "artifacts/releases/$versionName"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $apkName = "Track-v$versionName.apk"
    $destination = Join-Path $output $apkName
    Copy-Item -LiteralPath $ApkPath -Destination $destination -Force
    $digest = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $notes = Get-Content -LiteralPath $NotesFile -Raw
    $manifest = [ordered]@{
        schemaVersion = 1; versionCode = $versionCode; versionName = $versionName
        apkUrl = "https://github.com/$Repository/releases/download/v$versionName/$apkName"
        sha256 = $digest; sizeBytes = (Get-Item -LiteralPath $destination).Length
        minSdk = $minSdk; notes = $notes.Trim()
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'latest.json') -Encoding utf8NoBOM
    "$digest  $apkName" | Set-Content -LiteralPath (Join-Path $output "$apkName.sha256") -Encoding ascii
    Copy-Item -LiteralPath $NotesFile -Destination (Join-Path $output 'RELEASE_NOTES.md') -Force
    Copy-Item -LiteralPath "artifacts/reports/$versionName/apk-audit.json" -Destination (Join-Path $output 'PACKAGING_AUDIT.json') -Force
    Copy-Item -LiteralPath "artifacts/reports/$versionName/apk-permissions.txt" -Destination (Join-Path $output 'PERMISSIONS.txt') -Force
    Copy-Item -LiteralPath "artifacts/reports/$versionName/VENDOR_REVIEW.md" -Destination (Join-Path $output 'VENDOR_REVIEW.md') -Force
    Write-Output "发布目录：$output"
    Write-Output '目录包含 APK、校验文件、更新描述、说明和包结构审计资料，不包含私钥或签名密码。'
} finally { Pop-Location }
