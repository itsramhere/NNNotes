# Save status during scrolling

25 October 2026.

## Cause and fix

The native canvas included its active finger-navigation pointer in the unfinished-content count. Starting a scroll therefore changed the save badge from Saved to Finishing, even when no ink or content edit was pending.

Navigation now has a separate `navigationInProgress` flag. Finger scrolling and pinch zoom no longer increase `pendingInk`. A shared `hasPendingInput` property keeps navigation, editing, page changes, exports, and back navigation protected while an input gesture is active. The save badge uses pending content rather than the navigation flag. Saving the final viewport after release can still briefly show Saving.

Actual strokes, erasing, and unfinished selection edits retain their content-processing state. Real pen input still reports Finishing ink until its handoff completes. Pointer release, cancellation, zoom-lock changes, and view detachment publish both states.

## Reproduction checks

```powershell
./scripts/test-scroll-status.ps1 -Serial emulator-5554
```

The regression checks hold a scroll gesture open, scroll in both directions, cancel it, and pinch zoom. They assert Saved, zero pending ink, an active navigation flag, disabled back navigation, and no Finishing badge. A separate test draws an actual pen stroke and verifies that pending ink still shows Finishing and eventually becomes Saved.

The continuity checks also verify unchanged page pixels across release, multi-page scrolling, notebook boundaries, writing on the selected page, and the saved viewport after reopening. Existing toolbar, page-navigation, zoom, storage, lasso cancellation, and handwriting tests cover the input guards.

## Results

`assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed. Lint reported zero errors, 35 warnings, and 10 hints, unchanged from the preceding update. `git diff --check` passed.

| Layout | Checks | Result |
| --- | --- | --- |
| Tablet landscape, 1280 × 800 at 160 dpi | ContinuousPagesUiTest (4), PageSwipeCanvasTest (5), EditorToolbarUiTest (7), Phase10EditorTest (10), HandwritingRegressionTest (2), Phase4CanvasTest (1), pending-ink storage regression (1) | 30 passed |
| Phone, 720 × 1280 at 320 dpi | ContinuousPagesUiTest (4) | 4 passed |

The final runs passed all 34 checks on the installed app APK. Earlier pinch assertions needed a wider synthetic gesture to exceed Android's zoom threshold; only the test setup changed during those adjustments. The corrected gesture verifies an actual increase in zoom as well as the save status.

Screenshots capture an active scroll while the badge remains Saved:

- [Tablet](../../docs/screenshots/scroll-status/tablet-scrolling-saved-status.png)
- [Phone](../../docs/screenshots/scroll-status/phone-scrolling-saved-status.png)

Tests use isolated fixtures and retain application data. The runner restores display and night-mode settings. Raw logs and screenshots in this directory are ignored by Git.

## Tablet update

Installed the fix on USB tablet `HA21LTVY` (`TB330XU`) with `adb install -r`. Installation succeeded and `MainActivity` launched successfully. Existing application data was retained; there was no uninstall, data clearing, or database migration.

Local APK and installed `base.apk` SHA-256 both match:

```text
B897FC3DF1A65B8A0C3827854B972A1AE8693A74F21574038052C48C102B6812
```
