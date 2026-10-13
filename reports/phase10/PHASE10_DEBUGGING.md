# Phase 10 debugging — 2026-10-12

Scope: the implementation-plan instructions and shared decisions, Phase 10 features, and Phase 10 debugging plan. No later phase is accepted by this work. The earlier no-debugging restriction was superseded by the user's request to debug Phase 10.

Status: emulator debugging completed. All 57 checks passed (26 Phase 10 checks and 31 regression checks), and the final build/lint passed with zero lint errors. Physical active-pen/palm acceptance remains pending, so Phase 10's full acceptance checkbox remains open. Existing notebooks, the supplied APK/inspection files, and Phase 9 evidence/APK are retained.

## Repairs

- **Import state could be cleared by Room emissions.** The first 18-test run reproduced a database update enabling editing/navigation during PDF copying. An explicit copying flag now survives object emissions and remains busy until cancellation cleanup completes; insertion emissions also retain the confirmation guard.
- **Cancelled PDF preview allocations could escape cleanup.** Canvas rendering and selection thumbnails now retain allocation ownership across the IO-to-Main handoff and recycle unfinished results on cancellation. Canvas PDFs share a visible-preview pixel budget and permit at most two simultaneous raster operations, with the existing 24 MiB cache retained.
- **New custom brushes used restricted Ink APIs.** Lint initially reported nine `RestrictedApi` errors. New stroke metadata is version 3 and uses public stock marker/pressure-pen/highlighter factories. Pencil remains 0.5 units and translucent, ball pen 2 units, ink pen 6 units; preferences remain independent. The version-2 custom decoder is retained behind a narrowly documented lint suppression to preserve any existing version-2 strokes. Unversioned legacy ink/highlighter decoding is retained. See the [pinned AndroidX stock-brush source](https://android.googlesource.com/platform/frameworks/support/+/a7cc3baa03c979e6c2cfdf9912b4b13215e1a81f/ink/ink-brush/src/commonMain/kotlin/androidx/ink/brush/StockBrushes.kt).
- Updated the existing migration regression builders to include the version-7 migration. This changes the test migration paths, not the original notebook contents.
- Screenshot inspection found navigation hints still asking users to drag/pinch while locked. The toolbar now says writing remains enabled, and the overview row asks users to unlock before navigating; the UI test checks these hints.

## Verification commands and evidence

Build with the existing JDK 25 installation, emitting Java/Kotlin 17 bytecode:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest --offline --no-daemon --console=plain --max-workers=1
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --offline --no-daemon --console=plain --max-workers=1
.\scripts\test-phase10.ps1 -SkipBuild
```

The runner installs with `adb install -r`, uses separate test databases and file roots for PDF tests, saves/restores emulator display settings, executes a real activity rotation check, and force-stops/restarts the app between process-persistence checks. It never clears app data. The native rotation test creates and removes only its uniquely identified temporary notebook.

Initial source build passed. The initial regression run had one application failure (copying guard) and one overstrict test assertion (expected an unchanged save timestamp). Both were repaired. The first extended run passed 21 feature tests; two additional rendering tests had invalid source UUIDs in their fixtures, subsequently corrected. An early runner attempt installed the previous test APK before packaging completed; it was rerun after installing the rebuilt APK and is not counted as feature verification.

Full command output is in `runtime/build-initial.txt`, `runtime/build-final.txt`, the incremental build logs, `runtime/suite-initial.txt`, and `runtime/suite-render-fixture-failure.txt`. The final delivery build is `runtime/build-delivery.txt`; final runtime orchestration is `runtime/runner-final.txt`.

| Final check | Result | Evidence |
| --- | --- | --- |
| Build and Android lint | Passed; 0 errors, 46 warnings, 6 hints | `runtime/build-delivery.txt`, `app/build/reports/lint-results-debug.html` |
| Phase 10 ink/editor/PDF/UI/rendering suite | 23 passed | `runtime/phase10-suite.txt` |
| Real activity rotation | 1 passed; document center and zoom retained while locked | `runtime/rotation.txt`, `runtime/rotation-before.png`, `runtime/rotation-after.png` |
| Migration/template/content/canvas regressions | 31 passed | `runtime/regressions.txt` |
| Process persistence | 2 passed, with distinct prepare/verify and restarted app PIDs | `runtime/process-prepare.txt`, `runtime/process-restart.txt`, `runtime/process-verify.txt`, `runtime/restart-verified.json` |
| Runtime crash log | No AndroidRuntime error entries returned | `runtime/runtime-log.txt` |
| Java/Kotlin bytecode | Class-file major 61 (Java 17) verified for generated Java and compiled Kotlin | `runtime/verification.json` |

Visually inspected the final locked editor and rotation screenshots. The locked toolbar shows the pencil's fractional width and writing-enabled hint; imported PDF objects are visible without paper overlays. Rotation preserves the locked indicator and zoom. Pixel assertions also verify the six-color pen export and the multi-source canvas export in `runtime/pen-colors.pdf` and `runtime/restart-canvas.pdf`.

## Coverage

- Independent fractional pencil/ball/ink/highlighter widths across pages, notebooks, recreated editors, and process restart; saved stroke payloads remain unchanged.
- Raster and flattened PDF comparisons on all six paper colors; different default widths; pressure-sensitive ink and constant-width ball pen; fractional codec widths; unversioned and version-2 brush compatibility.
- Locked drag/pinch/double tap/generic scroll/fit/recenter routing, mid-gesture toggles, writing, erasing, lasso, text movement, image importing/resizing, PDF-object copying/resizing/deleting/undo, page navigation, accessible lock labels, activity recreation, real rotation, and saved viewport center/scale after resize.
- Selected/all-page insertion into standard, PDF, and infinite notebooks; source order/dimensions/identity, existing paper and annotations, duplicate confirmation, multiple sources with equal page indices, reorder/duplicate/delete, offline reopen, and pixel-checked PDF export.
- Populated version-6 migration preserving source/page IDs, legacy ink/highlighter preferences/payloads and export; earlier migration paths and template/content regressions.
- Invalid/encrypted sources, copy cancellation/stream closure, injected copy failure and SQLite-full transaction rollback, preview cancellation/discard, abandoned-copy recovery after process restart, committed-source retention, and cleanup after last references are removed.
- Hundred-page import without a count cap, four-million-pixel raster ceiling, shared preview pixel budget, at most two simultaneous canvas PDF jobs, and cancellation recycling during the IO handoff.

## Remaining acceptance

- A physical active pen and palm are unavailable. Real pressure-range input, palm contacts, hardware eraser behavior, and physical writing latency are not accepted from synthetic/emulator tests.
- Split-screen viewport resizing is covered at the ViewModel boundary; actual multi-window interaction on a physical tablet remains pending.
- Low storage is fault-injected rather than achieved by filling the device's storage. Cancellation and restart exercise copying and abandoned-copy recovery; power loss at every filesystem/SQLite commit boundary is not exhaustively simulated.
- Android 8.0 device rendering and a sustained physical-device memory/latency soak remain pending; these are not established by API-36 emulator results.
- Lint warnings (dependency update suggestions, existing draw allocations, EXIF/accessibility suggestions) remain separate from Phase 10 errors. The pinned compatibility decoder still depends on restricted custom APIs for reading version-2 strokes and must be regression-tested if Ink is upgraded.

## APK

Delivered APK: `reports/phase10/Notein-phase10-debug.apk`; checksum: `reports/phase10/Notein-phase10-debug.apk.sha256`. Its SHA-256 matches the tested build output at `app/build/outputs/apk/debug/app-debug.apk`. Installed with replacement on `emulator-5554`, preserving app data. Machine-readable verification is in `runtime/verification.json`.

## Zoom-only lock revision — 2026-10-12

Updated the lock requirement following tablet feedback: keep the paper's zoom/size fixed while allowing navigation to other sections. The existing per-notebook lock setting now blocks scaling only, with no database migration. Single-finger pan (or two-finger pan with finger writing enabled) and overview recentering retain zoom. The shared ViewModel boundary saves position changes while keeping the page's locked scale. Fit actions remain disabled. The toolbar and accessibility label now say "Zoom locked" and "Lock zoom", with navigation instructions.

Build, test APK packaging, and lint passed (`runtime/build-zoom-lock.txt`; zero lint errors). All 27 focused checks passed: 11 editor/canvas/UI checks, 1 rotation check, 13 earlier canvas/selection regressions, and 2 process-persistence checks. Evidence is in `zoom-lock/`; orchestration is in `runtime/runner-zoom-lock.txt`. Canvas checks cover committed single-finger pan, two-finger pan with changing span, fixed-scale overview recentering, cancelled navigation, mid-gesture toggling, and writing/erasing/selection. Editor checks verify position changes survive page navigation and recreation with zoom fixed. Updated screenshots were visually inspected.

Current APK: `reports/phase10/Notein-phase10-zoom-lock-debug.apk`, with adjacent SHA-256 file. This revision matches the current tested build output; the original APK and baseline evidence above remain archived. Installed with `adb -s HA21LTVY install -r` on the Lenovo TB330XU tablet and successfully launched, preserving existing app data. Physical pen/palm acceptance remains pending.

## Centered paper navigation revision — 2026-10-12

Following tablet feedback, finite paper and PDF pages now open fitted and centered when first displayed. They stay horizontally centered at every zoom; dragging cannot move them sideways. Vertical scrolling is bounded to the sheet edges, and sheets smaller than the viewport stay centered vertically. The canvas and shared ViewModel apply the same navigation constraint. Saved zoom and permitted scroll positions survive reopening; each newly added or imported page initializes using its own dimensions. Infinite canvases retain unrestricted navigation and the zoom-only lock behavior. Finite pages show gray surrounding space and instructions to scroll vertically. No notebook content or database schema is changed.

Final build, test packaging, and lint passed with zero lint errors (`runtime/build-centered-paper-final.txt`). All 33 checks passed: 17 focused paper/editor/canvas/UI checks, 1 real rotation check, 13 earlier canvas/selection regressions, and 2 process-persistence checks. Evidence is in `centered-paper/`, with orchestration in `runtime/runner-centered-paper.txt`. New checks verify first/added paper and imported PDF initialization, diagonal drag without horizontal drift, bounded vertical scrolling under zoom lock, off-center pinch, cancelled gesture restoration, saved position after recreation, and handwriting coordinates on centered paper. First/added-page screenshots were visually inspected.

Latest APK: `reports/phase10/Notein-phase10-centered-paper-debug.apk`, with adjacent SHA-256 file, matching the tested build output. Installed with replacement on tablet `HA21LTVY` (Lenovo TB330XU), preserving notes, and successfully launched. Earlier APKs and evidence remain archived; physical pen/palm acceptance remains pending.

## Page layouts and notebook-wide zoom lock — 2026-10-12

Added saved per-notebook "Side by side" and "One below another" page layouts. The editor hosts the current sheet and its adjacent sheets, with actual ink/media/PDF content. Horizontal drags turn pages after a deliberate threshold; vertical scrolling first travels within the current sheet, then turns pages when dragged sufficiently beyond its top or bottom. Short, cancelled, reversed, and pinch gestures do not accidentally turn pages; endpoints do not wrap. Finger writing uses two fingers for navigation. Adjacent sheets can be activated with a pen tap. Removed the previous/next arrows and Move left/right controls; page thumbnails remain available. Scrolled sheets are clipped to the editing frame, preserving toolbar visibility.

Zoom locking now captures a shared notebook scale and applies it to existing, added, automatic, duplicated, and PDF-imported pages. The toolbar displays the percentage. Page changes and reopening retain that percentage; vertical gesture navigation enters a next page at its top or a previous page at its bottom. Page transitions commit outgoing edits and scroll before changing selection, with a serialized save loop and a busy flag that survives Room emissions. Failed saves retain the outgoing page.

Room version 7 → 8 adds the layout and nullable locked scale. Existing locked notebooks use their selected page's saved scale, falling back to the first page. Only viewport/settings fields change; notebook IDs, page IDs, annotations, sources, and ordering are retained. Earlier migration regression paths include the new migration.

The final core run passed 55 checks: 26 focused editor/gesture/layout/UI/migration checks, 1 real rotation check, 26 migration/PDF/canvas/selection regressions, and 2 process-persistence checks. The process check includes a paged notebook with a saved vertical layout and shared zoom across previously different page scales. Evidence is in `page-layout/`; orchestration is `runtime/runner-page-layout-verified.txt`. A migration-fixture assumption about absent index arrays was corrected; an image-import timeout passed its isolated recheck and the subsequent full run (`runtime/image-regression-recheck.txt`). Failed-attempt logs are retained.

Screenshot inspection caught paper drawing over the toolbar during vertical dragging. After fixing clipping, all 3 affected UI checks passed again (`page-layout/ui-clipping.txt`, `runtime/runner-page-layout-ui.txt`), including a pixel assertion that the toolbar is unaffected by the drag. Updated horizontal/vertical screenshots were visually inspected. Final build/test packaging/lint passed with zero lint errors (`runtime/build-page-layout-clipped.txt`).

Current APK: `reports/phase10/Notein-phase10-page-layout-debug.apk`, with adjacent SHA-256 file, matching the final build output. Installed with `adb -s HA21LTVY install -r` on the Lenovo TB330XU and successfully launched after the data-preserving migration. Earlier APKs/evidence remain archived. Physical pen/palm acceptance remains pending.
