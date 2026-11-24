# -*- coding: utf-8 -*-
"""按 wiki 的 17 种组合 + 各类因子池，算「刷到目标」的期望刷新次数。

模型假设：
  1) 4 格组合在 17 种合法组合里等可能（各 1/17）；
  2) 每个格子从自己类别的池子里等可能抽取，同类两个格子不重复。
"""
import itertools
import json
from fractions import Fraction

IDX = json.load(open(r"C:\Users\sanyi\Documents\dsh\soulknight\因子索引.json", encoding="utf-8"))

POOLS = {
    0: IDX["恶性-大恶"],
    1: IDX["恶性-中恶"],
    2: IDX["恶性-小恶"],
    3: IDX["中性"],
    4: IDX["良性-小良"],
    5: IDX["良性-大良"],
}

COMBOS = [
    (0, 1, 3, 5), (0, 1, 4, 5), (0, 1, 5, 5),
    (0, 2, 3, 5), (0, 2, 4, 5), (0, 2, 5, 5),
    (0, 3, 3, 5), (0, 3, 4, 5), (0, 3, 4, 4),
    (1, 1, 3, 5), (1, 1, 4, 5),
    (1, 2, 3, 5), (1, 2, 4, 5),
    (1, 3, 4, 4), (1, 3, 3, 4),
    (2, 2, 2, 5), (2, 2, 3, 4),
]

# 预计算每个组合的「格子 -> 候选因子」
_slot_cache = {}


def combo_choices(combo):
    if combo in _slot_cache:
        return _slot_cache[combo]
    pools = [POOLS[c] for c in combo]
    out = []
    for choice in itertools.product(*pools):
        if len(set(zip(combo, choice))) != len(combo):
            continue          # 同类两格不能是同一个因子
        out.append(set(choice))
    _slot_cache[combo] = out
    return out


def prob_roll(want=(), avoid=()):
    """单次刷新就满足条件的概率"""
    want, avoid = set(want), set(avoid)
    total = Fraction(0)
    for combo in COMBOS:
        choices = combo_choices(combo)
        hit = sum(1 for s in choices
                  if want <= s and not (avoid & s))
        total += Fraction(hit, len(choices)) * Fraction(1, len(COMBOS))
    return total


def report(want, avoid=(), rate=2.7):
    p = prob_roll(want, avoid)
    e = 1 / p if p else None
    label = " + ".join(want) + (("  避开 " + " / ".join(avoid)) if avoid else "")
    if p == 0:
        print(f"{label:42s} 不可能")
        return
    sec = float(e) / rate
    print(f"{label:42s} P={float(p)*100:7.3f}%  期望 {float(e):9.1f} 次"
          f"  ({sec/60:6.1f} 分钟 @{rate}次/秒)")
    return float(e)


if __name__ == "__main__":
    print("== 池子大小 ==", {k: len(v) for k, v in POOLS.items()})
    print("== 常见目标 ==")
    report(["好运气"])
    report(["无限能量"])
    report(["双倍快乐"])
    report(["天赋异禀"])
    report(["双领主"])
    report(["双领主", "好运气"])
    report(["无限能量", "好运气"])
    report(["天赋异禀", "好运气"])
    report(["生命恢复术", "好运气"])
    report(["双领主", "无限能量"])
    print("== 带排除 ==")
    report(["好运气"], ["坏运气"])
    report(["无限能量"], ["能量衰弱"])
    report(["天赋异禀"], ["坏运气", "能量衰弱"])