package com.yqqs.factor;

import android.graphics.Bitmap;
import android.graphics.Rect;

/**
 * 纯 Java 图像匹配：灰度 / 块平均降采样 / NCC / 刷新按钮定位 / 4 个因子格子几何推导。
 */
public class Matcher {

    public static class Hit {
        public Rect rect;
        public float score;
        public float scale;
        Hit(Rect r, float s, float sc) { rect = r; score = s; scale = sc; }
    }

    public static int[] toGray(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] g = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 151 + (c & 0xff) * 28) >> 8;
        }
        return g;
    }

    /** 块平均降采样 k 倍，同时转灰度 */
    public static int[] downGray(Bitmap b, int k) {
        int w = b.getWidth(), h = b.getHeight();
        int gw = w / k, gh = h / k;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] out = new int[gw * gh];
        int area = k * k;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int s = 0;
                for (int dy = 0; dy < k; dy++) {
                    int row = (y * k + dy) * w + x * k;
                    for (int dx = 0; dx < k; dx++) {
                        int c = px[row + dx];
                        s += (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 151 + (c & 0xff) * 28) >> 8;
                    }
                }
                out[y * gw + x] = s / area;
            }
        }
        return out;
    }

    static float nccRegion(int[] s, int sw, int tw, int th, int[] t, int ox, int oy) {
        int n = tw * th;
        long sumS = 0, sumT = 0;
        for (int y = 0; y < th; y++) {
            int so = (oy + y) * sw + ox, to = y * tw;
            for (int x = 0; x < tw; x++) { sumS += s[so + x]; sumT += t[to + x]; }
        }
        double mS = (double) sumS / n, mT = (double) sumT / n;
        double cov = 0, vS = 0, vT = 0;
        for (int y = 0; y < th; y++) {
            int so = (oy + y) * sw + ox, to = y * tw;
            for (int x = 0; x < tw; x++) {
                double a = s[so + x] - mS, b = t[to + x] - mT;
                cov += a * b; vS += a * a; vT += b * b;
            }
        }
        if (vS < 1e-6 || vT < 1e-6) return 0f;
        return (float) (cov / Math.sqrt(vS * vT));
    }

    /** 在整屏（降采样后）中定位刷新按钮，返回全分辨率矩形 */
    public static Hit detectRefresh(Bitmap full, Bitmap tpl, float[] scales) {
        int k = 8;
        int W = full.getWidth(), H = full.getHeight();
        int gw = W / k, gh = H / k;
        int[] g = downGray(full, k);
        float best = -2f;
        int bx = 0, by = 0, btw = 0, bth = 0;
        float bs = 1f;
        for (float s : scales) {
            int tw = Math.round(tpl.getWidth() * s / k);
            int th = Math.round(tpl.getHeight() * s / k);
            if (tw < 6 || th < 6 || tw >= gw || th >= gh) continue;
            Bitmap st = Bitmap.createScaledBitmap(tpl, tw, th, true);
            int[] t = toGray(st);
            st.recycle();
            for (int y = 0; y + th <= gh; y++) {
                for (int x = 0; x + tw <= gw; x++) {
                    float v = nccRegion(g, gw, tw, th, t, x, y);
                    if (v > best) { best = v; bx = x; by = y; btw = tw; bth = th; bs = s; }
                }
            }
        }
        if (best < 0.5f) return null;
        Rect r = new Rect(bx * k, by * k, (bx + btw) * k, (by + bth) * k);
        return new Hit(r, best, bs);
    }

    /** 由刷新按钮推 4 个因子格子（基于 1920x864 截图实测比例） */
    public static Rect[] tileRects(Rect btn, int screenW, int screenH) {
        float bw = btn.width();
        float cx = btn.exactCenterX(), cy = btn.exactCenterY();
        float pitch = 1.404f * bw;
        float ts = 0.9f * bw;
        float tcy = cy - 1.382f * bw;
        Rect[] out = new Rect[4];
        for (int i = 0; i < 4; i++) {
            float ccx = cx + pitch * (i - 1.5f);
            int l = Math.round(ccx - ts / 2), t = Math.round(tcy - ts / 2);
            Rect r = new Rect(l, t, l + Math.round(ts), t + Math.round(ts));
            r.intersect(0, 0, screenW, screenH);
            out[i] = r;
        }
        return out;
    }

    /** 把图标按 fit 比例等比放进 box x box 画布（底色 bg），返回灰度数组 */
    public static int[] render(Bitmap tpl, int box, float fit, int bg) {
        int[] out = new int[box * box];
        java.util.Arrays.fill(out, bg);
        float maxSide = box * fit;
        float sc = Math.min(maxSide / tpl.getWidth(), maxSide / tpl.getHeight());
        int w = Math.max(1, Math.min(box, Math.round(tpl.getWidth() * sc)));
        int h = Math.max(1, Math.min(box, Math.round(tpl.getHeight() * sc)));
        Bitmap b = Bitmap.createScaledBitmap(tpl, w, h, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        int ox = (box - w) / 2, oy = (box - h) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int gy = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 151 + (c & 0xff) * 28) >> 8;
                out[(oy + y) * box + ox + x] = gy;
            }
        }
        return out;
    }

    /** 取整屏上某个格子的灰度块 */
    public static int[] cropGray(int[] fullGray, int screenW, Rect r) {
        int[] out = new int[r.width() * r.height()];
        for (int y = 0; y < r.height(); y++) {
            System.arraycopy(fullGray, (r.top + y) * screenW + r.left, out, y * r.width(), r.width());
        }
        return out;
    }

    /** 直接从 Bitmap 裁一块并转灰度（比整屏转灰度快几十倍） */
    public static int[] cropGray(Bitmap b, Rect r) {
        int w = r.width(), h = r.height();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, r.left, r.top, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 151 + (c & 0xff) * 28) >> 8;
        }
        return px;
    }

    public static float nccSquare(int[] a, int[] b, int n) {
        return nccRegion(a, n, n, n, b, 0, 0);
    }

    /** 在 s 里滑窗找 t 的最佳位置（先 2 倍步长粗搜，再在峰值附近细搜，约快 3 倍） */
    public static float nccBest(int[] s, int sw, int sh, int[] t, int tn) {
        float best = -1f;
        int bx = -1, by = -1;
        for (int y = 0; y + tn <= sh; y += 2) {
            for (int x = 0; x + tn <= sw; x += 2) {
                float v = nccRegion(s, sw, tn, tn, t, x, y);
                if (v > best) { best = v; bx = x; by = y; }
            }
        }
        if (bx < 0) return best;
        int x0 = Math.max(0, bx - 2), x1 = Math.min(sw - tn, bx + 2);
        int y0 = Math.max(0, by - 2), y1 = Math.min(sh - tn, by + 2);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float v = nccRegion(s, sw, tn, tn, t, x, y);
                if (v > best) best = v;
            }
        }
        return best;
    }

    /** 裁剪区域并按 k 块平均降采样成灰度 */
    public static int[] cropGrayDown(Bitmap b, Rect r, int k) {
        int w = r.width() / k, h = r.height() / k;
        if (w < 1 || h < 1) return new int[]{0};
        int fw = w * k;
        int[] px = new int[fw * h * k];
        b.getPixels(px, 0, fw, r.left, r.top, fw, h * k);
        int[] out = new int[w * h];
        int area = k * k;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0;
                for (int dy = 0; dy < k; dy++) {
                    int row = (y * k + dy) * fw + x * k;
                    for (int dx = 0; dx < k; dx++) {
                        int c = px[row + dx];
                        s += (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 151 + (c & 0xff) * 28) >> 8;
                    }
                }
                out[y * w + x] = s / area;
            }
        }
        return out;
    }

    /** 灰度数组按 k 块平均降采样（边长为 n 的方阵） */
    public static int[] downArray(int[] src, int n, int k) {
        int w = n / k;
        if (w < 1) return new int[]{0};
        int[] out = new int[w * w];
        int area = k * k;
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0;
                for (int dy = 0; dy < k; dy++) {
                    int row = (y * k + dy) * n + x * k;
                    for (int dx = 0; dx < k; dx++) s += src[row + dx];
                }
                out[y * w + x] = s / area;
            }
        }
        return out;
    }
}