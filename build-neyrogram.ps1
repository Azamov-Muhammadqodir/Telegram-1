# Neyrogram build skripti
#   .\build-neyrogram.ps1                 -> debug APK, faqat arm64-v8a (tez)
#   .\build-neyrogram.ps1 -Abis all       -> debug APK, barcha ABI'lar
#   .\build-neyrogram.ps1 -Release        -> release APK
param(
    [string]$Abis = "arm64-v8a",
    [switch]$Release
)

$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$task = if ($Release) { ":TMessagesProj_App:assembleAfatRelease" } else { ":TMessagesProj_App:assembleAfatDebug" }
$gradleArgs = @($task, "--stacktrace")
if ($Abis -ne "all") { $gradleArgs += "-PneyroAbis=$Abis" }

Set-Location $PSScriptRoot
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -eq 0) {
    Get-ChildItem -Recurse "TMessagesProj_App\build\outputs\apk" -Filter *.apk | Select-Object FullName, Length, LastWriteTime
}
exit $LASTEXITCODE
