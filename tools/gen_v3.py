# -*- coding: utf-8 -*-
"""生成带「可视化多选」的 v3 脚本，因子名单从因子索引.json 自动注入，避免手打出错。"""
import os, json

BASE = r"C:\Users\sanyi\Documents\dsh\soulknight"
TPL_DIR = os.path.join(BASE, "因子库_模板")
index = json.load(open(os.path.join(BASE, "因子索引.json"), encoding="utf-8"))

# 展示顺序：好因子在前
ORDER = ["良性-大良", "良性-小良", "中性", "周挑战", "特殊", "恶性-小恶", "恶性-中恶", "恶性-大恶"]
TAG = {
    "良性-大良": "大良", "良性-小良": "小良", "中性": "中性", "周挑战": "周挑战",
    "特殊": "特殊", "恶性-小恶": "小恶", "恶性-中恶": "中恶", "恶性-大恶": "大恶",
}

entries = []
for cat in ORDER:
    for name in index.get(cat, []):
        if os.path.exists(os.path.join(TPL_DIR, name + ".png")):
            entries.append((name, TAG[cat]))

lines = ",\n".join('    ["%s", "%s"]' % (n, t) for n, t in entries)
print("因子条目数:", len(entries))

JS = '''"auto";
/**
 * 元气骑士 · 联机房间自动刷挑战因子  v3（可视化多选版）
 * 运行后会弹出因子列表，勾选你要的，点确定就开始。
 */

const CFG = {
    libDir: "/sdcard/元气/因子库",
    refreshImg: "/sdcard/元气/刷新按钮.png",
    threshold: 0.80,
    scales: [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.25, 1.4],
    delayAfterClick: 900,
    maxRounds: 500,
    countdown: 3,
};

// [因子名, 分类]
const ALL = [
__ENTRIES__
];

// ---------- 1. 弹出多选列表 ----------
const labels = ALL.map(function (e) { return "【" + e[1] + "】" + e[0]; });
const store = storages.create("soulknight_factors");
let lastPicked = store.get("picked", []);
if (!Array.isArray(lastPicked)) lastPicked = [];

const picked = dialogs.multiChoice("选择要刷的因子（可多选）\\n勾选后点「确定」开始", labels, lastPicked);
if (!picked || picked.length === 0) {
    toastLog("没有选择任何因子，已取消");
    exit();
}
store.put("picked", picked);

const targets = picked.map(function (i) { return ALL[i][0]; });
log("本次目标：" + targets.join("、"));

// ---------- 2. 准备模板 ----------
auto.waitFor();
if (!requestScreenCapture(false)) {
    toastLog("没拿到截屏权限，退出");
    exit();
}

const refreshTpl = images.read(CFG.refreshImg);
if (!refreshTpl) {
    toastLog("读不到刷新按钮：" + CFG.refreshImg);
    exit();
}

const targetTpls = [];
for (let i = 0; i < targets.length; i++) {
    let img = images.read(CFG.libDir + "/" + targets[i] + ".png");
    if (img) {
        targetTpls.push({ name: targets[i], img: img });
    } else {
        toastLog("缺少模板：" + targets[i]);
    }
}
if (targetTpls.length === 0) {
    toastLog("目标模板一个都没读到，检查 " + CFG.libDir);
    exit();
}

function findScaled(screen, tpl) {
    for (let i = 0; i < CFG.scales.length; i++) {
        let s = CFG.scales[i];
        let w = Math.round(tpl.getWidth() * s);
        let h = Math.round(tpl.getHeight() * s);
        if (w < 10 || h < 10) continue;
        if (w > screen.getWidth() || h > screen.getHeight()) continue;
        let scaled = images.resize(tpl, w, h);
        let p = findImage(screen, scaled, { threshold: CFG.threshold });
        scaled.recycle();
        if (p) return { x: p.x, y: p.y, w: w, h: h };
    }
    return null;
}

for (let i = CFG.countdown; i > 0; i--) {
    toast("" + i + " 秒后开始，请切回游戏");
    sleep(1000);
}

let round = 0;
while (round < CFG.maxRounds) {
    round++;

    let screen = captureScreen();
    let btn = findScaled(screen, refreshTpl);
    screen.recycle();
    if (!btn) {
        toastLog("第 " + round + " 轮找不到⟳按钮，停止");
        break;
    }
    click(btn.x + Math.round(btn.w / 2), btn.y + Math.round(btn.h / 2));
    sleep(CFG.delayAfterClick);

    let shot = captureScreen();
    let hit = null;
    for (let i = 0; i < targetTpls.length; i++) {
        let p = findScaled(shot, targetTpls[i].img);
        if (p) {
            hit = targetTpls[i].name;
            break;
        }
    }
    shot.recycle();

    if (hit) {
        toastLog("第 " + round + " 次命中：" + hit);
        device.vibrate(1200);
        try {
            media.playMusic("/system/media/audio/notifications/Heaven.mp3", 0.8);
            sleep(3000);
            media.stopMusic();
        } catch (e) { }
        exit();
    }

    if (round % 5 === 0) toast("已刷 " + round + " 次");
}

toastLog("结束，共刷 " + round + " 次");

refreshTpl.recycle();
for (let i = 0; i < targetTpls.length; i++) targetTpls[i].img.recycle();
'''

JS = JS.replace("__ENTRIES__", lines)
out = os.path.join(BASE, "刷因子_v3.js")
open(out, "w", encoding="utf-8").write(JS)
print("生成:", out, len(JS), "字符")