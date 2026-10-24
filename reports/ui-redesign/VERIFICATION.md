# UI redesign

The library and editor share a warm ivory and forest-green palette, serif headings, quieter borders, and consistent rounded controls. The dark theme uses deep green surfaces and lighter sage accents. Notebook covers use native vector drawing and a stable colour derived from the notebook ID, so renaming a notebook does not change its cover.

## Layout and behaviour

- The library uses an adaptive notebook grid. Screens at least 840 dp wide gain a persistent folder sidebar; narrower screens retain horizontally scrollable folder filters.
- Phones use a dedicated bottom action bar, so notebook details and empty-state controls remain unobstructed. Cover titles adapt to the available width.
- Search, notebook creation, PDF import, folder creation, rename, move, trash, restore, and confirmed permanent deletion retain their existing handlers.
- Empty libraries, empty folders, empty trash, and unsuccessful searches have distinct states. Empty content scrolls when the viewport or larger text requires more room.
- The editor keeps its tool order and scroll behaviour. Selected tools use the primary green, insertion actions have icons, and ink options use a bordered panel.
- Page controls, save status, paper options, and dialogs use the shared theme. The canvas surround follows the current theme without changing paper colours or exported content.
- The notebook list query also reads the existing notebook type so covers can accurately identify standard notebooks, infinite canvases, and imported PDF documents. No database schema or migration changes are required.
- Existing handwriting, storage, autosave, and gesture changes were preserved.

## Reproduce

```powershell
./scripts/test-ui-redesign.ps1 -Serial emulator-5554
```

The runner builds the debug app and instrumentation APK, runs lint, exercises the tablet layout and phone light/dark themes, saves screenshots, and restores the emulator display and night-mode settings. It updates installations with `adb install -r`. Library regression fixtures use a separate database and files; the runner never clears application data.

Generated screenshots and raw logs are saved beside this report and excluded from Git. Reviewed screenshots are copied to `docs/screenshots/` for the published documentation. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Results — 23 October 2026

The final debug app and test APK built successfully. Android lint passed with **zero errors** (29 existing warnings and 6 hints). `git diff --check` passed.

| Configuration | Result | Coverage |
| --- | --- | --- |
| Phone, 720 × 1280 px / 320 dpi, light | 8 passed | Library workflows and editor toolbar |
| Phone, 720 × 1280 px / 320 dpi, dark | 8 passed | Library workflows and editor toolbar |
| Tablet, 1600 × 1000 px / 160 dpi, light | 16 passed | Library, toolbar, paper configuration, page layout, PDF subset import, recreation, and handwriting regressions |
| **Total** | **32 passed, 0 failed** | |

The library tests cover creation, standard/infinite notebook choices, search, rename, folder moves, no-result recovery, trash, restore, and cancellation/confirmation of permanent deletion. The editor tests cover tool ordering and reachability, independent pen thickness, colours, insertion, undo/redo, zoom lock, page creation and layouts, paper settings, picker cancellation, export choices, and reopening. The existing handwriting regression tests verify that bursts of dots and crossbars and delayed storage snapshots preserve ink.

Screenshots inspected: [tablet library](../../docs/screenshots/ui-redesign/tablet-library.png), [phone library](../../docs/screenshots/ui-redesign/phone-library.png), [dark library](../../docs/screenshots/ui-redesign/phone-dark-library.png), [phone no-results state](../../docs/screenshots/ui-redesign/phone-search-empty.png), and [dark ink options](../../docs/screenshots/ui-redesign/phone-dark-pencil-options.png). All configurations keep their controls reachable and the creation action clear of the content.

Debug APK SHA-256: `F16B81E3E8F22C52076EC4568546A7773C74E0A298F9E077215F1B9CAA561408`.
