$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyPath = Join-Path $projectRoot 'personal-signing.jks'
$propertyPath = Join-Path $projectRoot 'signing.properties'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $propertyPath)) { throw '签名材料已存在；为保证覆盖升级，请保留现有密钥。' }
$portableJava = Join-Path $projectRoot '.toolchain\java'
if (Test-Path -LiteralPath $portableJava) { $env:JAVA_HOME = (Get-ChildItem -LiteralPath $portableJava -Directory | Select-Object -First 1).FullName }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$random = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($random)
$env:HABIT_SIGN_PASS = [Convert]::ToHexString($random).ToLowerInvariant()
try {
    & $keytool -genkeypair -keystore $keyPath -storetype JKS -alias habittrack -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=HabitTrack Personal' -storepass:env HABIT_SIGN_PASS -keypass:env HABIT_SIGN_PASS
    if ($LASTEXITCODE -ne 0) { throw '签名密钥生成失败' }
    @('storeFile=personal-signing.jks', "storePassword=$env:HABIT_SIGN_PASS", 'keyAlias=habittrack', "keyPassword=$env:HABIT_SIGN_PASS") | Set-Content -LiteralPath $propertyPath
    Write-Output '已生成本地专用密钥。请将 personal-signing.jks 和 signing.properties 一起安全备份，均不提交 Git。'
} finally { Remove-Item Env:\HABIT_SIGN_PASS -ErrorAction SilentlyContinue }
