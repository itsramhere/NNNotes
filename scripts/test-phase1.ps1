param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$evidence = Join-Path $projectRoot 'reports\phase1\runtime'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

function Invoke-AdbChecked {
    param([string[]]$Arguments)
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}

function Invoke-Instrumentation {
    param([string]$Class, [string]$LogName)
    $output = Invoke-AdbChecked -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Class,
        'com.ramst.notein.test/androidx.test.runner.AndroidJUnitRunner')
    $output | Tee-Object -FilePath (Join-Path $evidence $LogName)
    if (($output -join "`n") -notmatch 'OK \(\d+ tests?\)' -or
        ($output -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Instrumentation failed; see $LogName"
    }
}

if (!$SkipBuild) {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $evidence 'build.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed' }
}
Invoke-AdbChecked -Arguments @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-AdbChecked -Arguments @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
Invoke-Instrumentation 'com.ramst.notein.Phase1StorageTest,com.ramst.notein.Phase1UiTest' 'suite.txt'
Invoke-Instrumentation 'com.ramst.notein.Phase1ProcessTest#prepare' 'process-prepare.txt'
Invoke-AdbChecked -Arguments @('shell', 'am', 'start', '-W', '-n', 'com.ramst.notein/.MainActivity')
$before = (Invoke-AdbChecked -Arguments @('shell', 'pidof', 'com.ramst.notein')) -join ''
Invoke-AdbChecked -Arguments @('shell', 'am', 'force-stop', 'com.ramst.notein')
$remaining = & $adb -s $Serial shell pidof com.ramst.notein
if ($remaining) { throw 'App process still exists after force-stop' }
Invoke-AdbChecked -Arguments @('shell', 'am', 'start', '-W', '-n', 'com.ramst.notein/.MainActivity')
$after = (Invoke-AdbChecked -Arguments @('shell', 'pidof', 'com.ramst.notein')) -join ''
if (!$before -or !$after -or $before -eq $after) { throw 'A distinct app process was not started' }
"Process restart: before=$before after=$after" | Tee-Object -FilePath (Join-Path $evidence 'process-restart.txt')
Invoke-Instrumentation 'com.ramst.notein.Phase1ProcessTest#verify' 'process-verify.txt'
Invoke-AdbChecked -Arguments @('shell', 'am', 'start', '-W', '-n', 'com.ramst.notein/.MainActivity')
Write-Output "Phase 1 runtime checks passed on $Serial. Evidence: $evidence"
