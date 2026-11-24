# -*- coding: utf-8 -*-
"""把 wiki 原始精灵图归一化成 128x128 的模板（等比缩放居中，铺游戏底色），
并生成一张带名字的总览图。"""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = r"C:\Users\sanyi\Documents\dsh\soulknight"
LIB = os.path.join(BASE, "因子库")
TPL = os.path.join(BASE, "因子库_模板")
os.makedirs(TPL, exist_ok=True)
BG = (29, 32, 39)
BOX = 100          # 内容最大边长
CANVAS = 128

def norm(path, dst):
    im = Image.open(path).convert("RGBA")
    bbox = im.getbbox()
    if bbox:
        im = im.crop(bbox)
    w, h = im.size
    s = BOX / max(w, h)
    im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    canvas = Image.new("RGB", (CANVAS, CANVAS), BG)
    canvas.paste(im, ((CANVAS - im.width) // 2, (CANVAS - im.height) // 2), im)
    canvas.save(dst)

names = []
for f in sorted(os.listdir(LIB)):
    if not f.lower().endswith(".png"):
        continue
    name = f[:-4]
    names.append(name)
    norm(os.path.join(LIB, f), os.path.join(TPL, name + ".png"))

# 总览图
COLS, CELL, PAD, LABEL_H = 8, 128, 10, 22
rows = (len(names) + COLS - 1) // COLS
sheet = Image.new("RGB", (COLS * (CELL + PAD) + PAD,
                          rows * (CELL + LABEL_H + PAD) + PAD), (18, 18, 20))
draw = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 15)
except Exception:
    font = ImageFont.load_default()

for i, n in enumerate(names):
    cx = PAD + (i % COLS) * (CELL + PAD)
    cy = PAD + (i // COLS) * (CELL + LABEL_H + PAD)
    sheet.paste(Image.open(os.path.join(TPL, n + ".png")), (cx, cy))
    draw.text((cx + CELL // 2, cy + CELL + 3), n, fill=(230, 230, 230),
              font=font, anchor="ma")

sheet.save(os.path.join(BASE, "因子总览.png"))
print("模板数:", len(names))
print("输出:", TPL)