"""B 경제 초안의 산술·차종 참조 검사. 제품 엔진/밸런스 시뮬레이션이 아니다."""
import csv
from fractions import Fraction as F
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
rows = list(csv.DictReader((HERE / 'unlock_candidates.csv').open(encoding='utf-8')))
facts = {r['vehicle_id']: r for r in csv.DictReader((REPO / 'docs/data/vehicles.csv').open(encoding='utf-8-sig'))}
assert len(rows) == len({r['vehicle_id'] for r in rows}) == 20
assert [sum(int(r['unlock_shop_level']) == i for r in rows) for i in range(1, 5)] == [8, 6, 4, 2]
assert all(r['vehicle_id'] in facts for r in rows)
assert all('버스' not in facts[r['vehicle_id']]['body_type'] and '트럭' not in facts[r['vehicle_id']]['body_type'] for r in rows)
assert len({r['game_role'] for r in rows if r['unlock_shop_level'] == '1'}) == 5

# 기존 레벨/사고 이력 보정은 VehicleCare와 같은 가산식이다.
def rent(rate, hours, rarity=F(1), level=1, previous_accidents=0, match=F(1), accident=False, insured=False):
    vehicle = F(100 + 2 * (level - 1) - min(3 * previous_accidents, 15), 100)
    loss = (F(3, 4) if insured else F(1, 2)) if accident else F(1)
    value = F(rate) * hours * rarity * vehicle * match * loss
    return value.numerator // value.denominator

assert rent(100, 1) == 100
assert rent(100, 3) == 300
assert rent(100, 6) == 600
assert rent(100, 1, rarity=F(13, 10), match=F(6, 5)) == 156
assert rent(100, 1, accident=True) == 50
assert rent(100, 1, accident=True, insured=True) == 75
assert rent(100, 1, level=20) == 138
assert rent(100, 1, level=20, previous_accidents=5) == 123
assert F(150, 100) * F(13, 10) == F(195, 100)
assert 100 * (F(1, 2) + F(3, 10) * F(15, 2)) == 275

lines = ['차종 참조·개수: 20종, 단계별 8/6/4/2, 모든 ID 일치, 트럭/버스 제외, Lv1 게임 역할 5가지',
         '기본료 100/시간: 1/3/6시간 = 100/300/600',
         '전설×희망 차종 일치 = 156, 사고 = 50, 보험 적용 사고 = 75',
         'Lv20 = 138, 과거 사고 5회·Lv20 = 123',
         '차종 상한 1.5 × 전설 1.3 = 1.95 (나머지 조건 동일)',
         '100/시간을 전 구간 가동 가정한 주차 8시간 가중 총액 = 275 (실제 차량별 수익 예측 아님)']
(HERE / 'calculation_results.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('\n'.join(lines))
