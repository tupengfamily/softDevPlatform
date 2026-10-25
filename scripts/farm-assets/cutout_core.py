#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
作物精灵抠图**共享核心**（2026-10-24 抽出）。

为什么抽出来：
    之前抠图算法只写在 `prep_crops.py` 里，处理 22 种作物专属图。
    但还有另外 10 张图走的是旧色度法（`prep.py` 的 `cutout`）：
        plant_rice_young/ripe、plant_wheat_young/ripe、
        plant_corn_young/ripe、plant_soybean_young/ripe、
        plant_tree、plant_grass_tuft
    旧算法的实测残留（渲染可见口径）：
        plant_wheat_ripe   37.43%
        plant_grass_tuft   26.49%
        plant_corn_ripe    20.81%
        plant_tree          7.17%
    —— 叶片之间的白底成块保留，这就是"纸片感"。

    抽出共享模块后，两个管线共用同一套算法，行为一致。

🔴 **管线全貌**（两条，共 52 张 albedo）：
    ① `prep_crops.py`     22 种作物专属图（src: hort/src/<crop>/{young,ripe}.png）
    ② `prep_legacy.py`    10 张旧图（src: src/plant_*.png）—— 本模块调用方
    另有 `prep_crops_normal.py` 由 albedo 派生法线图。

核心算法（8 步，每步都有实测数据支撑）：
    1. 背景候选：低饱和 + 高亮
    2. 3×3 邻域去噪
    3. 闭运算 → 连通性判定（**只用于 flood，不当抠除掩码**）
    4. 实际抠除：reachable（位置约束）× 绿-蓝差（颜色确认）
    5. 投影阴影：不通到外部但紧贴已识别背景
    6. 孔洞抠除：在**前景掩码**上定义（fill_holes(~outside)）
    7. 近白边缘环抠除
    8. alpha 羽化 + 近白半透归零 + RGB 颜色扩散
"""
import numpy as np
from PIL import Image, ImageFilter

# ---- 全局参数（2026-10-24 实测确定）----
CLOSE_RATIO = 0.03     # 闭运算半径 = 短边 × 3%
HOLE_AREA_RATIO = 0.50  # 单个孔洞面积安全阀 = 画布 × 50%
GB_LIMIT = 55          # 绿-蓝差上限：背景/阴影 G-B ≈ 0~45，植物 G-B 60~130
LUM_MIN = 140          # 亮度下限：背景/阴影亮度 > 140

# 🔴 **饱和度上限**（2026-10-24 新增，用于救回「黄色系主体」）。
#
#   问题：绿-蓝差（G-B < 55）判据把**黄色系主体**误判成背景 ——
#     wheat_ripe 丢失像素：gb p50=47  sat p50=0.349
#     tulip_ripe 丢失像素：gb p50=49  sat p50=0.321
#     melon_ripe 丢失像素：gb p50=51  sat p50=0.320
#   都是「G-B 差值恰好卡在 55 附近」的高饱和像素（金黄麦穗、暖色花瓣）。
#
#   而**真正的背景/阴影**饱和度低：
#     带绿调的投影阴影 sat p50=0.113，源图叶片之间的白底 sat 最高 0.25
#   → 饱和度是区分「黄色主体」与「灰白背景」的关键判据。
#
#   取值 1.0 = 不限制（等价于旧行为）。
#
#   🔴 **实测扫描结果**（2026-10-24，12 张采样：黄色系问题作物 + 绿系对照）：
#       SAT_MAX   平均主体损失   最差主体损失   平均残留*
#         1.00       4.604%        17.59%      0.648%
#         0.35       2.949%        10.75%      0.287%
#         0.32       1.633%         7.78%      0.260%
#         0.30       0.000%         0.00%      0.236%
#         0.28       0.000%         0.00%      0.208%   ← 选定
#         0.25       0.000%         0.00%      0.157%
#     (* 残留列用了 `sat < sat_max` 判据，跨参数循环论证、不可直接比较；
#      选定 0.28 后用独立口径 `eval_alpha_residual.py` 复测。)
#
#   **为什么选 0.28 而不是更严的 0.25**：
#     · 0.25 / 0.28 / 0.30 的主体损失都是 0.000% —— 这段区间是免费的
#     · 但黄色系主体的丢失像素 sat p50=0.32，**0.30 就贴着脸了**
#     · 0.28 留一点余量，同时比 0.30 多抠一点背景
SAT_MAX = 0.28


def np2img(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def dilate(mask, radius):
    """布尔膨胀（用 PIL 的 MaxFilter，比 numpy 手写快且边界处理正确）。"""
    if radius <= 0:
        return mask
    m = Image.fromarray((mask * 255).astype(np.uint8))
    m = m.filter(ImageFilter.MaxFilter(radius * 2 + 1))
    return np.array(m) > 127


def close_mask(mask, radius):
    """
    形态学闭运算（先膨胀后腐蚀）：把相邻的碎块**合并**。

    ⚠️ **只用于连通性判定，不能直接当抠除掩码**（2026-10-24 实测教训）：
      直接当掩码 → 膨胀把背景推进叶片像素 → 细叶作物被撑破成碎片
      （梨苗 aspect 0.47 → 0.176，谷子 0.50 → 0.363）。

    @param mask    布尔掩码（True = 背景候选）
    @param radius  膨胀/腐蚀半径（像素）
    """
    if radius <= 0:
        return mask
    d = dilate(mask, radius)
    m = Image.fromarray((d * 255).astype(np.uint8))
    m = m.filter(ImageFilter.MinFilter(radius * 2 + 1))
    return np.array(m) > 127


def flood_from_border(cand, connectivity=4):
    """
    从画面边界出发做连通域填充，返回所有「与边界连通」的候选像素。

    ⚠️ **用 numpy 整数数组当栈，不要用 Python 元组列表**（2026-10-24 实测）：
      元组列表在 1024×1536 画布上会吃掉几百 MB → 批量跑 44 张直接 OOM (Exit 137)。
      改成扁平 int64 数组（y*w+x）后内存降到 1/8，且快一个数量级。
    """
    h, w = cand.shape
    out = np.zeros((h, w), dtype=bool)
    if connectivity == 8:
        offs = ((-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1))
    else:
        offs = ((-1, 0), (1, 0), (0, -1), (0, 1))

    seeds = []
    for x in range(w):
        if cand[0, x]:
            seeds.append(x)
        if cand[h - 1, x]:
            seeds.append((h - 1) * w + x)
    for y in range(h):
        if cand[y, 0]:
            seeds.append(y * w)
        if cand[y, w - 1]:
            seeds.append(y * w + w - 1)
    if not seeds:
        return out
    stack = np.array(seeds, dtype=np.int64)
    out.reshape(-1)[stack] = True

    while stack.size:
        idx = stack
        ys = idx // w
        xs = idx % w
        nxt = []
        for dy, dx in offs:
            ny = ys + dy
            nx = xs + dx
            ok = (ny >= 0) & (ny < h) & (nx >= 0) & (nx < w)
            if not ok.any():
                continue
            yy = ny[ok]
            xx = nx[ok]
            flat = yy * w + xx
            take = cand.reshape(-1)[flat] & (~out.reshape(-1)[flat])
            if not take.any():
                continue
            sel = flat[take]
            out.reshape(-1)[sel] = True
            nxt.append(sel)
        if nxt:
            stack = np.concatenate(nxt)
        else:
            stack = np.array([], dtype=np.int64)
    return out


def _drop_big_holes(holes, max_area):
    """把面积超过 max_area 的连通域从 holes 里剔除（逐个判断，不是整体判断）。"""
    if not holes.any():
        return holes
    h, w = holes.shape
    keep = np.zeros_like(holes)
    visited = np.zeros_like(holes)
    ys, xs = np.nonzero(holes)
    for y0, x0 in zip(ys.tolist(), xs.tolist()):
        if visited[y0, x0]:
            continue
        stack = [(y0, x0)]
        visited[y0, x0] = True
        comp = []
        while stack:
            y, x = stack.pop()
            comp.append((y, x))
            for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and holes[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    stack.append((ny, nx))
        if len(comp) <= max_area:
            for (y, x) in comp:
                keep[y, x] = True
    return keep


def fill_holes(mask, max_area_ratio=HOLE_AREA_RATIO):
    """
    找出 mask 里**被完全包围**的区域（返回 True = 孔洞）。

    ⚠️ **参数语义（2026-10-24 实测教训，重要，两次都踩过）**：

      本函数返回的是 **mask 的子集**（holes ⊆ mask）——
      「在 mask 内部、且不与 mask 的外部连通」的区域。

      ✅ **正确用法**：孔洞定义在**前景掩码**上。
         `fg = ~outside`（植物），`fill_holes(fg)` 找出
         「被植物完全包围、但不属于植物」的区域 = 叶间缝隙里的白底。

      ❌ **错误用法 1**：传 `outside`（背景掩码）。
         holes ⊆ outside → `outside | holes` 是 no-op，这一步静默失效。
         （2026-10-24 实际踩过，管线里这一步从来没生效。）

      ❌ **错误用法 2**：传 `~outside` 却想找「被植物包围的白底」。
         方向反了 —— 那是找「被植物包围的植物」，实测返回 0%。

    ⚠️ **逐连通域面积安全阀**：单个孔洞 > 画布 `max_area_ratio` 就放过。
       🔴 **2026-10-24 实测：8% → 50%**（44 组对照，主体损失全程 0.000%）。
       8% 太紧 —— 白菜的叶球把背景围成一大片（约占画布 1/4），
       超过 8% 被放过 → 白菜残留 5.2%（全作物最差）；放宽到 50% → 0.000%。
       敢放宽的依据：保护主体的是**颜色判据**（gb<55），面积阀只是兜底。
    """
    h, w = mask.shape
    reach = np.zeros((h, w), dtype=bool)
    stack = []
    for x in range(w):
        if mask[0, x] and not reach[0, x]:
            reach[0, x] = True
            stack.append((0, x))
        if mask[h - 1, x] and not reach[h - 1, x]:
            reach[h - 1, x] = True
            stack.append((h - 1, x))
    for y in range(h):
        if mask[y, 0] and not reach[y, 0]:
            reach[y, 0] = True
            stack.append((y, 0))
        if mask[y, w - 1] and not reach[y, w - 1]:
            reach[y, w - 1] = True
            stack.append((y, w - 1))
    while stack:
        y, x = stack.pop()
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not reach[ny, nx]:
                reach[ny, nx] = True
                stack.append((ny, nx))
    holes = mask & (~reach)
    return _drop_big_holes(holes, h * w * max_area_ratio)


def color_bleed(rgba, iters=8):
    """
    RGB 颜色扩散（color bleed）：把透明/半透区的 RGB 用最近的不透明像素颜色填充。

    🔴 **为什么需要它**（2026-10-24 实测修复，"纸片感"的最后一层根因）：

      three.js 的 `alphaTest` 材质在 `transparent=false` 时**不做 alpha 混合**，
      只做二值裁剪：`alpha < alphaTest` 的像素丢弃，其余**原样输出**。

      而抠图只改 alpha、**不改 RGB** →
      羽化带（alpha=150）的 RGB 仍然是**白底色** →
      它通过了 alphaTest（0.588 > 0.42）→ 白底色被原样显示 → **叶缘白边**。

      ⚠️ 之前试过 unpremultiply（反解出不含背景色的真实色），
         方向是反的 —— 当时用「不透明邻居均值」当背景色（那是植物色）。
         用白底色反解的话，纯白底像素会算出 0（纯黑），也不对。

      ✅ 颜色扩散是行业标准做法：把透明区的颜色用最近的实体像素颜色外扩，
         羽化带就变成"植物色的柔化边缘"，配合 alphaToCoverage 就是完美软边。

    ⚠️ **只改 RGB，不改 alpha** —— 轮廓形状完全不变，主体的像素一个不动。

    @param rgba  H×W×4 的 uint8 数组
    @param iters 扩散轮数（每轮向外扩 1 像素）
    """
    a = rgba[:, :, :3].astype(np.float32)
    al = rgba[:, :, 3]
    opaque = al >= 250
    if not opaque.any():
        return rgba

    out = a.copy()
    known = opaque.copy()
    for _ in range(iters):
        new_known = dilate(known, 1) & ~known
        if not new_known.any():
            break
        acc = np.zeros_like(out)
        cnt = np.zeros(al.shape, dtype=np.float32)
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            shifted_known = np.roll(np.roll(known, dy, axis=0), dx, axis=1)
            shifted_col = np.roll(np.roll(out, dy, axis=0), dx, axis=1)
            acc += shifted_col * shifted_known[:, :, None]
            cnt += shifted_known
        with np.errstate(divide="ignore", invalid="ignore"):
            avg = np.where(cnt[:, :, None] > 0, acc / np.maximum(cnt[:, :, None], 1), out)
        out[new_known] = avg[new_known]
        known = known | new_known

    res = rgba.copy()
    res[:, :, :3] = np.clip(out, 0, 255).astype(np.uint8)
    return res


def cutout(path, target_h=None, keep_aspect=True, return_alpha=False, sat_max=None):
    """
    抠掉浅色背景 + 投影阴影，保留植物。

    ⚠️ **2026-10-24 重写：为什么不能用「颜色距离 + 边界连通」**。

      原实现：算每像素到背景色的距离 dist < soft → 与边界连通 → 判为背景。
      实测两个硬伤：
        ① **投影阴影抠不掉** —— 商品图的投影阴影压在植物后面，
           被植物挡住、不与画布边界连通 → 整片保留下来。
           画面表现：萝卜右侧一圈灰白云影（radish 残留 7.4%，小麦最高 37.4%）。
        ② **软过渡带范围失控** —— 条件 `edge = (~outside) & (dist < soft)`
           把阴影周围那一大片浅灰过渡区全判成半透明 →
           14514 个半透明像素里 13913 个是接近白的（96%）。

    **新判据：低饱和 + 高亮 = 背景或阴影**，配合**绿-蓝差**做颜色确认。

    管线 8 步（每步都有实测数据支撑，见模块头部注释）：
      1. 背景候选：低饱和 + 高亮
      2. 3×3 邻域去噪
      3. 闭运算 → 连通性判定（**只用于 flood，不当抠除掩码**）
      4. 实际抠除：reachable（位置约束）× 绿-蓝差（颜色确认）
      5. 投影阴影：不通到外部但紧贴已识别背景
      6. 孔洞抠除：在**前景掩码**上定义（fill_holes(~outside)）
      7. 近白边缘环抠除
      8. alpha 羽化 + 近白半透归零 + RGB 颜色扩散

    @param path       源图路径（RGB）
    @param target_h   输出高度（None = 不缩放）
    @param keep_aspect 保持宽高比
    @return PIL RGBA 图（已裁掉透明边框）
    """
    im = Image.open(path).convert("RGB")
    a = np.array(im).astype(np.float32)
    h, w, _ = a.shape

    # 背景参考：四角各取 24×24 均值
    k = 24
    corners = np.concatenate([
        a[:k, :k].reshape(-1, 3), a[:k, -k:].reshape(-1, 3),
        a[-k:, :k].reshape(-1, 3), a[-k:, -k:].reshape(-1, 3),
    ])
    bg = corners.mean(axis=0)
    bgLum = float((bg[0] * 0.2126 + bg[1] * 0.7152 + bg[2] * 0.0722))

    mx = a.max(axis=2)
    mn = a.min(axis=2)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1.0), 0.0)
    lum = a[:, :, 0] * 0.2126 + a[:, :, 1] * 0.7152 + a[:, :, 2] * 0.0722
    # sat_max：None → 用模块默认值（SAT_MAX）；传值则覆盖（供参数扫描用）
    if sat_max is None:
        sat_max = SAT_MAX
    # 🔴 **绿-蓝差（G-B）**：植物性指标（2026-10-24 实测）。
    #   背景（纯白/灰白/带绿调的投影阴影）：G ≈ B（差值 0~40）
    #   植物（任何绿的）：G >> B（差值 60~130）
    #   ⚠️ 注意：源图里叶片之间的背景**不是纯白**，而是带淡绿调的灰白
    #     （sat 最高到 0.25、G-B 最高到 45）—— 单纯靠 sat<0.10 抓不住。
    gb = a[:, :, 1] - a[:, :, 2]

    # ---- 1) 背景候选：低饱和 + 高亮 ----
    bg_cand = (sat < 0.10) & (lum > bgLum * 0.75)
    # 去掉孤立噪点（3×3 邻域至少 5 个候选）
    ci = bg_cand.astype(np.int32)
    neigh = np.zeros_like(ci)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            neigh += np.roll(np.roll(ci, dy, axis=0), dx, axis=1)
    bg_cand = bg_cand & (neigh >= 5)

    # ---- 2) 闭运算：把被叶片切碎的背景缝隙接回外部背景 ----
    #
    # 🔴 **闭运算只参与「连通性判定」，不参与「实际抠除」**（实测教训，见 close_mask）。
    #   半径按画布尺寸自适应：短边 × 3%（实测 1.5% → 3%，残留 0.504% → 0.317%，
    #   主体损失仍是 0.000%）。原因：叶片缝隙的"细颈"宽度可达 20~30 像素。
    close_r = max(3, int(round(min(h, w) * CLOSE_RATIO)))

    # ---- 3) 连通性判定（用闭运算后的掩码）----
    closed = close_mask(bg_cand, close_r)
    reachable = flood_from_border(closed)

    # ---- 4) 实际抠除：reachable（位置约束）× 绿-蓝差（颜色确认）----
    #
    # 🔴🔴 **最终根因修复**。
    #   `reachable` 已正确判定「哪些像素能通到画面外部」，
    #   但 `bg_cand`（sat<0.10）把 sat 0.10~0.25 的那层白底排除在外，
    #   结果位置判据白算 —— 那层白底漏网。
    #   实测残留白底 3.265% 的性质：100% 满足 gb<45，但只有 1.7% 在 bg_cand 里
    #   （sat p50=0.133 > 0.10）→ 是「与外部连通、但颜色比纯白略深」的背景。
    #
    #   ✅ **位置约束 × 颜色确认**：
    #       outside = reachable & (gb < 55) & (lum > 140)
    #   `reachable` 天然是位置约束 —— **主体像素不在其中**（主体把背景完全隔开）。
    #   所以即使颜色判据放宽，也不会伤到主体。
    loose = loose_mask(gb, lum, sat, sat_max)
    outside = reachable & loose
    del loose

    # ---- 5) 投影阴影：独立的灰块，紧贴背景区 → 也抠掉 ----
    #   条件：低绿蓝差 + 亮度够（是背景色系）
    #        + 不与边界连通（否则已是背景）
    #        + 与已识别的背景区相邻（阴影总是贴着背景）
    shadow = loose_mask(gb, lum, sat, sat_max) & (~reachable) & dilate(outside, 3)
    outside = outside | shadow

    # ---- 6) 孔洞抠除：被植物完全包围的残留白底 ----
    #
    #   位置约束是本方案的关键（区别于"全局放宽颜色阈值"）：
    #     · 白底残留在「植物**内部**的缝隙」→ 可以用宽松颜色判据确认
    #     · 真实叶缘高光在「植物的**外轮廓**边缘」→ 绝不能碰
    #   面积安全阀 50%：白菜的叶球把背景围成一大片，超 8% 被放过 → 残留 5.2%。
    fg_mask = ~outside
    internal = fill_holes(fg_mask, max_area_ratio=HOLE_AREA_RATIO)
    outside = outside | (internal & loose_mask(gb, lum, sat, sat_max))

    # ---- 7) 近白边缘环抠除：外轮廓上最后的一圈白羽化 ----
    #   源图里叶片的外轮廓有一圈**白色羽化环**（真实照片的柔边），
    #   它的绿蓝差在 0~45 之间但饱和度可以到 0.25 ——
    #   第 4 步的 `reachable` 可能因为「细颈太窄」而漏掉这一圈，
    #   于是白环留下 → 渲染出来叶片边缘一圈白描边。
    #   ⚠️ **参数必须收紧**（实测扫描 sat×radius 全组合）：
    #     sat<0.13 & r=2 → 主体损失 1.9~5.3%（谷子/稻子/梨苗掉须）
    #     sat<0.10 & r=1 → 主体损失 0.0~0.8%（全部安全）✅
    near_white = (sat < 0.10) & (mx > 175)
    edge_ring = near_white & dilate(outside, 1) & (~outside)
    outside = outside | edge_ring

    # ---- 8) alpha：背景 0，边缘 1 像素羽化，其余 255 ----
    alpha = np.full((h, w), 255, dtype=np.float32)
    alpha[outside] = 0
    fg = ~outside
    border = fg & dilate(outside, 1)
    alpha[border] = 150

    # 🔴 **近白半透像素直接归零**（实测修复）。
    #   剩下的白色残留不是"没抠掉的整块"，而是**羽化带里的近白像素**：
    #   源图里叶片下方的浅色投影/透光被 alpha=150 羽化保留下来，
    #   渲染时贴在土上就是一片片白斑（甜瓜特写里非常明显）。
    #   实测：甜瓜半透像素 11507 个，其中 49.3% 是近白（sat<0.12 且 max>190）。
    semi = (alpha > 0) & (alpha < 255)
    white_semi = semi & (sat < 0.12) & (mx > 190)
    alpha[white_semi] = 0

    # 收一下：alpha 太低的地方直接清掉
    alpha[alpha < 26] = 0

    rgba = np.dstack([a, alpha]).astype(np.uint8)

    # ---- 9) RGB 颜色扩散（color bleed）----
    #   three.js alphaTest 材质在 transparent=false 时不做 alpha 混合，
    #   羽化带的 RGB 仍是白底色 → 通过 alphaTest → 叶缘白边。
    #   实测（半透带 vs 主体的亮度差）：甜瓜 +37 → +23，土豆 +13 → +7。
    rgba = color_bleed(rgba)

    # 🔴 **源图坐标系的 alpha 掩码**（2026-10-24）。
    #   产物会被裁边+缩放，**坐标系会变** ——
    #   拿产物去和源图逐像素比对必然错位（实测主体损失误报 6~29%，
    #   而甜瓜的真实主体损失是 0.000%）。
    #   所以这里额外返回**源图坐标系**的 alpha 掩码，
    #   供 `eval_subject_loss.py` 在同一坐标系下精确比对。
    if return_alpha:
        src_alpha = alpha.astype(np.uint8)

    out = Image.fromarray(rgba, "RGBA")
    bbox = out.getchannel("A").getbbox()
    if bbox:
        out = out.crop(bbox)

    if target_h:
        w2, h2 = out.size
        nw = max(1, round(w2 * target_h / h2)) if keep_aspect else target_h
        out = out.resize((nw, target_h), Image.LANCZOS)

    if return_alpha:
        return out, src_alpha
    return out


def loose_mask(gb, lum, sat=None, sat_max=None):
    """
    背景/阴影的颜色判据：绿-蓝差小 + 亮度够（+ 饱和度不高）。

    🔴 **sat_max 的作用**（2026-10-24）：救回「黄色系主体」。
      金黄麦穗 / 暖色花瓣的 G-B 差值恰好卡在 55 附近（实测 p50=47~51），
      光看 G-B 会把它们当成背景抠掉。
      加上饱和度上限后，只有「低饱和的灰白」才算背景。
    """
    m = (gb < GB_LIMIT) & (lum > LUM_MIN)
    cap = SAT_MAX if sat_max is None else sat_max
    if sat is not None and cap < 1.0:
        m = m & (sat < cap)
    return m


def merge_sprites_json(out_dir, aspects):
    """
    合并写入 `sprites.json`（实测宽高比，纯数值）。

    🔴 **结构必须统一成纯数值**（2026-10-24 实测教训）：
      旧脚本写的是 `{"plant_x": {"w":..,"h":..,"size":..}}`（对象），
      新脚本写的是 `{"plant_x": 0.78}`（数字）。
      混在一起会让消费方（前端 SPRITE_ASPECT / 校验脚本）拿到对象而非数字，
      几何比例直接变成 NaN → 整片作物消失。

    非数值的历史条目会按**实际 PNG 文件**重算宽高比，
    文件已不存在的条目直接删除。

    @param out_dir 贴图输出目录
    @param aspects 本次新算出的 {key: aspect}
    """
    import json
    import os

    sprites_path = os.path.join(out_dir, "sprites.json")
    merged = {}
    if os.path.isfile(sprites_path):
        try:
            with open(sprites_path, "r", encoding="utf-8") as f:
                merged = json.load(f)
        except (ValueError, OSError):
            merged = {}

    for key in list(merged.keys()):
        png = os.path.join(out_dir, "%s.png" % key)
        if not os.path.isfile(png):
            del merged[key]
            continue
        if not isinstance(merged[key], (int, float)) or isinstance(merged[key], bool):
            with Image.open(png) as im:
                pw, ph = im.size
            merged[key] = round(pw / ph, 3)

    merged.update(aspects)
    with open(sprites_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2, ensure_ascii=False, sort_keys=True)
    return merged
