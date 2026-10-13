# Phase 3 debugging — 12 October 2026

Phase 3 emulator debugging is complete. Build, lint, all 48 combined tests, and both process-restart checks passed. Physical-device acceptance remains pending.

## Scope and repairs

Read the implementation instructions, shared decisions, and the Phase 3 feature/debugging requirements. No later-phase work was introduced.

- Page duplication now reads and copies content inside one transaction, assigns independent object IDs, and preserves stroke ordering. Page creation allocates its ordering index transactionally.
- Deletion renumbers surviving pages and protects the final page. The editor serializes page changes and blocks them during unsaved edits, pending ink, navigation gestures, or page loading.
- Saved page selection survives reopening. Room schema 4 adds selection with a non-destructive migration; schema 1, 2, and 3 migration paths retain existing records.
- Database emissions cannot overwrite newer unsaved ink. Toolbar/history actions during page loading cannot save an empty snapshot over a duplicated page.
- Pinch zoom uses the finger centroid, retains the document point under the gesture focus, rebases after a finger lifts, and restores the previous scale on cancellation.
- Fit-content can zoom out to include widely separated strokes. Completed-ink drawing skips offscreen strokes; ruled-paper density adapts at very small scales.
- Page thumbnails contain handwriting. Preview decoding runs on a background dispatcher, and unchanged content does not regenerate previews during viewport updates. Thumbnail drawing clips to its paper bounds.
- The infinite overview uses the measured editor surface, reuses background-generated previews, shows the actual viewport, and supports tapping to center the document on the mapped point.
- Updated the existing Phase 2 test harness for the scale-aware callback and the complete migration chain.

## Verification commands and evidence

Builds use the existing JDK 25 with Java/Kotlin 17 output, as authorized in the shared decisions:

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --console=plain
./scripts/test-phase3.ps1 -Serial emulator-5554 -SkipBuild
```

The new runner builds by default; omit `-SkipBuild` for normal use. It installs with `adb install -r`, uses unique test databases/notebooks, verifies distinct process IDs around force-stop/relaunch, and never clears application data.

Evidence is in `runtime/`. Initial attempts and failures are retained: `baseline-build.txt` contains an incomplete build while repairs were underway; `tests-build.txt` records the prohibited inline-class vararg in the first pinch harness; `focused-suite.txt` records a synthetic pinch below the device's minimum scaling span; `ui-suite.txt` records an offscreen LazyRow selector; the first combined suite reached 46/47 passing checks before the disabled-button assertion was corrected. These were followed by the final runs listed below.

Device: `emulator-5554`, Android 16 / API 36, x86_64, 1080 × 1920 portrait with real landscape configuration changes. The connected physical device was not used.

## Final results

- `runtime/verified-build.txt`: `BUILD SUCCESSFUL`; `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed. Lint: zero errors, 19 pinned-tool/dependency update warnings. `runtime/bytecode-target.txt`: class major version 61 (Java 17).
- `runtime/suite.txt` and `runtime/final-run.txt`: **48/48 passed**, zero ignored, 220.572 seconds. Includes library/handwriting regressions and 13 Phase 3 tests.
- `runtime/process-prepare.txt` and `runtime/process-verify.txt`: **1/1 passed each**. `runtime/process-restart.txt`: process changed from PID 5260 to PID 5306. Both notebook types retained ink, viewport translation/scale, notebook type, selected page, and standard-page background/count. Test-owned fixtures were removed after verification.

| Phase 3 debugging requirement | Verified result |
| --- | --- |
| Stroke stability through zoom, pan, rotation, reopening | Stored input coordinates/payloads remain unchanged; synthetic pinch retains focus and cancellation restores translation/scale; real configuration changes and activity recreation preserve state. |
| Duplicate, reorder, delete with handwriting | Independent IDs and editable content; ordering persists; surviving page indices remain contiguous; deleting the last page is rejected by storage and disabled in the UI. |
| Duplicate edit isolation | Adding/removing ink on the duplicate leaves source ink unchanged; immediate toolbar/history actions during duplication cannot erase copied ink. |
| Both sides of the origin and far-away drawing | Negative input coordinates and input beyond 30,000 document units round-trip; fit-content includes strokes spanning approximately -50,000 to +60,100 units. |
| Overview vs actual document position | Mapping round-trips document points; viewport dimensions use measured canvas size; tapping navigates correctly and state survives landscape rotation. |
| Thumbnail generation does not block writing | Final 500-stroke workload: combined saving/preview generation 1,585 ms; main-thread writing dispatch 3 ms; current-page ink saved while previews populated. Earlier runs were 1,906/3 ms and 1,862/9 ms. See `runtime/profile.txt`. |

Visually inspected `runtime/pages-landscape.png` and `runtime/infinite-landscape.png`; handwriting, paper grid, page previews, and the viewport overview render after rotation. No known Phase 3 defect remains in the exercised emulator scenarios.

## Remaining limits

- Real active-pen behavior, palm rejection, latency, and physical-tablet rotation/navigation acceptance remain pending. Synthetic emulator input cannot certify those behaviors.
- Thumbnail/overview strokes are simplified line previews; the editable canvas retains the full AndroidX Ink records, including pressure. Preview timings describe the tested workload, not a guarantee for every notebook or device.
- Runtime testing on Android 8 and low-memory physical hardware remains pending. This phase did not add later content/PDF/export features.

## APK

Latest APK: `reports/phase3/Notein-phase3-debug.apk` (25,258,234 bytes), also built as `app/build/outputs/apk/debug/app-debug.apk` and installed on the emulator.

SHA-256: `72C09FDF7D4998B55BDCC53F2AF2DF30BB38364082D6A8DEEFC0F57E1C623C1F`.

The test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. The original APKM and inspection artifacts were unchanged.
