# Insert tools

Settings has separate Settings and Insert tabs. Insert contains Shape, Graph,
Heading, and Table. Existing insertion and page settings remain available.

- Shapes: square, rectangle, triangle, right triangle, circle, ellipse, diamond,
  line, and arrow. Shape size, outline width, colour, and fill are editable.
- Maths graphs: coordinate axes with configurable X/Y ranges, fractional grid
  steps, optional grid and number labels, and editable dimensions. Ranges must
  include zero and use 1–100 intervals per axis. Pens can annotate the graph.
- Headings: H1–H6 with saved heading-level metadata, text, and colour. The Insert
  tab lists headings on the current page; choosing one centres and selects it.
- Tables: cell editing, header row styling, row/column selection, adding and
  deleting rows or columns, individual row heights and column widths, whole-table
  dimensions, and whole-table deletion. A preview lets users select a cell by
  tapping. Save applies the draft as one undoable edit; Cancel preserves the
  previous table. The last row/column cannot be deleted; Delete table removes
  the entire table instead.

Inserted content can be selected, moved, resized with Lasso, copied, cut/pasted
between pages, deleted, and undone/redone. Use Edit shape/graph/heading/table in
the lasso actions, or double-tap the selected object to reopen its editor.
Page previews and PDF export use the same drawing code as the editor. The new
versioned object payloads use the existing editable_objects table; no database
schema change is required. Unknown future object types remain preserved.

Tables support up to 50 rows and 30 columns, with up to 2000 characters per
cell. Text wraps and clips to the chosen cell size; increase row height or
column width to reveal more text. The heading outline covers the current page.

Reproduce:

```powershell
./scripts/test-insert.ps1 -Serial emulator-5554
```

Tests use isolated notebook fixtures. The runner checks tablet landscape,
tablet portrait, phone light, and phone dark, then restores display settings.

Screenshots reviewed:

- [Settings > Insert](../../docs/screenshots/insert/tablet-landscape-insert-menu.png)
- [Coordinate graph](../../docs/screenshots/insert/tablet-landscape-graph-dialog.png)
- [Heading levels](../../docs/screenshots/insert/tablet-landscape-heading-dialog.png)
- [Table editor](../../docs/screenshots/insert/tablet-landscape-table-editor.png)
- [Dark-mode heading preview](../../docs/screenshots/insert/phone-dark-heading-dialog.png)

Verified on 25 October 2026:

| Layout | Size / density | Passing checks |
| --- | --- | --- |
| Tablet landscape | 1280 × 800 / 160 dpi | 18 |
| Tablet portrait | 800 × 1280 / 160 dpi | 4 |
| Phone light | 720 × 1280 / 320 dpi | 4 |
| Phone dark | 720 × 1280 / 320 dpi | 4 |

All **30 checks passed**. Coverage includes UI insertion and editing, independent
table row/column resizing and deletion, whole-table resizing/deletion, cancelling
drafts, heading levels and outline registration, invalid graph input, all nine
shape renderers, exact saved payloads after reopening, cross-page cut/paste,
Undo/Redo, PDF rendering, unknown-object retention, and the existing toolbar and
handwriting regressions.

Debug and instrumentation APK builds and `lintDebug` passed with zero errors.
Lint reports 34 warnings and 10 hints, including style suggestions for KTX
Canvas helpers and primitive Compose state. After visual review, the preview
background was changed to white paper for readable colours in dark mode. The
debug APK was rebuilt and **all four Insert UI tests passed again in dark mode**
on that final build. Screenshot review includes tablet, phone, and dark mode.

Final APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `171F62B6A85E04D373F4F747FB85561B47609C8C8E47A0A3E53A31DD2F8431F4`.

Installed on the USB-connected TB330XU tablet with `adb install -r`. The installed
APK checksum matches the final build, launch returned `Status: ok`, and the
application process was running. No uninstall or data-clear operation was used.
