# -*- coding: utf-8 -*-
"""把 77 个模板全部拿去匹配截图里的 4 个格子，验证整个图库并识别第 3 格。"""
import os
import numpy as np
from PIL import Image
from numpy.lib.stride_tricks import sliding_window_view

BASE = r"C:\Users\sanyi\Documents\dsh\soulknight"
TPL = os.path.join(BASE, "因子库_模板")
shot_path = r"C:\Users\sanyi\.dsh\attachments\v1\objects\82\823169190f1ed2d1cbf70e4e6a11618468fffa3435e10d8396db19d7c92b687e"
full = np.asarray(Image.open(shot_path).convert("L"), dtype=np.float32)

def ncc_map(region, tpl):
    th, tw = tpl.shape
    win = sliding_window_view(region, (th, tw))
    wm = win.mean(axis=(2, 3))
    wv = win.var(axis=(2, 3))
    t = tpl - tpl.mean()
    ts = t.std() + 1e-6
    num = np.einsum("ijkl,kl->ij", win, t) / (th * tw)
    return num / (np.sqrt(np.maximum(wv, 1e-6)) * ts)

tiles = {
    "格子1": (905, 180, 1005, 280),
    "格子2": (1030, 180, 1130, 280),
    "格子3": (1155, 180, 1255, 280),
    "格子4": (1280, 180, 1380, 280),
}
scales = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.15, 1.3]
names = [f[:-4] for f in sorted(os.listdir(TPL)) if f.endswith(".png")]

for tile, (x0, y0, x1, y1) in tiles.items():
    region = full[y0:y1, x0:x1]
    scores = []
    for n in names:
        img = Image.open(os.path.join(TPL, n + ".png")).convert("L")
        best = -2
        bests = 0
        for s in scales:
            w, h = max(8, int(img.width * s)), max(8, int(img.height * s))
            if w > region.shape[1] - 4 or h > region.shape[0] - 4:
                continue
            t = np.asarray(img.resize((w, h), Image.LANCZOS), dtype=np.float32)
            m = ncc_map(region, t)
            v = float(m.max())
            if v > best:
                best, bests = v, s
        scores.append((best, n, bests))
    scores.sort(reverse=True)
    print(tile, " -> ", ", ".join("%s %.3f@%.2f" % (n, s, sc) for s, n, sc in scores[:4]))