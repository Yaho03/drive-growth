"""제안값의 비교 실험. 제품 로직·현실 주행·플레이 시간 예측이 아니다."""
import csv
import json
import random
from pathlib import Path
from statistics import mean

OUT = Path(__file__).resolve().parent
SEEDS = range(200)
HORIZON = 720.0
TIERS = [('기준', 1000, 100, .25), ('중간', 2000, 130, .225), ('상단', 3000, 150, .20)]
RARITIES = [('일반', 1.0, 1.0), ('전설', 1.3, 1.15)]

def trial(seed, price, rate, demand, rarity, cost_multiplier, accident_probability):
    # 모든 가격·희귀도 비교에 같은 seed의 도착 간격/대여시간/궁합/사고 난수 사용.
    rng = random.Random(seed)
    now = gross = repairs = paid_hours = 0.0
    accidents = completed = 0
    while True:
        wait_unit = rng.expovariate(1.0)
        duration, returning = rng.choice([(1, .25), (3, .5), (6, 1)])
        match = 1.2 if rng.random() < .5 else .8
        crashed = rng.random() < accident_probability
        start = now + wait_unit / demand
        finish = start + duration
        if finish > HORIZON:
            break
        vehicle_bonus = (100 - min(3 * accidents, 15)) / 100
        gross += rate * duration * rarity * vehicle_bonus * match * (.5 if crashed else 1)
        paid_hours += duration
        completed += 1
        if crashed:
            accidents += 1
            repairs += price * .05 * cost_multiplier
        # 비교용 정책: 반납 후 즉시 수리하고 다시 영업. 수리 불가능/판매 정책은 제외.
        now = finish + returning
    return {'gross': gross / HORIZON, 'net': (gross-repairs) / HORIZON,
            'utilization': paid_hours / HORIZON, 'completed': completed}

results=[]
for risk in [.03, .13]:
    for rarity, multiplier, cost in RARITIES:
        base_gross = base_net = None
        for tier, price, rate, demand in TIERS:
            trials=[trial(seed,price,rate,demand,multiplier,cost,risk) for seed in SEEDS]
            gross=mean(x['gross'] for x in trials)
            net=mean(x['net'] for x in trials)
            if base_gross is None: base_gross,base_net=gross,net
            results.append({'risk':risk,'rarity':rarity,'tier':tier,'price':price,'rate':rate,
                            'demand':demand,'gross_per_hour':round(gross,4),'net_per_hour':round(net,4),
                            'gross_ratio':round(gross/base_gross,4),'net_ratio':round(net/base_net,4),
                            'utilization':round(mean(x['utilization'] for x in trials),4)})
with (OUT/'economy_results.csv').open('w',newline='',encoding='utf-8-sig') as f:
    w=csv.DictWriter(f,fieldnames=list(results[0]));w.writeheader();w.writerows(results)
json.dump({'seed_count':len(SEEDS),'normalized_hours_per_seed':HORIZON,
           'input_status':'UNAPPROVED_PROPOSAL', 'results':results},
          (OUT/'economy_results.json').open('w',encoding='utf-8'),ensure_ascii=False,indent=2)
for r in results:
    print(f"위험{r['risk']:.0%} {r['rarity']} {r['tier']}: 총매출/시간 {r['gross_per_hour']}, 순수익/시간 {r['net_per_hour']}, 기준차종 대비 {r['gross_ratio']} / {r['net_ratio']}")
