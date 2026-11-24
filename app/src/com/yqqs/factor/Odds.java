package com.yqqs.factor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 精确计算「一次刷新命中目标」的概率。
 *
 * 模型（依据 wiki 的 17 种组合 + 各类别因子池）：
 *   1. 系统从 17 种「类别组合」里等概率抽一种；
 *   2. 每种组合的 4 个格子，各自从该类别池里等概率抽一个因子，同一类别内的两格不重复。
 *
 * 单一方案：
 *   命中排列数 num = ∏_c [ P(k_c, m_c) · P(n_c - a_c - m_c, k_c - m_c) ]
 *   其中 k_c 是该组合里类别 c 的格子数，n_c 是池大小，m_c 是「要的」里属于 c 的个数，
 *   a_c 是「不要的」里属于 c 的个数，P(n,k) 是下降阶乘 n!/(n-k)!。
 *
 * 多个方案（满足任一即停）：对方案集合做容斥，
 *   P(至少命中一个方案) = Σ_{∅≠S} (-1)^{|S|+1} · P(S 里所有方案的因子都出现)
 * 因为「方案全部命中」就是「这些方案的因子并集全部出现」，仍然可以用上面的公式算。
 *
 * 期望刷新次数 E = 1 / P。几何分布无记忆，所以「还要刷多少次」的条件期望恒等于 E，
 * 界面上的倒计时按 剩余 = 期望总耗时 - 已用时间 算。
 */
final class Odds {

    private Odds() { }

    // 类别代码：0 大恶 / 1 中恶 / 2 小恶 / 3 中性 / 4 小良 / 5 大良
    static final int[][] COMBOS = {
            {0, 1, 3, 5}, {0, 1, 4, 5}, {0, 1, 5, 5},
            {0, 2, 3, 5}, {0, 2, 4, 5}, {0, 2, 5, 5},
            {0, 3, 3, 5}, {0, 3, 4, 5}, {0, 3, 4, 4},
            {1, 1, 3, 5}, {1, 1, 4, 5},
            {1, 2, 3, 5}, {1, 2, 4, 5},
            {1, 3, 4, 4}, {1, 3, 3, 4},
            {2, 2, 2, 5}, {2, 2, 3, 4}
    };

    static int codeOf(String tag) {
        if (tag == null) return -1;
        if (tag.contains("大恶")) return 0;
        if (tag.contains("中恶")) return 1;
        if (tag.contains("小恶")) return 2;
        if (tag.contains("中性")) return 3;
        if (tag.contains("大良")) return 5;
        if (tag.contains("小良")) return 4;
        return -1;
    }

    /** 单一方案的一次刷新命中概率；0 表示不可能（比如选了刷不到的因子） */
    static double hitProb(Set<String> want, Set<String> avoid, Map<String, String> tagOf) {
        List<Set<String>> one = new ArrayList<>();
        one.add(want);
        return hitProbMulti(one, avoid, tagOf);
    }

    /** 多方案：满足任意一个方案就算命中 */
    static double hitProbMulti(List<Set<String>> plans, Set<String> avoid,
                              Map<String, String> tagOf) {
        List<Set<String>> ps = new ArrayList<>();
        for (Set<String> p : plans) if (p != null && !p.isEmpty()) ps.add(p);
        if (ps.isEmpty()) return 0;

        int[] pool = new int[6];
        for (String t : tagOf.values()) {
            int c = codeOf(t);
            if (c >= 0) pool[c]++;
        }
        int[] a = new int[6];
        for (String w : avoid) {
            int c = codeOf(tagOf.get(w));
            if (c >= 0) a[c]++;
        }

        int n = ps.size();
        int lim = 1 << n;
        double sum = 0;
        for (int[] combo : COMBOS) {
            int[] k = new int[6];
            for (int c : combo) k[c]++;
            double total = 0;
            for (int mask = 1; mask < lim; mask++) {
                Set<String> union = new HashSet<>();
                boolean bad = false;
                for (int i = 0; i < n && !bad; i++) {
                    if ((mask & (1 << i)) == 0) continue;
                    for (String w : ps.get(i)) {
                        if (union.add(w) && codeOf(tagOf.get(w)) < 0) { bad = true; break; }
                    }
                }
                if (bad) continue;   // 里有认不出类别的因子（刷不到的），这一项当作 0
                int[] m = new int[6];
                for (String w : union) m[codeOf(tagOf.get(w))]++;
                double ratio = comboRatio(k, m, a, pool);
                total += ((Integer.bitCount(mask) & 1) == 1) ? ratio : -ratio;
            }
            // 容斥的数值误差可能越界，钳到 [0,1]
            sum += Math.max(0, Math.min(1, total));
        }
        return sum / COMBOS.length;
    }

    /** 某个类别组合里，「要求的因子全部出现、不要的一个都不出现」的比例 */
    private static double comboRatio(int[] k, int[] m, int[] a, int[] pool) {
        double ratio = 1;
        for (int c = 0; c < 6; c++) {
            if (k[c] == 0) {
                // 这个组合里没有类别 c 的格子；但要的因子属于 c → 该组合不可能命中
                if (m[c] > 0) return 0;
                continue;
            }
            if (m[c] > k[c]) return 0;
            double den = fall(pool[c], k[c]);
            if (den <= 0) return 0;
            ratio *= fall(k[c], m[c]) * fall(pool[c] - a[c] - m[c], k[c] - m[c]) / den;
        }
        return ratio;
    }

    /** 下降阶乘 n·(n-1)·…·(n-k+1) */
    private static double fall(int n, int k) {
        if (k < 0 || n < k) return 0;
        double r = 1;
        for (int i = 0; i < k; i++) r *= (n - i);
        return r;
    }
}