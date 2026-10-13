param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$evidence = Join-Path $projectRoot 'reports\phase10\runtime'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
function Invoke-AdbChecked {
    param([string[]]$Arguments)
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}
function Invoke-Instrumentation {
    param([string]$Class, [string]$LogName)
    $output = Invoke-AdbChecked -Arguments @('shell','am','instrument','-w','-r','-e','class',$Class,
        'com.ramst.notein.test/androidx.test.runner.AndroidJUnitRunner')
    $output | Set-Content (Join-Path $evidence $LogName)
    $joined = $output -join "`n"
    if ($joined -notmatch 'OK \(\d+ tests?\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        Write-Output ($output | Select-Object -Last 60)
        throw "Instrumentation failed; see $LogName"
    }
    Write-Output "$LogName : $($Matches[0])"
}
function Copy-TestEvidence {
    param([string]$Name)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.Arguments = "-s $Serial exec-out run-as com.ramst.notein cat files/phase10-evidence/$Name"
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($start)
    $stream = [IO.File]::Create((Join-Path $evidence $Name))
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Evidence copy failed: $Name" }
    $process.Dispose()
}
if (!$SkipBuild) {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --offline --no-daemon --console=plain --max-workers=1 2>&1 | Tee-Object (Join-Path $evidence 'build-final.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Build/lint failed' }
}
Invoke-AdbChecked -Arguments @('install','-r','app/build/outputs/apk/debug/app-debug.apk')
Invoke-AdbChecked -Arguments @('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
$isEmulator = ((Invoke-AdbChecked -Arguments @('shell','getprop','ro.kernel.qemu')) -join '').Trim() -eq '1'
$oldSize = ((Invoke-AdbChecked -Arguments @('shell','wm','size')) -join "`n")
$oldDensity = ((Invoke-AdbChecked -Arguments @('shell','wm','density')) -join "`n")
try {
    if ($isEmulator) {
        Invoke-AdbChecked -Arguments @('shell','wm','size','1600x1000')
        Invoke-AdbChecked -Arguments @('shell','wm','density','160')
    }
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase10PdfTest,com.ramst.notein.editor.Phase10EditorTest,com.ramst.notein.editor.Phase10InkTest,com.ramst.notein.editor.Phase10MigrationTest,com.ramst.notein.editor.Phase10CanvasTest,com.ramst.notein.editor.Phase10UiTest,com.ramst.notein.editor.Phase10RenderingTest' 'phase10-suite.txt'
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase10RotationTest' 'rotation.txt'
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase2MigrationTest,com.ramst.notein.editor.Phase3MigrationTest,com.ramst.notein.editor.Phase6MigrationTest,com.ramst.notein.editor.Phase9MigrationTest,com.ramst.notein.editor.Phase9StorageTest,com.ramst.notein.editor.Phase9PaperTest,com.ramst.notein.editor.Phase9EditorTest,com.ramst.notein.editor.Phase3CanvasTest,com.ramst.notein.editor.Phase4CanvasTest,com.ramst.notein.editor.Phase4SelectionTest' 'regressions.txt'
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase10ProcessTest#prepare' 'process-prepare.txt'
    Invoke-AdbChecked -Arguments @('shell','am','start','-W','-n','com.ramst.notein/.MainActivity')
    $before = ((Invoke-AdbChecked -Arguments @('shell','pidof','com.ramst.notein')) -join '').Trim()
    Invoke-AdbChecked -Arguments @('shell','am','force-stop','com.ramst.notein')
    Invoke-AdbChecked -Arguments @('shell','am','start','-W','-n','com.ramst.notein/.MainActivity')
    $after = ((Invoke-AdbChecked -Arguments @('shell','pidof','com.ramst.notein')) -join '').Trim()
    if (!$before -or !$after -or $before -eq $after) { throw 'No distinct app process after force-stop' }
    "Force-stop/restart: before=$before after=$after" | Set-Content (Join-Path $evidence 'process-restart.txt')
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase10ProcessTest#verify' 'process-verify.txt'
    foreach ($file in @('pen-colors.pdf','editor-locked.png','editor-recreated.png','rotation-before.png','rotation-after.png','restart-canvas.pdf','restart-verified.json')) { Copy-TestEvidence $file }
    $runtimeErrors = Invoke-AdbChecked -Arguments @('shell','logcat','-d','-s','AndroidRuntime:E')
    Set-Content -LiteralPath (Join-Path $evidence 'runtime-log.txt') -Value ($runtimeErrors -join "`n")
} finally {
    if ($isEmulator) {
        $size = if ($oldSize -match 'Override size: (\d+x\d+)') { $Matches[1] } else { 'reset' }
        $density = if ($oldDensity -match 'Override density: (\d+)') { $Matches[1] } else { 'reset' }
        Invoke-AdbChecked -Arguments @('shell','wm','size',$size)
        Invoke-AdbChecked -Arguments @('shell','wm','density',$density)
    }
}
Write-Output "Phase 10 emulator verification passed on $Serial; physical active-pen/palm acceptance remains separate."
