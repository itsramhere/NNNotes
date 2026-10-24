# New notebook picker

The New notebook dialog uses illustrated, selectable cards for Standard pages,
Infinite canvas, and the four paper layouts. A live paper preview sits beside
the form on tablets and appears as a compact summary on phones. The header
and Create/Cancel actions stay visible while the form scrolls.

The picker retains folder selection, all paper patterns, six background
colours, custom hexadecimal colours, A4/A5/Letter sizes, both orientations,
spacing presets and numeric input, line/dot weight, and automatic next pages.
Spacing and weight appear under More paper options. Custom colours have their
own expandable field. Infinite canvas hides page-only settings and retains the
chosen paper settings when switching back to Standard pages.

Creation still uses the existing repository and rejects duplicate submissions.
Cancellation creates no notebook, page, or asset. The editor and its toolbar
are unchanged by this update.

Reproduce with:

```powershell
./scripts/test-notebook-picker.ps1 -Serial emulator-5554
```

The runner checks tablet landscape, tablet portrait, phone light, and phone
dark layouts, collects screenshots, and restores the emulator's display
settings. Test databases are separate from the user's notebooks.

Verified on 24 October 2026:

| Layout | Size / density | Result |
| --- | --- | --- |
| Tablet landscape | 1280 × 800 / 160 dpi | 7 passed |
| Tablet portrait | 800 × 1280 / 160 dpi | 4 passed |
| Phone light | 720 × 1280 / 320 dpi | 4 passed |
| Phone dark | 720 × 1280 / 320 dpi | 4 passed |

All **19 checks passed** on the final APK. The checks cover exact persisted
templates, folders, custom colours, spacing validation and defaults, duplicate
submission prevention, automatic-page settings, retaining choices across
notebook types, finger scrolling, button/outside-tap cancellation, and the
existing library workflows. Both builds and `lintDebug` succeeded with zero
lint errors; the existing 29 warnings and 6 hints remain.

Screenshots reviewed:

- [Tablet creation dialog](../../docs/screenshots/notebook-picker/tablet-landscape-new-notebook.png)
- [Tablet paper choices](../../docs/screenshots/notebook-picker/tablet-landscape-paper-choices.png)
- [Portrait tablet dialog](../../docs/screenshots/notebook-picker/tablet-portrait-new-notebook.png)
- [Phone dialog](../../docs/screenshots/notebook-picker/phone-new-notebook.png)
- [Phone dark paper choices](../../docs/screenshots/notebook-picker/phone-dark-paper-choices.png)

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `68B21BBAB2A290B77FF521ABDCBD6252CB707A8FEBC9D500D123C06D1AC5EA94`.
Tablet installation is deferred at the user's request.
