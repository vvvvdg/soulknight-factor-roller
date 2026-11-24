# -*- coding: utf-8 -*-
"""生成 v4：支持「要的因子」+「不要的因子」双名单，命中要的但含不要的就继续刷。"""
import os, json

BASE = r"C:\Users\sanyi\Documents\dsh\soulknight"
TPL_DIR = os.path.join(BASE, "因子库_模板")
index = json.load(open(os.path.join(BASE, "因子索引.json"), encoding="utf-8"))

ORDER = ["良性-大良", "良性-小良", "中性", "周挑战", "特殊", "恶性-小恶", "恶性-中恶", "恶性-大恶"]
TAG = {"良性-大良": "大良", "良性-小良": "小良", "中性": "中性", "周挑战": "周挑战",
       "特殊": "特殊", "恶性-小恶": "小恶", "恶性-中恶": "中恶", "恶性-大恶": "大恶"}

entries = []
for cat in ORDER:
    for name in index.get(cat, []):
        if os.path.exists(os.path.join(TPL_DIR, name + ".png")):
            entries.append((name, TAG[cat]))

lines = ",\n".join('    ["%s", "%s"]' % (n, t) for n, t in entries)
print("因子条目:", len(entries))

JS = r'''"auto";
/**
 * 元气骑士 · 联机房间自动刷挑战因子  v4
 * 功能：勾选「要的因子」+「不要的因子」
 *      出现要的因子、但同时带着不要的因子 → 继续刷
 *      只有满足「至少出现一个要的」且「一个不要的都没有」才停
 * 运行环境：AutoX.js / Auto.js（Android 7+，免 root）
 */

const CFG = {
    libDir: "/sdcard/元气/因子库",
    refreshImg: "/sdcard/元气/刷新按钮.png",
    threshold: 0.80,
    scales: [0.6, 0.7, 0.8, 0.9, 1.0, 1.15],
    delayAfterClick: 900,
    maxRounds: 500,
    countdown: 3,
    scanHeight: 320,   // 在刷新按钮上方多大范围内找因子图标
};

// ---------- 因子总表 [名字, 分类] ----------
const ALL = [
__ENTRIES__
];

// ---------- 永远不会同时出现的组合（wiki） ----------
const MUTEX = [
    ["巨大化", "缩小隧道"],
    ["攻速强化术", "肌肉强化术"],
    ["孤勇者", "宠物巨大化"],
    ["冷却减半", "冷却翻倍"],
    ["好运气", "坏运气"],
    ["天赋位置+1", "天赋选择+1"],
    ["脆皮", "生命恢复术"],
    ["天降神兵", "驯兽师"],
    ["脆皮", "多重人格"],
    ["硬壳守护", "多重人格"],
    ["无限能量", "能量衰弱"],
];

const store = storages.create("soulknight_factors_v4");

/** 弹多选；不支持时退化为手输 */
function pickMany(title, defaults) {
    const labels = ALL.map(function (e) { return "【" + e[1] + "】" + e[0]; });
    if (typeof dialogs.multiChoice === "function") {
        return dialogs.multiChoice(title, labels, defaults);
    }
    let lastNames = defaults.map(function (i) { return ALL[i][0]; }).join("，");
    let s = dialogs.rawInput(title + "\n（当前版本不支持勾选，请手输名字，逗号分隔）", lastNames);
    if (!s) return null;
    let out = [];
    s.split(/[,，、\s]+/).forEach(function (w) {
        w = w.trim();
        if (!w) return;
        for (let i = 0; i < ALL.length; i++) {
            if (ALL[i][0] === w && out.indexOf(i) < 0) out.push(i);
        }
    });
    return out;
}

function nameOf(i) { return ALL[i][0]; }

// ================= 1. 选「要的」 =================
let lastWant = store.get("want", []);
if (!Array.isArray(lastWant)) lastWant = [];
let wantIdx = pickMany("第一步：要刷哪些因子？（可多选）\n出现其中任意一个才考虑停下", lastWant);

if (wantIdx === null) {
    if (!dialogs.confirm("不限定要的因子？", "你一个都没勾。\n\n这样脚本只按「不要的因子」来筛：\n只要这一把里没有你不要的，就停下。\n\n确定吗？")) {
        exit();
    }
    wantIdx = [];
}
store.put("want", wantIdx);

// ================= 2. 选「不要的」 =================
let lastAvoid = store.get("avoid", []);
if (!Array.isArray(lastAvoid)) lastAvoid = [];
let avoidIdx = pickMany("第二步：出现哪些因子就【继续刷】？（可多选，不想排除了就点取消）", lastAvoid);
if (!avoidIdx) avoidIdx = [];
store.put("avoid", avoidIdx);

if (wantIdx.length === 0 && avoidIdx.length === 0) {
    toastLog("要的和不要的都没选，没法刷");
    exit();
}

// ================= 3. 冲突检查 =================
(function checkMutex() {
    let warns = [];
    wantIdx.forEach(function (wi) {
        avoidIdx.forEach(function (ai) {
            let w = nameOf(wi), a = nameOf(ai);
            for (let k = 0; k < MUTEX.length; k++) {
                let p = MUTEX[k];
                if ((p[0] === w && p[1] === a) || (p[1] === w && p[0] === a)) {
                    warns.push("「" + w + "」和「" + a + "」永远不会同时出现，把后者设为排除没有意义");
                }
            }
        });
    });
    if (warns.length > 0) {
        dialogs.alert("提示", warns.join("\n\n") + "\n\n（不影响使用，只是白设了排除项）");
    }
})();

log("要的：" + wantIdx.map(nameOf).join("、"));
log("不要的：" + (avoidIdx.length ? avoidIdx.map(nameOf).join("、") : "无"));

// ================= 4. 准备模板 =================
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

/** 把一个模板的所有缩放版本预先算好，省得每轮重复 resize */
function buildVariants(name) {
    let img = images.read(CFG.libDir + "/" + name + ".png");
    if (!img) {
        toastLog("缺少模板：" + name);
        return null;
    }
    let vs = [];
    for (let i = 0; i < CFG.scales.length; i++) {
        let s = CFG.scales[i];
        let w = Math.round(img.getWidth() * s);
        let h = Math.round(img.getHeight() * s);
        if (w < 10 || h < 10) continue;
        vs.push({ img: images.resize(img, w, h), w: w, h: h });
    }
    img.recycle();
    return { name: name, variants: vs };
}

const wantTpls = [];
for (let i = 0; i < wantIdx.length; i++) {
    let v = buildVariants(nameOf(wantIdx[i]));
    if (v) wantTpls.push(v);
}
const avoidTpls = [];
for (let i = 0; i < avoidIdx.length; i++) {
    let v = buildVariants(nameOf(avoidIdx[i]));
    if (v) avoidTpls.push(v);
}
if (wantTpls.length === 0 && avoidTpls.length === 0) {
    toastLog("模板一个都没读到，检查 " + CFG.libDir);
    exit();
}

/** 在给定图片里找任意一个模板，返回模板名或 null */
function findAnyIn(img, tpls) {
    for (let i = 0; i < tpls.length; i++) {
        let vs = tpls[i].variants;
        for (let j = 0; j < vs.length; j++) {
            let p = findImage(img, vs[j].img, { threshold: CFG.threshold });
            if (p) return tpls[i].name;
        }
    }
    return null;
}

for (let i = CFG.countdown; i > 0; i--) {
    toast("" + i + " 秒后开始，请切回游戏");
    sleep(1000);
}

let round = 0;
let scanRect = null;   // 复用找到的扫描区域

while (round < CFG.maxRounds) {
    round++;

    // --- 找 ⟳ ---
    let screen = captureScreen();
    let btn = null;
    for (let j = 0; j < CFG.scales.length && !btn; j++) {
        let s = CFG.scales[j];
        let w = Math.round(refreshTpl.getWidth() * s);
        let h = Math.round(refreshTpl.getHeight() * s);
        if (w < 10 || h < 10) continue;
        let scaled = images.resize(refreshTpl, w, h);
        let p = findImage(screen, scaled, { threshold: CFG.threshold });
        scaled.recycle();
        if (p) btn = { x: p.x, y: p.y, w: w, h: h };
    }
    if (!btn) {
        screen.recycle();
        toastLog("第 " + round + " 轮找不到⟳按钮，停止");
        break;
    }

    // 扫描区域：按钮上方一块（只在这里找因子，速度快很多）
    let ry = Math.max(0, btn.y - CFG.scanHeight);
    let rh = Math.min(CFG.scanHeight, btn.y);
    let region = images.clip(screen, 0, ry, screen.getWidth(), rh);
    screen.recycle();

    // --- 先看「要的」 ---
    let gotWant = wantTpls.length === 0 ? "(不限定)" : findAnyIn(region, wantTpls);

    if (!gotWant) {
        region.recycle();
        // 点刷新，进入下一轮
        click(btn.x + Math.round(btn.w / 2), btn.y + Math.round(btn.h / 2));
        sleep(CFG.delayAfterClick);
        if (round % 5 === 0) toast("已刷 " + round + " 次");
        continue;
    }

    // --- 出现了要的，再看有没有「不要的」 ---
    let badOne = avoidTpls.length === 0 ? null : findAnyIn(region, avoidTpls);
    region.recycle();

    if (badOne) {
        log("第 " + round + " 轮：碰到 " + gotWant + "，但带了不要的「" + badOne + "」，继续");
        if (round % 5 === 0) toast("第 " + round + " 次带了「" + badOne + "」，继续");
        click(btn.x + Math.round(btn.w / 2), btn.y + Math.round(btn.h / 2));
        sleep(CFG.delayAfterClick);
        continue;
    }

    // --- 完全满足 ---
    let msg = wantTpls.length === 0 ? ("第 " + round + " 轮：没有不要的因子，停")
                                    : ("第 " + round + " 次命中：" + gotWant);
    toastLog(msg);
    device.vibrate(1200);
    try {
        media.playMusic("/system/media/audio/notifications/Heaven.mp3", 0.8);
        sleep(3000);
        media.stopMusic();
    } catch (e) { }
    exit();
}

toastLog("结束，共刷 " + round + " 次");

refreshTpl.recycle();
wantTpls.concat(avoidTpls).forEach(function (t) {
    t.variants.forEach(function (v) { v.img.recycle(); });
});
'''

JS = JS.replace("__ENTRIES__", lines)
out = os.path.join(BASE, "刷因子_v4.js")
open(out, "w", encoding="utf-8").write(JS)
print("生成:", out, len(JS), "字符")