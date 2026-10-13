# Notein implementation and debugging plans

Approved phase order: **Phase 1 -> Phase 2 -> Phase 3 -> Phase 4 -> Phase 6 -> Phase 9 -> Phase 10 -> Phase 8**.

This file is the implementation handoff for a native Android app. The workspace now contains Android source under `app/`, alongside the supplied compiled APKM and inspection artifacts. Extend the existing source and preserve its recorded progress; use the supplied APK as a feature reference.

Requirements revised on 2026-10-12: the notebook-creation template picker and automatic matching pages remain in Phase 9. Pencil, ball pen, and ink pen with independent thickness settings are moved out of Phase 2 into a **separate Phase 10**, together with viewport/zoom lock and importing PDFs inside an existing notebook. Phase 10 runs after Phase 9 and before final Phase 8 checks. This document update does not implement or verify the new Phase 10 features.

Each phase ends with a working APK, completed checks, and a short record of known limitations. Features assigned to a phase must work before that phase is considered complete.

## Instructions for implementing agents

- Follow the approved order. Keep the original phase numbers; Phases 5 and 7 are omitted intentionally. Phase 2 retains the baseline handwriting scope. Complete Phase 9 and the separate Phase 10 before final Phase 8 acceptance.
- Implement the feature set and execute the debugging plan for the current phase before proceeding to the next phase.
- Do not add excluded features or silently expand the scope.
- Record the commands run, verification results, remaining defects, and the location of the latest APK in a phase completion note.
- Distinguish emulator checks from physical-device checks. Never claim stylus or palm-handling acceptance solely from emulator tests.
- Preserve existing notebooks as later phases extend the storage model. Do not delete user data to resolve migrations or test failures.
- If a required check cannot run, record the exact blocker and leave that completion criterion pending.

## Shared decisions

- Create a new native Android project using Kotlin and Jetpack Compose. Use AndroidX Ink for handwriting, Room for local persistence, and Android's PDF renderer for imported documents.
- Use Android 8.0 as the minimum supported version. Compile and target API 36. Set up JDK 17 and the Android SDK before implementation; the existing workspace contains no Android source project.
- Debugging override approved on 2026-10-11: build and test with the existing JDK 25 installation. Emit Java/Kotlin 17 bytecode for Android compatibility; do not install JDK 17.
- Baseline build tooling: Android Gradle Plugin 9.1.1, Gradle 9.3.1, SDK Build Tools 36.0.0, and AndroidX Ink 1.0.0. Pin other compatible stable dependencies in a version catalog.
- Use the working name **Notein**, with application ID `com.ramst.notein`, allowing installation alongside the supplied app.
- Every included feature is free. Include no authentication, billing, entitlement checks, watermarks, or commercial usage quotas.
- **Exclude backup, cloud services, laser pointer, font importing, and layer controls entirely.**
- Also exclude audio recording, OCR/handwriting recognition, AI, linked-document study tools, and migration from the supplied Notein app in this first build.
- Keep notes editable inside the app. PDF exports are flattened. Start with new notebooks.
- Store document content independently of screen size and zoom. Persist completed edits transactionally; display a saving error if storage fails.
- Phase 10 writing tools are pencil, ball pen, ink pen, and highlighter. Each has its own durable thickness preference; changing one must never change another or restyle completed strokes. Pencil and ball pen start thinner than the existing ink pen.
- Phase 10 adds zoom lock: zoom stays fixed while finger panning and overview navigation remain available, along with writing/editing. It also adds importing PDFs into the currently open notebook, without requiring a separate notebook.
- Phase 9 adds pre-creation paper selection and matching-page generation. A matching next page is appended after the first committed nonempty edit on the last standard-notebook page, without moving the user away from the current page. This creates a ready next page without trying to guess when a handwritten page is full.
- Use one application module, separated into library, editor, storage, and PDF packages. Keep the supplied APK and inspection artifacts unchanged.
- No commercial quota means no app-imposed notebook, page, import, or export cap. Actual device storage and memory limits must be handled safely; caches may be bounded without restricting saved content counts.
- Local persistence, autosave, and the notebook trash are retained. They do not imply a backup feature. Do not implement backup archives, backup/restore screens, or account-based synchronization.

## Phase 1 - App foundation and notebook library

**Features:** Launch without login, notebook creation and organization, recent notebooks, local storage, trash, and restoration.

### Implementation plan

1. Create the Android project, reproducible Gradle build, Material 3 theme, navigation, and light/dark appearance.
2. Build an adaptive notebook library with title search, recently modified sorting, and single-level folders.
3. Implement creating, renaming, moving, and deleting notebooks. New notebooks receive one blank page. Initially, selecting a notebook opens its blank page surface. Phase 9 replaces the immediate/default creation flow with a template preview and confirmation; its first page uses the selected template.
4. Establish storage entities for notebooks, folders, pages, and editable objects. Use stable IDs so later phases can add content without replacing existing notebooks.
5. Implement trash as soft deletion. Trashed notebooks disappear from the main library and remain recoverable until the user permanently deletes them.
6. Require confirmation for permanent deletion. Remove associated assets only when no remaining notebook references them.
7. Handle empty-library, loading, and storage-error states. Hide future editing controls until their features are implemented.

### Debugging plan

- Test create -> rename -> move -> close app -> reopen, verifying names, locations, and IDs.
- Test trash -> restore and permanent deletion, including notebooks with duplicate titles.
- Verify title search and sorting after edits and restoration.
- Test database initialization, transaction failure, and process restart.
- Inspect the manifest and dependency list for unintended login, billing, or cloud components.

### Completion criteria

The app builds and installs. Notebook operations survive restart, trash works, and users never encounter account or payment requirements.

## Phase 2 - Handwriting experience

**Features:** Baseline pen, pressure sensitivity, highlighter, stroke eraser, colors, thickness, undo/redo, stylus input, finger navigation, palm handling, and autosave. Additional writing tools and independent thickness preferences are assigned to Phase 10, not this phase.

### Implementation plan

1. Add the handwriting surface to the blank page editor, using AndroidX Ink for stroke capture and rendering.
2. Support a pressure-sensitive pen and a constant-width highlighter. Provide color and thickness controls.
3. Implement stroke erasing: touching an existing stroke removes that entire stroke.
4. Route active-stylus input to writing and finger input to navigation. Provide an explicit finger-writing setting.
5. Ignore palm-classified input and prevent finger movement from changing the viewport while a stylus stroke is active.
6. Persist stroke input, brush settings, and document position after each completed stroke. Show saving status; retain unsaved work in memory if a write fails.
7. Implement undo/redo for writing and erasing. Keep history during the open editing session; committed content survives reopening.
8. Expose pen, highlighter, eraser, undo/redo, colors, and thickness in the toolbar. Phase 10 replaces the baseline writing-tool selection with the three requested pen types and independent preferences.

### Debugging plan

- Capture debug traces of input type, pressure, stroke lifecycle, viewport transforms, and save completion.
- Test rapid strokes, dots, diagonal lines, changing pressure, and drawing near screen edges.
- Test palm contact before, during, and after writing on a real active-pen tablet.
- Verify finger navigation does not create ink in stylus mode.
- Test write -> erase -> undo -> redo, comparing both visible content and stored objects.
- Kill and reopen the app after completed edits. Exercise storage failures and retry saving.
- Verify constant-width behavior when pressure information is unavailable.

### Completion criteria

Completed strokes reopen correctly. Writing, erasing, and undo/redo agree with stored content. Hardware input checks are recorded separately from emulator checks. Phase 2 evidence covers baseline handwriting only; Phase 10 has separate acceptance for the added tools.

## Phase 3 - Pages and infinite canvas

**Features:** Standard notebooks, page management, infinite canvas, navigation, overview, and paper backgrounds.

### Implementation plan

1. Add a notebook type selector: standard pages or infinite canvas.
2. Implement page creation, duplication, reordering, deletion, and navigation for standard notebooks. Prevent deletion of the final page.
3. Use A4 portrait as the default standard-page size. Add blank, lined, and grid backgrounds. Phase 9 extends this with dotted paper, adjustable spacing, page sizes/orientation, background colors, and choosing the template before notebook creation.
4. Implement infinite canvas as an unbounded document coordinate space with viewport-based rendering.
5. Add pinch zoom, fit-page for standard/PDF pages, and fit-content for infinite canvases. New finite pages open fitted and centered in the frame, using the notebook's shared zoom when locked. Finite paper stays horizontally centered; horizontal dragging turns pages instead of freely moving paper. Persist a per-notebook layout choice: side by side or one below another. Show adjacent sheets during navigation and turn pages after a deliberate horizontal swipe or vertical scrolling past a page edge, according to the chosen layout. Short/cancelled drags, pinching, writing, and notebook endpoints must not accidentally turn pages. Infinite canvases retain unrestricted panning.
6. Provide page thumbnails and an infinite-canvas overview showing the current viewport.
7. Persist notebook type, page ordering, backgrounds, and viewport position. Page duplication creates independently editable content.

### Debugging plan

- Verify stroke positions remain stable through repeated zooming, panning, rotation, and reopening.
- Test page duplication, reordering, and deletion with existing handwriting.
- Check that editing a duplicated page does not modify its source.
- Test drawing on both sides of the canvas origin and far from the initial viewport.
- Compare overview navigation with the actual document position.
- Verify first/added/automatically generated/imported finite pages initialize centered. Check diagonal drag cannot move them horizontally, off-center pinch keeps paper centered, and zoom lock retains vertical scrolling. Reopen and rotate without losing saved zoom or permitted scroll position.
- Profile thumbnail generation and confirm it does not block writing.

### Completion criteria

Both notebook types retain their content and navigation state. Page operations are reliable, and canvas navigation does not distort handwriting.

## Phase 4 - Content editing

**Features:** Lasso selection, moving/resizing/copying/deleting content, text boxes, and images.

### Implementation plan

1. Define editable object types for strokes, text, and images, using common position and transform information.
2. Implement freeform lasso selection for intersecting objects on the current page or canvas.
3. Add selection handles and actions for moving, proportional resizing, copying, and deleting.
4. Add text boxes with keyboard editing, font size, color, bold, and italic using bundled/system fonts.
5. Import images through Android's photo/document picker and copy them into app-private storage.
6. Allow image movement and proportional resizing. Preserve the original asset while generating appropriately sized display previews.
7. Extend undo/redo and transactional saving to every new editing operation.
8. Preserve object insertion order for overlapping content. Do not introduce layers or a layer-management interface.

### Debugging plan

- Test lasso selection at different zoom levels and with mixed stroke, text, and image content.
- Verify repeated resizing does not accumulate coordinate errors.
- Exercise overlapping objects and selection near object boundaries.
- Test copy -> move -> delete -> undo, ensuring restored objects retain their content and position.
- Test large images, unsupported files, cancelled selection, and failed asset copying.
- Reopen edited documents and compare object counts, transforms, text, and asset references.

### Completion criteria

All content types remain editable and survive restart. Selection operations and undo/redo produce matching visual and stored results.

## Phase 6 - PDF workflow and export

**Features:** PDF import, navigation, annotation, selected-page export, flattened export, and canvas pagination.

### Implementation plan

1. Import PDFs through the system document picker and copy them locally.
2. Validate the PDF before creating its notebook. Reject unsupported, encrypted, or corrupt documents with a useful message; leave no partially created notebook.
3. Create a PDF-backed notebook whose original pages are fixed backgrounds beneath editable objects.
4. Render visible PDF pages on demand with bounded caching. Allow the Phase 2-4 tools to annotate those pages.
5. Add page thumbnails, page-number navigation, and selected-page export.
6. Export visible backgrounds and editable content as flattened PDF pages without watermarks.
7. Use a streaming PDF exporter: render one page at a time, encode its image, and write it before processing the next page. Do not retain every rendered bitmap in memory.
8. Default export rendering to 150 DPI. Preserve source-page dimensions for imported PDFs and A4 dimensions for standard notes.
9. For infinite canvases, provide a content-region selection and A4 pagination preview. Let users adjust the starting boundary before export.
10. Snapshot document state when export begins. Show progress and cancellation; discard incomplete temporary output on failure.

### Debugging plan

- Test portrait, landscape, scanned, image-heavy, and mixed-size PDFs.
- Verify annotations remain aligned while zooming, navigating, and reopening.
- Test malformed and password-protected files, interrupted imports, and missing external source files.
- Open exported PDFs in an independent viewer and compare page order, dimensions, colors, text appearance, and annotations.
- Test selected-page export and canvas content crossing page boundaries.
- Profile a 100-page export, checking memory growth and responsiveness.
- Exercise cancellation and low storage without changing the notebook or leaving misleading completed output.

### Completion criteria

Imported PDFs remain usable offline. Annotations stay editable inside Notein. Exported PDFs open correctly, contain the intended pages, and carry no watermark or usage gate.

## Phase 9 - Notebook templates and automatic matching pages

**Execution position:** After Phase 6 and before Phase 8. This is an additional feature phase, not a replacement for the excluded Advanced tools or backup phases.

**Features:** Choose paper before creating a notebook; blank, lined, squared, and dotted layouts; adjustable ruling/grid/dot spacing; paper size and orientation; background colors; live preview; automatic next-page creation with identical paper settings.

### Implementation plan

1. Change Create notebook to open a configuration dialog or tablet-sized sheet before any notebook/page is saved. Include title, folder, notebook type, and a live paper preview. Only the Create action persists the notebook and its first page; cancellation leaves no empty notebook behind.
2. Offer Blank, Lined, Squares, and Dots. Use the existing grid representation for Squares, and add a stable stored value for Dots. Pattern-specific controls appear only for the selected pattern.
3. Offer A4, A5, and Letter paper sizes with portrait/landscape orientation for standard notebooks. Default to A4 portrait. Infinite canvas uses the same pattern, spacing, and color controls, but hides fixed page size/orientation and automatic-page settings.
4. Provide labeled spacing presets and numeric adjustment: ruled-line spacing `4-12 mm`, default `7 mm`; grid-square size `2-10 mm`, default `5 mm`; dot spacing `2-10 mm`, default `5 mm`. Offer fine/normal/bold ruling and small/normal/large dots as visual controls. Keep these choices independent of writing-tool thickness. Convert paper measurements into the existing document coordinate system rather than screen pixels.
5. Offer white, cream, pale gray, pale blue, pale yellow, and dark-gray backgrounds, plus a custom color picker. Adapt ruling/dot contrast to the background. Preview the exact pattern, spacing, dimensions, orientation, color, and pattern weight before creation; do not rewrite saved handwriting colors when the background changes.
6. Persist a notebook default template and a complete template snapshot per page: page dimensions/orientation, pattern, spacing, pattern weight/dot size, and background color. Extend the Room schema with data-preserving migrations. Existing pages retain their dimensions and appearance; infer matching template values for their existing blank/lined/grid backgrounds.
7. Apply the selected template to the first page and inherit the notebook default for manually added pages. Duplicated pages keep the source template. Changing the notebook default affects future manually added pages, not existing pages; changing one page's paper affects only that page.
8. Reuse one paper-rendering implementation in the creation preview, editor, thumbnails, canvas overview, and PDF export. PDF-backed notebooks retain their imported page backgrounds; template selection must not replace or recolor source PDF pages.
9. Enable automatic matching pages by default for standard notebooks, with a notebook-level "Automatically add next page" toggle in the creation sheet and notebook settings. On the first committed nonempty content edit to the current last page, append one blank page with that written page's exact template. Handwriting, nonempty text, and inserted images count as content; hover, panning, zooming, cancelled strokes, erasing, and selecting do not.
10. Keep the editor on the page being written. Further edits to that page must not append more pages while a next page already exists. The new blank page does not create another page until it receives content. Writing an earlier page does not append a page if a later page is already present. Do not guess page completion from ink coverage or switch pages automatically.
11. Serialize content commits and page-extension requests in the repository. Commit written content first; then perform the last-page check and append in a separate guarded database transaction so page-allocation failure cannot roll back saved writing. Prevent duplicate appends from rapid strokes or concurrent callbacks by checking the current last-page ID inside that transaction. Reopening a notebook must not create extra pages. Undoing the triggering edit leaves the generated blank page in place and never removes later work. If the user deletes the trailing blank page, a later new content commit on the now-last page may create one replacement; deletion alone does not recreate it.
12. Disable this feature for infinite canvases and imported PDF notebooks. Do not add paper to, or duplicate, imported PDFs automatically. Mark automatically generated pages so the untouched trailing page can be omitted from default whole-notebook PDF export; explicitly selecting it still exports it.
13. If saving the content succeeds but next-page allocation cannot be completed, keep the written content, report the page-creation failure, and allow an explicit retry. Retry rechecks the current last page and must not create duplicate pages. Do not silently advance or lose strokes because storage is full.

### Debugging plan

- Exercise every layout, size, orientation, spacing range, pattern weight, and background choice before creation; compare the preview with the actual first page and exported PDF.
- Cancel notebook creation repeatedly and verify no notebook, page, or orphan asset is persisted. Confirm the creation action cannot accidentally run twice.
- Verify millimetre-based spacing stays consistent through zooming, rotation, and export, and that pattern controls never modify pen thickness.
- Upgrade a populated existing database and compare old pages before/after. Test Dots persistence and decoding without falling back to Blank.
- Verify templates survive restart, page duplication, manual page creation, deletion/reordering, and notebook-default changes.
- Write one stroke on the last page: exactly one matching blank page must appear and focus must stay on the original page. Write many more strokes there: no additional pages should appear.
- Write on the generated last page: exactly one further matching page appears. Test rapid commits, text/image insertion, undo/redo, delete-last-page then edit, toggle off/on, restart, and save failure/retry.
- Verify page-template equality includes dimensions, orientation, layout, spacing, background color, ruling weight, and dot size; confirm no content or viewport offset is copied into the generated page.
- Confirm no automatic page creation for navigation, cancelled input, infinite canvases, or PDF annotation. Confirm default export excludes only an untouched auto-generated trailing page, not manually created blank pages or explicitly selected pages.
- Test page creation under low storage and interrupted transactions. Existing written content must remain readable, and repeated retries must never multiply pages.

### Completion criteria

Users choose and preview page appearance before notebook creation. All paper settings persist and render consistently. Writing on the last standard-notebook page generates exactly one matching next page without interrupting writing, duplicating pages, changing pen preferences, or altering imported PDFs. Older notebooks remain intact. This phase has no account/payment gate or notebook/page quota.

## Phase 10 - Pen types, viewport lock, and PDF import inside notebooks

**Execution position:** A separate phase after Phase 9 and before Phase 8. Do not treat these requirements as additions to Phase 2 or mark them complete using older handwriting/PDF evidence.

**Features:** Pencil, ball pen, and ink pen; independent saved thickness for each writing tool; very fine pencil and thinner ball pen defaults; lock zoom while allowing pan; import PDFs directly into an existing notebook.

### Implementation plan

#### Writing tools and independent thickness

1. Offer three distinctly labeled writing tools: Pencil (very fine graphite-style stroke with light pressure variation), Ball pen (thin constant-width stroke), and Ink pen (pressure-sensitive ink). Retain highlighter and eraser. Interpret "lighter thickness" as a narrower ball-pen stroke, not merely more transparent ink.
2. Store independent app-wide thickness preferences for pencil, ball pen, ink pen, and highlighter. Switching tools/pages/notebooks or restarting restores the selected tool's value. Updating one slider must never update the others; selecting eraser does not modify writing-tool preferences. Show the selected tool's name and a live stroke sample beside its thickness control.
3. Initial defaults in existing editor size units: pencil `0.5`, ball pen `2.0`, ink pen `6.0`; retain the existing highlighter default. Supported ranges: pencil `0.25-4.0`, ball pen `0.5-8.0`, ink pen `1.0-24.0`; retain the highlighter's own range. Support fractional fine-pencil widths throughout rendering and serialization. These are document/editor units, not claimed physical millimetres.
4. Preserve stored legacy `pen` strokes as ink pen without changing their existing widths, colors, or appearance. Initialize the new ink-pen preference from the last active page's saved legacy width; new pencil/ball tools receive their finer defaults. Version new brush metadata and continue decoding existing strokes. Saved strokes retain the width recorded when drawn, regardless of later preference changes.
5. Render new brush types consistently in live ink, saved strokes, thumbnails, lasso previews, and exported PDFs. With missing pressure information, use the tool's base thickness; ball pen must remain constant-width even when pressure is available.

#### Viewport/zoom lock

6. Add an accessible "Lock zoom" toggle in the editor toolbar, with a clear locked/unlocked indicator and zoom percentage. Default to unlocked. Persist the locked zoom scale per notebook and apply it to all existing, automatically generated, manually added, duplicated, and imported pages. Moving to a different page must keep the same locked percentage.
7. When locked, preserve the current scale while allowing finger drag/pan and overview recentering. Ignore pinch zoom and other scaling actions. Disable Fit page/Fit content while locked. Writing, erasing, lasso/object editing, tool controls, and explicit page-navigation buttons remain usable. With finger writing enabled, use two fingers to pan.
8. Apply the lock at the shared viewport-update boundary so alternative input paths cannot bypass it. Cancel navigation gesture state when the toggle changes. Unlocking resumes navigation from the same view without applying an accumulated gesture delta.
9. Preserve saved scroll, notebook layout, and shared zoom lock after restart. On rotation/window resize, preserve zoom and the document point at the viewport center while adjusting to bounds and finite-sheet constraints. Thumbnail navigation restores a saved view with the notebook's locked scale; gesture navigation enters the next page at its top or the previous page at its bottom. PDF insertion must not force-fit or recenter the current locked page. New pages initialize at the shared locked scale.

#### PDF import into an existing notebook

10. Add "Import PDF into this notebook" inside the open editor. Use the system document picker and show PDF thumbnails/page selection before confirming. Select all pages by default; import into the current notebook without creating a new notebook, replacing its contents, or changing its title/folder.
11. For paged standard/PDF notebooks, insert selected PDF pages immediately after the current page, in source order. Keep each imported page's original dimensions as a fixed PDF background with editable annotations above it. Preserve existing page IDs, annotations, templates, and ordering outside the inserted range. Allow multiple PDFs and ordinary paper pages in one notebook without converting all pages to a single background type.
12. For infinite canvases, insert selected pages as movable/resizable PDF-page objects, vertically arranged from the current viewport center with a visible gap. Keep references to their original local PDF pages and render them on demand so they remain sharp when enlarged. Support existing lasso and export operations for these objects; do not replace the canvas or insert a separate notebook.
13. Extend PDF storage from one source per notebook to individually identified local PDF sources. Each imported page/object references a source ID plus source page index. Migrate existing PDF notebooks without changing their page IDs, asset paths, or annotations. Update source lookup, thumbnails, rendering, duplication, export, and asset cleanup to handle multiple sources; remove a source only when no page/object references it.
14. Copy and validate the source before committing the insertion. Use a transactional insertion with stable IDs and guarded page-order updates. Cancellation, corruption, unsupported encryption, revoked file access, and low storage must leave the target notebook unchanged and remove uncommitted temporary files. Show progress; prevent double confirmation from inserting the same operation twice. Imported content remains accessible after the external source is removed.
15. Keep Phase 9 paper settings on ordinary pages. Never paint a notebook template over a PDF background. Automatic matching-page creation applies only to ordinary paper pages in standard notebooks, not imported PDF pages or canvas PDF objects; importing a PDF must not itself trigger matching-page generation. Default export follows the notebook's combined page/object order and includes the inserted PDF content.

### Debugging plan

#### Writing tools

- Give every writing tool a different width; change one, switch among all tools, navigate pages/notebooks, rotate, and restart. Verify each width remains independent, including highlighter.
- Compare default pencil/ball/ink strokes on supported paper colors and in PDF export. Pencil is very fine, ball pen is visibly narrower than the legacy size-6 pen, and ink responds to pressure.
- Check fractional widths are not clamped by legacy slider, brush, codec, or renderer code. Test pressure-present and pressure-unavailable input.
- Migrate an existing database and compare legacy strokes before/after, including older highlighter settings. Changing preferences must not restyle saved strokes.

#### Viewport lock

- Record scale and viewport coordinates, lock zoom, then send pinch, single/two-finger drag, double tap, wheel, and overview gestures. Scale must remain unchanged; deliberate panning and overview recentering must work and persist, while rejected palm contacts and cancelled gestures must not commit navigation.
- Verify pen, eraser, lasso, text/image editing, toolbar actions, and explicit page navigation still work while locked.
- Toggle the lock during an active gesture and unlock afterward; verify no jump or deferred movement occurs. Repeat with rotation, split-screen resizing, page changes, process restart, and PDF insertion.
- Verify lock state and saved viewport persist and that fit cannot change the locked scale. Overview recentering must retain scale. Test the visible indicator and accessibility label.

#### PDF insertion

- Import all pages and selected subsets into a nonempty standard notebook, an existing PDF notebook, and an infinite canvas. Verify notebook identity, placement, source order, dimensions, existing annotations, and templates.
- Import two different PDFs, including identical source page numbers. Reorder/duplicate/delete their pages or objects, reopen, and export; each must still render the correct source.
- Migrate older single-source PDF notebooks. Compare existing contents, IDs, and exports before/after.
- Exercise repeated confirmation, cancellation during copying, invalid/encrypted PDFs, low storage, process interruption, and external-source deletion. Verify atomic insertion, no orphan files, and no loss of existing work.
- Verify imported pages do not inherit paper overlays or generate unwanted next pages, while later writing on ordinary paper still follows Phase 9 rules.
- On a locked canvas, import PDF objects and verify the viewport does not move. Test large multi-page imports for bounded rendering memory and responsive writing.

### Completion criteria

All three pen types work with independently retained thickness settings and preserved legacy strokes. Locking zoom prevents accidental scaling while keeping panning and editing usable. Users can import PDFs into their existing paged notebooks and infinite canvases, including multiple sources, without losing existing content or breaking templates, annotations, or export. The phase has its own implementation/debugging evidence and no account/payment gate or import quota.

## Phase 8 - Tablet polish and release readiness

**Features:** Adaptive layouts, accessibility, performance, storage resilience, compatibility, and installable delivery.

### Implementation plan

1. Refine portrait, landscape, and Android split-screen layouts. Ensure the toolbar and keyboard do not obscure essential controls.
2. Add accessible labels, adequate touch targets, selected-tool indicators, and consistent loading/error messages.
3. Optimize rendering through viewport culling, bounded image/PDF caches, and background thumbnail work.
4. Move expensive import, export, and storage operations away from the input/rendering path.
5. Review asset cleanup, low-storage behavior, database migrations, and recovery after interrupted operations.
6. Validate native dependencies and packaging for 16 KB Android page sizes.
7. Deliver the source project, Gradle wrapper, debug APK, build instructions, and verification results. Store publication and release signing remain separate.
8. Include Phase 9 templates/automatic pages and Phase 10 writing tools, independent thickness, viewport lock, and in-notebook PDF import in final UI, accessibility, migration, and performance checks. Final acceptance must use the updated requirements rather than the earlier single-pen/default-paper baseline.

### Debugging plan

- Run the complete workflow: create notebook -> write -> edit -> import PDF -> annotate -> export -> reopen.
- Test rotation and split-screen changes during writing, text editing, importing, and exporting.
- Use generated datasets containing 100 notebooks, 1,000 pages, and a canvas with 10,000 strokes. Check for crashes, UI stalls, and unexpected quotas.
- Profile handwriting on the target tablet, checking missed input, frame stalls, and memory growth during sustained sessions.
- Verify completed saved edits survive process death and app upgrades.
- Run unit tests, database tests, instrumentation tests, lint, and APK builds across supported configurations.
- Check that backup, account, premium, laser, font-import, and layer controls are absent.
- Record device-specific failures with reproduction steps; fix release-blocking defects and rerun affected checks.
- Run create notebook with custom paper -> choose pencil/ball/ink widths independently -> write -> verify matching next page -> reopen -> export. Check that repeated writing and process restart do not create redundant pages or reset tool preferences.
- Run lock viewport -> import multiple PDFs into the existing notebook -> annotate/reorder -> reopen -> export. Verify no gesture drift, source mix-ups, accidental paper overlays, or loss of previous content.

### Completion criteria

No known reproducible lost-work defect remains. Included features work without network access or payment. The APK passes build and compatibility checks, with any outstanding physical-device testing explicitly identified.

## Technical references

- [Android Gradle Plugin 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [AndroidX Ink releases](https://developer.android.com/jetpack/androidx/releases/ink)
- [Android PDF renderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [Android 16 KB compatibility guidance](https://developer.android.com/guide/practices/page-sizes)

## Progress checklist

- [x] Phase 1 implemented and verified on an API 36 emulator; see `reports/phase1/PHASE1_COMPLETION.md` for commands, evidence, and physical-device limitations.
- [ ] Phase 2 baseline handwriting fully implemented and verified. Earlier handwriting work is implemented and emulator-debugged; physical active-pen/palm acceptance remains pending. See `reports/phase2/PHASE2_DEBUGGING.md` for earlier repairs and evidence. New writing tools are tracked separately in Phase 10.
- [ ] Phase 3 implemented and verified.
- [ ] Phase 4 implemented and verified. Source implementation recorded in `reports/phase4/PHASE4_IMPLEMENTATION.md`; verification and APK delivery remain pending under the user's no-debugging instruction.
- [ ] Phase 6 implemented and verified. Source implementation recorded in `reports/phase6/PHASE6_IMPLEMENTATION.md`; verification and APK delivery remain pending under the user's no-debugging instruction.
- [x] Phase 9 notebook templates and automatic matching pages implemented and verified on the API 36 emulator (21 passing checks, including migration, failure/retry, export, live rotation and process restart). See `reports/phase9/PHASE9_DEBUGGING.md`. APK: `reports/phase9/Notein-phase9-debug.apk`. Physical-device acceptance remains separate.
- [ ] Phase 10 pen types, independent thickness, zoom lock, and PDF import inside notebooks implemented and verified. Emulator debugging completed on 2026-10-12: 26 Phase 10 checks and 31 regression checks passed; build/lint passed with zero errors. Subsequent zoom-only lock and centered-paper revisions passed 27 and 33 checks. The latest page-layout/shared-zoom revision passed 55 checks plus 3 final UI rechecks and build/lint and was installed on the Lenovo tablet. See `reports/phase10/PHASE10_DEBUGGING.md`; current APK: `reports/phase10/Notein-phase10-page-layout-debug.apk`. Physical active-pen/palm and tablet acceptance remain pending, so full phase acceptance stays unchecked.
- [ ] Phase 8 implemented and verified.

Phases 1 and 9 have recorded emulator verification results. Preserve those records; they do not establish acceptance of the new Phase 10 requirements. Other unchecked phases retain their documented pending verification or physical-device limitations.
