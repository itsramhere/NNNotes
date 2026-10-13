"""Convert the vendored Lucide SVGs to Android VectorDrawables (no dependencies)."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/icons/editor"
TARGET = ROOT / "app/src/main/res/drawable"
COUNTS = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7}
NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?")


def normalize_path(data):
    # SVG allows adjacent arc flags (e.g. '011.704'); Android requires separators.
    result, command, position = [], None, 0
    while position < len(data):
        while position < len(data) and data[position] in " ,\r\n\t":
            position += 1
        if position == len(data):
            break
        if data[position].isalpha():
            command = data[position]
            position += 1
            if command.lower() == "z":
                result.append(command)
                continue
        values = []
        for index in range(COUNTS[command.lower()]):
            while position < len(data) and data[position] in " ,\r\n\t":
                position += 1
            if command.lower() == "a" and index in (3, 4):
                assert data[position] in "01"
                value = data[position]
            else:
                match = NUMBER.match(data, position)
                if not match:
                    raise ValueError(f"Invalid path at {data[position:]}")
                value = match.group()
            position += len(value)
            values.append(value)
        result.append(command + ",".join(values))
        if command in ("m", "M"):
            command = "l" if command == "m" else "L"
    return " ".join(result)


def shape_path(element):
    tag = element.tag.split("}")[-1]
    if tag == "path":
        return normalize_path(element.attrib["d"])
    if tag == "circle":
        x, y, r = (float(element.attrib[key]) for key in ("cx", "cy", "r"))
        return f"M{x-r},{y} a{r},{r},0,1,0,{2*r},0 a{r},{r},0,1,0,{-2*r},0"
    if tag == "rect":
        x, y = float(element.get("x", 0)), float(element.get("y", 0))
        w, h, r = (float(element.get(key, 0)) for key in ("width", "height", "rx"))
        return (f"M{x+r},{y} H{x+w-r} Q{x+w},{y},{x+w},{y+r} V{y+h-r} "
                f"Q{x+w},{y+h},{x+w-r},{y+h} H{x+r} Q{x},{y+h},{x},{y+h-r} "
                f"V{y+r} Q{x},{y},{x+r},{y} Z")
    raise ValueError(f"Unsupported SVG shape: {tag}")


TARGET.mkdir(parents=True, exist_ok=True)
for source in sorted(SOURCE.glob("*.svg")):
    svg = ET.parse(source).getroot()
    paths = "\n".join(
        '    <path android:fillColor="#00000000" android:strokeColor="#FF000000" '
        'android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" '
        f'android:pathData="{shape_path(shape)}" />' for shape in svg
    )
    output = ('<?xml version="1.0" encoding="utf-8"?>\n'
              '<!-- Generated from assets/icons/editor; Lucide ISC/MIT licenses in that directory. -->\n'
              '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
              'android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
              f'{paths}\n</vector>\n')
    (TARGET / f"ic_editor_{source.stem.replace('-', '_')}.xml").write_text(output, encoding="utf-8")
print(f"Converted {len(list(SOURCE.glob('*.svg')))} editor icons.")
