from pathlib import Path
import os
import sys

sys.path.insert(0, str(Path(os.environ['TEMP']) / 'jxsc-fixture-generator'))
import imageio_ffmpeg

asset = Path(__file__).resolve().parent.parent / 'dev-assets/course-sample.mp4'
frames = imageio_ffmpeg.read_frames(str(asset), pix_fmt='rgb24')
meta = next(frames)
count = sum(1 for _ in frames)
assert meta['size'] == (320, 180), meta
assert meta['fps'] == 10, meta
assert count == 600, count
assert abs(meta['duration'] - 60) < 0.1, meta
print(f'PASS: decoded {count} frames; {meta}')
