param([string[]]$Tasks = @(':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $portableJava = Join-Path $projectRoot '.toolchain\java'
    if (Test-Path -LiteralPath $portableJava) { $env:JAVA_HOME = (Get-ChildItem -LiteralPath $portableJava -Directory | Select-Object -First 1).FullName }
    if (-not $env:JAVA_HOME) { throw '请安装 JDK 17 并设置 JAVA_HOME，或将 JDK 放入 .toolchain/java。' }
    $portableSdk = Join-Path $projectRoot '.toolchain\sdk'
    if (Test-Path -LiteralPath $portableSdk) {
        $env:ANDROID_HOME = $portableSdk
        'sdk.dir=' + $portableSdk.Replace('\', '/').Replace(':', '\:') | Set-Content -LiteralPath 'local.properties'
    }
    & '.\gradlew.bat' @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，退出码 $LASTEXITCODE" }
} finally { Pop-Location }
