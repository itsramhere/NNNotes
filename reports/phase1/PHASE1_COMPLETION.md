# Phase 1 debugging and runtime verification

Completed 2026-10-11 (Asia/Calcutta). Scope: the implementing-agent instructions,
shared decisions, and Phase 1 features/debugging plan. The user approved using
the existing JDK 25 instead of installing JDK 17.

## Result

The application and instrumentation APK build on JDK 25 and install on the
Android 16 / API 36 x86_64 emulator `emulator-5554`. All 14 runtime checks passed:
9 Room/storage tests, 3 Compose UI tests, and 2 separately invoked process
persistence checks. Final lint passed with 0 errors and 16 warnings.

Latest APK: `app/build/outputs/apk/debug/app-debug.apk` (25,519,657 bytes).
SHA-256: `614a8ebf204d6fef9d7164c2e9ee6a1ab02777be617264479870f3ba45231e22`.
Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Fixes

- Corrected the AGP 9.1 configuration: built-in Kotlin, explicit Compose compiler
  plugin, AndroidX support, pinned Build Tools, and the pinned KSP compatibility
  bridge. Exported the Room version-1 schema for future migrations.
- Selected JDK 25 for compilation and explicitly retained Java/Kotlin 17 bytecode.
  `javap` verified class major version 61. No JDK 17 installation was needed.
- Fixed the Windows Gradle wrapper to honor `JAVA_HOME` and propagate build failures.
- Increased Gradle heap/metaspace and bounded workers after a reproducible
  metaspace-exhaustion failure with the default settings.
- Replaced an unavailable Material XML launch theme with Android platform
  light/dark launch themes. Compose still provides Material 3 styling.
- Corrected the library's unsupported six-input typed `combine` call.
- Kept database-read errors visible instead of presenting an empty library.
  Read observers now support retry and reconnect after a recoverable open failure.
- Made title searches case-insensitive for ASCII titles and literal for SQL
  wildcard/escape characters `%`, `_`, and `\`.
- Made missing/stale notebook writes surface a save failure. Restricted permanent
  deletion to trashed notebooks; the UI retains its confirmation dialog.
- Prevented trashed notebook cards from opening the blank-page screen.
- Made the move-folder dialog scrollable so folder choices remain reachable.
- Added explicit exclusions for automatic cloud/device transfer extraction and
  retained `allowBackup=false` / `fullBackupContent=false`. This enforces the
  no-backup decision; it does not add a backup feature. Android documents why
  `allowBackup=false` alone is insufficient for some device transfers in its
  [Auto Backup guidance](https://developer.android.com/identity/data/autobackup#enable-and-disable-backup).

## Phase 1 debugging-plan coverage

| Required check | Verified result |
| --- | --- |
| Create → rename → move → close/reopen | Passed against Room and through the UI. Notebook/page IDs, title, and folder retained. |
| Actual process restart | Passed with app PID 2965 before force-stop and 3009 after relaunch; independently invoked verification reopened the actual application database. |
| Trash → restore | Passed, including duplicate titles and retained notebook/page records. |
| Permanent deletion | Passed: confirmation/cancel in UI; only selected ID removed, page/object cascades verified, duplicate sibling retained; active notebook deletion rejected. |
| Title search and recent sorting | Passed after renaming, moving, trashing, and restoring; `%`, `_`, and `\` match literally. |
| Database initialization | Passed: fresh database initializes empty, new notebook receives exactly one blank page. |
| Transaction failure | Passed: invalid-page insertion rolled back the notebook; invalid-folder move left previous data unchanged. Database remained usable. |
| Save failure and retry | Passed: injected SQLite abort retained existing title and produced a save-error state; retry succeeded. A stale rename also showed the error in the real UI. |
| Open failure and retry | Passed: an isolated test-only filesystem obstruction produced a persistent storage-error state; removing it and retrying restored reads/writes. |
| Manifest/dependency inspection | Passed: merged manifest and resolved runtime dependencies contain no login, billing, or cloud components; no internet permission. |
| Light/dark launch-theme correction | Both appearances captured and visually checked. |

Process verification retained these exact identities across independently
launched processes:

- Notebook: `0f397bb4-2685-409b-a880-93413129ad48`
- Blank page: `a823ae95-35df-4bfa-a574-42276f21f952`
- Folder: `07b45491-6d99-4917-80e3-82bd0f575b81`

Tests used uniquely named temporary databases or uniquely identified fixtures in
the application's database. They removed only their own fixtures. No app-data
clear, destructive migration, or reference-APKM modification was used.

## Commands and evidence

Android tools are installed locally under `.tools/android-sdk`, which is ignored
by Git. Versions: platform-tools/adb 37.0.1, command-line tools 22.0, emulator
37.2.12, API 36 platform revision 2, Build Tools 36.0.0, and AOSP x86_64 image
revision 2. Download archives were verified against Google's published checksums.
WHPX acceleration was confirmed usable. The AVD lives in `.tools/avd`.

Build and verification commands (from the project directory):

```powershell
$env:JAVA_HOME = 'C:\Users\ramst\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.0.36-hotspot'
./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --console=plain
./scripts/test-phase1.ps1 -Serial emulator-5554 -SkipBuild
./gradlew.bat :app:dependencies --configuration debugRuntimeClasspath --console=plain
./gradlew.bat lintDebug --console=plain
```

The runtime runner performs `adb install -r` for both APKs, invokes
`Phase1StorageTest,Phase1UiTest`, invokes `Phase1ProcessTest#prepare`, launches and
force-stops `com.ramst.notein`, confirms a distinct process on relaunch, and invokes
`Phase1ProcessTest#verify`. Process tests are guarded against accidental execution
as an unordered default suite; use the runner for this sequence.

Emulator setup used `sdkmanager`, verified direct archive downloads,
`avdmanager create avd --name Notein_Phase1_API36 --package
'system-images;android-36;default;x86_64' --device pixel_2`, and a headless
`emulator -avd Notein_Phase1_API36 -gpu swiftshader -no-snapshot -no-audio
-no-boot-anim -memory 1536 -cores 2 -port 5554 -no-metrics` launch.
`ANDROID_HOME` and `ANDROID_AVD_HOME` pointed to this workspace's tool directories.
The command-line tool's version precheck needed `SKIP_JDK_VERSION_CHECK=true` with
JDK 25; the tools themselves executed successfully.

Evidence in `runtime/`:

- `build.txt`, `test-build.txt`, `lint-final.txt`: successful builds and final lint.
- `suite.txt`: `OK (12 tests)`.
- `process-prepare.txt`, `process-verify.txt`: `OK (1 test)` each.
- `process-restart.txt`, `process-identities.txt`: distinct process IDs and identical saved record IDs.
- `merged-manifest.xml`, `dependencies.txt`, `bytecode-version.txt`, `apk-sha256.txt`: build/scope inspection.
- `library-light.png`, `library-dark.png`: visually inspected library screens.
- `crash-check.txt`: no AndroidRuntime crash output.
- Earlier failed build/test logs are retained separately. The first UI runs
  failed because test selectors used merged FAB semantics or also matched the
  search input. Those harness selectors were corrected; final UI tests passed.

## Remaining limitations

- Verification was on the API 36 emulator. No physical Android device was
  connected; physical-device checks and minimum-API runtime coverage are pending.
  No stylus/palm acceptance is claimed in Phase 1.
- Lint's 16 warnings are 15 newer-version suggestions for deliberately pinned
  tooling/dependencies and one missing explicit launcher icon. These do not block
  the Phase 1 library workflows.
- Kotlin/KSP log a JDK-target fallback warning during toolchain setup; the actual
  compiled output was verified as Java 17 bytecode. The KSP/AGP compatibility
  bridge remains experimental.
- Physical low-storage behavior was not tested. Transaction/save failure tests
  used real SQLite constraint/abort failures and a test-only open obstruction.
- Asset cleanup has no filesystem assets to exercise in Phase 1; database
  page/object cascade cleanup was verified.

Phase 1's emulator completion criteria are met. No reproducible persistence
failure remains in the tested workflows. Later-phase features are not part of
this verification.
