# Compact toolbar verification

Verified on 13 October 2026 using emulator-5554.

- Debug app and instrumentation APKs build successfully.
- `lintDebug` passes with zero errors. Existing project warnings remain.
- Tablet, 1600 × 1000 pixels at 160 dpi: **10 tests passed**.
- Phone, 720 × 1280 pixels at 320 dpi: **5 tests passed**.
- Screenshots visually reviewed at both sizes; the emulator's original display
  size and density were restored after testing.

The checks cover exact icon order, compact toolbar height, horizontal scrolling,
pen and pencil palettes, independent fractional thicknesses, highlighter colour,
settings actions, all four page patterns, page/default-paper dialogs, adding a
page, text insertion, lasso, selection copy/move/delete, undo/redo, zoom locking,
PDF export options, and opening/cancelling photo, image and PDF pickers. Existing
checks also exercise fitting, page navigation, zoom persistence and recreation.

Logs: `build.txt`, `tablet.txt`, `phone.txt`. Screenshots are named
`tablet-*` and `phone-*`. Reproduce with `scripts/test-editor-toolbar.ps1`.

The tests use temporary notebook fixtures. Installation uses `adb install -r`;
application data is preserved. Physical pen and palm hardware were not exercised.
