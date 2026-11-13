"""가게 지도 바닥·레벨별 덮개·칸 좌표 생성기 (2차 시안 A 기준 배치·생김새).

시안 A 배치: 본관은 위쪽에서 정면(왼쪽 아래 면)이 주차장·도로를 향한다. 주차장은 본관 앞,
도로는 왼쪽 아래를 ↘ 방향으로 가로지른다. 주유소는 도로에 붙은 오른쪽 아래, 보험 터는 왼쪽 가운데,
세차장 터는 본관 옆 뒤쪽, 확장 구역은 울타리 친 풀밭이다. 팔레트는 공용 32색, 타일은 32x16.

실행: python3 make_map.py → ground.png, cover_lv1~3.png, slots.csv, lots.csv, preview_lv1~4@2x.png
좌표는 모두 지도 원본(640x464, 앱은 2배) 픽셀 기준이며, 칸 좌표는 차가 놓일 바닥 중심점이다.
"""
import csv
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

HERE = Path(__file__).parent
KIT = HERE.parent
W, H = 640, 464
TW, TH = 32, 16

C = {k: tuple(int(v[i:i + 2], 16) for i in (0, 2, 4)) + (255,) for k, v in {
    "outline": "1c1e2c", "road_dark": "404658", "road": "5c6476", "metal": "7a8294", "grey_lt": "a8b0c0",
    "hi": "d6dce6", "white": "f6f6f2", "forest": "284e40", "pine": "346e48", "grass_sh": "4e9248",
    "tuft": "70ac50", "grass": "86c05a", "yellow": "fcdc64", "wood_dk": "6e4a38", "wood": "a4744e",
    "dirt": "d2aa78", "sand": "e8d6b4",
}.items()}

# ── 배치 (타일 좌표: i는 ↘, j는 ↙ 방향) ──
OX, OY = 304, -70
PI, PJ = 12, 16                 # 주차장 시작 타일
STALL_I, STALL_J = 3, 3         # 칸: 폭 3타일(↘ 방향으로 나란히), 깊이 3타일(↙)
AISLE = 2
ROW_A = PJ                      # 본관 쪽 줄 (j 기준)
ROW_B = PJ + STALL_J + AISLE    # 도로 쪽 줄
LOT_I = (PI - 1, PI + 5 * STALL_I + 1)
LOT_J = (PJ - 1, ROW_B + STALL_J + 1)
ROAD_J = (LOT_J[1] + 2, LOT_J[1] + 5)      # 도로: ↘ 방향으로 화면을 가로지름
CAPACITY = {1: 3, 2: 5, 3: 7, 4: 10}       # PRD: 가게 Lv1~4 주차 칸
LOTS = {                                    # 터: (i0, i1, j0, j1)
    "building": (PI + 3, PI + 13, PJ - 8, PJ - 2),          # 주차장 바로 뒤, 정면이 주차장을 향함
    "fuel_station": (LOT_I[1] + 2, LOT_I[1] + 7, ROW_B, ROW_B + 5),   # 도로에 붙은 오른쪽 아래
    "insurance": (PI - 7, PI - 2, PJ - 4, PJ + 1),          # 왼쪽 가운데
    "car_wash": (PI - 1, PI + 3, PJ - 10, PJ - 6),          # 본관 왼쪽 뒤(시안의 공사 예정 터)
}


def iso(i, j, z=0):
    return OX + (i - j) * TW / 2, OY + (i + j) * TH / 2 - z


def quad(d, i0, i1, j0, j1, fill, z=0):
    d.polygon([iso(i0, j0, z), iso(i1, j0, z), iso(i1, j1, z), iso(i0, j1, z)], fill=fill)


def edge(d, a, b, fill, z=0, width=1):
    d.line([iso(*a, z), iso(*b, z)], fill=fill, width=width)


def slots():
    out = []
    for row in (ROW_A, ROW_B):
        for s in range(5):
            i0 = PI + s * STALL_I
            out.append((i0, i0 + STALL_I, row, row + STALL_J))
    return out   # 0~4: 본관 쪽 줄, 5~9: 도로 쪽 줄 → Lv1 0~2, Lv2 0~4, Lv3 +5,6, Lv4 전부


def dither(d, i0, i1, j0, j1, color, step=2):
    """사각 터 안을 바둑판 점으로 채운다(시안의 보도 타일 질감)."""
    mask = Image.new("1", (W, H), 0)
    ImageDraw.Draw(mask).polygon([iso(i0, j0), iso(i1, j0), iso(i1, j1), iso(i0, j1)], fill=1)
    px = mask.load()
    for y in range(H):
        for x in range(y % step, W, step * 2):
            if px[x, y]:
                d.point((x, y), fill=color)


def ground():
    im = Image.new("RGBA", (W, H), C["grass"])
    d = ImageDraw.Draw(im)
    rnd = random.Random(7)
    for _ in range(1400):                    # 풀 무늬(시안의 짧은 풀잎)
        x, y = rnd.randrange(W), rnd.randrange(H)
        d.point([(x, y), (x + 1, y - 1), (x + 2, y)], fill=C["tuft"])
    # 도로: 양쪽 모래색 보도 + 아스팔트 + 어두운 가장자리 + 노란 점선
    quad(d, -60, 80, ROAD_J[0] - 1, ROAD_J[1] + 1, C["sand"])
    quad(d, -60, 80, *ROAD_J, C["road"])
    edge(d, (-60, ROAD_J[0]), (80, ROAD_J[0]), C["road_dark"])
    edge(d, (-60, ROAD_J[1]), (80, ROAD_J[1]), C["road_dark"])
    mid = (ROAD_J[0] + ROAD_J[1]) / 2
    for k in range(-60, 80, 3):
        edge(d, (k, mid), (k + 1.5, mid), C["yellow"], width=2)
    # 주차장 둘레 보도와 바닥(도로와 같은 아스팔트)
    quad(d, LOT_I[0] - 1, LOT_I[1] + 1, LOT_J[0] - 1, LOT_J[1] + 1, C["sand"])
    quad(d, *LOT_I, *LOT_J, C["road"])
    quad(d, PI - 1, PI + 2, LOT_J[1], ROAD_J[0], C["road"])      # 진입로: 통로 → 도로
    for (i0, i1, j0, j1) in slots():          # 흰 주차선(칸 양옆 + 안쪽 끝)
        edge(d, (i0, j0), (i0, j1), C["white"])
        edge(d, (i1, j0), (i1, j1), C["white"])
        back = j0 if j0 == ROW_A else j1
        edge(d, (i0, back), (i1, back), C["white"])
    # 본관 앞 광장: 모래색 + 흰 타일 점
    bi0, bi1, bj0, bj1 = LOTS["building"]
    quad(d, bi0 - 1, bi1 + 1, bj0 - 1, LOT_J[0], C["sand"])
    dither(d, bi0 + 2, bi1 - 2, bj1, LOT_J[0] - .5, C["white"])
    # 시설 터: 흙 바닥 + 갈색 테두리(시안의 잠긴 터)
    for name, (i0, i1, j0, j1) in LOTS.items():
        if name == "building":
            continue
        quad(d, i0 - .5, i1 + .5, j0 - .5, j1 + .5, C["sand"])
        quad(d, i0, i1, j0, j1, C["dirt"])
        for a, b in (((i0, j0), (i1, j0)), ((i1, j0), (i1, j1)), ((i0, j1), (i1, j1)), ((i0, j0), (i0, j1))):
            edge(d, a, b, C["wood"])
    return im


def fence(d, i0, i1, j0, j1):
    pts = [(i0, j0), (i1, j0), (i1, j1), (i0, j1), (i0, j0)]
    for a, b in zip(pts, pts[1:]):
        for z in (3, 6):
            edge(d, a, b, C["wood"], z)
    posts = {(i, j0) for i in range(i0, i1 + 1)} | {(i, j1) for i in range(i0, i1 + 1)} | \
            {(i0, j) for j in range(j0, j1 + 1)} | {(i1, j) for j in range(j0, j1 + 1)}
    for (i, j) in posts:
        x, y = iso(i, j)
        d.line([(x, y), (x, y - 8)], fill=C["wood_dk"])


def cover(level):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rnd = random.Random(level)
    closed = slots()[CAPACITY[level]:]
    for (i0, i1, j0, j1) in closed:
        quad(d, i0, i1, j0, j1, C["grass"])
        for _ in range(16):
            ci, cj = rnd.uniform(i0 + .3, i1 - .3), rnd.uniform(j0 + .3, j1 - .3)
            x, y = iso(ci, cj)
            d.point([(x, y), (x + 1, y - 1), (x + 2, y)], fill=C["tuft"])
    for row in (ROW_A, ROW_B):              # 닫힌 칸 묶음 둘레 울타리(줄별)
        group = [s for s in closed if s[2] == row]
        if group:
            fence(d, min(s[0] for s in group), max(s[1] for s in group), row, row + STALL_J)
    return im


def anchor(box):
    i0, i1, j0, j1 = box
    return iso((i0 + i1) / 2, (j0 + j1) / 2)


def place_cars(im, level, refs):
    """미리보기용: 칸에 ↙ 방향 참고 차량을 놓는다(뒤에서 앞 순서)."""
    boxes = slots()[:CAPACITY[level]]
    for n, box in sorted(enumerate(boxes), key=lambda t: anchor(t[1])[1]):
        spr = refs[n % len(refs)]
        x, y = anchor(box)
        im.alpha_composite(spr, (round(x - spr.width * 0.58), round(y - spr.height + 14)))


def ref_cars():
    return [ImageOps.mirror(Image.open(KIT / "ref-sprites" / f"{n}_se_ref.png").convert("RGBA"))
            for n in ("car_light", "car_sedan", "car_suv", "car_van", "car_luxury")]


def main():
    g = ground()
    g.save(HERE / "ground.png")
    for lv in (1, 2, 3):
        cover(lv).save(HERE / f"cover_lv{lv}.png")
    with open(HERE / "slots.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["slot", "x", "y", "opens_at_level", "row", "car_facing"])
        for n, box in enumerate(slots()):
            x, y = anchor(box)
            lv = next(l for l in (1, 2, 3, 4) if n < CAPACITY[l])
            a_side = box[2] == ROW_A   # 두 줄 모두 가운데 통로를 보고 선다
            w.writerow([n, round(x), round(y), lv, "building_side" if a_side else "road_side", "sw" if a_side else "ne"])
    with open(HERE / "lots.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["lot", "x", "y", "tiles_i", "tiles_j"])
        for name, box in LOTS.items():
            x, y = anchor(box)
            w.writerow([name, round(x), round(y), box[1] - box[0], box[3] - box[2]])
    refs = ref_cars()
    for lv in (1, 2, 3, 4):
        im = g.copy()
        if lv < 4:
            im.alpha_composite(cover(lv))
        place_cars(im, lv, refs)
        im.convert("RGB").resize((W * 2, H * 2), Image.NEAREST).save(HERE / f"preview_lv{lv}@2x.png")


if __name__ == "__main__":
    main()
