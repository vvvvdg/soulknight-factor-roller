package com.yqqs.factor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    static final String P = "cfg";
    static final int MODE_WANT = 0, MODE_AVOID = 1;
    static final int MAX_PLANS = 4;

    private final List<String[]> items = new ArrayList<>();   // id, name, tag, desc
    private final List<Integer> shown = new ArrayList<>();    // items 里要显示的索引
    private final Map<String, Bitmap> icons = new HashMap<>();
    private final Map<String, String> tagOf = new HashMap<>();   // 因子名 → 类别，规则判断用
    private final Set<String> blocked = new HashSet<>();         // 和当前已选冲突、置灰不可选
    private Set<String> want = new HashSet<>();      // 指向 plans.get(planIdx)，编辑的就是当前方案
    private final Set<String> avoid = new HashSet<>();
    private final List<Set<String>> plans = new ArrayList<>();   // 多个目标组合，满足任一即停
    private int planIdx = 0;
    private int mode = MODE_WANT;
    private boolean showExtra = false;   // 是否显示「特殊/周挑战」这类刷不到的因子

    private GridView grid;
    private TextView status, hint, editorTitle;
    private Button bWant, bAvoid, bExtra;
    private LinearLayout listRoot, editorRoot, planList;
    private View current;
    private FactorAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadFactors();
        loadIcons();

        SharedPreferences sp = getSharedPreferences(P, MODE_PRIVATE);
        avoid.addAll(sp.getStringSet("avoid", new HashSet<String>()));
        int pc = sp.getInt("planCount", 0);
        for (int i = 0; i < pc && i < MAX_PLANS; i++) {
            plans.add(new HashSet<>(sp.getStringSet("plan" + i, new HashSet<String>())));
        }
        if (plans.isEmpty()) {   // 兼容旧版本：以前的单个「要的」当作方案 1
            plans.add(new HashSet<>(sp.getStringSet("want", new HashSet<String>())));
        }
        want = plans.get(0);

        buildListScreen();
        buildEditorScreen();
        setScreen(listRoot);

        rebuildShown();
        rebuildPlanList();
        refreshModeButtons();
        refreshEditor();
    }

    // ---------- 两个界面 ----------

    private void setScreen(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        setContentView(v);
        current = v;
    }

    /** 一级界面：方案列表 */
    private void buildListScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#11141A"));
        root.setPadding(dp(10), dp(12), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("元气骑士 · 刷挑战因子");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView credit = new TextView(this);
        credit.setText("by 1carvone  ·  v2.5");
        credit.setTextColor(Color.parseColor("#7A8399"));
        credit.setTextSize(10);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(1), 0, dp(2));
        root.addView(credit);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#8A93A6"));
        status.setTextSize(11);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(6));
        root.addView(status);

        LinearLayout perms = new LinearLayout(this);
        perms.setOrientation(LinearLayout.HORIZONTAL);
        Button bAcc = smallBtn("无障碍权限");
        bAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button bOverlay = smallBtn("悬浮窗权限");
        bOverlay.setOnClickListener(v -> requestOverlay());
        Button bTest = smallBtn("测试定位");
        bTest.setOnClickListener(v -> {
            if (FactorService.instance == null) {
                toast("先开启无障碍服务");
                return;
            }
            toast("正在定位，稍等…");
            moveTaskToBack(true);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> FactorService.instance.testOnce(), 800);
        });
        perms.addView(bAcc, weight());
        perms.addView(bOverlay, weight());
        perms.addView(bTest, weight());
        root.addView(perms);

        TextView listTitle = new TextView(this);
        listTitle.setText("目标方案（满足任意一组就停）");
        listTitle.setTextColor(Color.parseColor("#8A93A6"));
        listTitle.setTextSize(11);
        listTitle.setGravity(Gravity.LEFT);
        listTitle.setPadding(dp(2), dp(10), 0, dp(4));
        root.addView(listTitle);

        planList = new LinearLayout(this);
        planList.setOrientation(LinearLayout.VERTICAL);
        root.addView(planList);

        Button bAdd = smallBtn("+ 新建方案");
        bAdd.setBackgroundColor(Color.parseColor("#232F3A"));
        LinearLayout.LayoutParams aw = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aw.setMargins(dp(2), dp(4), dp(2), dp(4));
        bAdd.setOnClickListener(v -> addPlan());
        root.addView(bAdd, aw);

        TextView spacer = new TextView(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        Button bStart = new Button(this);
        bStart.setText("显示悬浮窗");
        bStart.setTextColor(Color.WHITE);
        bStart.setBackgroundColor(Color.parseColor("#1F8A4C"));
        bStart.setOnClickListener(v -> start());
        Button bStop = new Button(this);
        bStop.setText("停止");
        bStop.setTextColor(Color.WHITE);
        bStop.setBackgroundColor(Color.parseColor("#8A2B2B"));
        bStop.setOnClickListener(v -> {
            if (FactorService.instance != null) FactorService.instance.stopLoop();
            toast("已停止");
        });
        LinearLayout.LayoutParams lw = weight();
        lw.topMargin = dp(8);
        bottom.addView(bStart, lw);
        bottom.addView(bStop, weight());
        root.addView(bottom);

        TextView sign = new TextView(this);
        sign.setText("开发者 1carvone · github.com/vvvvdg/soulknight-factor-roller");
        sign.setTextColor(Color.parseColor("#5A6273"));
        sign.setTextSize(9);
        sign.setGravity(Gravity.CENTER);
        sign.setPadding(0, dp(6), 0, 0);
        root.addView(sign);

        listRoot = root;
    }

    /** 二级界面：改某组方案里的因子 */
    private void buildEditorScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#11141A"));
        root.setPadding(dp(10), dp(10), dp(10), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        Button bBack = smallBtn("← 返回");
        bBack.setOnClickListener(v -> backToList());
        editorTitle = new TextView(this);
        editorTitle.setTextColor(Color.WHITE);
        editorTitle.setTextSize(13);
        editorTitle.setGravity(Gravity.CENTER);
        head.addView(bBack, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        head.addView(editorTitle, weight());
        root.addView(head);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        bWant = smallBtn("① 选「要的」");
        bWant.setOnClickListener(v -> {
            if (mode != MODE_WANT) {
                mode = MODE_WANT;
                refreshModeButtons();
                adapter.notifyDataSetChanged();
            }
        });
        bAvoid = smallBtn("② 选「不要的」");
        bAvoid.setOnClickListener(v -> {
            if (mode != MODE_AVOID) {
                mode = MODE_AVOID;
                refreshModeButtons();
                adapter.notifyDataSetChanged();
            }
        });
        LinearLayout.LayoutParams mw = weight();
        mw.topMargin = dp(6);
        modes.addView(bWant, mw);
        modes.addView(bAvoid, weight());
        root.addView(modes);

        // 刷不到的那 18 个（周挑战 8 + 特殊 10）默认不显示，避免误选
        bExtra = smallBtn("显示特殊/周挑战（18 个，刷不到）");
        bExtra.setTextSize(11);
        bExtra.setOnClickListener(v -> {
            showExtra = !showExtra;
            bExtra.setText(showExtra ? "只显示可刷的 59 个" : "显示特殊/周挑战（18 个，刷不到）");
            rebuildShown();
            adapter.notifyDataSetChanged();
            updateHint();
        });
        LinearLayout.LayoutParams ew = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ew.setMargins(dp(2), dp(2), dp(2), dp(2));
        root.addView(bExtra, ew);

        hint = new TextView(this);
        hint.setTextColor(Color.parseColor("#6FC08F"));
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(4));
        root.addView(hint);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setBackgroundColor(Color.parseColor("#0C0E13"));
        adapter = new FactorAdapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        editorRoot = root;
    }

    private void openPlan(int idx) {
        planIdx = idx;
        want = plans.get(idx);
        mode = MODE_WANT;
        refreshModeButtons();
        refreshEditor();
        setScreen(editorRoot);
    }

    private void openAvoid() {
        mode = MODE_AVOID;
        refreshModeButtons();
        refreshEditor();
        setScreen(editorRoot);
    }

    private void backToList() {
        mode = MODE_WANT;
        save();
        refreshModeButtons();
        rebuildPlanList();
        onResume();
        setScreen(listRoot);
    }

    @Override
    public void onBackPressed() {
        if (current == editorRoot) backToList();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        SharedPreferences.Editor e = getSharedPreferences(P, MODE_PRIVATE).edit();
        e.putInt("planCount", plans.size());
        for (int i = 0; i < plans.size(); i++) {
            e.putStringSet("plan" + i, new HashSet<>(plans.get(i)));
        }
        e.putStringSet("avoid", new HashSet<>(avoid));
        e.apply();
    }

    /** 一级界面的方案列表 */
    private void rebuildPlanList() {
        if (planList == null) return;
        planList.removeAllViews();
        for (int i = 0; i < plans.size(); i++) {
            final int idx = i;
            Set<String> p = plans.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(roundRect("#161A22", p.isEmpty() ? "#2A3242" : "#2F7D4F"));
            LinearLayout.LayoutParams rw = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rw.setMargins(dp(2), dp(3), dp(2), dp(3));
            row.setPadding(dp(10), dp(8), dp(6), dp(8));

            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText("方案 " + (i + 1) + (p.isEmpty() ? "　（未选）" : ""));
            t1.setTextColor(p.isEmpty() ? Color.parseColor("#7A8399") : Color.WHITE);
            t1.setTextSize(13);
            TextView t2 = new TextView(this);
            t2.setText(p.isEmpty() ? "点这一行进去选因子" : joinNames(p));
            t2.setTextColor(Color.parseColor("#7BE3A8"));
            t2.setTextSize(11);
            inner.addView(t1);
            inner.addView(t2);
            inner.setOnClickListener(v -> openPlan(idx));
            row.setOnClickListener(v -> openPlan(idx));
            row.addView(inner, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button bDel = smallBtn("删");
            bDel.setTextSize(11);
            bDel.setOnClickListener(v -> delPlan(idx));
            LinearLayout.LayoutParams dw = new LinearLayout.LayoutParams(dp(52),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            dw.setMargins(dp(4), 0, 0, 0);
            row.addView(bDel, dw);

            planList.addView(row, rw);
        }

        // 全局「不要的」入口
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(roundRect("#161A22", avoid.isEmpty() ? "#2A3242" : "#8A3B3B"));
        LinearLayout.LayoutParams rw = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rw.setMargins(dp(2), dp(8), dp(2), dp(3));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("不要的因子（全局）");
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(13);
        TextView t2 = new TextView(this);
        t2.setText(avoid.isEmpty() ? "没设 · 出现了就继续刷" : joinNames(avoid));
        t2.setTextColor(Color.parseColor("#F08A8A"));
        t2.setTextSize(11);
        inner.addView(t1);
        inner.addView(t2);
        inner.setOnClickListener(v -> openAvoid());
        row.setOnClickListener(v -> openAvoid());
        row.addView(inner, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        planList.addView(row, rw);
    }

    private GradientDrawable roundRect(String fill, String stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(Color.parseColor(fill));
        g.setStroke(dp(1), Color.parseColor(stroke));
        return g;
    }

    // ---------- 方案增删 ----------

    /** 编辑当前方案后刷新二级界面 */
    private void refreshEditor() {
        if (editorTitle != null) {
            editorTitle.setText(mode == MODE_AVOID ? "不要的因子（全局）"
                    : "方案 " + (planIdx + 1) + " / " + plans.size());
        }
        rebuildBlocked();
        adapter.notifyDataSetChanged();
        updateHint();
        onResume();
    }

    /** 和当前方案已选的因子冲突（互斥 / 凑不出合法组合 / 超过 4 格）的，置灰不可选 */
    private void rebuildBlocked() {
        blocked.clear();
        for (String[] it : items) {
            String n = it[1];
            if (want.contains(n) || !rollable(it[2])) continue;
            Set<String> t = new HashSet<>(want);
            t.add(n);
            if (Rules.whyNot(t, tagOf) != null) blocked.add(n);
        }
    }

    private void addPlan() {
        if (plans.size() >= MAX_PLANS) { toast("最多 " + MAX_PLANS + " 组方案"); return; }
        plans.add(new HashSet<String>());
        planIdx = plans.size() - 1;
        want = plans.get(planIdx);
        save();
        rebuildPlanList();
        onResume();
        openPlan(planIdx);
        toast("已加方案 " + (planIdx + 1) + "，点图标往里选");
    }

    private void delPlan(int idx) {
        if (plans.size() <= 1) {
            plans.get(0).clear();
            save();
            rebuildPlanList();
            onResume();
            return;
        }
        plans.remove(idx);
        if (planIdx >= plans.size()) planIdx = plans.size() - 1;
        want = plans.get(planIdx);
        save();
        rebuildPlanList();
        onResume();
    }

    /** 依据 showExtra 重建「显示哪些因子」的索引表 */
    private void rebuildShown() {
        shown.clear();
        for (int i = 0; i < items.size(); i++) {
            if (showExtra || rollable(items.get(i)[2])) shown.add(i);
        }
    }

    /** 这 6 类才是刷新能刷到的；周挑战靠远程联机单独开启，特殊因子来自隐藏场景/活动 */
    static boolean rollable(String tag) {
        return tag.startsWith("恶性") || tag.equals("中性") || tag.startsWith("良性");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(accOn() ? "无障碍 ✓" : "无障碍 ✗");
        sb.append("   ");
        sb.append(Settings.canDrawOverlays(this) ? "悬浮窗 ✓" : "悬浮窗 ✗");
        sb.append("   ");
        int nonEmpty = 0;
        for (Set<String> p : plans) if (!p.isEmpty()) nonEmpty++;
        sb.append("方案 ").append(nonEmpty).append("/").append(plans.size())
                .append(" 组 · 不要 ").append(avoid.size());
        sb.append("  图标 ").append(icons.size()).append("/").append(items.size());
        String ie = IconPack.error();
        if (!ie.isEmpty()) sb.append(" (").append(ie).append(")");
        String ex = expectText();
        if (!ex.isEmpty()) sb.append("\n").append(ex);
        status.setText(sb.toString());
    }

    /** 按当前勾选估算期望次数与耗时（3.2 轮/秒是实测均值，实际以悬浮窗为准） */
    private String expectText() {
        List<Set<String>> live = new ArrayList<>();
        for (Set<String> p : plans) if (!p.isEmpty()) live.add(p);
        if (live.isEmpty()) return "";
        double p = Odds.hitProbMulti(live, avoid, tagOf);
        if (p <= 0) return "期望：不可能出现";
        double e = 1.0 / p;
        String n = e >= 10000
                ? String.format("%.1f万", e / 10000.0)
                : String.valueOf((long) Math.ceil(e));
        long ms = (long) (e / 3.2 * 1000);
        return "期望 " + n + " 次 ≈ " + FactorService.fmtClock(ms)
                + "（约 3.2 次/秒）" + (live.size() > 1 ? " · 任一方案命中即停" : "");
    }

    private void refreshModeButtons() {
        if (bWant == null || bAvoid == null) return;
        bWant.setBackgroundColor(Color.parseColor(mode == MODE_WANT ? "#2F7D4F" : "#232830"));
        bAvoid.setBackgroundColor(Color.parseColor(mode == MODE_AVOID ? "#8A3B3B" : "#232830"));
        if (editorTitle != null) {
            editorTitle.setText(mode == MODE_AVOID ? "不要的因子（全局）"
                    : "方案 " + (planIdx + 1) + " / " + plans.size());
        }
        updateHint();
    }

    private void updateHint() {
        if (hint == null) return;
        if (mode == MODE_WANT) {
            String cur = want.isEmpty() ? "（还没选）" : joinNames(want);
            String tail = !blocked.isEmpty() ? "\n灰色的和已选冲突，点了会告诉你原因" : "";
            hint.setText("方案 " + (planIdx + 1) + "：" + cur + "\n点图标加入本方案 · 长按看介绍" + tail);
        } else {
            String cur = avoid.isEmpty() ? "（没设，出现了也照停）" : joinNames(avoid);
            hint.setText("不要的（全局）：" + cur + "\n这几个出现了就继续刷（不影响各方案）");
        }
    }

    private static String joinNames(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String x : s) {
            if (i++ > 0) sb.append("+");
            sb.append(x);
        }
        return sb.toString();
    }

    /** 因子介绍：长按图标弹出，离线内容 + 可选跳 wiki */
    private void showInfo(String[] it) {
        String name = it[1], tag = it[2];
        String desc = it.length > 3 && !it[3].isEmpty() ? it[3] : "（这个因子还没有简介）";
        boolean ok = rollable(tag);
        String tail;
        if (ok) {
            tail = "✅ 刷新能刷到";
        } else if (tag.equals("周挑战")) {
            tail = "⛔ 刷新刷不到：周挑战因子要在「远程联机」里单独开启，不参与这 4 格刷新";
        } else {
            tail = "⛔ 刷新刷不到：特殊因子来自隐藏场景（溶洞/墓穴/沼泽）、NPC 对话或活动";
        }
        String msg = "【" + tag + "】\n\n" + desc + "\n\n" + tail;
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(msg)
                .setPositiveButton("打开 wiki", (d, w) -> openWiki(name))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openWiki(String name) {
        try {
            String u = "https://yqqs.huijiwiki.com/wiki/"
                    + java.net.URLEncoder.encode(name, "UTF-8");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Throwable t) {
            toast("打不开浏览器");
        }
    }

    private void start() {
        if (!accOn()) {
            toast("先去开启「元气刷因子」无障碍服务");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (FactorService.instance == null) {
            toast("无障碍服务还没连上，开关一次再试");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许悬浮窗");
            requestOverlay();
            return;
        }
        boolean any = false;
        for (Set<String> p : plans) if (!p.isEmpty()) { any = true; break; }
        if (!any) {
            toast("请至少在一组方案里选一个「要的」因子");
            return;
        }
        save();
        // 这里只负责"把悬浮窗叫出来"，真正的开始由悬浮窗上的按钮触发，
        // 免得游戏还没开就自己刷起来
        FactorService.instance.showPanel();
        toast("悬浮窗已显示，进游戏后再点「开始」");
        moveTaskToBack(true);
    }

    private void requestOverlay() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private boolean accOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void loadFactors() {
        items.clear();
        try (InputStream in = getAssets().open("factors.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\|", 4);
                if (p.length >= 3) {
                    items.add(new String[]{p[0], p[1], p[2], p.length > 3 ? p[3] : ""});
                    tagOf.put(p[1], p[2]);
                }
            }
        } catch (Throwable ignored) { }
    }

    private void loadIcons() {
        IconPack.ensure(getAssets());
        for (String[] it : items) {
            Bitmap b = IconPack.decode(it[1]);
            if (b != null) icons.put(it[1], b);
        }
    }

    private Bitmap readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private Button smallBtn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setTextColor(Color.parseColor("#D7DEEA"));
        b.setBackgroundColor(Color.parseColor("#232830"));
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class FactorAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return items.get(shown.get(i)); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(final int pos, View convert, ViewGroup parent) {
            final String[] it = items.get(shown.get(pos));
            final String name = it[1];
            final boolean ok = rollable(it[2]);
            // 只在「选要的」时置灰；选「不要的」不受组合规则限制
            final boolean blk = mode == MODE_WANT && blocked.contains(name);

            LinearLayout cell = new LinearLayout(MainActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(2), dp(4), dp(2), dp(4));
            cell.setBackground(drawableFor(name));

            ImageView iv = new ImageView(MainActivity.this);
            Bitmap b = icons.get(name);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (b != null) {
                iv.setImageBitmap(b);
            } else {
                // 图标没读出来时退化成文字，至少还能用
                iv.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                        Color.parseColor("#2A3242")));
            }
            cell.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));

            TextView tv = new TextView(MainActivity.this);
            tv.setText(name);
            tv.setTextColor(avoid.contains(name) ? Color.parseColor("#F08A8A")
                    : want.contains(name) ? Color.parseColor("#7BE3A8")
                    : blk ? Color.parseColor("#48505F")
                    : ok ? Color.parseColor("#B9C2D0") : Color.parseColor("#5E6675"));
            tv.setTextSize(9);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            cell.addView(tv);
            if (!ok) cell.setAlpha(0.62f);
            else if (blk) cell.setAlpha(0.30f);

            cell.setOnLongClickListener(v -> {
                showInfo(it);
                return true;
            });

            cell.setOnClickListener(v -> {
                if (!ok) {                      // 刷不到的因子：只给介绍，不给选
                    showInfo(it);
                    return;
                }
                if (blk) {                      // 和已选的冲突：说明原因，不允许点
                    Set<String> test = new HashSet<>(want);
                    test.add(name);
                    toast("「" + name + "」和已选的冲突：" + Rules.shortWhy(Rules.whyNot(test, tagOf)));
                    return;
                }
                if (mode == MODE_WANT) {
                    if (want.contains(name)) want.remove(name);
                    else { want.add(name); avoid.remove(name); }
                } else {
                    if (avoid.contains(name)) avoid.remove(name);
                    else { avoid.add(name); want.remove(name); }
                }
                rebuildBlocked();
                adapter.notifyDataSetChanged();
                updateHint();
                save();
            });
            return cell;
        }

        private GradientDrawable drawableFor(String name) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(6));
            if (want.contains(name)) {
                g.setColor(Color.parseColor("#12301F"));
                g.setStroke(dp(2), Color.parseColor("#3FBF74"));
            } else if (avoid.contains(name)) {
                g.setColor(Color.parseColor("#33161A"));
                g.setStroke(dp(2), Color.parseColor("#D25555"));
            } else {
                g.setColor(Color.parseColor("#161A22"));
            }
            return g;
        }
    }
}