"""가게 본관 Lv1 / Lv2+ 생성기 — 2차 시안 A의 본관 생김새를 앱 규칙에 맞게 옮긴다.

시안 A 본관: 길고 낮은 크림색 건물, 크게 덮은 하늘색 평지붕(파란 테두리), 지붕 위 회색·흰 설비 상자,
정면 지붕 끝에 선 남색 간판 상자, 정면의 노란 반사 유리창·문·산호색 차양·화단, 모서리 빨간 자판기,
옆면의 파란 가로 창. 정면은 왼쪽 아래(j=j1) 면으로 주차장·도로를 향한다.
Lv2+: 정면이 길어지고, 시안의 주황 지붕 별관(셔터 2개)이 붙고, 간판이 커진다.

실행: python3 make_building.py → main_lv1.png, main_lv2.png, sign_mask_lv1.png, sign_mask_lv2.png,
      building.csv, preview_lv1@2x.png, preview_lv4@2x.png, compare_with_draft.png
"""
import csv
import importlib.util
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
MAP = HERE.parent / "map"
spec = importlib.util.spec_from_file_location("make_map", MAP / "make_map.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
iso, W, H = m.iso, m.W, m.H
C = dict(m.C)
C.update({k: tuple(int(v[i:i + 2], 16) for i in (0, 2, 4)) + (255,) for k, v in {
    "sky": "68a0f0", "glass": "aad6f6", "blue": "346cd6", "navy": "243e8c", "red": "d24040",
    "maroon": "822838", "coral": "f68068", "pink": "f08caa", "orange": "f6aa3c", "orange_dk": "c86e28",
}.items()})
SHADOW = (28, 30, 44, 70)
O = C["outline"]


def poly(d, pts, fill, outline=O):
    d.polygon(pts, fill=fill, outline=outline)


def box(d, i0, i1, j0, j1, z0, z1, top, front, side, outline=O):
    """보이는 세 면: 윗면, 정면(j=j1, 왼쪽 아래), 옆면(i=i1, 오른쪽 아래). 빛이 왼쪽 위 → 윗면 > 정면 > 옆면."""
    poly(d, [iso(i0, j1, z0), iso(i1, j1, z0), iso(i1, j1, z1), iso(i0, j1, z1)], front, outline)
    poly(d, [iso(i1, j0, z0), iso(i1, j1, z0), iso(i1, j1, z1), iso(i1, j0, z1)], side, outline)
    poly(d, [iso(i0, j0, z1), iso(i1, j0, z1), iso(i1, j1, z1), iso(i0, j1, z1)], top, outline)


def on_front(d, j, ia, ib, za, zb, fill, outline=O):
    poly(d, [iso(ia, j, za), iso(ib, j, za), iso(ib, j, zb), iso(ia, j, zb)], fill, outline)


def on_side(d, i, ja, jb, za, zb, fill, outline=O):
    poly(d, [iso(i, ja, za), iso(i, jb, za), iso(i, jb, zb), iso(i, ja, zb)], fill, outline)


def glass_window(d, j, ia, ib, za, zb):
    """시안의 유리창: 밝은 유리 + 노란 반사 점 + 세로 창살."""
    on_front(d, j, ia, ib, za, zb, C["glass"])
    x0, y0 = iso(ia, j, zb)
    x1, y1 = iso(ib, j, za)
    for x in range(int(min(x0, x1)) + 2, int(max(x0, x1)) - 1, 2):
        for y in range(int(min(y0, y1)) + 2, int(max(y0, y1)) - 1, 4):
            t = (x - x0) / max(1, x1 - x0)                  # 창 안쪽인지 평행사변형 기준으로 확인
            top = y0 + t * (iso(ib, j, zb)[1] - y0)
            if top + 2 < y < top + (zb - za) - 2 and (x // 2 + y // 4) % 3 == 0:
                d.point((x, y), fill=C["yellow"])
    mid = (ia + ib) / 2
    d.line([iso(mid, j, za), iso(mid, j, zb)], fill=O)


def flower_box(d, j, ia, ib):
    box(d, ia + .15, ib - .15, j, j + .25, 0, 3, C["grass_sh"], C["pine"], C["pine"])
    k = ia + .2
    n = 0
    while k < ib - .1:
        x, y = iso(k, j + .12, 4)
        d.point((round(x), round(y)), fill=C["pink"] if n % 2 else C["yellow"])
        k += .35
        n += 1


def vending(d, i, j):
    box(d, i, i + .55, j, j + .5, 0, 16, C["coral"], C["red"], C["maroon"])
    on_front(d, j + .5, i + .1, i + .3, 5, 13, C["glass"])


def sign_box(d, md, ia, ib, j, z0, z1):
    """정면 지붕 끝에 선 남색 간판 상자. 정면 판 안쪽이 꾸미기 색 영역이다."""
    box(d, ia, ib, j - .45, j, z0, z1, C["blue"], C["navy"], O)
    inner = [iso(ia + .15, j, z0 + 2), iso(ib - .15, j, z0 + 2), iso(ib - .15, j, z1 - 2), iso(ia + .15, j, z1 - 2)]
    md.polygon(inner, fill=(255, 255, 255, 255))
    return inner   # 좌하, 우하, 우상, 좌상 (글자는 i가 커지는 쪽으로 읽힘)


def render(level):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    mask = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d, md = ImageDraw.Draw(im), ImageDraw.Draw(mask)
    bi0, bi1, bj0, bj1 = m.LOTS["building"]
    i0, j0, j1 = bi0 + 1.2, bj0 + 1.4, bj1 - .4
    i1 = i0 + (6.4 if level == 1 else 7.8)
    wall = 26
    # 바닥 그림자(빛이 왼쪽 위 → 오른쪽 아래로)
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    far = i1 + (2.6 if level >= 2 else 0) + 1.2
    ImageDraw.Draw(sh).polygon([iso(i0, j0), iso(far, j0), iso(far, j1), iso(i0, j1)], fill=SHADOW)
    im.alpha_composite(sh)
    # 본체 벽(크림) + 받침
    box(d, i0, i1, j0, j1, 0, 2, C["grey_lt"], C["grey_lt"], C["metal"])
    box(d, i0, i1, j0, j1, 2, wall, C["sand"], C["sand"], C["dirt"])
    # 정면: 창 - 문(차양) - 창, 화단
    door = i0 + 2.9
    glass_window(d, j1, i0 + .4, door - .2, 5, wall - 4)
    on_front(d, j1, door, door + .8, 2, wall - 6, C["sky"])
    glass_window(d, j1, door + 1, i1 - .4, 5, wall - 4)
    box(d, door - .5, door + 1.3, j1, j1 + .7, wall - 9, wall - 6, C["coral"], C["red"], C["maroon"])   # 차양
    flower_box(d, j1, i0 + .5, door - .25)
    flower_box(d, j1, door + 1.05, i1 - .5)
    # 옆면: 파란 가로 창
    on_side(d, i1, j0 + .5, j1 - .5, 10, 18, C["blue"])
    on_side(d, i1, j0 + .7, j0 + 1.8, 13, 16, C["sky"], None)
    if level >= 2:   # 별관: 시안의 주황 지붕 건물 + 셔터 2개
        a0, a1 = i1, i1 + 2.6
        box(d, a0, a1, j0 + .6, j1, 0, 18, C["sand"], C["sand"], C["dirt"])
        for ia, ib in ((a0 + .2, a0 + 1.2), (a0 + 1.4, a1 - .2)):
            on_front(d, j1, ia, ib, 1, 15, C["grey_lt"])
            for z in range(3, 15, 2):
                d.line([iso(ia, j1, z), iso(ib, j1, z)], fill=C["metal"])
        box(d, a0 - .1, a1 + .2, j0 + .4, j1 + .2, 18, 21, C["orange"], C["orange_dk"], C["orange_dk"])
    # 지붕: 크게 덮은 하늘색 + 파란 테두리
    box(d, i0 - .2, i1 + .2, j0 - .2, j1 + .2, wall, wall + 4, C["sky"], C["blue"], C["blue"])
    # 지붕 설비: 회색 상자, 흰 상자
    rz = wall + 4
    box(d, i0 + 1, i0 + 2.4, j0 + .4, j0 + 1.6, rz, rz + 3, C["hi"], C["grey_lt"], C["metal"])
    box(d, i1 - 2.6, i1 - 1, j0 + 1.6, j0 + 2.9, rz, rz + 3, C["white"], C["hi"], C["grey_lt"])
    if level >= 2:
        box(d, i1 - 4.4, i1 - 3.4, j0 + .4, j0 + 1.2, rz, rz + 4, C["hi"], C["grey_lt"], C["metal"])
    # 간판 상자(정면 지붕 끝)
    sa, sb = (i0 + .2, i0 + 3.8) if level == 1 else (i0 + .2, i0 + 4.8)
    inner = sign_box(d, md, sa, sb, j1 - .3, rz, rz + (12 if level == 1 else 14))
    vending(d, i1 + .1 if level == 1 else i1 + 2.75, j1 - .4)
    bb = im.getbbox()
    return im.crop(bb), mask.crop(bb), bb, inner


def text_on_board(base, inner, text, scale):
    """미리보기용: 간판 자리에 글자를 얹는다(실제로는 앱이 화면 해상도로 그린다)."""
    (bl, br, tr, tl) = [(x * scale, y * scale) for x, y in inner]
    font = ImageFont.truetype("/System/Library/Fonts/AppleSDGothicNeo.ttc", 72, index=6)
    tw, th = 300, 96
    t = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    ImageDraw.Draw(t).text((tw // 2, th // 2), text, font=font, fill=C["yellow"], anchor="mm")
    ux, uy = (tr[0] - tl[0]) / tw, (tr[1] - tl[1]) / tw
    vx, vy = (bl[0] - tl[0]) / th, (bl[1] - tl[1]) / th
    det = ux * vy - uy * vx
    a, b, c_, d_ = vy / det, -vx / det, -uy / det, ux / det
    coeffs = (a, b, -(a * tl[0] + b * tl[1]), c_, d_, -(c_ * tl[0] + d_ * tl[1]))
    base.alpha_composite(t.transform(base.size, Image.AFFINE, coeffs, Image.BICUBIC))


def compose(map_lv, b_lv, built, accent):
    im = Image.open(MAP / "ground.png").convert("RGBA")
    if map_lv < 4:
        im.alpha_composite(Image.open(MAP / f"cover_lv{map_lv}.png").convert("RGBA"))
    b, mask, bb, inner = built[b_lv]
    tinted = b.copy()
    tinted.paste(Image.new("RGBA", b.size, accent), (0, 0), mask)
    im.alpha_composite(tinted, (bb[0], bb[1]))
    m.place_cars(im, map_lv, m.ref_cars())
    big = im.resize((W * 2, H * 2), Image.NEAREST)
    text_on_board(big, inner, "렌터카", 2)
    return big


def main():
    rows, built = [], {}
    for level, name in ((1, "lv1"), (2, "lv2")):
        im, mask, bb, inner = render(level)
        im.save(HERE / f"main_{name}.png")
        mask.save(HERE / f"sign_mask_{name}.png")
        built[level] = (im, mask, bb, inner)
        rows.append([f"main_{name}", bb[0], bb[1], im.width, im.height] + [round(v) for p in inner for v in p])
    with open(HERE / "building.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["sprite", "left", "top", "width", "height",
                    "sign_bl_x", "sign_bl_y", "sign_br_x", "sign_br_y", "sign_tr_x", "sign_tr_y", "sign_tl_x", "sign_tl_y"])
        w.writerows(rows)
    navy = C["navy"]
    draft = Image.open(HERE.parent / "ref-sprites" / "draft_a2_main_native.png").convert("RGB").crop((195, 55, 395, 185))
    mine = compose(1, 1, built, navy)                 # 2배(글자 포함)
    l, t, w_, h_ = rows[0][1:5]
    mine = mine.crop((2 * (l - 15), 2 * (t - 10), 2 * (l + w_ + 15), 2 * (t + h_ + 15))).convert("RGB")
    draft = draft.resize((draft.width * 2, draft.height * 2), Image.NEAREST)
    k = 1
    sheet = Image.new("RGB", ((draft.width + mine.width) * k + 30, max(draft.height, mine.height) * k), "white")
    sheet.paste(draft.resize((draft.width * k, draft.height * k), Image.NEAREST), (0, 0))
    sheet.paste(mine.resize((mine.width * k, mine.height * k), Image.NEAREST), (draft.width * k + 30, 0))
    sheet.save(HERE / "compare_with_draft.png")
    for map_lv, b_lv in ((1, 1), (4, 2)):
        compose(map_lv, b_lv, built, navy).convert("RGB").save(HERE / f"preview_lv{map_lv}@2x.png")


if __name__ == "__main__":
    main()
