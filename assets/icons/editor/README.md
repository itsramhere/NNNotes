# Editor icons

SVG originals from [Lucide](https://github.com/lucide-icons/lucide/tree/main/icons).
The accompanying LICENSE includes the Lucide ISC license and Feather MIT notice.

Toolbar mapping: ink pen → pen-tool, ball pen → pen, pencil → pencil,
eraser → eraser, highlighter → highlighter, lasso → lasso,
lock/unlock → lock-keyhole/lock-keyhole-open, undo/redo → undo-2/redo-2,
PDF → file-text, settings → settings, export → download.

Run `python scripts/convert-editor-icons.py` to regenerate the native Android
VectorDrawables. SVG paths are normalized because Android's path parser requires
explicit separation of SVG arc flags. No runtime image library is required.
