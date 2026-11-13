"""마을 그림(town.png)의 도로·잔디·나무를 잘라 이어 붙여, 같은 그림체의 고속도로 배경(highway.png)을 만든다.

마을 왼쪽 아래 도로는 2:1 사선이라, 먼저 가로로 펴서(y' = y - x/2) 한 구간(점선 1개 = 122px)을 옆으로 이어 붙인 뒤 다시 기울인다.
나무는 구간 3개마다 있어서, 결과는 (366, 183)만큼 옮기면 똑같다. 시제품이 그만큼씩 밀면 끊김 없이 흐른다.
"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
HERE = Path(__file__).parent
town = Image.open(HERE.parent.parent / "town-preview/assets/town.png").convert("RGB")
P = 122                      # 도로 한 구간(점선 간격)
W, H = 1342, 760             # 결과 크기(마을 px). W는 P의 배수
flat = town.transform((700, 330), Image.AFFINE, (1, 0, 0, 0.5, 1, 580), Image.NEAREST)   # 도로를 가로로 편 그림
band = flat.crop((180, 55, 180 + P, 250))        # 위 연석 ~ 아래 연석 한 구간(덤불·가로등 없는 자리)
grass = town.crop((786, 0, 847, 61))           # 꽃·그림자 없는 잔디 조각. 61×61이라 (122, 61)만큼 밀어도 무늬가 맞는다
HU = H + W // 2 + 40
KEY = (255, 0, 255)
u = Image.new("RGB", (W, HU), KEY)
ROAD_Y = 620                                     # 편 그림에서 도로 띠의 위쪽
for x in range(0, W, P): u.paste(band, (x, ROAD_Y))
road = u.transform((W, H), Image.AFFINE, (1, 0, 0, -0.5, 1, W // 2), Image.NEAREST, fillcolor=KEY)   # 다시 2:1로 기울인다
out = Image.new("RGB", (W, H))                   # 잔디는 기울이지 않고 깐다
for x in range(0, W, grass.width):
    for y in range(0, H, grass.height):
        out.paste(grass, (x, y))
_r = np.asarray(road); _m = Image.fromarray(((_r != np.array(KEY)).any(axis=2) * 255).astype("uint8"))
out.paste(road, (0, 0), _m)
# 나무: 마을의 둥근 나무를 부드러운 타원으로 잘라 잔디 위에 (구간 3개마다)
tree = town.crop((612, 2, 742, 140)); mask = Image.new("L", tree.size, 0)
ImageDraw.Draw(mask).ellipse((8, 4, tree.width - 8, tree.height - 2), fill=255); mask = mask.filter(ImageFilter.GaussianBlur(5))
def road_top(x): return ROAD_Y + x / 2 - W // 2      # 그 x에서 위 연석의 y
for k in range(-2, W // (3 * P) + 3):
    x = k * 3 * P
    out.paste(tree, (int(x + 40), int(road_top(x + 40) - 190)), mask)                 # 길 위쪽
    out.paste(tree, (int(x + 200), int(road_top(x + 200) + band.height + 40)), mask)  # 길 아래쪽
out.save(HERE / "highway.png", optimize=True)
print("highway.png", out.size, (HERE / "highway.png").stat().st_size // 1024, "KB", "road top at x=0:", ROAD_Y - W // 2)
