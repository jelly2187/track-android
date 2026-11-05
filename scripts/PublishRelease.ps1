param([string]$Repository = 'jelly2187/track-android', [string]$Version = '0.1.6')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or $Version -notmatch '^\d+\.\d+\.\d+$') { throw '无效的发布参数' }
    $gh = Join-Path $projectRoot '.toolchain/gh/bin/gh.exe'
    if (-not (Test-Path -LiteralPath $gh)) { $gh = (Get-Command gh -ErrorAction Stop).Source }
    $login = & $gh api user --jq '.login'
    if ($LASTEXITCODE -ne 0) { throw '请先通过 gh auth login 登录 GitHub，再执行发布。' }
    if ($login.Trim() -ne ($Repository -split '/')[0]) { throw '当前 GitHub 登录账号与目标仓库所有者不一致，已停止。' }
    $existing = & $gh repo view $Repository --json nameWithOwner --jq '.nameWithOwner' 2>$null
    if ($LASTEXITCODE -ne 0 -or $existing.Trim() -ne $Repository) { throw '目标源码仓库不存在，已停止。' }
    $origin = git remote get-url origin
    if ($LASTEXITCODE -ne 0 -or $origin.Trim() -notin @("https://github.com/$Repository.git", "https://github.com/$Repository", "git@github.com:$Repository.git")) { throw '目标发布仓库与当前源码 origin 不一致，已停止以避免修改其他项目。' }
    $folder = Join-Path $projectRoot "artifacts/releases/$Version"
    $manifestPath = Join-Path $folder 'latest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $apkPath = Join-Path $folder "Track-v$Version.apk"
    if ($manifest.versionName -ne $Version -or $manifest.apkUrl -ne "https://github.com/$Repository/releases/download/v$Version/Track-v$Version.apk" -or (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256) { throw '发布文件或仓库地址与更新描述不一致' }
    & $gh release view "v$Version" --repo $Repository --json tagName 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { throw '此版本已经存在，拒绝覆盖；请递增应用版本后重新构建。' }
    $assets = @($apkPath, $manifestPath, "$apkPath.sha256", (Join-Path $folder 'RELEASE_NOTES.md'),
        (Join-Path $folder 'PACKAGING_AUDIT.json'), (Join-Path $folder 'PERMISSIONS.txt'), (Join-Path $folder 'VENDOR_REVIEW.md'))
    & $gh release create "v$Version" @assets --repo $Repository --target main --draft --title "Track $Version" --notes-file (Join-Path $folder 'RELEASE_NOTES.md')
    if ($LASTEXITCODE -ne 0) { throw '发布草稿或资源上传失败；请检查草稿，尚未公开发布。' }
    $verificationFolder = Join-Path $folder 'remote-verification'
    New-Item -ItemType Directory -Path $verificationFolder -Force | Out-Null
    & $gh release download "v$Version" --repo $Repository --pattern 'latest.json' --pattern "Track-v$Version.apk" --dir $verificationFolder --clobber
    if ($LASTEXITCODE -ne 0) { throw '无法下载发布草稿核验，保留草稿不公开。' }
    $remote = Get-Content -LiteralPath (Join-Path $verificationFolder 'latest.json') -Raw | ConvertFrom-Json
    if ($remote.sha256 -ne $manifest.sha256 -or (Get-FileHash -LiteralPath (Join-Path $verificationFolder "Track-v$Version.apk") -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256) { throw '远程资源校验失败，保留草稿不公开。' }
    & $gh release edit "v$Version" --repo $Repository --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw '发布失败，请检查 GitHub 草稿状态。' }
    Write-Output "更新地址：https://github.com/$Repository/releases/latest/download/latest.json"
    Write-Output "版本地址：https://github.com/$Repository/releases/tag/v$Version"
} finally { Pop-Location }
