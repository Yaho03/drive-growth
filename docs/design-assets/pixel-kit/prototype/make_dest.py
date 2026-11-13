"""마을 그림(town.png)의 색만 바꿔 탁송 도착지(다른 렌터카 가게) 그림 dest-town.png를 만든다.

본관의 파란 지붕·차양을 주황으로 바꾸고 간판 글자(르르렌트카)를 지운다. 새 이름은 시제품이 글자로 얹는다.
"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter
HERE = Path(__file__).parent
im = np.asarray(Image.open(HERE.parent.parent / "town-preview/assets/town.png").convert("RGB")).astype(int)
r, g, b = im[..., 0], im[..., 1], im[..., 2]
blue = (b > r + 35) & (b > g + 15)
dens = np.asarray(Image.fromarray((blue * 255).astype("uint8")).filter(ImageFilter.BoxBlur(9))).astype(int) / 255
yy, xx = np.mgrid[0:1024, 0:1536]
sign = (~blue) & (dens > 0.35) & (xx > 640) & (xx < 925) & (yy > 255) & (yy < 410) & (yy > 0.5 * xx - 110)   # 파란 띠 안의 흰 글씨
im[sign] = np.median(im[blue & (xx > 700) & (xx < 900) & (yy > 300) & (yy < 390)], axis=0).astype(int)
r, g, b = im[..., 0], im[..., 1], im[..., 2]
blue = (b > r + 35) & (b > g + 15)
out = im.copy()
out[..., 0] = np.where(blue, np.clip(b * 1.02, 0, 255), r)
out[..., 1] = np.where(blue, np.clip(g * 0.75 + 20, 0, 255), g)
out[..., 2] = np.where(blue, np.clip(r * 0.9, 0, 255), b)
Image.fromarray(out.astype("uint8")).quantize(colors=128, dither=Image.Dither.NONE).save(HERE / "dest-town.png", optimize=True)   # 색 수를 줄여 파일을 작게
print("dest-town.png")
