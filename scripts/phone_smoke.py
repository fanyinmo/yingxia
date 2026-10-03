"""Small, foreground-checked controls for testing only this app on an authorized phone."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
SERIAL = ''
PACKAGE = 'com.local.douyinsaver'

def adb(*args):
    result = subprocess.run([str(ADB), '-s', SERIAL, *args], capture_output=True,
                            encoding='utf-8', errors='replace', timeout=30)
    if result.returncode:
        if args[:3] == ('shell', 'uiautomator', 'dump') and 'UI hierchary dumped to:' in (result.stdout + result.stderr):
            # MuMu reports a nonzero exit even after writing the verified dump.
            adb('shell', 'test', '-s', args[3])
            return result.stdout
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout

def foreground():
    deadline = time.monotonic() + 3
    while True:
        state = adb('shell', 'dumpsys', 'activity', 'activities')
        resumed = [line for line in state.splitlines() if 'topResumedActivity=' in line]
        if any(PACKAGE + '/' in line for line in resumed):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('This app is not foreground; refusing to operate another app.')
        time.sleep(.1)

def snapshot():
    foreground()
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/douyin-saver-ui.xml')
    destination = ROOT / 'work/douyin-saver-ui.xml'
    destination.parent.mkdir(parents=True, exist_ok=True)
    adb('pull', '/data/local/tmp/douyin-saver-ui.xml', str(destination))
    tree = ET.parse(destination)
    nodes = list(tree.iter('node'))
    if not any(n.get('package') == PACKAGE for n in nodes):
        raise RuntimeError('Unexpected UI package.')
    return tree, nodes

def texts(nodes):
    return [n.get('text') for n in nodes if n.get('package') == PACKAGE and n.get('text')]

def center(node):
    values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    if len(values) != 4 or values[0] >= values[2] or values[1] >= values[3]:
        raise RuntimeError('Invalid visible bounds.')
    return (values[0] + values[2]) // 2, (values[1] + values[3]) // 2

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['snapshot', 'tap', 'share', 'swipe', 'screenshot'])
parser.add_argument('--text')
parser.add_argument('--description', help='Exact content description of an accessible control in this app')
parser.add_argument('--url')
parser.add_argument('--name', default='phone-current')
parser.add_argument('--serial', required=True, help='Explicit authorized phone or emulator ADB serial')
parser.add_argument('--adb', type=Path, default=ADB, help='Path to an installed Android SDK adb executable')
args = parser.parse_args()
SERIAL = args.serial
ADB = args.adb
if args.action == 'share':
    if not args.url or not re.fullmatch(r'https://(?:v\.douyin\.com/[A-Za-z0-9_-]+/|www\.(?:douyin|iesdouyin)\.com/(?:share/)?(?:video|note|slides)/\d+/?)', args.url):
        raise RuntimeError('Expected a plain Douyin video URL.')
    print(adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity', '-a',
              'android.intent.action.SEND', '-t', 'text/plain', '--es',
              'android.intent.extra.TEXT', args.url))
elif args.action == 'screenshot':
    foreground()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.name):
        raise RuntimeError('Invalid output name.')
    adb('shell', 'screencap', '-p', '/data/local/tmp/douyin-saver-screen.png')
    output = ROOT / 'outputs/reports' / (args.name + '.png')
    output.parent.mkdir(parents=True, exist_ok=True)
    adb('pull', '/data/local/tmp/douyin-saver-screen.png', str(output))
    print(str(output))
else:
    tree, nodes = snapshot()
    if args.action == 'snapshot':
        print(json.dumps(texts(nodes), ensure_ascii=False, indent=2))
    elif args.action == 'swipe':
        foreground()
        rectangles = [list(map(int, re.findall(r'\d+', n.get('bounds', '')))) for n in nodes
                      if n.get('package') == PACKAGE and n.get('scrollable') == 'true']
        rectangles = [r for r in rectangles if len(r) == 4 and r[2] > r[0] and r[3] > r[1]]
        if not rectangles:
            raise RuntimeError('No visible scrollable area in this app.')
        left, top, right, bottom = max(rectangles, key=lambda r: (r[2] - r[0]) * (r[3] - r[1]))
        # Use the page margin rather than dragging a palette or a slider in the middle.
        x = left + min(24, max(1, (right - left) // 20))
        print(adb('shell', 'input', 'swipe', str(x), str(int(top + (bottom - top) * .85)),
                  str(x), str(int(top + (bottom - top) * .2)), '450'))
    else:
        matches = [n for n in nodes if n.get('package') == PACKAGE and
                   (n.get('content-desc') == args.description if args.description else n.get('text') == args.text)]
        if len(matches) != 1:
            raise RuntimeError(f'Expected one visible matching control, found {len(matches)}.')
        parents = {child: parent for parent in tree.iter() for child in parent}
        node = matches[0]
        while node.get('clickable') != 'true' and node in parents:
            node = parents[node]
        if node.get('package') != PACKAGE or node.get('clickable') != 'true' or node.get('enabled') != 'true':
            raise RuntimeError('Matching control is not enabled/clickable.')
        x, y = center(node)
        foreground()
        adb('shell', 'input', 'tap', str(x), str(y))
        print(f'Tapped {args.description or args.text}')
