from pathlib import Path
from pypdf import PdfReader
import pypdfium2 as pdfium
from PIL import Image, ImageChops, ImageStat

root = Path(__file__).resolve().parents[1]
evidence = root / 'reports/phase6/runtime/pdf-evidence'
fixtures = root / 'app/src/androidTest/assets/phase6'

def check(name, dimensions):
    path = evidence / name
    reader = PdfReader(path, strict=True)
    assert len(reader.pages) == len(dimensions), (name, len(reader.pages))
    viewer = pdfium.PdfDocument(path)
    assert len(viewer) == len(dimensions)
    for i, size in enumerate(dimensions):
        page = reader.pages[i]
        assert tuple(float(n) for n in page.mediabox[2:]) == size
        assert not page.get('/Annots'), 'Exports must be flattened'
        images = page['/Resources']['/XObject']
        assert len(images) == 1
        image = next(iter(images.values())).get_object()
        assert image['/Width'] >= int(size[0] * 150 / 72)
        assert image['/Height'] >= int(size[1] * 150 / 72)
        rendered = viewer[i].render(scale=1).to_pil().convert('RGB')
        if name == 'selected.pdf':
            r, g, b = rendered.getpixel((60, 50)); assert b > r + 70
            if i == 0:
                r, g, b = rendered.getpixel((350, 180)); assert g > 200
                r, g, b = rendered.getpixel((190, 170)); assert r < 150, 'Ink coordinate mismatch'
            rendered.save(evidence / f'selected-{i+1}.png')
        elif name == 'canvas.pdf':
            x = 570 if i == 0 else 20
            r, g, b = rendered.getpixel((x, 60)); assert b > r + 70
            if i == 1: assert min(rendered.getpixel((180, 60))) > 245
            rendered.save(evidence / f'canvas-{i+1}.png')
        elif name == 'snapshot.pdf' and i == 1:
            r, g, b = rendered.getpixel((80, 80)); assert b > r + 70
    print(f'{name}: strict PDF structure, independent viewer, dimensions, page count, flattening and 150 DPI passed')

check('selected.pdf', [(842., 595.), (400., 600.), (595., 842.)])
check('canvas.pdf', [(595., 842.)] * 2)
check('snapshot.pdf', [(595., 842.)] * 2)
check('canvas-four.pdf', [(595., 842.)] * 4)
check('restarted.pdf', [(842., 595.)])
check('hundred-export.pdf', [(595., 842.)] * 100)

# Source background comparison excludes the intentionally annotated region on page 2.
source = pdfium.PdfDocument(fixtures / 'mixed.pdf')
output = pdfium.PdfDocument(evidence / 'selected.pdf')
for output_index, source_index in enumerate([1, 4, 0]):
    original = source[source_index].render(scale=1).to_pil().convert('RGB')
    actual = output[output_index].render(scale=1).to_pil().convert('RGB')
    region = (10, 10, original.width - 10, 90)
    difference = ImageStat.Stat(ImageChops.difference(original.crop(region), actual.crop(region)))
    assert max(difference.mean) < 12, difference.mean
    print(f'Page {output_index + 1} source title/colors average difference: {difference.mean}')
print('Independent PDF verification passed. No interactive objects, watermark resources, or extra pages.')
