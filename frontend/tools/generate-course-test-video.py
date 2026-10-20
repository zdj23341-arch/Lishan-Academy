"""Generate original, visibly labelled DEV media; never download course content."""
from pathlib import Path
import hashlib
import os
import subprocess
import sys

sys.path.insert(0, str(Path(os.environ['TEMP']) / 'jxsc-fixture-generator'))
import imageio_ffmpeg

root = Path(__file__).resolve().parent.parent
target = root / 'dev-assets/course-sample.mp4'
target.parent.mkdir(exist_ok=True)
if target.exists():
    raise SystemExit('Existing fixture retained; remove only after explicit asset review.')

subprocess.run([
    imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-loglevel', 'error', '-nostdin',
    '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=10:duration=60',
    '-vf', "drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='DEV TEST ONLY - 60 seconds':fontsize=17:fontcolor=white:box=1:boxcolor=black:x=8:y=8",
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '32', '-pix_fmt', 'yuv420p',
    '-an', '-movflags', '+faststart', '-n', str(target),
], check=True)
print(f'{target}: {target.stat().st_size} bytes')
print('SHA256 ' + hashlib.sha256(target.read_bytes()).hexdigest())
