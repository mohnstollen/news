#!/usr/bin/python3
"""Erzeugt die App-Symbole (RSS-Zeichen auf Orange) für Web-App, Android und Linux.
Aufruf: python3 scripts/make-icons.py   (braucht python3-pil)"""
import os
from PIL import Image, ImageChops, ImageDraw

ORANGE = (232, 89, 12, 255)
WHITE = (255, 255, 255, 255)


def symbol(img, s, scale=1.0):
    """RSS-Zeichen im 108er-Raster des Android-Symbols, um die Mitte skaliert."""
    S = img.size[0]
    k = S / 108 * scale
    ox = oy = S / 2 - 54 * k

    def px_(v):
        return ox + v * k

    def py_(v):
        return oy + v * k

    d = ImageDraw.Draw(img)
    d.ellipse([px_(33), py_(63), px_(45), py_(75)], fill=WHITE)
    for r0, r1 in ((20, 28), (34, 42)):
        cx, cy = px_(33), py_(75)
        ring = Image.new('L', (S, S), 0)
        rd = ImageDraw.Draw(ring)
        rd.ellipse([cx - r1 * k, cy - r1 * k, cx + r1 * k, cy + r1 * k], fill=255)
        rd.ellipse([cx - r0 * k, cy - r0 * k, cx + r0 * k, cy + r0 * k], fill=0)
        quad = Image.new('L', (S, S), 0)
        ImageDraw.Draw(quad).rectangle([cx, 0, S, cy], fill=255)
        img.paste(WHITE, (0, 0), ImageChops.multiply(ring, quad))


def rounded(size, scale=1.25):
    big = size * 4
    img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle([big * .07, big * .07, big * .93, big * .93], radius=big * .18, fill=ORANGE)
    symbol(img, big, scale)
    return img.resize((size, size), Image.LANCZOS)


def full(size, scale=1.0):
    """Ohne Rand (maskierbar, Android-Vordergrund auf orangem Hintergrund)."""
    big = size * 4
    img = Image.new('RGBA', (big, big), ORANGE)
    symbol(img, big, scale)
    return img.resize((size, size), Image.LANCZOS)


def foreground(size):
    big = size * 4
    img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    symbol(img, big, 1.0)
    return img.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
icons = os.path.join(root, 'icons')
save(rounded(64), os.path.join(icons, 'favicon.png'))
save(rounded(192), os.path.join(icons, 'icon-192.png'))
save(rounded(512), os.path.join(icons, 'icon-512.png'))
save(full(512, 0.8), os.path.join(icons, 'icon-maskable-512.png'))
save(full(180, 1.0), os.path.join(icons, 'apple-touch-icon.png'))

res = os.path.join(root, 'android', 'app', 'src', 'main', 'res')
if os.path.isdir(res):
    for name, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        d = os.path.join(res, 'mipmap-' + name)
        save(full(px, 1.25), os.path.join(d, 'ic_launcher.png'))
        circle = full(px, 1.25)
        mask = Image.new('L', (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
        circle.putalpha(mask.resize((px, px), Image.LANCZOS))
        save(circle, os.path.join(d, 'ic_launcher_round.png'))
        save(foreground(round(px * 2.25)), os.path.join(d, 'ic_launcher_foreground.png'))
print('Symbole erzeugt')
