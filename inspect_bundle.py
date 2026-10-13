"""Read-only inspection of the supplied APKM; does not install or run it."""
import io
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'inspection'
OUT.mkdir(exist_ok=True)
sys.stdout.reconfigure(encoding='utf-8')


def u16(data, offset):
    return struct.unpack_from('<H', data, offset)[0]


def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def string_pool(data, start):
    count, flags, strings_start = u32(data, start + 8), u32(data, start + 16), u32(data, start + 20)
    header = u16(data, start + 2)
    result = []
    for i in range(count):
        pos = start + strings_start + u32(data, start + header + 4 * i)
        if flags & 0x100:
            # Each UTF-8 entry stores both UTF-16 and UTF-8 lengths.
            for _ in range(2):
                length = data[pos]
                pos += 1
                if length & 0x80:
                    length = ((length & 0x7f) << 8) | data[pos]
                    pos += 1
            result.append(data[pos:pos + length].decode('utf-8', errors='replace'))
        else:
            length = u16(data, pos)
            pos += 2
            if length & 0x8000:
                length = ((length & 0x7fff) << 16) | u16(data, pos)
                pos += 2
            result.append(data[pos:pos + length * 2].decode('utf-16le', errors='replace'))
    return result


def manifest_elements(data):
    pos, strings, result = u16(data, 2), [], []
    while pos < len(data):
        kind, size = u16(data, pos), u32(data, pos + 4)
        if kind == 1:
            strings = string_pool(data, pos)
        elif kind == 0x102:
            ext = pos + 16
            name = strings[u32(data, ext + 4)]
            attr_start, attr_size, attr_count = u16(data, ext + 8), u16(data, ext + 10), u16(data, ext + 12)
            attrs = {}
            for i in range(attr_count):
                at = ext + attr_start + i * attr_size
                key, raw = strings[u32(data, at + 4)], u32(data, at + 8)
                dtype, value = data[at + 15], u32(data, at + 16)
                if raw != 0xffffffff:
                    value = strings[raw]
                elif dtype == 3:
                    value = strings[value]
                elif dtype == 0x12:
                    value = bool(value)
                elif dtype == 1:
                    value = f'@0x{value:08x}'
                attrs[key] = value
            result.append({'element': name, 'attributes': attrs})
        if size < 8:
            raise ValueError('Invalid binary XML chunk')
        pos += size
    return result


def dex_strings(data):
    count, offset = u32(data, 56), u32(data, 60)
    result = []
    for i in range(count):
        pos = u32(data, offset + i * 4)
        while data[pos] & 0x80:
            pos += 1
        pos += 1
        end = data.index(0, pos)
        result.append(data[pos:end].decode('utf-8', errors='replace'))
    return result


bundle_path = next(ROOT.glob('*.apkm'))
with zipfile.ZipFile(bundle_path) as bundle:
    metadata = json.loads(bundle.read('info.json'))
    with zipfile.ZipFile(io.BytesIO(bundle.read('base.apk'))) as apk:
        entries = apk.infolist()
        elements = manifest_elements(apk.read('AndroidManifest.xml'))
        arsc = apk.read('resources.arsc')
        resources = string_pool(arsc, u16(arsc, 2))
        guide_dir = OUT
        guide_dir.mkdir(parents=True, exist_ok=True)
        guide_path = guide_dir / 'bundled-quick-start-guide.pdf'
        guide_path.write_bytes(apk.read('assets/Quick Start Guide.pdf'))
        from pypdf import PdfReader
        guide = PdfReader(guide_path)
        guide_text = '\n\n'.join(f'PAGE {i + 1}\n{page.extract_text() or ""}' for i, page in enumerate(guide.pages))
        (guide_dir / 'guide-text.txt').write_text(guide_text, encoding='utf-8')
        native_alignment = []
        for entry in entries:
            if entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                lib = apk.read(entry)
                is64 = lib[4] == 2
                phoff = struct.unpack_from('<Q' if is64 else '<I', lib, 32 if is64 else 28)[0]
                phsize, phcount = u16(lib, 54 if is64 else 42), u16(lib, 56 if is64 else 44)
                aligns = []
                for i in range(phcount):
                    at = phoff + i * phsize
                    if u32(lib, at) == 1:
                        aligns.append(struct.unpack_from('<Q' if is64 else '<I', lib, at + (48 if is64 else 28))[0])
                native_alignment.append({'path': entry.filename, 'load_segment_alignment': aligns})
        (OUT / 'resource-strings.txt').write_text('\n'.join(resources), encoding='utf-8')
        pattern = re.compile(r'handwrit|palm|stylus|pressure|lasso|infinite|canvas|backup|restore|sync|export|import|record|audio|search|recognition|layer|hyperlink|split.screen|pdf|eraser|pen.button|ocr|offline|recycle|trash', re.I)
        feature_strings = [s for s in resources if pattern.search(s) and len(s) < 600]
        dex = []
        for entry in entries:
            if re.fullmatch(r'classes\d*\.dex', entry.filename):
                dex.extend(dex_strings(apk.read(entry)))
        app_classes = sorted(set(s for s in dex if s.startswith('Lcom/orion/') and any(w in s.lower() for w in ['backup', 'sync', 'note', 'pdf', 'ink', 'canvas', 'record', 'ocr'])))
        report = {
            'bundle_metadata': metadata,
            'bundle_sha256': hashlib.sha256(bundle_path.read_bytes()).hexdigest(),
            'bundle_size_bytes': bundle_path.stat().st_size,
            'base_apk_size_bytes': bundle.getinfo('base.apk').file_size,
            'manifest': [e for e in elements if e['element'] in ['manifest', 'uses-sdk', 'uses-permission', 'uses-feature', 'application']],
            'native_libraries': [{'path': e.filename, 'size_bytes': e.file_size} for e in entries if e.filename.startswith('lib/')],
            'native_elf_alignment': native_alignment,
            'bundled_guide_pages': len(guide.pages),
            'largest_entries': [{'path': e.filename, 'size_bytes': e.file_size} for e in sorted(entries, key=lambda x: x.file_size, reverse=True)[:20]],
            'feature_resource_strings': feature_strings,
            'selected_app_classes': app_classes[:150],
            'limitations': 'Static inspection only. Resource strings and class names indicate included code/UI, not working functionality or performance.'
        }
        (OUT / 'apk-inspection.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
        print(json.dumps({'guide_pages': len(guide.pages), 'arm64_libraries_under_16kb_alignment': sum(1 for x in native_alignment if '/arm64-v8a/' in x['path'] and any(a < 16384 for a in x['load_segment_alignment'])), 'report': str(OUT / 'apk-inspection.json')}, indent=2))
