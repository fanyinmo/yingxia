param([switch]$Test, [switch]$Validate, [switch]$Instrumentation, [switch]$PhoneCheck)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($taskJava) { $env:JAVA_HOME = $taskJava.FullName }
if (-not $env:JAVA_HOME) { throw 'Run scripts/bootstrap_tools.py or install JDK 17 first.' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'local.properties'))) { throw 'Android SDK is not configured. See docs/BUILD.md.' }
$taskGradle = Join-Path $taskRoot '.tools\gradle-8.13\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $taskGradle) -and $env:GRADLE_HOME) { $taskGradle = Join-Path $env:GRADLE_HOME 'bin\gradle.bat' }
if (-not (Test-Path -LiteralPath $taskGradle)) { $taskGradle = Join-Path $taskRoot 'gradlew.bat' }
$taskGradleArgs = @('--no-daemon')
foreach ($taskProtocol in @('http', 'https')) {
    $taskProxyText = [Environment]::GetEnvironmentVariable("$($taskProtocol.ToUpper())_PROXY")
    if ($taskProxyText) {
        $taskProxy = [Uri]$taskProxyText
        if ($taskProxy.Scheme -eq 'http' -and -not $taskProxy.UserInfo) {
            $taskGradleArgs += "-D$taskProtocol.proxyHost=$($taskProxy.Host)"
            $taskGradleArgs += "-D$taskProtocol.proxyPort=$($taskProxy.Port)"
        }
    }
}
if ($Validate) { $taskGradleArgs += @('testDebugUnitTest', 'lintDebug', 'assembleDebug') }
elseif ($Test) { $taskGradleArgs += 'testDebugUnitTest' }
else { $taskGradleArgs += 'assembleDebug' }
if ($Instrumentation) { $taskGradleArgs += 'assembleDebugAndroidTest' }
if ($PhoneCheck) { $taskGradleArgs += 'assemblePhonecheck' }
& $taskGradle @taskGradleArgs
$taskBuildExit = $LASTEXITCODE
if ($taskBuildExit -eq 0 -and (-not $Test -or $Validate)) {
    New-Item -ItemType Directory -Path 'outputs\apk' -Force | Out-Null
    $taskVersion = (Select-String -LiteralPath 'app\build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches.Groups[1].Value
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination "outputs\apk\yingxia-$taskVersion.apk"
}
exit $taskBuildExit
