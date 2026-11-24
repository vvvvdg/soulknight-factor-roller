package com.yqqs.factor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class FactorService extends AccessibilityService {

    public static volatile FactorService instance;

    private volatile boolean running;
    private Thread worker;
    private volatile long runStart;
    private volatile int roundNow;
    private volatile double expRounds;

    private WindowManager wm;
    private View overlay;
    private TextView overlayText;
    private TextView overlayTarget;
    private View bubble;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<String, String> idOf = new HashMap<>();
    private final HashMap<String, Bitmap> iconCache = new HashMap<>();
    private final HashMap<String, int[]> renderCache = new HashMap<>();

    private static final float[] SCALES = {0.6f, 0.8f, 1.0f, 1.2f, 1.4f};
    private static final float[] FITS = {0.75f, 0.90f, 1.05f};
    private static final int SCAN_K = 3;
    private static final int BG_GRAY = 31;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { stopLoop(); }

    @Override
    public void onDestroy() {
        stopLoop();
        ui.post(() -> {
            hideOverlay();
            hideBubble();
        });
        instance = null;
        super.onDestroy();
    }

    public boolean isRunning() { return running; }

    // ---------- assets ----------

    private Bitmap loadAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadIndex() {
        idOf.clear();
        try (InputStream in = getAssets().open("factors.txt");
             java.io.BufferedReader br = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\|");
                if (p.length >= 2) idOf.put(p[1], p[0]);
            }
        } catch (Throwable ignored) { }
    }

    private Bitmap icon(String name) {
        Bitmap b = iconCache.get(name);
        if (b != null) return b;
        IconPack.ensure(getAssets());
        b = IconPack.decode(name);
        if (b != null) iconCache.put(name, b);
        return b;
    }

    /** template already downsampled by SCAN_K, cached per (name, box, fit) */
    private int[] tplDown(String name, int box, float fit) {
        String key = name + "|" + box + "|" + fit;
        int[] v = renderCache.get(key);
        if (v != null) return v;
        Bitmap ic = icon(name);
        if (ic == null) return null;
        int[] full = Matcher.render(ic, box, fit, BG_GRAY);
        v = Matcher.downArray(full, box, SCAN_K);
        renderCache.put(key, v);
        return v;
    }

    // ---------- screen ----------

    /**
     * AOSP 硬限制：两次 takeScreenshot 的间隔必须 > 333ms，否则系统直接回调
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT 并返回空图。
     * 常量见 AccessibilityService.ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333。
     * 所以无障碍截图的天然上限约 3 张/秒，想再快只能换 MediaProjection。
     */
    private static final int SHOT_MIN_GAP = 345;
    private long lastShotReqAt = 0;
    private int lastShotErr = 0;

    private Bitmap grabScreen() {
        long gap = SystemClock.uptimeMillis() - lastShotReqAt;
        if (gap < SHOT_MIN_GAP) sleepMs((int) (SHOT_MIN_GAP - gap));
        lastShotReqAt = SystemClock.uptimeMillis();
        final AtomicReference<Bitmap> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                            if (hw != null) ref.set(toSoftware(hw));
                        } catch (Throwable ignored) { }
                        try { hb.close(); } catch (Throwable ignored) { }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        lastShotErr = errorCode;
                        latch.countDown();
                    }
                });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        return ref.get();
    }

    private Bitmap shotBuf;
    private boolean canvasPath = true;

    /** 硬件位图转软件位图：优先画进可复用的缓冲，避免每轮分配 10MB 触发 GC */
    private Bitmap toSoftware(Bitmap hw) {
        if (canvasPath) {
            try {
                if (shotBuf == null || shotBuf.getWidth() != hw.getWidth()
                        || shotBuf.getHeight() != hw.getHeight()) {
                    shotBuf = Bitmap.createBitmap(hw.getWidth(), hw.getHeight(),
                            Bitmap.Config.ARGB_8888);
                }
                Canvas c = new Canvas(shotBuf);
                c.drawBitmap(hw, 0f, 0f, null);
                int p1 = shotBuf.getPixel(hw.getWidth() / 2, hw.getHeight() / 2);
                int p2 = shotBuf.getPixel(hw.getWidth() / 4, hw.getHeight() / 4);
                if (p1 == 0 && p2 == 0) {
                    canvasPath = false;
                    shotBuf = null;
                } else {
                    return shotBuf;
                }
            } catch (Throwable t) {
                canvasPath = false;
                shotBuf = null;
            }
        }
        return hw.copy(Bitmap.Config.ARGB_8888, false);
    }

    private void release(Bitmap b) {
        if (b != null && b != shotBuf) b.recycle();
    }

    private void tap(float x, float y) {
        ui.post(() -> {
            try {
                Path p = new Path();
                p.moveTo(x, y);
                GestureDescription.StrokeDescription sd =
                        new GestureDescription.StrokeDescription(p, 0, 50);
                GestureDescription g = new GestureDescription.Builder().addStroke(sd).build();
                dispatchGesture(g, null, null);
            } catch (Throwable ignored) { }
        });
    }

    // ---------- matching ----------

    /** slide-window NCC inside cell expanded by 28%, returns best score */
    private float scanBest(Bitmap shot, Rect cell, Set<String> names, String[] nameOut) {
        int pad = Math.round(cell.width() * 0.28f);
        Rect reg = new Rect(cell);
        reg.inset(-pad, -pad);
        reg.intersect(0, 0, shot.getWidth(), shot.getHeight());
        if (reg.width() < SCAN_K * 8 || reg.height() < SCAN_K * 8) return 0f;
        int gw = reg.width() / SCAN_K, gh = reg.height() / SCAN_K;
        int tn = cell.width() / SCAN_K;
        if (tn < 4 || tn >= gw || tn >= gh) return 0f;
        int[] g = Matcher.cropGrayDown(shot, reg, SCAN_K);
        float best = 0f;
        for (String n : names) {
            for (float fit : FITS) {
                int[] tpl = tplDown(n, cell.width(), fit);
                if (tpl == null) continue;
                float v = Matcher.nccBest(g, gw, gh, tpl, tn);
                if (v > best) { best = v; nameOut[0] = n; }
            }
        }
        return best;
    }

    /** 一次裁剪同时给多个模板打分（每个名字取多尺度里的最高分） */
    private void scanTile(Bitmap shot, Rect cell, Set<String> names, Map<String, Float> out) {
        if (names.isEmpty()) return;
        int pad = Math.round(cell.width() * 0.28f);
        Rect reg = new Rect(cell);
        reg.inset(-pad, -pad);
        reg.intersect(0, 0, shot.getWidth(), shot.getHeight());
        if (reg.width() < SCAN_K * 8 || reg.height() < SCAN_K * 8) return;
        int gw = reg.width() / SCAN_K, gh = reg.height() / SCAN_K;
        int tn = cell.width() / SCAN_K;
        if (tn < 4 || tn >= gw || tn >= gh) return;
        int[] g = Matcher.cropGrayDown(shot, reg, SCAN_K);
        for (String n : names) {
            float bv = 0f;
            for (float fit : FITS) {
                int[] tpl = tplDown(n, cell.width(), fit);
                if (tpl == null) continue;
                float v = Matcher.nccBest(g, gw, gh, tpl, tn);
                if (v > bv) bv = v;
            }
            out.put(n, bv);
        }
    }

    /** cheap fingerprint of the 4 tiles, used to detect that the refresh redraw finished */
    private int sigOf(Bitmap shot, Rect[] tiles) {
        int h = 17;
        int w = shot.getWidth(), ht = shot.getHeight();
        for (Rect r : tiles) {
            for (int i = 1; i <= 6; i++) {
                for (int j = 1; j <= 6; j++) {
                    int x = r.left + r.width() * i / 7;
                    int y = r.top + r.height() * j / 7;
                    if (x > 0 && x < w && y > 0 && y < ht) h = h * 31 + shot.getPixel(x, y);
                }
            }
        }
        return h;
    }

    private void dump(Bitmap shot, Rect btn, Rect[] tiles) {
        try {
            Bitmap d = shot.copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(d);
            Paint p = new Paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, shot.getWidth() / 500f));
            p.setColor(Color.RED);
            c.drawRect(btn, p);
            p.setColor(Color.GREEN);
            for (Rect r : tiles) c.drawRect(r, p);
            File dir = getExternalFilesDir(null);
            File f = new File(dir, "debug.png");
            FileOutputStream fo = new FileOutputStream(f);
            d.compress(Bitmap.CompressFormat.PNG, 90, fo);
            fo.close();
            d.recycle();
        } catch (Throwable ignored) { }
    }

    // ---------- one-shot test ----------

    public void testOnce() {
        new Thread(() -> {
            Bitmap shot = grabScreen();
            if (shot == null) { toast("截图失败，检查无障碍服务"); return; }
            Bitmap tpl = loadAsset("refresh.png");
            if (tpl == null) { toast("缺少 refresh.png"); return; }
            Matcher.Hit hit = Matcher.detectRefresh(shot, tpl, SCALES);
            Bitmap dbg = shot.copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(dbg);
            Paint p = new Paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(3, shot.getWidth() / 400f));
            String msg;
            if (hit == null) {
                msg = "没找到刷新按钮";
            } else {
                p.setColor(Color.RED);
                c.drawRect(hit.rect, p);
                Rect[] ts = Matcher.tileRects(hit.rect, shot.getWidth(), shot.getHeight());
                p.setColor(Color.GREEN);
                for (Rect r : ts) c.drawRect(r, p);
                msg = "refresh " + hit.rect.centerX() + "," + hit.rect.centerY()
                        + " w=" + hit.rect.width() + " score " + String.format("%.2f", hit.score);
            }
            String path = "";
            try {
                File dir = getExternalFilesDir(null);
                File f = new File(dir, "debug.png");
                FileOutputStream fo = new FileOutputStream(f);
                dbg.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();
                path = f.getAbsolutePath();
            } catch (Throwable ignored) { }
            dbg.recycle();
            release(shot);
            ui.post(this::showOverlay);
            setOverlayText("定位: " + msg + "\n" + path);
            toast("已保存到 " + path);
        }, "factor-test").start();
    }

    // ---------- loop ----------

    public void startLoop() {
        if (running) return;
        running = true;
        if (overlayTarget != null) overlayTarget.setText(targetLine());
        worker = new Thread(this::loopBody, "factor-loop");
        worker.setDaemon(true);
        worker.start();
    }

    public void stopLoop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
        ui.post(() -> {
            if (overlayText != null) {
                String t = "已停止";
                if (runStart > 0) {
                    t += " · 用时 " + fmtClock(System.currentTimeMillis() - runStart)
                            + " · " + roundNow + " 轮";
                }
                overlayText.setText(t);
            }
        });
    }

    /** 只把悬浮窗叫出来，不自动开刷（开始由悬浮窗上的按钮触发） */
    public void showPanel() {
        ui.post(() -> {
            showOverlay();
            if (overlayTarget != null) overlayTarget.setText(targetLine());
            if (overlayText != null && !running) overlayText.setText("点「开始」开始刷");
        });
    }

    private void loopBody() {
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            List<Set<String>> plans = loadPlans();
            Set<String> avoid = new HashSet<>(sp.getStringSet("avoid", Collections.<String>emptySet()));
            final float thr = sp.getFloat("threshold", 0.72f);
            int delay = sp.getInt("delay", 450);
            int maxRounds = sp.getInt("maxRounds", 3000);

            Bitmap refreshTpl = loadAsset("refresh.png");
            if (refreshTpl == null) {
                toast("缺少 refresh.png，无法定位刷新按钮");
                stopLoop();
                return;
            }

            ui.post(this::showOverlay);
            sleepMs(300);

            // 可行性：逐组检查，不可行的踢掉；全都不行才报错停手
            if (!plans.isEmpty()) {
                List<Set<String>> usable = new ArrayList<>();
                List<String> rejected = new ArrayList<>();
                for (int i = 0; i < plans.size(); i++) {
                    String why = whyNot(plans.get(i));
                    if (why == null) usable.add(plans.get(i));
                    else rejected.add("方案" + (i + 1) + "：" + why);
                }
                if (usable.isEmpty()) {
                    setOverlayText("每一组方案都不可能成立：\n" + join(rejected));
                    running = false;
                    return;
                }
                plans = usable;
                if (!rejected.isEmpty()) setOverlayText("跳过不可行的方案：\n" + join(rejected));
            }
            Set<String> wantFlat = new HashSet<>();
            for (Set<String> p : plans) wantFlat.addAll(p);

            // 期望刷新次数：E = 1/P（多方案用容斥，见 Odds）
            expRounds = 0;
            try {
                double p = Odds.hitProbMulti(plans, avoid, tags());
                if (p > 0) expRounds = 1.0 / p;
            } catch (Throwable ignored) { }

            Rect btn = null;
            Rect[] tiles = null;
            int round = 0;
            int lastSig = 0;
            long lastClickAt = 0;
            boolean afterClick = false;
            long msShot = 0, msMatch = 0;
            // 自适应等待：只由「画面有没有换」这个证据驱动，不再把自指的时间差当测量值
            // 下限 300ms 是因为无障碍截图本身有 333ms 的硬限制（见 SHOT_MIN_GAP）
            long lat = 340;
            int retries = 0;
            runStart = System.currentTimeMillis();
            int stale = 0;
            int timeouts = 0;
            int shotFails = 0;
            // 诊断：前 30 轮平均轮时作为基准，用来看是不是在衰减
            long first30Sum = 0;
            int first30N = 0;
            long baseMs = 0;
            long roundStart = System.currentTimeMillis();

            while (running && round < maxRounds) {
                long t0 = System.currentTimeMillis();
                Bitmap shot = grabScreen();
                if (shot == null) {
                    shotFails++;
                    // 偶尔一张截不到很常见（正好碰上转场），别把悬浮窗刷成报警器
                    if (shotFails >= 5) {
                        setOverlayText("截图失败 x" + shotFails + " (err=" + lastShotErr
                                + ")\n请检查无障碍权限");
                        sleepMs(400);
                    } else {
                        sleepMs(30);
                    }
                    continue;
                }
                shotFails = 0;
                msShot = System.currentTimeMillis() - t0;
                int sw = shot.getWidth(), sh = shot.getHeight();

                if (btn == null) {
                    Matcher.Hit hit = Matcher.detectRefresh(shot, refreshTpl, SCALES);
                    if (hit == null) {
                        setOverlayText("没找到刷新按钮\n请停在联机房间设置界面重试");
                        release(shot);
                        sleepMs(600);
                        continue;
                    }
                    btn = hit.rect;
                    tiles = Matcher.tileRects(btn, sw, sh);
                }

                if (round == 0 || round % 200 == 0) dump(shot, btn, tiles);

                int sig = sigOf(shot, tiles);
                if (afterClick && sig == lastSig
                        && System.currentTimeMillis() - lastClickAt < 2500) {
                    // 画面还没刷新：只重截，不重复点；每个点击最多上调一次等待
                    release(shot);
                    if (stale == 0) lat = Math.min(800, lat + 80);
                    stale++;
                    long waited = System.currentTimeMillis() - lastClickAt;
                    if (waited > lat + 700 && retries < 2) {
                        // 点击多半被系统吞了，补点一次
                        retries++;
                        lastClickAt = System.currentTimeMillis();
                        tap(btn.exactCenterX(), btn.exactCenterY());
                    }
                    sleepMs(stale > 3 ? 60 : 25);
                    continue;
                }
                if (afterClick && sig == lastSig) {
                    // 等满 2.5 秒画面还是没换，视为异常
                    timeouts++;
                    lat = Math.min(800, lat + 120);
                    if (timeouts >= 3) {
                        lat = 340;
                        timeouts = 0;
                        setOverlayText("连续 3 轮刷新超时\n已重置等待时间");
                    }
                } else if (afterClick) {
                    // 看到画面确实换了：这次等得够久，下次更激进
                    lat = Math.max(300, lat - 4);
                    timeouts = 0;
                }
                afterClick = false;
                stale = 0;
                retries = 0;

                long t1 = System.currentTimeMillis();
                float bestWant = 0f, bestAvoid = 0f;
                String wantName = null, avoidName = null;
                Set<String> found = new HashSet<>();
                Set<String> avoidFound = new HashSet<>();
                Set<String> allNames = new HashSet<>(wantFlat);
                allNames.addAll(avoid);
                Map<String, Float> sc = new HashMap<>();
                Map<String, Float> peak = new HashMap<>();

                for (int i = 0; i < 4; i++) {
                    Rect r = tiles[i];
                    if (r.width() < 24) continue;
                    sc.clear();
                    scanTile(shot, r, allNames, sc);
                    for (Map.Entry<String, Float> e : sc.entrySet()) {
                        float v = e.getValue();
                        String k = e.getKey();
                        Float old = peak.get(k);
                        if (old == null || v > old) peak.put(k, v);
                        if (wantFlat.contains(k)) {
                            if (v > bestWant) { bestWant = v; wantName = k; }
                            if (v >= thr) found.add(k);
                        } else {
                            if (v > bestAvoid) { bestAvoid = v; avoidName = k; }
                            if (v >= thr) avoidFound.add(k);
                        }
                    }
                }
                msMatch = System.currentTimeMillis() - t1;

                // 多方案：找一个「差得最少」的方案来展示
                int bestPlan = -1;
                List<String> bestMissing = null;
                float planScore = 0f;
                for (int pi = 0; pi < plans.size(); pi++) {
                    List<String> miss = new ArrayList<>();
                    float mn = 1f;
                    for (String w : plans.get(pi)) {
                        Float v = peak.get(w);
                        float vv = v == null ? 0f : v;
                        if (vv < thr) miss.add(w);
                        if (vv < mn) mn = vv;
                    }
                    if (bestMissing == null || miss.size() < bestMissing.size()
                            || (miss.size() == bestMissing.size() && mn > planScore)) {
                        bestMissing = miss;
                        planScore = mn;
                        bestPlan = pi;
                    }
                }

                boolean hitWant = plans.isEmpty() || (bestMissing != null && bestMissing.isEmpty());
                boolean hasAvoid = !avoidFound.isEmpty();

                if (hitWant && !hasAvoid) {
                    release(shot);
                    success(planScore, bestPlan, round, plans);
                    return;
                }

                long nowMs = System.currentTimeMillis();
                long roundMs = Math.max(1, nowMs - roundStart);
                roundStart = nowMs;
                if (first30N < 30) {
                    first30Sum += roundMs;
                    first30N++;
                    if (first30N == 30) baseMs = Math.max(1, first30Sum / 30);
                }
                long el = Math.max(1, nowMs - runStart);
                String rate = String.format("%.1f", round * 1000f / el);
                long avgMs = Math.max(1, el / Math.max(1, round));
                String decay = baseMs > 0
                        ? String.format(" (前30轮 %.1f/s = %.2fx)", 1000f / baseMs,
                        (float) baseMs / avgMs)
                        : "";
                String tag = fmtClock(el) + "  第 " + (round + 1) + " 轮 " + rate + "/s" + decay
                        + "\n截" + msShot + " 匹" + msMatch + " 等" + lat + "ms "
                        + (canvasPath ? "缓存:复用" : "缓存:回落")
                        + "\n" + expLine(expRounds, el, avgMs)
                        + "\n" + (plans.size() > 1 ? "方案" + (bestPlan + 1) + "/" + plans.size() + " " : "")
                        + "想要 " + String.format("%.2f", planScore)
                        + " / 需 " + thr
                        + (bestMissing == null || bestMissing.isEmpty()
                        ? "" : "\n还差 " + bestMissing.size() + ": " + join(bestMissing))
                        + (hasAvoid ? "\n不要的出现了: " + join(avoidFound) : "");

                lastSig = sig;
                lastClickAt = System.currentTimeMillis();
                afterClick = true;
                round++;
                roundNow = round;
                tap(btn.exactCenterX(), btn.exactCenterY());
                setOverlayText(tag + "\n刷新中");
                release(shot);
                long wait = lat - 45;
                if (wait > 20) sleepMs((int) wait);
            }

            if (round >= maxRounds) {
                setOverlayText("已刷 " + maxRounds + " 轮未命中，自动停止");
                sleepMs(2500);
            }
            stopLoop();
        } catch (Throwable t) {
            setOverlayText("出错: " + t);
            sleepMs(1500);
            stopLoop();
        }
    }

    private void success(float wantScore, int planIdx, int round, List<Set<String>> plans) {
        String got = "";
        if (planIdx >= 0 && planIdx < plans.size()) {
            got = "\n" + (plans.size() > 1 ? "方案" + (planIdx + 1) + "：" : "") + join(plans.get(planIdx));
        }
        setOverlayText("刷到了" + got + "\n共 " + round + " 轮 · 用时 "
                + fmtClock(System.currentTimeMillis() - runStart)
                + (expRounds > 0 ? " · 期望 " + (long) Math.ceil(expRounds) + " 轮" : "")
                + " · 想要 " + String.format("%.2f", wantScore));
        ui.post(() -> {
            try {
                android.media.Ringtone r = android.media.RingtoneManager.getRingtone(
                        this, android.media.RingtoneManager.getDefaultUri(
                                android.media.RingtoneManager.TYPE_NOTIFICATION));
                if (r != null) r.play();
            } catch (Throwable ignored) { }
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(1200);
            toast("刷到了！");
        });
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
    }

    private static String join(Iterable<String> it) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : it) {
            if (i++ > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }

    // ---------- 位置规则可行性 ----------

    private Map<String, String> tags() {
        Map<String, String> m = new HashMap<>();
        try {
            InputStream in = getAssets().open("factors.txt");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\|");
                if (p.length >= 3) m.put(p[1].trim(), p[2].trim());
            }
            br.close();
        } catch (Throwable ignored) { }
        return m;
    }

    /** 返回 null 表示可行；否则返回给用户看的原因（规则实现见 Rules，App 里也用同一份） */
    private String whyNot(Set<String> want) {
        return Rules.whyNot(want, tags());
    }

    private void sleepMs(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void toast(final String s) {
        ui.post(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show());
    }

    private void setOverlayText(final String s) {
        ui.post(() -> { if (overlayText != null) overlayText.setText(s); });
    }

    /** 毫秒 → 12:34 / 1:02:03 */
    static String fmtClock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }

    /**
     * 期望与倒计时。几何分布无记忆：还没刷到时，「还要刷多少次」的条件期望仍是 E，
     * 所以这里用 剩余 = 期望总耗时 - 已用时间 做减法，给用户一个随时间下降的倒计时。
     */
    static String expLine(double expRounds, long elapsedMs, long avgMs) {
        if (expRounds <= 0) return "期望 —";
        long total = (long) (expRounds * Math.max(1, avgMs));
        long remain = Math.max(0, total - elapsedMs);
        String e = expRounds >= 10000
                ? String.format("%.1f万", expRounds / 10000.0)
                : String.valueOf((long) Math.ceil(expRounds));
        return "期望 " + e + " 轮 ≈ " + fmtClock(total) + " · 还差 " + fmtClock(remain);
    }

    private void showOverlay() {
        if (overlay != null) return;
        if (!Settings.canDrawOverlays(this)) return;
        hideBubble();
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout ll = new LinearLayout(this);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setBackgroundColor(Color.parseColor("#E6101318"));

            overlayTarget = new TextView(this);
            overlayTarget.setTextColor(Color.parseColor("#7BE3A8"));
            overlayTarget.setTextSize(11);
            overlayTarget.setPadding(dp(10), dp(8), dp(10), 0);
            overlayTarget.setText(targetLine());
            ll.addView(overlayTarget);

            overlayText = new TextView(this);
            overlayText.setTextColor(Color.WHITE);
            overlayText.setTextSize(12);
            overlayText.setPadding(dp(10), dp(4), dp(10), dp(4));
            overlayText.setText("点「开始」开始刷");
            ll.addView(overlayText);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button startBtn = new Button(this);
            startBtn.setText("开始");
            startBtn.setOnClickListener(v -> {
                if (!running) {
                    if (wantEmpty()) toast("先在 App 里选「要的」因子");
                    else startLoop();
                }
            });
            Button stop = new Button(this);
            stop.setText("停止");
            stop.setOnClickListener(v -> stopLoop());
            Button close = new Button(this);
            close.setText("关闭");
            close.setOnClickListener(v -> {
                stopLoop();
                hideOverlay();
                toast("悬浮窗已隐藏，需要时回 App 点「显示悬浮窗」");
            });
            LinearLayout.LayoutParams bw = new LinearLayout.LayoutParams(
                    0, dp(40), 1f);
            row.addView(startBtn, bw);
            row.addView(stop, new LinearLayout.LayoutParams(0, dp(40), 1f));
            row.addView(close, new LinearLayout.LayoutParams(0, dp(40), 1f));
            ll.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(220), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(6);
            lp.y = dp(60);
            wm.addView(ll, lp);
            overlay = ll;
        } catch (Throwable ignored) { }
    }

    private List<Set<String>> loadPlans() {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        List<Set<String>> ps = new ArrayList<>();
        int pc = sp.getInt("planCount", 0);
        for (int i = 0; i < pc; i++) {
            Set<String> s = new HashSet<>(sp.getStringSet("plan" + i,
                    Collections.<String>emptySet()));
            if (!s.isEmpty()) ps.add(s);
        }
        if (ps.isEmpty()) {   // 兼容旧版本：单个「要的」
            Set<String> old = new HashSet<>(sp.getStringSet("want", Collections.<String>emptySet()));
            if (!old.isEmpty()) ps.add(old);
        }
        return ps;
    }

    private boolean wantEmpty() {
        return loadPlans().isEmpty();
    }

    private String targetLine() {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        List<Set<String>> ps = loadPlans();
        Set<String> a = new HashSet<>(sp.getStringSet("avoid", Collections.<String>emptySet()));
        StringBuilder sb = new StringBuilder();
        if (ps.isEmpty()) {
            sb.append("刷: (未选)");
        } else if (ps.size() == 1) {
            sb.append("刷: ").append(join(ps.get(0)));
        } else {
            for (int i = 0; i < ps.size(); i++) {
                if (i > 0) sb.append(" 或 ");
                sb.append(join(ps.get(i)));
            }
        }
        if (!a.isEmpty()) sb.append("\n避: ").append(join(a));
        return sb.toString();
    }

    // 收起成一个小圆点，点它重新展开
    private void collapse() {
        hideOverlay();
        if (bubble != null || !Settings.canDrawOverlays(this)) return;
        try {
            if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView b = new TextView(this);
            b.setText("刷");
            b.setTextColor(Color.parseColor("#7BE3A8"));
            b.setTextSize(13);
            b.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setColor(Color.parseColor("#CC101318"));
            gd.setCornerRadius(dp(20));
            gd.setStroke(dp(1), Color.parseColor("#4A7BE3A8"));
            b.setBackground(gd);
            b.setOnClickListener(v -> {
                hideBubble();
                showOverlay();
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(40), dp(40),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(6);
            lp.y = dp(60);
            wm.addView(b, lp);
            bubble = b;
        } catch (Throwable ignored) { }
    }

    private void hideBubble() {
        if (bubble == null) return;
        try { wm.removeView(bubble); } catch (Throwable ignored) { }
        bubble = null;
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try { wm.removeView(overlay); } catch (Throwable ignored) { }
        overlay = null;
        overlayText = null;
        overlayTarget = null;
    }

    private int dp(int v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round(v * dm.density);
    }
}
