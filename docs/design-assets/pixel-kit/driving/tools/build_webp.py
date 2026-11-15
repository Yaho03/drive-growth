"""export-frames.html이 내려받은 PNG 프레임을 앱 자산 WebP로 묶는다.

사용: 이 폴더를 정적 서버로 열고(PNG를 POST로 받아 out/에 저장하는 서버) export-frames.html에서 run(10)을 실행한 뒤
  python3 build_webp.py out ../../../../../app/src/main/assets/pixel/driving
"""
import glob, os, sys
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
for t in ["day", "night"]:
    frames = [Image.open(f).convert("RGB") for f in sorted(glob.glob(f"{src}/rrr-{t}-loop_*.png"))]
    frames[0].save(f"{dst}/rrr-{t}-loop.webp", save_all=True, append_images=frames[1:], lossless=True, method=6, duration=100, loop=0)
    Image.open(f"{src}/rrr-{t}-still_000.png").convert("RGB").save(f"{dst}/rrr-{t}-still.webp", lossless=True, method=6)
    print(t, len(frames), "frames")
