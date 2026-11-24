# -*- coding: utf-8 -*-
import os, hashlib, urllib.request, urllib.parse, ssl, json, time

OUT = r"C:\Users\sanyi\Documents\dsh\soulknight"
LIB = os.path.join(OUT, "因子库")
os.makedirs(LIB, exist_ok=True)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

FACTORS = {
    "恶性-大恶": ["双倍快乐", "元素异常", "脆皮", "人海战术", "双领主", "细胞分裂"],
    "恶性-中恶": ["武器过热", "劣质药剂", "闪现的敌人", "坚强的敌人", "亢奋的敌人", "狂野的敌人",
                 "近视眼", "无知觉", "梦游", "地牢扩建", "全面戒备", "跟踪激光", "箱子突变", "瘴气弥漫"],
    "恶性-小恶": ["精神的敌人", "坏运气", "能量衰弱", "冷却翻倍", "加价", "炸弹宝箱", "单武器", "孤勇者"],
    "中性": ["跑起来的敌人", "天赋选择+1", "天赋位置+1", "硬壳守护", "既视感", "时间流速不稳定",
             "配置错误", "近战限定", "多重人格", "武器重铸", "精准强化术", "巨大化", "缩小隧道",
             "肌肉强化术", "攻速强化术"],
    "良性-小良": ["天降神兵", "驯兽师", "复活苏生", "神灯", "天赋自助餐", "好运气", "多重雕像"],
    "良性-大良": ["无限能量", "特卖日", "越战越勇", "配件大师", "生命恢复术", "天赋异禀",
                 "疯狂匕首", "宠物巨大化", "冷却减半"],
    "周挑战": ["时空裂隙", "爆爆爆", "命中注定", "扭曲空间", "红色通缉令", "火山喷发", "怪物飞艇", "与时间赛跑"],
    "特殊": ["冻伤", "体魄强健", "盗墓者诅咒", "赐福手杖", "精神错乱", "心志坚毅",
             "经脉错乱", "经脉畅通", "敌人复活", "团结就是力量！"],
}

def url_for(name):
    fn = name + ".png"
    h = hashlib.md5(fn.encode("utf-8")).hexdigest()
    return "https://huiji-public.huijistatic.com/yqqs/uploads/%s/%s/%s" % (h[0], h[:2], urllib.parse.quote(fn))

ok, fail = [], []
for cat, names in FACTORS.items():
    for n in names:
        dst = os.path.join(LIB, n + ".png")
        if os.path.exists(dst) and os.path.getsize(dst) > 300:
            ok.append((cat, n, "cached"))
            continue
        u = url_for(n)
        try:
            req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://yqqs.huijiwiki.com/"})
            data = urllib.request.urlopen(req, timeout=25, context=ctx).read()
            if len(data) < 200:
                raise RuntimeError("too small %d" % len(data))
            open(dst, "wb").write(data)
            ok.append((cat, n, len(data)))
        except Exception as e:
            fail.append((cat, n, str(e)))

index = {cat: names for cat, names in FACTORS.items()}
open(os.path.join(OUT, "因子索引.json"), "w", encoding="utf-8").write(
    json.dumps(index, ensure_ascii=False, indent=2))

print("OK:", len(ok), "FAIL:", len(fail))
for f in fail:
    print("  FAIL", f)
print("saved to", LIB)