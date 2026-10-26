#!/usr/bin/env python3
"""Assemble a silent, captioned walkthrough from reviewed native captures and video clips."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('plan', type=Path, help='JSON with source paths, titles, platform labels and durations')
parser.add_argument('output', type=Path, help='New output directory')
parser.add_argument('--font', default='/System/Library/Fonts/Supplemental/Arial.ttf')
args = parser.parse_args()
plan = json.loads(args.plan.read_text())
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
ffmpeg = shutil.which('ffmpeg')
if not ffmpeg:
    raise SystemExit('ffmpeg is required')
clips = []
for index, segment in enumerate(plan['segments']):
    source = (args.plan.parent / segment['source']).resolve(strict=True)
    duration = float(segment['duration'])
    overlay = output / f'{index:02}-labels.png'
    labels = Image.new('RGBA', (1080, 1920))
    draw = ImageDraw.Draw(labels)
    def label(text, y, size, color):
        font = ImageFont.truetype(args.font, size)
        if draw.textlength(text, font=font) > 1000:
            raise ValueError(f'Caption is too wide: {text}')
        draw.text((540, y), text, font=font, fill=color, anchor='mt')
    label('Power Log', 30, 26, '#ff8a2b')
    label(segment['title'], 77, 42, 'white')
    label(f"{plan['version']}  |  Fictional data  |  {segment['platform']}", 1850, 23, '#aab5c2')
    labels.save(overlay)
    clip = output / f'{index:02}.mp4'
    command = [ffmpeg, '-hide_banner', '-loglevel', 'error']
    if source.suffix.lower() == '.png':
        command += ['-loop', '1', '-framerate', '30']
    command += ['-i', str(source), '-i', str(overlay), '-t', str(duration)]
    # Captures retain their aspect ratio and full screen. Text is outside the app frame.
    height = 1200 if segment['platform'] == 'Watch simulator' else 1660
    timing = '' if source.suffix.lower() == '.png' else (
        f'fps=30,trim=start={float(segment["start"])}:duration={duration},setpts=PTS-STARTPTS,'
    )
    if segment.get('holdEndSeconds'):
        timing += f'tpad=stop_mode=clone:stop_duration={float(segment["holdEndSeconds"])},'
    filters = (
        f'[0:v]{timing}scale=940:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,'
        'pad=1080:1920:(ow-iw)/2:150+(1660-ih)/2:color=0x10151c,setsar=1,fps=30[app];'
        '[app][1:v]overlay=0:0:format=auto[v]'
    )
    command += ['-filter_complex', filters, '-map', '[v]', '-an', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '19',
                '-threads', '2', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(clip)]
    subprocess.run(command, check=True)
    clips.append(clip)
    print(f'Rendered {index + 1}/{len(plan["segments"])}: {segment["title"]}', flush=True)
concat = output / 'clips.txt'
concat.write_text(''.join(f"file '{clip.name}'\n" for clip in clips))
subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0',
                '-i', str(concat), '-c', 'copy', '-movflags', '+faststart', str(output / 'PowerLog-demo.mp4')], check=True)
shutil.copy2(args.plan, output / 'source-plan.json')
print(output / 'PowerLog-demo.mp4')
