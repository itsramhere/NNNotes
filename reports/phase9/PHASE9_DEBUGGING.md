# Phase 9 debugging — 2026-10-12

Status: Phase 9 implementation and emulator debugging completed. The final combined suite passed 19 tests plus two separate process-restart tests (21 total). This supersedes the earlier implementation-only restriction under the user's explicit debugging request. Physical-device acceptance remains separate.

## Scope

Read the implementation plan's instructions, shared decisions, and Phase 9 features/debugging requirements. Work stays in Phase 9; Phase 8 acceptance and the additional Phase 2 pen implementation are not performed here. Existing notebooks and the supplied APKM/reference artifacts are preserved. Tests use separate fixture databases; app installation uses `adb install -r`, with no app-data clearing.

## Defects addressed

- Fixed the malformed retry branch and missing Compose imports that prevented compilation.
- Automatic page allocation now follows a changed, nonempty content commit. Viewport/tool saves, unchanged snapshots, empty text, erasing, navigation, and reopening do not allocate pages. Content commits and retries are serialized in the repository; the guarded Room allocation transaction rechecks the current last page.
- Content saving remains a separate committed transaction from allocation. Allocation errors retain written content, remain visible after subsequent viewport saves, and allow explicit retry without multiplying pages. The retry banner distinguishes saved writing with a pending next page from unsaved writing.
- Explicit duplicates are manual pages. Written or paper-edited automatic pages lose their untouched marker, so default export retains them even after undo/erasing makes them blank. An only remaining page is always exportable.
- One paper renderer supplies the creation/settings previews, editor, page thumbnails, canvas overview, and PDF export. It renders spacing in document points, adapts contrast, distinguishes three dot sizes, honors paper dimensions/orientation, and preserves canvas-region pattern origin.
- The version 5→6 migration preserves legacy 32-point lined / 28-point grid spacing, 1-point ruling weight, and the original lighter ruling color. These values were confirmed against the previously delivered Phase 6 APK, recorded in `legacy-renderer-evidence.txt`. It retains dimensions, ink payloads, viewport state, selection, folders, trash, and PDF source records.
- Added valid per-layout spacing defaults, labeled presets and numeric input. Pattern switching cannot retain an out-of-range spacing value. Custom colors require exactly six hexadecimal digits. Creation has a folder choice and an immediate submission guard. Orientation has its own visible row, and paper-settings dialogs scroll.
- Added an actual whole-notebook export path alongside explicit current-page export. Default export excludes only the untouched automatic trailing page; PDF notebooks retain imported paper and reject page-paper replacement.
- Dense or distant paper views use a bounded cache of repeating raster tiles with the exact document-space period. This prevents millions of primitive calls and float-increment loops without imposing a saved-content quota. Ordinary paper rendering retains native lines/circles.
- Notebook settings are refreshed after page emissions instead of being overwritten by a stale captured notebook. Infinite-canvas paper settings preserve zero dimensions and hide fixed-page/automatic-page controls.

## Commands and evidence

- `gradlew.bat assembleDebug assembleDebugAndroidTest --no-daemon --console=plain --max-workers=1` builds the app and instrumentation suite. Build iterations and compilation diagnostics are retained in `build-*.txt`.
- `gradlew.bat lintDebug --no-daemon --console=plain --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2560m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8"` runs lint. An earlier parallel lint analysis stalled and was cancelled; retrying with one worker/larger heap succeeded. Logs are retained in `lint-*.txt`.
- `scripts/test-phase9.ps1 -SkipBuild` installs without data clearing, runs the Phase 9 suite, performs separate prepare/verify instrumentation around a force-stop and restart, retrieves evidence, and restores emulator display overrides in `finally`.
- The emulator is the existing API 36 x86_64 AVD, run in the background. Runtime UI checks use a 1600×1000 / 160-dpi tablet viewport. JDK 25 emits Java/Kotlin 17 bytecode, per the approved override.

## Coverage and practical limits

The dedicated suite covers rapid concurrent commits, complete template equality, no copied content/viewport, text/image content, empty text, undo/redo, delete-last then edit, toggle off/on, notebook/page template independence, duplicates, manual pages, reorder/delete, database reopen, content-save rollback, allocation rollback and repeated retry, infinite/PDF exclusions, real imported PDF source preservation, and default/explicit PDF export counts.

Paper rendering is exercised across 1,512 layout/size/orientation/spacing/weight/color combinations. A 25-page PDF exercises all four layouts, three sizes, both orientations and a custom color, with dimensional/color/ruling comparisons against the renderer. Editor rendering is checked at 0.25×, 1× and 2×. Dense rendering also exercises a 0.000001× viewport and an origin at 10 billion document points. A real notebook is opened through the actual activity/navigation, rotated from landscape to portrait, and checked for preserved ink payload, template, selected page and exactly two pages. Creation checks cover cancellation without notebook/page/orphan-image persistence, spacing controls, hidden infinite options, exact template persistence, double submission and export choices.

Storage-full and interrupted-transaction scenarios use SQLite triggers to inject failures; the emulator/device disk is not filled. These are fault-injection checks, not physical low-storage measurements. No physical tablet/stylus/palm acceptance is claimed. Extended real-device performance profiling and final accessibility/release checks belong to the remaining Phase 8 acceptance. The workspace still has the earlier single-pen toolbar; the additional Phase 2 pencil/ball/ink tool requirements remain a separate prerequisite to final Phase 8.

## Final results and delivery

- Final app and instrumentation builds passed. `build-and-lint-delivery.txt` records the complete app build/lint; subsequent test-only builds are recorded separately.
- Lint on the final app source: **0 errors, 42 warnings, 6 hints**. Existing deprecation/accessibility/style warnings are not release acceptance.
- `test-run-final-success.txt` and `runtime/phase9-suite.txt`: **OK (19 tests)**. The suite contains 5 storage, 1 migration, 5 paper/export/rendering, 4 editor, 3 creation UI and 1 real-activity rotation checks.
- `runtime/process-prepare.txt` and `runtime/process-verify.txt`: **OK (1 test)** each. The app process changed from PID **4696** to **4737** across force-stop/restart; no page was added on reopen.
- Visually inspected the final creation preview, created editor, real rotated editor, and a Poppler-rendered 25-page contact sheet. Portrait/landscape dimensions, paper colors, layouts and custom dotted paper match the stored choices. Normal subpixel aliasing is visible in dense thumbnail patterns.
- Evidence: `runtime/templates.pdf`, `runtime/paper-contact-sheet.png`, `runtime/custom-paper-export.png`, `runtime/creation-preview.png`, `runtime/created-editor.png`, `runtime/rotation-before.png`, `runtime/rotation-after.png` and `runtime/restart.json`.
- Final APK: **`reports/phase9/Notein-phase9-debug.apk`** (also `app/build/outputs/apk/debug/app-debug.apk`). APK Signature Scheme v2 verification passed; details are in `apk-verification.txt`.
- SHA-256: **`217D8EF82B251C8F5348A168449C8EE3DAC6253FB9A5C70F06178DC3AE2675BA`**.

The creation harness initially failed to tap offscreen nested-row controls. It now scrolls using unclipped coordinates and verifies visibility before tapping. A Compose/Espresso frame wait stalled during rotation while the app was visibly responsive; `rotation-stack.txt` records that diagnostic. The final rotation test uses native activity/accessibility traversal and passes. Emulator root was temporarily enabled solely to inspect the stalled test process. The stopped test's uniquely identified fixture was removed with foreign-key cascades; `rotation-fixture-cleanup.txt` records that cleanup. No existing/user notebook was removed to resolve a failure.

No known Phase 9 functional defect remains in the exercised emulator scenarios. Actual physical-device rotation/storage exhaustion, stylus/palm handling, extended profiling and the remaining Phase 2/8 acceptance are not claimed. Only an emulator was available; disk-full/allocation interruption was simulated with targeted transaction failures rather than filling device storage.
