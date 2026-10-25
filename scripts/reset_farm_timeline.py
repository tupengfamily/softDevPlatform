#!/usr/bin/env python3
"""
重置稼穑的演示时间轴 —— 把「世界日期」拉回现实日期附近。

为什么需要它
------------
演示模式下每块地有一个时间偏移 {@code demo_offset_days}，
「今天」= 现实今天 + 该偏移。玩家用「推进时间」按钮或建田到未来日期后，
偏移会累积到几百天，于是：

  * 「今天」跑到 2028 年，建田面板的「最近推荐」变成 2029 年；
  * 地块之间的日期跨度能到 2 年（早期地块看起来像落后两年）。

本脚本把整条时间轴**平移**回现实日期附近。

核心原理 —— **平移，不是清零**
--------------------------------
刚建成的田满足：{@code 有效今天 = 现实今天 + 偏移 = 播期}。

若只把偏移清零，播期还停在 2028-09-20，
于是「今天」变成 2026-10-23 而作物种在 2028 年 → 生长计算全乱。

正确做法：对每块地算 {@code delta = 目标今天 - 该地当前有效今天}，
然后 **播期 += delta、偏移 += delta**。
两边同步平移 → **生长天数变化恒为 0**（作物不会突然长完或枯死）。

⚠️ 不能用「统一平移」（所有地块用同一个 delta）：
   那样所有地块挤到同一天，生长天数会突变 183~577 天，作物瞬间成熟/枯死。

平移口径（三类，别搞混）
--------------------------
* **地块（plot）/ 季节档案（season）/ 农事记录（action）** → 按 **各自的 plot delta**
  （它们是「地块内」的时间，保持地块内相对关系）
* **仓库（warehouse）** → 按 **用户级 delta**
  （仓库口径是用户级的，入库日期与老化基准同源，见 FarmWorldDate 类注释）
* **地块的历史成绩列（last_harvest_date）** → 也按 **各自的 plot delta**
  （🔴 2026-10-23 修复：它是「地块内」的历史时间，和播期同一坐标系）

🔴 2026-10-23 踩过的坑（脚本已修，勿回退）
-----------------------------------------
1. **备份必须用 SQLite backup API，不能用 shutil.copy**
   —— {@code shutil.copy} 只复制 .db 主文件，**WAL 里已提交的记录会丢**。
   本轮就是这样漏掉了 plot#48 / season#10 / season#11 / warehouse#20，
   事后只能靠逐条比对还原。
2. **必须处理「备份后新增的行」**
   —— 平移基线是备份时刻，而行可能是在备份之后创建的。
   对备份后新增的地块，要从**它的建田参数**反推应有偏移
   （{@code offset = 建田播期 - 现实今天}），而不是当成 0。
3. **必须平移 last_harvest_date**
   —— 漏了它会让「收获日」停在过去，与 season 档案自相矛盾。
4. **仓库入库日期只能用用户级 delta，且只能平移一次**
   —— 按 plot_id 的 delta 平移会让入库日期跑到未来（仓储天数为负）。
   同一个批次被平移两次（先按 plot 再按 user）也会算错。

用法
----
    python3 scripts/reset_farm_timeline.py                    # 目标 = 现实今天 + 2 天
    python3 scripts/reset_farm_timeline.py --target 2026-11-01
    python3 scripts/reset_farm_timeline.py --dry-run          # 只看计划，不写库

⚠️ 脚本会**先自动备份**数据库到 {@code data/sandbox.db.bak-timeline-<时间戳>}。
"""
import argparse
import datetime
import os
import shutil
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, 'data', 'sandbox.db')
MS = 1000

# 备份后新增的行：需要从「建田参数」反推，而不是当成基线里的普通地块。
#
# 这些 id 是 2026-10-23 那次重置留下的教训：新地块是在备份之后建的，
# 基线快照里根本没有它 → delta 映射缺它的键 → 漏平移或算错。
# 键 = (表名, id)，值 = 该行的「建田播期」（字符串 YYYY-MM-DD）
NEW_ROWS = {
    ('t_farm_plot', 48): '2028-09-20',
}


def real_today() -> datetime.date:
    """现实今天。⚠️ 必须在**当天**跑，不要硬编码。"""
    return datetime.date.today()


def epoch_ms(d: datetime.date) -> int:
    """本地午夜 → epoch 毫秒（与后端 LocalDate 落库口径一致）。"""
    return int(datetime.datetime(d.year, d.month, d.day).timestamp() * MS)


def to_date(v):
    return datetime.date.fromtimestamp(v / MS) if v else None


def backup() -> str:
    """安全备份。

    🔴 **必须用 SQLite backup API**，不能用 shutil.copy：
    copy 只复制 .db 主文件，**WAL 中已提交的记录会整段丢失**。
    backup API 会把主文件 + WAL 一起拍成一份自洽的快照。
    """
    os.makedirs(os.path.dirname(DB), exist_ok=True)
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    dst = DB + '.bak-timeline-' + stamp
    src = sqlite3.connect(DB)
    out = sqlite3.connect(dst)
    try:
        src.backup(out)
    finally:
        out.close()
        src.close()
    return dst


def main():
    ap = argparse.ArgumentParser(description='重置稼穑演示时间轴')
    ap.add_argument('--target', help='目标「今天」YYYY-MM-DD，默认 = 现实今天 + 2 天')
    ap.add_argument('--dry-run', action='store_true', help='只打印计划，不写库')
    args = ap.parse_args()

    real = real_today()
    target = datetime.date.fromisoformat(args.target) if args.target \
        else real + datetime.timedelta(days=2)

    if not os.path.exists(DB):
        print('找不到数据库：' + DB)
        sys.exit(1)

    print('现实今天 = %s    目标「今天」 = %s' % (real, target))
    bak = backup()
    print('已备份 → %s\n' % os.path.basename(bak))

    con = sqlite3.connect(DB)
    cur = con.cursor()

    # ---- 1. 算每块地的 delta ----
    #
    # delta(plot) = 目标今天 - 该地「当前有效今天」
    # 当前有效今天 = 现实今天 + 该地偏移
    plot_delta = {}
    for pid, off in cur.execute('select id, demo_offset_days from t_farm_plot'):
        plot_delta[pid] = (target - (real + datetime.timedelta(days=off or 0))).days

    # 🔴 备份后新增的地块：基线里没有 → delta 映射缺键。
    # 从它的建田播期反推应有偏移：offset = 建田播期 - 现实今天。
    for (table, pid), sow_text in NEW_ROWS.items():
        if table != 't_farm_plot':
            continue
        row = cur.execute('select demo_offset_days from t_farm_plot where id=?',
                          (pid,)).fetchone()
        if row is None:
            continue
        built_sow = datetime.date.fromisoformat(sow_text)
        implied_off = (built_sow - real).days
        expected = (target - (real + datetime.timedelta(days=implied_off))).days
        if plot_delta.get(pid) != expected:
            print('  ⚠️ plot#%s 偏移不符：当前 %s，期望 %s（按建田播期 %s 反推）'
                  % (pid, row[0], implied_off, sow_text))
            plot_delta[pid] = expected

    # 用户级 delta：仓库口径
    max_off = max((r[0] or 0) for r in cur.execute('select demo_offset_days from t_farm_plot'))
    user_delta = (target - (real + datetime.timedelta(days=max_off))).days
    print('地块 delta 映射 = %d 条；用户级 delta = %d 天\n' % (len(plot_delta), user_delta))

    def shift_plot(pid, v):
        return None if v is None else epoch_ms(
            to_date(v) + datetime.timedelta(days=plot_delta.get(pid, 0)))

    def shift_user(v):
        return None if v is None else epoch_ms(
            to_date(v) + datetime.timedelta(days=user_delta))

    # ---- 2. 打印计划 ----
    print('%-4s %-14s %-12s %6s %-12s %-12s %6s' % (
        'id', '作物', '原播期', '原偏移', '新播期', '新今天', '生长天'))
    for pid, crop, sow, off in cur.execute(
            'select id, crop_id, sow_date, demo_offset_days from t_farm_plot order by slot'):
        cur_today = real + datetime.timedelta(days=off or 0)
        new_sow = to_date(sow) + datetime.timedelta(days=plot_delta.get(pid, 0)) if sow else None
        new_off = max(0, (off or 0) + plot_delta.get(pid, 0))
        new_today = real + datetime.timedelta(days=new_off)
        g_old = (cur_today - to_date(sow)).days if sow else 0
        g_new = (new_today - new_sow).days if new_sow else 0
        flag = '' if g_old == g_new else '  ⚠️生长天数变了 %+d' % (g_new - g_old)
        print('%-4s %-14s %-12s %6d %-12s %-12s %6d%s' % (
            pid, str(crop), str(to_date(sow)), off or 0, str(new_sow), str(new_today), g_new, flag))

    if args.dry_run:
        print('\n--dry-run：未写库')
        con.close()
        return

    # ---- 3. 写入 ----
    for pid, sow, off in list(cur.execute(
            'select id, sow_date, demo_offset_days from t_farm_plot')):
        cur.execute('update t_farm_plot set sow_date=?, demo_offset_days=? where id=?',
                    (shift_plot(pid, sow), max(0, (off or 0) + plot_delta.get(pid, 0)), pid))

    # 🔴 历史成绩列：last_harvest_date 也是「地块内」的时间，必须一起平移，
    # 否则「收获日」停在过去，与 season 档案自相矛盾。
    for pid, lh in list(cur.execute('select id, last_harvest_date from t_farm_plot')):
        cur.execute('update t_farm_plot set last_harvest_date=? where id=?',
                    (shift_plot(pid, lh), pid))

    for sid, pid, sow, harv in list(cur.execute(
            'select id, plot_id, sow_date, harvest_date from t_farm_season')):
        cur.execute('update t_farm_season set sow_date=?, harvest_date=? where id=?',
                    (shift_plot(pid, sow), shift_plot(pid, harv), sid))

    for aid, pid, dv in list(cur.execute('select id, plot_id, date from t_farm_action')):
        cur.execute('update t_farm_action set date=? where id=?', (shift_plot(pid, dv), aid))

    # 仓库：用户级口径（入库日期与老化基准同源）
    for wid, sd, sold in list(cur.execute(
            'select id, stored_date, sold_date from t_farm_warehouse')):
        cur.execute('update t_farm_warehouse set stored_date=?, sold_date=? where id=?',
                    (shift_user(sd), shift_user(sold), wid))

    con.commit()

    # ---- 4. 验证 ----
    print('\n=== 重置后 ===')
    user_today = None
    for pid, crop, sow, off in cur.execute(
            'select id, crop_id, sow_date, demo_offset_days from t_farm_plot order by slot'):
        t = real + datetime.timedelta(days=off or 0)
        user_today = t if user_today is None or t > user_today else user_today
    print('用户级世界日期 = %s' % user_today)
    print('\n仓库（仓储天数应为正）:')
    for wid, name, sd, status, qty in cur.execute(
            'select id, crop_name, stored_date, status, quantity from t_farm_warehouse order by id'):
        dd = to_date(sd)
        age = (target - dd).days if dd else None
        flag = '' if age is None or age >= 0 else '  ⚠️入库日期在未来'
        print('  id=%-3s %-12s 入库=%-12s 仓储%5s天 %-10s 数量=%s%s' % (
            wid, str(name), str(dd), age, status, qty, flag))
    con.close()
    print('\n✅ 时间轴已重置（备份：%s）' % os.path.basename(bak))


if __name__ == '__main__':
    main()
