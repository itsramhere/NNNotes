# Notein

A local-first Android notebook app built with Kotlin, Jetpack Compose, AndroidX Ink, and Room. Create notebooks, write with a stylus, add text and images, annotate PDFs, and export your work. Notes are stored on the device; no account or cloud service is required.

## Features

- Notebook library with folders, search, rename, move, trash, and restore.
- Standard notebooks and infinite canvases.
- Ink pen, ball pen, pencil, highlighter, and stroke eraser.
- Ink colours, live stroke previews, and independent thickness preferences.
- Stylus input, optional finger writing, pan, pinch zoom, and persistent zoom lock.
- Lasso selection, copy, move, resize, delete, and undo/redo for mixed content.
- Editable text boxes and photo or image-file imports.
- Blank, lined, grid, and dotted paper with page-specific and notebook defaults.
- Page creation, duplication, deletion, previews, and horizontal or vertical navigation.
- Local PDF import, annotations, page/notebook export, and infinite-canvas region export.
- Autosave, failed-save retry, and Room migrations that preserve existing notes.
- Material 3 light and dark themes.

## Editor toolbar

The compact toolbar follows this order:

**Ink pen → Ball pen → Pencil → Eraser → Highlighter → Lasso → Lock → Undo → Redo → PDF → Settings → Export**

Tap a pen, pencil, or highlighter to open its ink palette, thickness slider, and live stroke sample. Tap the same tool again or the close button to collapse the panel. The toolbar scrolls horizontally on narrow screens.

Settings provides **Add text**, **Add photo**, **Image file**, **Add page**, **Page type** (Blank, Lined, Grid, Dots), **Page paper**, **Notebook default**, and **Finger writing**. Actions that do not apply to the current page are disabled.

## Requirements

| Component | Project configuration |
| --- | --- |
| Android device | Android 8.0 / API 26 or newer |
| JDK | 25; Java and Kotlin emit Java 17 bytecode |
| Android SDK | Platform 36 and Build Tools 36.0.0 |
| Gradle | 9.3.1, supplied through the wrapper |
| Android Gradle Plugin | 9.1.1 |
| Kotlin / Compose plugin | 2.2.20 |

Dependency versions are pinned in `gradle/libs.versions.toml`. Python is optional for regenerating icons and running the PDF verification helpers.

## Build

Open the project in Android Studio, select JDK 25 for Gradle, and install the required Android SDK components. Configure your SDK path in `local.properties` (excluded from Git):

```properties
sdk.dir=C:/path/to/Android/Sdk
```

Set `JAVA_HOME` to your JDK 25 installation, then build from the project root:

```powershell
./gradlew.bat assembleDebug
```

On macOS or Linux, use `./gradlew assembleDebug`. The first build needs internet access to download Gradle and dependencies. Use `--offline` only after they have been cached.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Install or update

Enable USB debugging on your device, connect it, and use the Android SDK's `adb` command. Replace `DEVICE_SERIAL` with a serial shown by `adb devices`:

```powershell
adb devices
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -n com.ramst.notein/.MainActivity
```

The `-r` option updates an existing installation while preserving its data, provided the package and signing key match. The application ID is `com.ramst.notein`.

## Verification

Build the app and instrumentation tests, and run Android lint:

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
```

For emulator checks on Windows, the PowerShell runners expect SDK tools at `.tools/android-sdk`. Adjust their `adb` path if your SDK is installed elsewhere.

```powershell
./scripts/test-editor-toolbar.ps1 -Serial emulator-5554
./scripts/test-phase10.ps1 -Serial emulator-5554
```

The toolbar runner checks tablet and phone layouts and restores the emulator's original display settings. The phase 10 runner checks editor, ink, PDF, migration, rendering, and persistence behaviour. Earlier phase runners are available in `scripts/`; some older UI checks reflect earlier toolbar layouts. Runners use `adb install -r` and remove their own fixtures rather than clearing application data.

The compact toolbar passed **10 tablet tests and 5 phone tests** on 13 October 2026. Build and lint passed with zero lint errors; existing lint warnings remain. See [toolbar verification](reports/editor-toolbar/VERIFICATION.md) for scope. Emulator tests use synthetic input and do not establish physical stylus latency or palm-rejection performance.

PDF verification uses `scripts/verify-phase6-pdfs.py` with `pypdf`, `pypdfium2`, and Pillow installed.

## Project layout

```text
app/
  src/main/java/com/ramst/notein/
    editor/          Drawing canvas, tools, paper and content controls
    storage/         Room entities, DAOs and repositories
    pdf/             PDF import, rendering and export
    ui/              App UI and theme
  src/main/res/      Android resources and vector icons
  src/androidTest/   Instrumentation tests
  schemas/           Room schemas used by migration tests
assets/icons/editor/ SVG originals, attribution and conversion notes
gradle/              Wrapper and dependency version catalog
scripts/             Build verification and asset helpers
reports/             Implementation and verification notes
```

## Icons and repository hygiene

Toolbar SVGs come from [Lucide](https://github.com/lucide-icons/lucide). The ISC and applicable Feather MIT notices are included in [`assets/icons/editor/LICENSE`](assets/icons/editor/LICENSE) and packaged in the app's assets. Regenerate the Android VectorDrawables with:

```powershell
python scripts/convert-editor-icons.py
```

The `.gitignore` excludes SDK downloads, build outputs, local settings, signing keys, scratch files, reference APK bundles, extracted inspection files, and generated test evidence. Source assets, Room schemas, the Gradle wrapper, and Markdown reports remain eligible for version control. Existing tracked files must be untracked separately for ignore rules to apply.

Implementation history is recorded in `NOTEIN_IMPLEMENTATION_PLAN.md` and the Markdown notes under `reports/`.
