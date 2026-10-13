from pathlib import Path
from io import BytesIO
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from PIL import Image, ImageDraw
from pypdf import PdfReader, PdfWriter

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/androidTest/assets/phase6'
assets.mkdir(parents=True, exist_ok=True)
scan = Image.new('RGB', (1200, 1600), 'white')
draw = ImageDraw.Draw(scan)
draw.rectangle((100, 100, 600, 400), fill=(0, 80, 180))
draw.text((100, 500), 'Scanned page: image background', fill='black')
noise = Image.effect_noise((1600, 1200), 90).convert('RGB')
image_bytes = BytesIO(); noise.save(image_bytes, format='JPEG', quality=90)
image_bytes.seek(0)
sizes = [(595, 842), (842, 595), (612, 792), (720, 540), (400, 600)]
pdf = canvas.Canvas(str(assets / 'mixed.pdf'))
for i, size in enumerate(sizes):
    w, h = size; pdf.setPageSize(size)
    if i == 2:
        pdf.drawImage(ImageReader(scan), 0, 0, w, h)
    elif i == 3:
        pdf.drawImage(ImageReader(image_bytes), 0, 0, w, h)
    pdf.setFillColorRGB(0, .3, .8)
    pdf.rect(20, h - 80, 80, 60, fill=1, stroke=0)
    pdf.setFillColorRGB(0, 0, 0); pdf.setFont('Helvetica-Bold', 20)
    pdf.drawString(120, h - 60, f'PDF fixture page {i + 1}')
    pdf.setFillColorRGB(.8, .2, 0)
    pdf.rect(w - 90, 20, 70, 50, fill=1, stroke=0)
    pdf.showPage()
pdf.save()
pdf = canvas.Canvas(str(assets / 'hundred.pdf'), pagesize=(595, 842))
for i in range(100):
    pdf.drawImage(ImageReader(image_bytes), 0, 0, 595, 842)
    pdf.setFillColorRGB(0, .3, .8); pdf.rect(20, 762, 80, 60, fill=1, stroke=0)
    pdf.setFillColorRGB(1, 1, 1); pdf.setFont('Helvetica-Bold', 20)
    pdf.drawString(120, 782, f'Heavy page {i + 1}')
    pdf.showPage()
pdf.save()
writer = PdfWriter(); writer.append(PdfReader(assets / 'mixed.pdf')); writer.encrypt('phase6-password')
with (assets / 'protected.pdf').open('wb') as output: writer.write(output)
(assets / 'malformed.pdf').write_bytes(b'%PDF-1.7\nThis is corrupt, not a document.\n')
print(f'Created fixtures in {assets}')
