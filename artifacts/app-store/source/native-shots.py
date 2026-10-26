#!/usr/bin/env python3
"""Capture an already-installed Power Log simulator with fictional rides; never installs or uploads."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('device', help='Explicit booted simulator UDID')
parser.add_argument('output', type=Path, help='New local capture directory')
parser.add_argument('--video', action='store_true')
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--watch', action='store_true')
mode.add_argument('--live', action='store_true', help='Record the simulator-only Lab sensor demo')
mode.add_argument('--all-metrics', action='store_true', help='Record every metric in the simulator-only Lab demo')
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
build = output / 'harness'
build.mkdir()
source_name = 'native-shots.swift'
if args.watch:
    source_name = 'watch-shots.swift'
elif args.all_metrics:
    source_name = 'all-metrics.swift'
elif args.live:
    source_name = 'live-ride.swift'
source = Path(__file__).with_name(source_name)
shutil.copy2(source, build / 'CaptureTests.swift')
env = os.environ.copy()
env.setdefault('BUNDLE_PATH', str(Path.home() / '.cache/power-log/bundle'))

def command(*argv, **kwargs):
    return subprocess.run(argv, cwd=root, env=env, check=True, **kwargs)

# simctl accepts simulator identifiers only; this runner never addresses physical devices.
lab_demo = args.live or args.all_metrics
bundle = 'app.powerlog.lab' if lab_demo else 'app.powerlog.mobile'
if args.watch:
    bundle += '.watchkitapp'
command('xcrun', 'simctl', 'get_app_container', args.device, bundle, 'data', stdout=subprocess.DEVNULL)
ruby = '''require 'xcodeproj'
p = Xcodeproj::Project.new(File.join(ARGV[0], 'Capture.xcodeproj'))
t = p.new_target(:ui_test_bundle, 'CaptureTests', :ios, '26.0')
t.add_file_references([p.main_group.new_file('CaptureTests.swift')])
t.build_configurations.each do |c|
  c.build_settings.merge!({'PRODUCT_BUNDLE_IDENTIFIER'=>'app.powerlog.capture.tests',
    'GENERATE_INFOPLIST_FILE'=>'YES', 'SWIFT_VERSION'=>'5.0',
    'CODE_SIGNING_ALLOWED'=>'NO', 'TARGETED_DEVICE_FAMILY'=>'1,2'})
end
p.save
s = Xcodeproj::XCScheme.new
s.add_build_target(t)
s.add_test_target(t)
s.save_as(p.path, 'Capture', true)
'''
if args.watch:
    ruby = ruby.replace(':ios', ':watchos').replace("'26.0'", "'10.0'").replace("'1,2'", "'4'")
command('bundle', 'exec', 'ruby', '-e', ruby, str(build))
test_arguments = ['-project', str(build / 'Capture.xcodeproj'), '-scheme', 'Capture',
                  '-destination', f'platform={"watchOS" if args.watch else "iOS"} Simulator,id={args.device}',
                  '-derivedDataPath', str(build / 'build'), '-parallel-testing-enabled', 'NO', 'CODE_SIGNING_ALLOWED=NO']
if lab_demo:
    with (output / 'harness-build.log').open('w') as log:
        command('xcodebuild', 'build-for-testing', *test_arguments, stdout=log, stderr=subprocess.STDOUT)
recorder = None
metadata = {'device': args.device, 'fictionalData': True, 'platform': 'native watchOS Simulator' if args.watch else 'native iOS Simulator'}
metadata['bundleIdentifier'] = bundle
try:
    if args.video:
        recorder = subprocess.Popen(['xcrun', 'simctl', 'io', args.device, 'recordVideo', '--codec=h264',
                                     '--mask=ignored', str(output / 'raw-demo.mov')], stderr=subprocess.PIPE, text=True)
        for line in recorder.stderr:
            if 'Recording started' in line:
                metadata['recordingStartedAt'] = time.time()
                break
        if 'recordingStartedAt' not in metadata:
            raise RuntimeError('Simulator video did not start')
    with (output / 'capture.log').open('w') as log:
        command('xcodebuild', 'test-without-building' if lab_demo else 'test', *test_arguments,
                '-resultBundlePath', str(output / 'capture.xcresult'), stdout=log, stderr=subprocess.STDOUT)
finally:
    if recorder:
        recorder.send_signal(signal.SIGINT)
        recorder.communicate(timeout=60)
    metadata['marks'] = []
    log = output / 'capture.log'
    if log.exists():
        for line in log.read_text().splitlines():
            if line.startswith('DEMO_MARK '):
                _, name, timestamp = line.split()
                metadata['marks'].append({'name': name, 'time': float(timestamp)})
    (output / 'capture.json').write_text(json.dumps(metadata, indent=2) + '\n')
container = command('xcrun', 'simctl', 'get_app_container', args.device,
                    'app.powerlog.capture.tests.xctrunner', 'data', capture_output=True, text=True).stdout.strip()
(output / 'screenshots').mkdir()
for mark in metadata['marks']:
    image = Path(container) / 'Documents/captures' / (mark['name'] + '.png')
    if image.exists():
        shutil.copy2(image, output / 'screenshots' / image.name)
command('xcrun', 'swift', str(Path(__file__).with_name('export-rgb.swift')),
        *map(str, sorted((output / 'screenshots').glob('*.png'))))
print(f'Native capture complete: {output}')
