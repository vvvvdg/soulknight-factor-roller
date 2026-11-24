package com.yqqs.factor;

import java.util.Map;
import java.util.Set;

/**
 * 精确计算「一次刷新命中目标」的概率。
 *
 * 模型（依据 wiki 的 17 种组合 + 各类别因子池）：
 *   1. 系统从 17 种「类别组合」里等概率抽一种；
 *   2. 每种组合的 4 个格子，各自从该类别池里等概率抽一个因子，同一类别内的两格不重复。
 *
 * 于是
 *   总排列数 den = Σ_combo ∏_c P(n_c, k_c)
 *   命中排列数 num = Σ_combo ∏_c [ P(k_c, m_c) · P(n_c - a_c - m_c, k_c - m_c) ]
 * 其中 k_c 是该组合里类别 c 的格子数，n_c 是池大小，m_c 是「要的」里属于 c 的个数，
 * a_c 是「不要的」里属于 c 的个数，P(n,k) 是下降阶乘 n!/(n-k)!。
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

    /** 一次刷新的命中概率；0 表示不可能（比如选了刷不到的因子） */
    static double hitProb(Set<String> want, Set<String> avoid, Map<String, String> tagOf) {
        int[] pool = new int[6];
        for (String t : tagOf.values()) {
            int c = codeOf(t);
            if (c >= 0) pool[c]++;
        }
        int[] m = new int[6];
        int[] a = new int[6];
        for (String w : want) {
            int c = codeOf(tagOf.get(w));
            if (c < 0) return 0;
            m[c]++;
        }
        for (String w : avoid) {
            int c = codeOf(tagOf.get(w));
            if (c >= 0) a[c]++;
        }
        double num = 0, den = 0;
        for (int[] combo : COMBOS) {
            int[] k = new int[6];
            for (int c : combo) k[c]++;
            double dn = 1, nn = 1;
            boolean ok = true;
            for (int c = 0; c < 6; c++) {
                if (k[c] == 0) continue;
                dn *= fall(pool[c], k[c]);
                if (m[c] > k[c]) { ok = false; break; }
                nn *= fall(k[c], m[c]) * fall(pool[c] - a[c] - m[c], k[c] - m[c]);
            }
            if (!ok) continue;
            den += dn;
            num += nn;
        }
        if (den <= 0) return 0;
        return num / den;
    }

    /** 下降阶乘 n·(n-1)·…·(n-k+1) */
    private static double fall(int n, int k) {
        if (k < 0 || n < k) return 0;
        double r = 1;
        for (int i = 0; i < k; i++) r *= (n - i);
        return r;
    }
}