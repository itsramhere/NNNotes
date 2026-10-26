# Continuous pages and table counts

Verified 25 October 2026.

## Behaviour

- Settings > One below another lays out a continuous vertical strip at a common zoom, with a 24 dp gap between sheets. Scrolling can leave parts of preceding and following pages visible.
- Vertical scrolling is bounded by the notebook's first and last edges, rather than the active sheet. A single drag can cross multiple pages, including sheets of different sizes.
- On release, the most visible sheet becomes editable without changing its screen position. The page counter follows the selected sheet. Its exact scroll offset survives saving and reopening.
- Only visible neighbouring sheets receive render views. Their ink, structured objects, media, and PDF backgrounds stay in page coordinates.
- Existing side-by-side page gestures, finger-writing rules, zoom lock, stylus writing, selection, and cancellation remain available.
- Insert > Table now starts with numeric Rows and Columns fields above the live preview. Accepted counts are 1–50 rows and 1–30 columns. Empty, fractional, zero, and out-of-range counts disable Insert.
- Changing counts updates the draft grid and retains cells and dimensions within the remaining rows and columns. The existing individual row/column and whole-table editing controls remain available.

## Checks

`assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` completed successfully. Lint reported zero errors, 35 warnings, and 10 hints. The additional constructor warning concerns Android layout-tool reflection; this custom view is created programmatically by Kotlin. `git diff --check` passed.

Run again with:

```powershell
./scripts/test-continuous-pages.ps1 -Serial emulator-5554
```

| Layout | Checks | Result |
| --- | --- | --- |
| Tablet landscape, 1280 × 800 at 160 dpi | ContinuousPagesUiTest (2), PageSwipeCanvasTest (5), InsertUiTest (5), Phase10EditorTest (10), HandwritingRegressionTest (2) | 24 passed |
| Phone, 720 × 1280 at 320 dpi | ContinuousPagesUiTest (2), InsertUiTest (5) | 7 passed |

The continuity test samples paper pixels before and after release to confirm that changing the editable sheet does not move the paper. It also checks both visible sheet colours, writes to the newly selected page, and reopens it to verify the saved offset and ink. A second test crosses multiple sheets in both directions and checks notebook boundaries. Table tests type counts, reject invalid input, save the requested cell matrix, and exercise subsequent row/column deletion. Existing tests cover table resizing, cancellation, whole-table deletion, headings, graphs, shapes, zoom, page navigation, and handwriting persistence.

Tests use isolated databases and temporary files. The runner restores emulator display and night-mode settings. Raw logs and screenshots under this directory are ignored by Git.

## Screenshots

- [Tablet with adjacent sheets visible](../../docs/screenshots/continuous-pages/tablet-adjacent-pages.png)
- [Tablet table size inputs](../../docs/screenshots/continuous-pages/tablet-table-counts.png)
- [Phone with adjacent sheets visible](../../docs/screenshots/continuous-pages/phone-adjacent-pages.png)
- [Phone table size inputs](../../docs/screenshots/continuous-pages/phone-table-counts.png)

## Tablet update

Installed `app/build/outputs/apk/debug/app-debug.apk` on USB tablet `HA21LTVY` (`TB330XU`) using `adb install -r`. Installation succeeded and `MainActivity` launched successfully. No uninstall, application-data clearing, or database migration was performed.

Local APK and installed `base.apk` SHA-256 both match:

```text
4DF5B2EA02EAF5D3F75824D0FDC0F148350DF6E0366A95DEFACCE8F18730EB3E
```
