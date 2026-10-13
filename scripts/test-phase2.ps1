param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$BeforeRepairs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$evidence = Join-Path $projectRoot 'reports\phase2\runtime'
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
    if (!$env:JAVA_HOME -or (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-String) -notmatch 'version "25[.\"]') {
        throw 'Set JAVA_HOME to the approved JDK 25 before running Phase 2 tests.'
    }
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $evidence 'build.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed' }
}
Invoke-AdbChecked -Arguments @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-AdbChecked -Arguments @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($BeforeRepairs) {
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase2CanvasTest,com.ramst.notein.editor.Phase2StorageTest' 'before-repairs.txt'
    exit
}
Invoke-Instrumentation 'com.ramst.notein.Phase1StorageTest,com.ramst.notein.Phase1UiTest,com.ramst.notein.editor.Phase2CanvasTest,com.ramst.notein.editor.Phase2StorageTest,com.ramst.notein.editor.Phase2UiTest,com.ramst.notein.editor.Phase2MigrationTest' 'suite.txt'
Invoke-Instrumentation 'com.ramst.notein.editor.Phase2ProcessTest#prepare' 'process-prepare.txt'
Invoke-AdbChecked -Arguments @('shell', 'am', 'start', '-W', '-n', 'com.ramst.notein/.MainActivity')
$before = (Invoke-AdbChecked -Arguments @('shell', 'pidof', 'com.ramst.notein')) -join ''
Invoke-AdbChecked -Arguments @('shell', 'am', 'force-stop', 'com.ramst.notein')
$remaining = & $adb -s $Serial shell pidof com.ramst.notein
if ($remaining) { throw 'App process still exists after force-stop' }
Invoke-AdbChecked -Arguments @('shell', 'am', 'start', '-W', '-n', 'com.ramst.notein/.MainActivity')
$after = (Invoke-AdbChecked -Arguments @('shell', 'pidof', 'com.ramst.notein')) -join ''
if (!$before -or !$after -or $before -eq $after) { throw 'A distinct app process was not started' }
"Process restart: before=$before after=$after" | Tee-Object -FilePath (Join-Path $evidence 'process-restart.txt')
Invoke-Instrumentation 'com.ramst.notein.editor.Phase2ProcessTest#verify' 'process-verify.txt'
Invoke-AdbChecked -Arguments @('shell', 'logcat', '-d', '-s', 'NoteinInk:D', 'AndroidRuntime:E') |
    Tee-Object -FilePath (Join-Path $evidence 'ink-trace.txt') | Out-Null
Invoke-AdbChecked -Arguments @('shell', 'am', 'start', '-W', '-n', 'com.ramst.notein/.MainActivity')
Write-Output "Phase 2 emulator checks passed on $Serial. Physical active-pen/palm acceptance is still required. Evidence: $evidence"
