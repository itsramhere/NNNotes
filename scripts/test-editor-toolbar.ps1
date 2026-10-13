param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$adb = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
$evidence = Join-Path $projectRoot 'reports/editor-toolbar'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
function Invoke-AdbChecked {
    param([string[]]$Arguments)
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}
function Test-Toolbar {
    param([string]$Classes, [string]$Name)
    $result = Invoke-AdbChecked @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Classes,
        'com.ramst.notein.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath (Join-Path $evidence "$Name.txt")
    $joined = $result -join "`n"
    if ($joined -notmatch 'OK \(\d+ tests?\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        Write-Output ($result | Select-Object -Last 90)
        throw "Toolbar tests failed: $Name"
    }
    Write-Output "$Name : $($Matches[0])"
}
function Copy-Screenshot {
    param([string]$Name, [string]$Prefix)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.Arguments = "-s $Serial exec-out run-as com.ramst.notein cat files/toolbar-evidence/$Name"
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
if (!$SkipBuild) {
    & ./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --offline --no-daemon --console=plain --max-workers=1 2>&1 |
        Tee-Object (Join-Path $evidence 'build.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Build/lint failed' }
}
Invoke-AdbChecked @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-AdbChecked @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if (((Invoke-AdbChecked @('shell','getprop','ro.kernel.qemu')) -join '').Trim() -ne '1') {
    throw 'Display-size verification requires an emulator.'
}
$oldSize = (Invoke-AdbChecked @('shell','wm','size')) -join "`n"
$oldDensity = (Invoke-AdbChecked @('shell','wm','density')) -join "`n"
try {
    Invoke-AdbChecked @('shell','wm','size','1600x1000')
    Invoke-AdbChecked @('shell','wm','density','160')
    Test-Toolbar 'com.ramst.notein.editor.EditorToolbarUiTest,com.ramst.notein.editor.Phase10UiTest,com.ramst.notein.editor.Phase4UiTest' 'tablet'
    foreach ($name in @('compact-toolbar.png','pencil-options.png','settings-page-types.png')) { Copy-Screenshot $name 'tablet' }
    Invoke-AdbChecked @('shell','wm','size','720x1280')
    Invoke-AdbChecked @('shell','wm','density','320')
    Test-Toolbar 'com.ramst.notein.editor.EditorToolbarUiTest' 'phone'
    foreach ($name in @('compact-toolbar.png','pencil-options.png','settings-page-types.png')) { Copy-Screenshot $name 'phone' }
} finally {
    $size = if ($oldSize -match 'Override size: (\d+x\d+)') { $Matches[1] } else { 'reset' }
    $density = if ($oldDensity -match 'Override density: (\d+)') { $Matches[1] } else { 'reset' }
    Invoke-AdbChecked @('shell','wm','size',$size)
    Invoke-AdbChecked @('shell','wm','density',$density)
}
Write-Output 'Editor toolbar verification passed at tablet and phone sizes.'
