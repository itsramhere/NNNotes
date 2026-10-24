# Handwriting loss and lag fix

Verified on 21 October 2026 with the API 36 emulator. The APK is
`Notein-handwriting-fix-debug.apk` in this directory.

## Changes

- Queued Room emissions can no longer replace newer saved handwriting. Reloads read the latest committed snapshot under the save mutex and reject results if the session changed during validation. Notifications matching the current page no longer decode all its strokes again.
- The Compose canvas reads the current session's strokes when applying an update. A frame prepared before pointer-up or undo cannot discard a new stroke or restore an undone one.
- Pointer-up serializes the captured samples without building another native stroke mesh. Completed rendering reuses AndroidX Ink's handed-off stroke. Input events target the explicit stroke ID, and persistence metadata is ready before requesting the handoff.
- Existing content and notebook previews are cached by immutable entity. Unchanged canvas settings and neighbor pages skip rebuilding, and mixed-content draw order is cached instead of sorting on every frame.

No storage schema or saved-stroke format changes were required.

## Checks

| Check | Result |
| --- | --- |
| Debug APK, instrumentation APK, Android lint | Passed; 0 lint errors, 29 existing warnings, 6 hints |
| Handwriting and canvas suites | 34 passed |
| Editor, selection, ink appearance and PDF suites | 24 passed |
| Real editor toolbar and page-navigation UI suites | 8 passed |
| Default ink pen rapid-writing and stale-storage regression rerun | 2 passed |

The rapid-writing regression sends 60 stems, 60 zero-duration dots and 60
1 ms crossbars without waiting between strokes. It checks all 180 IDs after
render handoff, database save and editor recreation, and verifies visible pixels
for every dot. The stale-storage test deliberately delivers an older page
snapshot after newer strokes have saved, then verifies another edit and reopening.
The canvas suite also verifies that stale screen updates cannot discard new ink
or reverse undo. Older pressure tests now identify a device with a declared stylus
pressure axis rather than using the emulator keyboard's device ID.

Build command:

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --offline --console=plain --max-workers=1
```

Instrumentation uses `adb install -r` for both APKs, then `adb shell am instrument
-w -r -e class ... com.ramst.notein.test/androidx.test.runner.AndroidJUnitRunner`.
The passing classes are `HandwritingRegressionTest`, `Phase2CanvasTest`,
`Phase3CanvasTest`, `Phase4CanvasTest`, `Phase10CanvasTest`, `PaperCanvasTest`,
`PageSwipeCanvasTest`, `Phase10EditorTest`, `Phase9EditorTest`,
`Phase4SelectionTest`, `Phase10InkTest`, `Phase10UiTest` and `EditorToolbarUiTest`.
Logs and a rapid-stroke screenshot are retained under `runtime/`.

## Limits

Initial SwiftShader runs hit a native Android HWUI/HardwareBufferRenderer mutex
crash during repeated activity teardown. The repository records earlier native
renderer failures in `reports/phase2/PHASE2_DEBUGGING.md` and
`reports/phase6/PHASE6_DEBUGGING.md`. The final passing runs used a fresh emulator
with host graphics and `debug.hwui.renderer=skiagl`; the app's renderer and pinned
Ink version were retained. This does not establish that the platform crash is
fixed. Synthetic checks do not measure physical stylus latency or palm rejection.
No connected physical device was available.
