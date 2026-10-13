# Phase 2 debugging and runtime verification

Run: 2026-10-11–12, Asia/Calcutta. Scope: implementing-agent instructions,
shared decisions, and Phase 2 features/testing/debugging criteria only. No later
phase implementation or excluded feature was added. JDK 25 remains the approved
build override; no JDK 17 was installed.

## Verification status

The Phase 2 handwriting path builds and installs on Android 16 / API 36,
x86_64 emulator `emulator-5554`. Full physical acceptance is **pending**:
there is no connected active-pen tablet. The plan's Phase 2 completion box
therefore remains unchecked.

The final suite contains 37 checks:

- 9 Phase 1 storage and 3 Phase 1 library UI regressions.
- 13 Phase 2 input/canvas checks: eraser drags, geometry hit testing, canceled
  eraser rollback, two-finger navigation, stylus/finger priority, system palm
  cancellation signals, hardware-eraser tool events, rapid strokes/dots,
  document coordinates, pressure changes/edges, and late-handoff removal.
- 6 Phase 2 storage/brush checks: history agreement, failed-save retention and
  retry, rapid history/settings, transactional rollback and unknown-object
  preservation, constant-width highlighting with/without pressure, and pending
  ink versus Saved status.
- 3 real-editor UI checks: toolbar/styling and write/erase/undo/redo/reopen,
  retry after an injected save failure across recreation, and recreation
  immediately after pen-up before native rendering handoff.
- 1 real version-1-schema migration check retaining page/notebook IDs, folders,
  trash metadata, and existing object payloads.
- 2 separately invoked process-persistence checks, with an adb force-stop and
  verified new PID between them.

Final result: **37/37 checks passed** on the delivered build: 35 in
`runtime/suite.txt`, plus one each in `runtime/process-prepare.txt` and
`runtime/process-verify.txt`. Force-stop/relaunch changed PID from 4132 to 4175
(`runtime/process-restart.txt`). The final JDK-25 build succeeded
(`runtime/final-handoff-build.txt`); lint reports 0 errors and 18 warnings.
The warnings include existing pinned-version/launcher-icon suggestions and
minor canvas API/accessibility suggestions; dependencies were not upgraded.
Java/Kotlin bytecode remains target 17 (class major version 61).

Delivered APK: [`Notein-phase2-debug.apk`](Notein-phase2-debug.apk),
25,203,603 bytes. Its SHA-256 matches the final build output:

```text
8D42DE195231D7D294DB1A0315D87FC5ABA6A2827092740868F14658F4957DB3
```

## Defects and repairs

- The starting source failed compilation with eight errors: an invalid Compose
  weight import, public/internal type exposure, an unavailable palm-classification
  constant, and the wrong Ink storage decoder overload. Corrected visibility,
  imports, InputStream decoding, and supported Android cancellation handling.
  Added the explicitly used, already-pinned Ink geometry dependency.
- Erasing previously ran on DOWN only and used the entire stroke bounding box
  as a hit target. It now sweeps historical/current movement samples and tests
  the actual Ink mesh. Canceled eraser gestures restore previews and do not
  modify storage/history.
- Two-finger navigation in finger-writing mode previously committed finger ink.
  It now cancels that draft and pans without ink. Stylus input takes priority
  over preceding finger input; remaining fingers cannot start navigation after
  the stylus lifts within the same gesture.
- Rejected input was skipped without canceling/resetting the active pointer.
  ACTION_CANCEL and flagged pointer-up now cancel the relevant gesture without
  canceling another live stylus pointer. Canceled pans roll back their offsets.
- Failed writes retained ink only in the View, not in ViewModel state. The
  session now owns desired strokes/history/settings, including unsaved edits.
  Room atomically reconciles ink plus page state/notebook timestamps, preserving
  other object types and rejecting cross-page/object-type collisions. Serialized
  revision-aware saves cannot falsely acknowledge a newer unsaved edit as Saved.
  The UI keeps a persistent retry action and guards navigation while unsaved.
- A completed stroke could be lost if the activity recreated before Ink's
  delayed wet/dry handoff. Completed pressure/input samples are now recorded at
  pen-up independently of that renderer callback. The old view cannot overwrite
  the new view's pending-input status after disposal.
- A late rendering handoff could restore removed ink. Handoff now respects
  desired stroke IDs; undo/redo wait until pending authoring has finished.
- Stroke ordering is stable even when rapid strokes share a wall-clock tick.
  Completed geometry is decoded only when its record changes. Black ink remains
  legible on white paper in a dark surrounding UI.
- Screenshot QA found that the long failed-save status overlapped the notebook
  title on the phone-sized emulator. The top bar now says Unsaved; the full
  warning and Retry save action remain visible below it.

## Evidence and execution notes

`runtime/baseline-build.txt` records the original compiler failures.
`runtime/before-repairs.txt` records 5 failures in the initial 9-check runtime
suite. The original synthetic canceled-UP fixture was subsequently corrected to
Android's real single-pointer ACTION_CANCEL form: ViewGroup dispatch stripped
the flag from the original ACTION_UP fixture. Final checks also cover flagged
multi-pointer ACTION_POINTER_UP without canceling the other stylus.

Intermediate test-harness issues (non-void JUnit cleanup and selecting an
unmerged Text child rather than its disabled button) were corrected; they were
not counted as product defects or passing checks.

Two intermediate runs crashed inside native HWUI/HardwareBufferRenderer on
SwiftShader and host-GPU OpenGL. The preserved stacks are
`runtime/emulator-native-crash.txt` and `runtime/host-native-crash.txt`.
After the completed-edit lifecycle repair, full 36-check runs passed on both
host-GPU Vulkan and OpenGL; see `runtime/vulkan-suite.txt` and
`runtime/opengl-suite.txt`. The isolated canvas harness also waits for the
asynchronous renderer handoff before disposing its activity. These passes do
not establish the native crash's exact root cause or replace longer physical
device stress testing. No restricted renderer fallback or library-version
upgrade was added to bypass it.

The emulator was rebooted with `-gpu host`, retaining its existing AVD and app
data. HWUI was compared using `debug.hwui.renderer=skiavk` and `skiagl`; the
final configuration is host GPU/OpenGL. WHPX acceleration is enabled; the
emulator selected the NVIDIA GeForce RTX 4050 Laptop GPU.

The prior Gradle daemon held a generated JAR open on Windows. Restarting the
daemon released the lock; subsequent builds use `--no-daemon`. Toolchain
initialization emits a Kotlin JDK-25-target fallback warning, but explicit
Java/Kotlin targets remain 17; javap verifies class major version 61.

Build and repeat commands:

```powershell
$env:JAVA_HOME='C:\Users\ramst\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.0.36-hotspot'
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --console=plain
.\scripts\test-phase2.ps1 -Serial emulator-5554 -SkipBuild
```

The script installs with `adb install -r`, validates JUnit success rather than
trusting adb's exit code alone, and never clears application data. Storage tests
delete only their uniquely named databases; UI/process tests remove only their
synthetic notebook fixtures. Existing notebooks, reference APKM/inspection
artifacts, and historical Phase 1 evidence were preserved.

Debug traces in `runtime/ink-trace.txt` include input type/pressure/cancellation,
stroke lifecycle, viewport changes, and save revisions/outcomes. They do not
log notebook titles or serialized handwriting. Test-generated screenshots are
`runtime/editor-saved.png`, `runtime/editor-recreated.png`, and
`runtime/editor-unsaved.png`.

The saved/recreated images were initially byte-identical and visually inspected:
the same red highlighter and pressure-pen geometry remained visible. Retry
screenshots show retained ink, an explicit unsaved warning, and disabled back
navigation while disk writes fail. Final screenshots were retrieved and
visually checked after the 37-check run: the short Unsaved header no longer
overlaps the title, Retry save remains visible, and recreated ink is intact.

## Pending physical checks

Connect an active-pen Android tablet, enable USB debugging, and authorize adb.
Record its Android version, model, pen and settings; then test palm placement
before/during/after writing, finger navigation without unintended ink, pressure
transitions, fast strokes/dots/diagonals/edges, and write/erase/undo/redo followed
by force-stop/reopen. Compare rendered and saved edits and run an extended
write/navigation/recreation stress session, watching for the earlier native
renderer failure. Emulator-injected stylus/eraser/palm-cancel events are only
software-path coverage, not real hardware acceptance.

Minimum-API-26 runtime coverage and genuinely constrained-device storage/latency
measurements were not performed in this run.

Relevant platform contracts: [Android palm cancellation](https://developer.android.com/develop/ui/views/touch-and-input/input-compatibility-on-large-screens)
and [Ink authoring](https://developer.android.com/reference/kotlin/androidx/ink/authoring/InProgressStrokesView).
