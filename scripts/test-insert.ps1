param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$adb = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
$evidence = Join-Path $projectRoot 'reports/insert'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

function Invoke-AdbChecked {
    param([string[]]$Arguments)
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}

function Test-Ui {
    param([string]$Classes, [string]$Name)
    $output = Invoke-AdbChecked @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Classes,
        'com.ramst.notein.test/androidx.test.runner.AndroidJUnitRunner')
    $output | Set-Content -LiteralPath (Join-Path $evidence "$Name.txt")
    $joined = $output -join "`n"
    if ($joined -notmatch 'OK \(\d+ tests?\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        Write-Output ($output | Select-Object -Last 75)
        throw "UI verification failed: $Name"
    }
    Write-Output "$Name : $($Matches[0])"
}

function Copy-PrivateScreenshot {
    param([string]$Directory, [string]$Name, [string]$Prefix)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.Arguments = "-s $Serial exec-out run-as com.ramst.notein cat files/$Directory/$Name"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($start)
    $stream = [IO.File]::Create((Join-Path $evidence "$Prefix-$Name"))
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Screenshot copy failed: $Name" }
    $process.Dispose()
}

function Copy-InsertScreenshots {
    param([string]$Prefix)
    foreach ($name in @('insert-menu.png', 'shape-dialog.png', 'shape-on-page.png', 'graph-dialog.png', 'graph-on-page.png', 'heading-dialog.png', 'table-editor.png', 'table-on-page.png')) {
        Copy-PrivateScreenshot 'insert-evidence' $name $Prefix
    }
}

if (!$SkipBuild) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & ./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --offline --no-daemon --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' 2>&1 |
            Tee-Object (Join-Path $evidence 'build.txt')
        $buildExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($buildExitCode -ne 0) { throw 'Build or lint failed' }
}
Invoke-AdbChecked @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-AdbChecked @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if (((Invoke-AdbChecked @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim() -ne '1') {
    throw 'Display-size verification requires an emulator.'
}
$oldSize = (Invoke-AdbChecked @('shell', 'wm', 'size')) -join "`n"
$oldDensity = (Invoke-AdbChecked @('shell', 'wm', 'density')) -join "`n"
$oldNight = (Invoke-AdbChecked @('shell', 'cmd', 'uimode', 'night')) -join "`n"
try {
    Invoke-AdbChecked @('shell', 'cmd', 'uimode', 'night', 'no')
    Invoke-AdbChecked @('shell', 'wm', 'size', '1280x800')
    Invoke-AdbChecked @('shell', 'wm', 'density', '160')
    Test-Ui 'com.ramst.notein.editor.InsertUiTest,com.ramst.notein.editor.InsertContentTest,com.ramst.notein.editor.EditorToolbarUiTest,com.ramst.notein.editor.HandwritingRegressionTest' 'tablet-landscape'
    Copy-InsertScreenshots 'tablet-landscape'
    Invoke-AdbChecked @('shell', 'wm', 'size', '800x1280')
    Test-Ui 'com.ramst.notein.editor.InsertUiTest' 'tablet-portrait'
    Copy-InsertScreenshots 'tablet-portrait'
    Invoke-AdbChecked @('shell', 'wm', 'size', '720x1280')
    Invoke-AdbChecked @('shell', 'wm', 'density', '320')
    Test-Ui 'com.ramst.notein.editor.InsertUiTest' 'phone'
    Copy-InsertScreenshots 'phone'
    Invoke-AdbChecked @('shell', 'cmd', 'uimode', 'night', 'yes')
    Test-Ui 'com.ramst.notein.editor.InsertUiTest' 'phone-dark'
    Copy-InsertScreenshots 'phone-dark'

} finally {
    $size = if ($oldSize -match 'Override size: (\d+x\d+)') { $Matches[1] } else { 'reset' }
    $density = if ($oldDensity -match 'Override density: (\d+)') { $Matches[1] } else { 'reset' }
    $night = if ($oldNight -match 'Night mode: (yes|no|auto|custom)') { $Matches[1] } else { 'auto' }
    Invoke-AdbChecked @('shell', 'wm', 'size', $size)
    Invoke-AdbChecked @('shell', 'wm', 'density', $density)
    Invoke-AdbChecked @('shell', 'cmd', 'uimode', 'night', $night)
}
Write-Output 'Insert features verified in tablet landscape, tablet portrait, phone, and phone dark mode.'
