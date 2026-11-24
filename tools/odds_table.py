# -*- coding: utf-8 -*-
"""正确模型：17 种组合等概率；组合内每类池子等概率不重复抽。
P = (1/17) * Σ_combo [ ∏_c  fall(k_c,m_c)*fall(n_c-a_c-m_c, k_c-m_c) / fall(n_c,k_c) ]
"""
from itertools import combinations_with_replacement
from fractions import Fraction

NAME = ["大恶", "中恶", "小恶", "中性", "小良", "大良"]
POOL = [6, 14, 8, 15, 7, 9]
COMBOS = [
    [0,1,3,5],[0,1,4,5],[0,1,5,5],[0,2,3,5],[0,2,4,5],[0,2,5,5],
    [0,3,3,5],[0,3,4,5],[0,3,4,4],[1,1,3,5],[1,1,4,5],[1,2,3,5],
    [1,2,4,5],[1,3,4,4],[1,3,3,4],[2,2,2,5],[2,2,3,4],
]

def fall(n, k):
    if k < 0 or n < k:
        return 0
    r = 1
    for i in range(k):
        r *= n - i
    return r

def prob(m, a):
    total = Fraction(0)
    for combo in COMBOS:
        k = [combo.count(c) for c in range(6)]
        ok = True
        f = Fraction(1)
        for c in range(6):
            if k[c] == 0:
                if m[c] > 0:
                    ok = False
                    break
                continue
            if m[c] > k[c]:
                ok = False
                break
            num = fall(k[c], m[c]) * fall(POOL[c] - a[c] - m[c], k[c] - m[c])
            den = fall(POOL[c], k[c])
            f *= Fraction(num, den)
        if ok:
            total += f
    return total / len(COMBOS)

def build(sel):
    m = [0]*6
    for c in sel:
        m[c] += 1
    return m

def show(label, sel, a=None):
    a = a or [0]*6
    p = prob(build(sel), a)
    if p == 0:
        print("%-18s 不可能" % label)
        return
    e = 1/float(p)
    print("%-18s P=%7.4f%%  期望 %7.1f 轮   @3.2/s %6.2f 分钟   @2.7/s %6.2f 分钟"
          % (label, float(p)*100, e, e/3.2/60, e/2.7/60))

print("=== 单个因子（按类别）===")
for c in range(6):
    show(NAME[c], [c])

print()
print("=== 三个因子的所有类别搭配（按期望轮数排序）===")
rows = []
for tags in combinations_with_replacement(range(6), 3):
    p = prob(build(list(tags)), [0]*6)
    if p == 0:
        continue
    rows.append((tags, float(p), 1/float(p)))
rows.sort(key=lambda r: r[2])
for tags, p, e in rows:
    print("%-16s P=%7.4f%%  期望 %7.1f 轮   @3.2/s %6.2f 分钟   @2.7/s %6.2f 分钟"
          % ("+".join(NAME[t] for t in tags), p*100, e, e/3.2/60, e/2.7/60))

print()
print("=== 具名示例 ===")
show("好运气", [4])
show("无限能量", [5])
show("双领主", [0])
show("双领主+好运气", [0, 4])
show("双领主+无限能量", [0, 5])
show("好运气+无限能量", [4, 5])
show("双领主+好运气+无限能量", [0, 4, 5])
show("好运气避坏运气", [4], [0,0,0,0,1,0])