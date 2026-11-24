package com.yqqs.factor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 因子组合规则（wiki 原文）：
 *   · 4 格必须是 17 种类别组合之一；
 *   · 大恶只出在第 1 格，中恶 1~2，小恶 1~3，中性 2~3，良性只出在第 3、4 格；
 *   · 11 组因子互斥，永远不会同时出现。
 *
 * App 用 whyNot() 判断「再加一个因子会不会冲突」（冲突就变灰），
 * 服务用同一个函数在开刷前提前报错。
 */
final class Rules {

    private Rules() { }

    /** 类别代码：0 大恶 / 1 中恶 / 2 小恶 / 3 中性 / 4 小良 / 5 大良 */
    static final int[][] COMBOS = Odds.COMBOS;

    static final String[][] MUTEX = {
            {"巨大化", "缩小隧道"}, {"攻速强化术", "肌肉强化术"},
            {"孤勇者", "宠物巨大化"}, {"冷却减半", "冷却翻倍"},
            {"好运气", "坏运气"}, {"天赋位置+1", "天赋选择+1"},
            {"脆皮", "生命恢复术"}, {"天降神兵", "驯兽师"},
            {"脆皮", "多重人格"}, {"硬壳守护", "多重人格"},
            {"无限能量", "能量衰弱"}
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

    static int[] slotsOf(String tag) {
        if (tag == null) return new int[]{0, 1, 2, 3};
        if (tag.contains("大恶")) return new int[]{0};
        if (tag.contains("中恶")) return new int[]{0, 1};
        if (tag.contains("小恶")) return new int[]{0, 1, 2};
        if (tag.contains("良")) return new int[]{2, 3};
        if (tag.contains("中性")) return new int[]{1, 2};
        return new int[]{0, 1, 2, 3};
    }

    private static boolean like(Set<String> set, String key) {
        for (String s : set) {
            if (s.equals(key) || s.contains(key) || key.contains(s)) return true;
        }
        return false;
    }

    /** 返回 null 表示这组因子可以同时出现；否则返回给用户看的原因 */
    static String whyNot(Set<String> want, Map<String, String> tagOf) {
        for (String[] p : MUTEX) {
            if (like(want, p[0]) && like(want, p[1]))
                return "这两个因子互斥，永远不会同时出现：\n" + p[0] + " / " + p[1];
        }
        List<String> items = new ArrayList<>(want);
        if (items.size() > 4) return "最多同时要求 4 个因子（一局只有 4 格）";
        int n = items.size();
        int[] code = new int[n];
        int[][] slots = new int[n][];
        for (int i = 0; i < n; i++) {
            String t = tagOf.get(items.get(i));
            code[i] = codeOf(t);
            if (code[i] < 0) return "认不出「" + items.get(i) + "」属于哪一类，\n请回 App 重新勾选一次";
            slots[i] = slotsOf(t);
        }
        int[] place = {-1, -1, -1, -1};
        if (place(items, code, slots, 0, place)) return null;
        return "这些因子不可能同时出现:\n" + join(want)
                + "\n大恶只出在第1格，中恶1~2格，\n小恶1~3格，良性只出在第3、4格，\n"
                + "且4格必须是wiki列出的17种组合之一";
    }

    /** 给 toast 用的短原因（第一行） */
    static String shortWhy(String why) {
        if (why == null) return "";
        int i = why.indexOf('\n');
        return i < 0 ? why : why.substring(0, i);
    }

    private static boolean place(List<String> items, int[] code, int[][] slots,
                                 int i, int[] place) {
        if (i == items.size()) return fitsCombo(code, place);
        for (int s : slots[i]) {
            if (place[s] != -1) continue;
            place[s] = i;
            if (place(items, code, slots, i + 1, place)) return true;
            place[s] = -1;
        }
        return false;
    }

    private static boolean fitsCombo(int[] code, int[] place) {
        for (int[] c : COMBOS) {
            boolean ok = true;
            for (int s = 0; s < 4 && ok; s++) {
                if (place[s] >= 0 && c[s] != code[place[s]]) ok = false;
            }
            if (ok) return true;
        }
        return false;
    }

    private static String join(Set<String> it) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : it) {
            if (i++ > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }
}