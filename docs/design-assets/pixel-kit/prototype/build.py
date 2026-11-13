"""template.html에 마을·차·사람 그림(data URI)과 크기를 넣어 prototype.html을 만든다.

그림은 경민의 마을 시안(docs/design-assets/town-preview/assets)을 그대로 쓴다. 마을 한 장을 층별 키트로
나누는 일은 #229 댓글로 원본을 요청해 둔 상태라, 오면 여기서 바꿔 끼운다.
"""
import base64, json
from pathlib import Path
from PIL import Image
HERE = Path(__file__).parent
TOWN = HERE.parent.parent / "town-preview/assets"
CARS = ["hyundai_casper", "hyundai_casper_electric", "hyundai_venue", "hyundai_kona", "hyundai_avante", "hyundai_sonata", "hyundai_grandeur",
        "hyundai_ioniq5", "hyundai_ioniq6", "hyundai_ioniq9", "hyundai_nexo", "hyundai_tucson", "hyundai_santafe", "hyundai_palisade",
        "hyundai_staria_tourer", "hyundai_staria_lounge", "genesis_g80", "genesis_g90", "genesis_gv70", "genesis_gv80"]
imgs = {"town": "town.png", "guest": "legacy-6b120a461ce7.png", "mechanic": "legacy-c64134e8756b.png", "fuel": "legacy-c64e341a7d95.png",
        **{c: f"{c}_se.png" for c in CARS},
        **{f"walk{i}": f"rururu_se_walk{i}.png" for i in range(1, 9)}, **{f"idle{i}": f"rururu_se_idle{i}.png" for i in (3, 4, 5)}}
uri = lambda p: "data:image/png;base64," + base64.b64encode((TOWN / p).read_bytes()).decode()
IMG = {k: uri(p) for k, p in imgs.items()}
IMG["staff"] = IMG["mechanic"]  # 안내 직원은 정비사 그림을 쓴다(2026-11-13 결정)
IMG["highway"] = "data:image/png;base64," + base64.b64encode((HERE / "highway.png").read_bytes()).decode()  # make_highway.py가 마을 그림에서 만든 고속도로 배경
IMG["dest"] = "data:image/png;base64," + base64.b64encode((HERE / "dest-town.png").read_bytes()).decode()  # make_dest.py가 마을 그림에서 만든 탁송 도착지
SPR = {k: list(Image.open(TOWN / p).size) for k, p in imgs.items()}
html = (HERE / "template.html").read_text(encoding="utf-8")
html = html.replace("{{img}}", json.dumps(IMG)).replace("{{spr}}", json.dumps(SPR))
motion = (HERE / "motion.jsx").read_text(encoding="utf-8") + "\n" + (HERE / "delivery.jsx").read_text(encoding="utf-8")
html = html.replace("{{motion}}", motion.replace("</script", "<\\/script"))
(HERE / "prototype.html").write_text(html, encoding="utf-8")
print(len(html) // 1024, "KB")
