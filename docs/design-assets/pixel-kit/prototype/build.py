"""template.html에 지도·본관·차 그림(data URI)과 좌표를 넣어 prototype.html을 만든다."""
import base64, csv, json
from pathlib import Path
HERE = Path(__file__).parent; KIT = HERE.parent
def uri(p): return "data:image/png;base64," + base64.b64encode((KIT / p).read_bytes()).decode()
imgs = {"ground": "map/ground.png", "cover_lv1": "map/cover_lv1.png", "cover_lv2": "map/cover_lv2.png", "cover_lv3": "map/cover_lv3.png",
        "main_lv1": "building/main_lv1.png", "main_lv2": "building/main_lv2.png", "person_staff": "ref-sprites/person_staff_se_ref.png",
        **{f"car_{n}": f"ref-sprites/car_{n}_se_ref.png" for n in ("light", "sedan", "suv", "van", "luxury")}}
slots = [{"x": int(r["x"]), "y": int(r["y"])} for r in csv.DictReader(open(KIT / "map/slots.csv"))]
build = {}
for r in csv.DictReader(open(KIT / "building/building.csv")):
    v = {k: int(x) for k, x in r.items() if k != "sprite"}
    build[r["sprite"]] = {"left": v["left"], "top": v["top"], "w": v["width"], "h": v["height"],
        "sign": [[v["sign_bl_x"], v["sign_bl_y"]], [v["sign_br_x"], v["sign_br_y"]], [v["sign_tr_x"], v["sign_tr_y"]], [v["sign_tl_x"], v["sign_tl_y"]]]}
html = (HERE / "template.html").read_text()
for k, p in imgs.items(): html = html.replace("{{" + k + "}}", uri(p))
html = html.replace("{{slots}}", json.dumps(slots)).replace("{{building}}", json.dumps(build))
html = html.replace("{{motion}}", (HERE / "motion.jsx").read_text(encoding="utf-8").replace("</script", "<\\/script"))
assert "{{" not in html.split("<script>")[1][:400] or True
(HERE / "prototype.html").write_text(html)
print(len(html) // 1024, "KB")
