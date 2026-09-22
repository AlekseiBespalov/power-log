#!/usr/bin/env python3
"""Compose one shareable overview image per platform from the captured screenshots.

Run from the project: python3 artifacts/app-store/source/overview-sheets.py
The sheets scale existing captures into a grid; no app content is composited or retouched.
"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'screenshots')
OUT = os.path.join(ROOT, 'overview')
BACKGROUND = (11, 13, 16)          # the app's own canvas colour
TITLE = (244, 246, 250)
CAPTION = (155, 166, 182)
TITLE_FONT = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc', 44)
CAPTION_FONT = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc', 26)

SHEETS = [
    ('iphone-overview', 'Power Log for iPhone', 'iphone', 4, 470, [
        ('History', '01-history.png'), ('Ride summary', '02-ride-summary.png'), ('Effort', '03-effort-analysis.png'),
        ('Battery', '04-battery.png'), ('Temperature', '05-temperature.png'), ('Layout editor', '06-custom-dashboard.png'),
        ('Settings', '07-settings.png'),
    ]),
    ('web-overview', 'Power Log in the browser', 'web', 4, 430, [
        ('Recording', '01-live-recording.png'), ('History', '02-history.png'), ('Ride summary', '03-ride-summary.png'),
        ('Effort', '04-effort-analysis.png'), ('Battery', '05-battery.png'), ('Temperature', '06-temperature.png'),
        ('Layout editor', '07-custom-dashboard.png'), ('Settings', '08-settings.png'),
    ]),
    ('macos-overview', 'Power Log on a Mac', 'macos', 2, 940, [
        ('Recording', '01-live-recording.png'), ('History', '02-history.png'), ('Ride summary', '03-ride-summary.png'),
        ('Effort', '04-effort-analysis.png'), ('Battery', '05-battery.png'), ('Temperature', '06-temperature.png'),
        ('Layout editor', '07-custom-dashboard.png'), ('Settings', '08-settings.png'),
    ]),
]


def compose(name, title, folder, per_row, width, items):
    images = []
    for caption, filename in items:
        source = Image.open(os.path.join(ROOT, folder, filename)).convert('RGB')
        height = round(source.height * width / source.width)
        images.append((caption, source.resize((width, height), Image.LANCZOS)))
    pad, caption_height, header = 28, 44, 96
    rows = [images[index:index + per_row] for index in range(0, len(images), per_row)]
    row_heights = [max(image.height for _, image in row) + caption_height for row in rows]
    canvas = Image.new('RGB', (per_row * (width + pad) + pad, header + sum(row_heights) + pad * len(rows)), BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, pad + 6), title, fill=TITLE, font=TITLE_FONT)
    y = header
    for row, row_height in zip(rows, row_heights):
        x = pad
        for caption, image in row:
            draw.text((x, y), caption, fill=CAPTION, font=CAPTION_FONT)
            canvas.paste(image, (x, y + caption_height))
            x += width + pad
        y += row_height + pad
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f'{name}.png')
    canvas.save(path, optimize=True)
    print(f'{os.path.relpath(path, ROOT)}  {canvas.size[0]} x {canvas.size[1]}')


for name, title, folder, per_row, width, items in SHEETS:
    compose(name, title, folder, per_row, width, items)
