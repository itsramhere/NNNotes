param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$Phase6Only)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$evidence = Join-Path $projectRoot 'reports\phase6\runtime'
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
    $output | Tee-Object -FilePath (Join-Path $evidence $LogName)
    $joined = $output -join "`n"
    if ($joined -notmatch 'OK \(\d+ tests?\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed; see $LogName" }
}
function Copy-TestEvidence {
    param([string]$Name)
    $directory = Join-Path $evidence 'pdf-evidence'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.Arguments = "-s $Serial exec-out run-as com.ramst.notein cat files/phase6-evidence/$Name"
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($start)
    $stream = [IO.File]::Create((Join-Path $directory $Name))
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Evidence copy failed: $Name" }
    $process.Dispose()
}
if (!$SkipBuild) {
    if (!$env:JAVA_HOME -or (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-String) -notmatch 'version "25[.\"]') { throw 'Set JAVA_HOME to the approved JDK 25.' }
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --console=plain 2>&1 | Tee-Object (Join-Path $evidence 'build.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed' }
}
Invoke-AdbChecked -Arguments @('install','-r','app/build/outputs/apk/debug/app-debug.apk')
Invoke-AdbChecked -Arguments @('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($Phase6Only) {
    Invoke-Instrumentation 'com.ramst.notein.editor.Phase6PdfTest,com.ramst.notein.editor.Phase6UiTest,com.ramst.notein.editor.Phase6MigrationTest' 'phase6-suite.txt'
} else {
    Invoke-Instrumentation 'com.ramst.notein.Phase1StorageTest,com.ramst.notein.Phase1UiTest,com.ramst.notein.editor.Phase2CanvasTest,com.ramst.notein.editor.Phase2StorageTest,com.ramst.notein.editor.Phase2UiTest,com.ramst.notein.editor.Phase2MigrationTest,com.ramst.notein.editor.Phase3StorageTest,com.ramst.notein.editor.Phase3CanvasTest,com.ramst.notein.editor.Phase3UiTest,com.ramst.notein.editor.Phase3MigrationTest,com.ramst.notein.editor.Phase4SelectionTest,com.ramst.notein.editor.Phase4StorageTest,com.ramst.notein.editor.Phase4ImageTest,com.ramst.notein.editor.Phase4CanvasTest,com.ramst.notein.editor.Phase4UiTest,com.ramst.notein.editor.Phase6PdfTest,com.ramst.notein.editor.Phase6UiTest,com.ramst.notein.editor.Phase6MigrationTest' 'suite.txt'
}
foreach ($phase in @(4,6)) {
    Invoke-Instrumentation "com.ramst.notein.editor.Phase${phase}ProcessTest#prepare" "phase$phase-process-prepare.txt"
    Invoke-AdbChecked -Arguments @('shell','am','start','-W','-n','com.ramst.notein/.MainActivity')
    $before = (Invoke-AdbChecked -Arguments @('shell','pidof','com.ramst.notein')) -join ''
    Invoke-AdbChecked -Arguments @('shell','am','force-stop','com.ramst.notein')
    $remaining = & $adb -s $Serial shell pidof com.ramst.notein
    if ($remaining) { throw 'App still running after force-stop' }
    Invoke-AdbChecked -Arguments @('shell','am','start','-W','-n','com.ramst.notein/.MainActivity')
    $after = (Invoke-AdbChecked -Arguments @('shell','pidof','com.ramst.notein')) -join ''
    if (!$before -or !$after -or $before -eq $after) { throw 'No distinct new app process' }
    "Phase $phase process restart: before=$before after=$after" | Tee-Object (Join-Path $evidence "phase$phase-process-restart.txt")
    Invoke-Instrumentation "com.ramst.notein.editor.Phase${phase}ProcessTest#verify" "phase$phase-process-verify.txt"
}
foreach ($file in @('selected.pdf','canvas.pdf','snapshot.pdf','canvas-four.pdf','hundred-export.pdf','profile.txt','restarted.pdf','alignment-0.25.png','alignment-1.0.png','alignment-2.0.png')) { Copy-TestEvidence $file }
Invoke-AdbChecked -Arguments @('shell','logcat','-d','-s','NoteinInk:D','AndroidRuntime:E') | Set-Content (Join-Path $evidence 'runtime-log.txt')
Invoke-AdbChecked -Arguments @('shell','am','start','-W','-n','com.ramst.notein/.MainActivity')
Write-Output "Requested emulator suite (Phase6Only=$Phase6Only) and restart checks passed on $Serial. Independently validate exported PDFs with scripts/verify-phase6-pdfs.py. Physical-device checks remain separate."
