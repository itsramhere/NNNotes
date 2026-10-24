# Inline editor toolbar

The editor now uses one fixed-height toolbar. Selecting an ink tool opens its
colours, thickness slider, and stroke preview on the right. Selecting Lasso
replaces those controls with Copy, Cut, Paste, Delete, Edit text, and Clear.
Selection actions are disabled when there is no applicable selection. The
eraser retains its existing whole-stroke behaviour.

The page counter uses `1/2` formatting and opens a page picker with previews.
Page layout, fit, duplicate, delete, zoom details, and gesture hints live in
Settings. The former options, selection, and page-navigation rows have been
removed, so changing tools does not resize the writing surface.

Tablet layouts reserve the right side for tool controls and scroll the tool
list when needed. Small screens can scroll the complete toolbar horizontally.
Existing one-tap Copy still duplicates the selection. Copy and Cut also make
the selection available to Paste within the open notebook, including across
pages; Cut and Paste participate in Undo and Redo.

Reproduce the build, lint, instrumentation checks, and screenshot collection:

```powershell
./scripts/test-inline-toolbar.ps1 -Serial emulator-5554
```

The runner uses an emulator and restores its size, density, and night mode.
Tests create their own notebook fixtures and never clear application data.

Verified on 23 October 2026:

| Layout | Size / density | Result |
| --- | --- | --- |
| Tablet landscape | 1280 × 800 / 160 dpi | 24 passed |
| Tablet portrait | 800 × 1280 / 160 dpi | 7 passed |
| Phone light | 720 × 1280 / 320 dpi | 7 passed |
| Phone dark | 720 × 1280 / 320 dpi | 7 passed |

All **45 checks passed**. Debug app and instrumentation builds succeeded.
`lintDebug` passed with zero errors; the existing 29 warnings and 6 hints remain.
The checks cover inline control bounds, stable canvas height, independent
fractional thicknesses, colours, highlighter, eraser, tool switching, page picker,
page layout and page actions in Settings, mixed-content selection, Copy, Cut,
Paste across pages, Undo/Redo, saving/reopening, PDF operations, zoom locking,
and handwriting bursts and stale-storage regressions.

Screenshots reviewed:

- [Landscape pencil controls](../../docs/screenshots/inline-toolbar/tablet-landscape-pencil-options.png)
- [Landscape lasso actions](../../docs/screenshots/inline-toolbar/tablet-landscape-lasso-actions.png)
- [Portrait pencil controls](../../docs/screenshots/inline-toolbar/tablet-portrait-pencil-options.png)
- [Portrait lasso actions](../../docs/screenshots/inline-toolbar/tablet-portrait-lasso-actions.png)
- [Phone controls](../../docs/screenshots/inline-toolbar/phone-pencil-options.png)
- [Phone dark controls](../../docs/screenshots/inline-toolbar/phone-dark-pencil-options.png)

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `BB1166F974CFAFE91299BD8ECC1363D1633B1CA9444B7E3B858F0BD4E69DC4EB`.

Installed on the USB-connected TB330XU tablet using `adb install -r`.
The installed APK checksum matches the verified build. Launch returned
`Status: ok` and the application process remained running. Existing application
data was preserved; no uninstall or data-clear operation was performed.
