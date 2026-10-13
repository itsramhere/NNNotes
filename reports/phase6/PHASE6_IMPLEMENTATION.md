# Phase 6 implementation - 2026-10-12

This records the initial implementation handoff. Subsequent debugging, the latest APK and remaining regression/device checks are recorded in `PHASE6_DEBUGGING.md`.

Status: source implementation delivered. The user explicitly requested implementation without debugging, so builds, test execution, installation, device checks, and APK generation remain pending.

## Implemented scope

- System document-picker PDF import copies the selected file into app-private storage before database creation. The temporary copy is opened with Android `PdfRenderer`; empty, corrupt, unreadable, and encrypted PDFs fail validation before a notebook is created. Failed imports remove only their temporary source files.
- PDF-backed notebooks persist an immutable local source record and one fixed-background page per original PDF page. Existing editable-object persistence remains page-scoped, so handwriting, text, and images stay editable annotations above the PDF background.
- Editor rendering includes a bounded current-page `PdfRenderer` bitmap cache under the existing editable canvas. PDF pages retain their source page dimensions; page-number navigation uses the normal page selector and disables standard-page mutation controls.
- The library exposes a PDF import action. The editor exposes selected-page export for PDF and standard notebooks.
- Export uses an Android `PdfDocument` at 150 DPI and produces flattened output with original PDF backgrounds or paper backgrounds plus ink, text, and image content in insertion order. It snapshots stored page objects before export and keeps only one output bitmap/page in memory at a time.
- Infinite canvases expose a document-space region dialog. Its A4 page-count preview changes as the starting boundary and dimensions change, then exports the selected region as A4 pagination.
- Export writes an internal temporary file first, copies it to the user-selected destination only after all pages finish, reports progress, supports cancellation, and attempts to remove an incomplete destination on failure.

## Storage compatibility

Database version 5 adds a `pdf_documents` table and nullable `pdf_page_index` page column through a forward migration. It does not rewrite existing notebooks, pages, or editable objects.

## Commands and verification

Read-only source and plan inspection used `Get-Content` and `rg`. Source changes used `apply_patch`. No Gradle, compiler, test, lint, emulator, device, build, or debugging command was run. No APK was produced and no Phase 6 completion or runtime-correctness claim is made.

## Pending acceptance

The Phase 6 debugging plan remains pending: PDF type coverage, encrypted/corrupt import behavior, annotation alignment after zoom/reopen, independent export inspection, selected-page and cross-boundary canvas export, 100-page memory profiling, cancellation, and low-storage recovery.
