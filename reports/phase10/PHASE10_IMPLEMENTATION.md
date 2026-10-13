# Phase 10 implementation - 2026-10-12

Historical source-only handoff: the user initially requested Phase 10 implementation without debugging. That restriction was superseded by the later request to debug Phase 10. Current results and repairs are in `PHASE10_DEBUGGING.md`: build/lint and 57 emulator checks passed, and `Notein-phase10-debug.apk` was delivered; physical active-pen/palm acceptance remains pending. The sections below describe the original source handoff, including the original version-2 custom brushes; new authoring now uses supported stock brushes with version-3 metadata while preserving older decoding. The existing Phase 9 verification records and APK have been preserved.

## Writing tools

- Added Pencil, Ball pen, and Ink pen labels alongside highlighter and eraser. Pencil starts at 0.5 units, ball pen at 2.0, and ink pen at 6.0. Each slider uses its specified independent range, including fractional pencil widths.
- Added app-wide Room thickness preferences, observed across editor sessions and serialized when saved. Eraser does not write a writing-tool preference. The toolbar shows the active name, numeric width, and a sample rendered with the actual brush.
- Pencil uses a fine translucent graphite-colored circular tip with a small pressure variation; ball pen uses the constant-width marker family; new ink uses a wider pressure response. Missing pressure is normalized to the neutral base width in authoring and saved input. New thickness values use document units, without density multiplication.
- New strokes carry brush metadata version 2. Legacy unversioned `pen` strokes continue to decode with the previous stock pressure-pen family, recorded color, and recorded size. Their payloads are not rewritten. Thumbnail ink now renders the decoded brush rather than substituting a constant-width path. Export and lasso continue to use the same decoded stroke.

## Viewport lock

- Added an accessible Lock zoom and position toggle and visible locked/unlocked state, persisted per notebook and defaulting to unlocked.
- Canvas gesture handling, scale changes, fit actions, overview recentering, and the ViewModel viewport boundary honor the lock. Changing the toggle resets navigation gesture state and retains the current view. Writing and content editing remain enabled, as does explicit page navigation.
- Added saved viewport bounds per page. Layout changes adjust translation to retain the document point at the center while preserving scale, including after activity recreation; this deliberate layout adjustment is separate from gesture navigation.

## PDFs inside existing notebooks

- Added an editor document-picker action, local copy/validation progress, lazy page thumbnails, and page selection with all pages initially selected. Confirmation is guarded in the ViewModel and transaction; repeated confirmation cannot insert the same source twice.
- Paged notebooks insert chosen PDF pages immediately after the current page in source order. Existing notebook identity, metadata, page IDs, annotations, paper snapshots, and current selection are retained. Standard and PDF notebooks support mixed ordinary/PDF pages with page management and source-specific previews.
- Infinite canvases insert `pdf_page_v1` editable objects from the viewport center with a vertical gap. The original local source/page reference is retained; rendering requests scale-dependent previews with bounded caches. Existing lasso movement, proportional resizing, copying, deletion, and flattened canvas export recognize these objects.
- PDF backgrounds bypass ordinary paper rendering. Automatic matching pages are excluded for source-backed pages and PDF canvas objects.
- Imports commit source and page/object insertion in one Room transaction with guarded page ordering. Cancellation and failed validation remove uncommitted copies. Source cleanup checks page/object references, retains sources required by session undo until the notebook is reopened, and removes unreferenced files after permanent notebook deletion. Abandoned import files are reconciled before the first PDF import in a new process.

## Storage migration

Room version 7 adds tool preferences, notebook viewport lock, page viewport dimensions, and a page source-ID reference. It rebuilds the prior single-source PDF table with independent source IDs. Existing sources use their notebook IDs as source IDs, retaining their file paths; existing PDF pages retain their IDs, dimensions, page indices, and annotations. Initial ink/highlighter preferences are inferred from the most recently modified applicable legacy pages. No destructive database fallback is used.

## Commands and evidence

Read the requested phase features/implementation plans and inspected source with `Get-Content`, `rg`, and directory listings. Inspected the installed Ink API class metadata using read-only ZIP access to identify the custom-brush opt-in and supported constructors. Consulted the [AndroidX Ink stock-brush source](https://android.googlesource.com/platform/frameworks/support/+/a7cc3baa03c979e6c2cfdf9912b4b13215e1a81f/ink/ink-brush/src/commonMain/kotlin/androidx/ink/brush/StockBrushes.kt) as an implementation reference. Source edits used `apply_patch`.

No build, compiler, test, lint, emulator, device, migration runner, or debugging command was executed. No Phase 10 APK was produced. The Phase 10 progress checkbox remains unchecked because execution and device verification are pending.

## Pending acceptance

Run the Phase 10 debugging plan when authorized: compilation and migration on populated data; independent widths across navigation/restart; legacy/new brush parity and hardware pressure handling; gesture lock and resize behavior; subset/multiple-source insertion and offline rendering; canvas object editing/export; atomic cancellation/failure behavior; source cleanup and bounded-memory checks. Physical active-pen and palm tests remain separate from emulator evidence.
