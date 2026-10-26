# Horizontal panning on zoomed paper

25 October 2026.

## Cause and behaviour

Finite pages previously reset their horizontal position to the centre in both the native renderer and saved editor state. The side-by-side gesture path also interpreted horizontal movement as paging instead of allowing the user to pan across oversized paper.

- Paper wider than the viewport can now pan left and right with zoom lock on or off. Lock fixes the magnification while leaving panning available.
- Both Side by side and One below another support horizontal panning. Paper stays centred when the available page width fits the viewport.
- In Side by side, a drag that starts within zoomed paper pans to its edge. A new outward drag starting at an edge can turn the page. Fitted-page gestures retain their previous page-turn behaviour.
- Vertical strips share a horizontal offset around the page centre. Bounds use the widest sheet, so differently sized pages remain aligned when the active page changes. The existing continuous vertical scrolling is retained.
- Horizontal offsets are saved, restored after reopening, and retained when zoom lock changes. Viewport resizing bounds the saved position without forcing an oversized page back to its centre.
- Initial fit, Fit page, infinite-canvas navigation, stylus input, the navigation save-status fix, and document-coordinate content remain available.

## Verification

Run again with:

```powershell
./scripts/test-horizontal-pan.ps1 -Serial emulator-5554
```

The new UI checks exercise both layouts with lock enabled and disabled. They verify movement in both directions by measuring a rendered red marker, compare native and saved offsets, reopen the notebook, toggle the lock, and verify that the source object payload stays unchanged. A mixed-width page test compares pixels before and after the active page changes to catch horizontal jumps.

Native gesture checks cover both layouts and lock states, bounds at both paper edges, cancellation, and deliberate page turns from an edge. Existing checks cover fitted pages, pinch zoom, finger writing, vertical continuity, save status, PDFs, persistence, toolbar operations, and handwriting.

`assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed. Lint reported zero errors, 35 warnings, and 10 hints, unchanged from the previous update. `git diff --check` passed.

| Layout | Checks | Result |
| --- | --- | --- |
| Tablet landscape, 1280 × 800 at 160 dpi | HorizontalPanUiTest (5), PageSwipeCanvasTest (7), ContinuousPagesUiTest (4), Phase10EditorTest (10), HandwritingRegressionTest (2), EditorToolbarUiTest (7) | 35 passed |
| Tablet portrait, 800 × 1280 at 160 dpi | HorizontalPanUiTest (5) | 5 passed |
| Phone, 720 × 1280 at 320 dpi | HorizontalPanUiTest (5), ContinuousPagesUiTest (4) | 9 passed |

All 49 checks passed on the final APK. Test fixtures use isolated databases and files; the runner restores emulator display and night-mode settings. Raw logs and screenshots in this directory are ignored by Git.

## Screenshots

- [Tablet: horizontal panning with zoom locked](../../docs/screenshots/horizontal-pan/tablet-horizontal-locked.png)
- [Tablet: vertical page transition retaining the horizontal offset](../../docs/screenshots/horizontal-pan/tablet-vertical-page-handoff.png)
- [Phone: vertical layout with zoom locked](../../docs/screenshots/horizontal-pan/phone-vertical-locked.png)
- [Phone: horizontal layout with zoom unlocked](../../docs/screenshots/horizontal-pan/phone-horizontal-unlocked.png)

## Tablet update

Installed `app/build/outputs/apk/debug/app-debug.apk` on USB tablet `HA21LTVY` (`TB330XU`) using `adb install -r`. Installation succeeded and `MainActivity` launched successfully. No uninstall, data clearing, or database migration was performed.

The local APK and installed `base.apk` SHA-256 match:

```text
1B5441783E344FA51F9908650B6B814FDB69F692610AB1389989E219111D13E5
```
