# Phase 4 debugging — 12 October 2026

Phase 4 emulator debugging is complete. Build, lint, all 69 combined tests, and both process-restart checks passed. Physical-device acceptance remains pending.

## Scope and repairs

Read the plan's implementing-agent instructions, shared decisions, Phase 4 features, and only Phase 4's debugging plan. No later-phase features were added. Existing notebooks and the supplied reference APKM/inspection files were preserved. All runtime checks used the emulator; the connected tablet was not modified.

- Fixed the `SelectionOverlay.enabled` JVM setter collision with Android View so Phase 4 compiles.
- Fixed the font-style lint error by using the explicit normal/bold/italic/bold-italic constants.
- Closed lassos now use their travelled path to distinguish selection from tapping. Returning to the starting point no longer clears selection.
- Resize picking uses the nearest corner and limits overlapping handle hit areas on small selections. The center remains available for moving, and the opposite resize corner remains fixed.
- Tapping uses stroke-path intersection instead of selecting empty space inside a stroke's bounding rectangle.
- Selection cancellation belongs to the affected pointer. A stylus lift commits on `ACTION_POINTER_UP`; unrelated cancelled palms no longer discard the gesture. Selection and ink/navigation pending counts are combined so a completed ink handoff cannot clear an active selection gesture.
- Image import retains its page lock across database emissions and checks the destination before inserting content. An in-flight import cannot append its object to another page.
- Source-image copying checks cancellation between reads, closes failed streams, and removes only the current import's staged files. Memory failures during import/display become visible errors while saved content remains intact.
- Reject transforms beyond representable document-coordinate precision and malformed image references before rendering or saving changed content. Resizing remains proportional and retains immutable source samples.
- Added accessible identification for the content toolbar and a repeatable Phase 4 test runner. The image reader/importer boundaries allow controlled partial-copy and delayed-import failure tests.

## Commands and evidence

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --console=plain
./gradlew.bat assembleDebugAndroidTest lintDebug --no-daemon --console=plain
./scripts/test-phase4.ps1 -Serial emulator-5554 -SkipBuild
```

The runner builds by default; omit `-SkipBuild` for subsequent full runs. It reinstalls with `adb install -r`, creates unique test databases/notebooks, verifies distinct app process IDs around a force-stop/relaunch, and never clears application data. Only test-owned notebooks/assets are removed.

Earlier failures are retained in `runtime/`: `baseline-build.txt` records the compile blocker, `baseline-tests-build.txt` records the font-style lint blocker, and `before-repairs.txt` records six selection failures plus a fixture ordering assertion that was corrected to compare logical insertion order. `focused-suite.txt` passed 20/21 before the native-input UI harness was synchronized with the Lasso mode change; `ui-suite.txt` then passed both UI checks. Actual end-to-end selection asserts that the lasso creates no extra ink. The first combined run passed 68/69: an older Phase 3 fixture assumed creation-list order for equal-timestamp strokes. `suite-before-ordering-assertion-repair.txt` retains that failure; the corrected assertion checks the source renderer's actual timestamp/ID order and passes in the final combined run.

Environment: existing JDK 25, Java/Kotlin 17 output, Android 16 / API 36 x86_64 emulator at 1080 × 1920. Build tooling/dependency pins and Room schema version 4 were retained.

## Results

- `runtime/final-build.txt`: **BUILD SUCCESSFUL**; app APK, test APK, and lint passed. Lint has zero errors and 32 warnings. `runtime/bytecode-target.txt` confirms Java 17 class major version 61.
- `runtime/suite.txt` and `runtime/verified-run.txt`: **69/69 passed**, zero ignored, 103.702 seconds. Includes 21 Phase 4 checks and all 48 earlier-phase regressions.
- `runtime/process-prepare.txt` and `runtime/process-verify.txt`: **1/1 passed each**. `runtime/process-restart.txt` verifies PID 3134 was replaced by PID 3176 after force-stop/relaunch.
- After restart, both notebook types retained exactly three mixed-content objects per edited page, the original object IDs and payloads, proportional transforms, Unicode/multiline text, viewport state, selected page, and shared image reference. Original image byte hashes and preview dimensions also matched. The verifier removed only its own notebooks/assets.
- No known Phase 4 defect remains in the exercised emulator scenarios; physical-device limits below remain pending.

| Phase 4 debugging requirement | Coverage |
| --- | --- |
| Mixed stroke/text/image lasso at different zooms | Closed-lasso input at 0.25×, 1×, and 4×; actual editor selection/copy of all three types; native stylus/palm pointer routing. |
| Repeated resizing without coordinate drift | 1,000 grow/shrink pairs retain original serialized stroke samples and keep decoded positions within 0.01 document units; nearest corner and low-zoom center movement checks. |
| Overlap and object boundaries | Topmost overlapping text selection, empty diagonal-stroke bounds, lasso crossing/enclosing stroke paths, overlapping rendered image copies. |
| Copy → move → delete → undo | Exact object payloads and positions restored for all three types; source objects remain independent; copied images share an immutable asset; text formatting/transform undo also checked. |
| Large/unsupported/cancelled/failed image operations | 5,000 × 3,000 image retains original bytes with a preview bounded to 2,048 pixels per side; unsupported/missing sources, blocked asset directory, interrupted copying with stream closure, delayed import/page-lock race, cancelled picker, and cancelled selection gestures. |
| Reopen and compare editable content/assets | Activity recreation, exact payload/count checks, and separate process-restart invocations for both notebook types; image byte hash and preview dimensions checked. |

Visually inspected `runtime/mixed-content.png`: original and copied handwriting, wrapped text, and blue images render after recreation. The UI test also asserts an image pixel's color after recreation.

## Remaining limits

- Physical-tablet stylus latency, palm rejection, and touch/pen acceptance remain pending. Synthetic stylus/palm events do not certify physical hardware behavior.
- Android 8 runtime checks and low-memory-device acceptance remain pending. Tested imports are PNG fixtures; every supported image format/EXIF orientation has not been exhaustively tested.
- Lasso stroke intersection follows the stroke's sampled path with brush-radius tolerance; it is not an exact outline comparison for every pressure-brush shape.
- Unreferenced image assets remain retained to support undo/shared references. Reclamation remains deferred as documented in the original implementation note. No backup, cloud, OCR, AI, layer, or later-phase feature was introduced.
- Lint warnings remain for pinned dependency/tool updates, platform EXIF usage, draw-time allocations, accessibility/style suggestions, and the existing application icon. The lint error was fixed; warnings were not suppressed.

## APK

Latest APK: `reports/phase4/Notein-phase4-debug.apk` (25,394,379 bytes), also built as `app/build/outputs/apk/debug/app-debug.apk`. The test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

SHA-256: `6AA0A78FDA5A7F4EEBCA952BBDE30867035B2CB17EC326A540DE2AD8150CBF50`.

This Phase 4 APK was installed on the emulator. The tablet keeps its previously installed version.
