#!/usr/bin/env python3
"""农场素材预处理：地面贴图去水印 + 派生法线/粗糙度；作物精灵抠图 + 根系裁剪 + 归一化。"""
import os
from PIL import Image, ImageFilter, ImageDraw
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
OUT = "/Volumes/data/demo/java/frontend-learning/public/farm/tex/web"
os.makedirs(OUT, exist_ok=True)


def np2img(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


# ---------------- 地面贴图 ----------------

def strip_watermark(im):
    """右下角 AI 水印：裁掉右 13% / 底 9% 后重采样回正方形"""
    w, h = im.size
    im = im.crop((0, 0, int(w * 0.87), int(h * 0.91)))
    return im.resize((1024, 1024), Image.LANCZOS)


def make_normal(im, strength=2.2):
    g = np.array(im.convert("L").filter(ImageFilter.GaussianBlur(0.6)), dtype=np.float32) / 255.0
    # Sobel
    kx = np.array([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]], dtype=np.float32)
    ky = kx.T
    from numpy.lib.stride_tricks import sliding_window_view
    pad = np.pad(g, 1, mode="edge")
    win = sliding_window_view(pad, (3, 3))
    dx = (win * kx).sum(axis=(2, 3))
    dy = (win * ky).sum(axis=(2, 3))
    nx = -dx * strength
    ny = dy * strength
    nz = np.ones_like(nx)
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / ln, ny / ln, nz / ln
    rgb = np.stack([(nx * 0.5 + 0.5) * 255, (ny * 0.5 + 0.5) * 255, (nz * 0.5 + 0.5) * 255], -1)
    return np2img(rgb)


def make_rough(im):
    """亮度 → 粗糙度（暗处=潮湿=光滑；亮处=干燥=粗糙），并压缩动态范围"""
    g = np.array(im.convert("L").filter(ImageFilter.GaussianBlur(2.0)), dtype=np.float32)
    g = g / 255.0
    r = 0.55 + (0.5 - 0.5 * g) * 0.55           # 0.30 ~ 0.83
    r = np.clip(r, 0.22, 0.92)
    return np2img(r * 255)


def proc_ground(src_name, out_name, tile_scale=1.0):
    from PIL import ImageEnhance
    im = Image.open(os.path.join(SRC, src_name)).convert("RGB")
    im = strip_watermark(im)
    # 实拍土偏红/偏暗，稍微去饱和并提亮，贴近华东旱地壤土的灰褐色
    if out_name in ("soil_dry", "soil_paddy"):
        im = ImageEnhance.Color(im).enhance(0.70)
        im = ImageEnhance.Brightness(im).enhance(1.32)
    im.save(os.path.join(OUT, f"{out_name}_albedo.jpg"), quality=88, optimize=True)
    if tile_scale >= 0:
        make_normal(im).resize((512, 512), Image.LANCZOS).save(
            os.path.join(OUT, f"{out_name}_normal.jpg"), quality=90)
        make_rough(im).resize((512, 512), Image.LANCZOS).save(
            os.path.join(OUT, f"{out_name}_rough.jpg"), quality=88)
    print("ground", out_name, im.size)


# ---------------- 精灵抠图 ----------------

def cutout(name, trim_root=True, pad=0.03, out_size=512):
    im = Image.open(os.path.join(SRC, name)).convert("RGB")
    a = np.array(im, dtype=np.float32)
    H, W, _ = a.shape

    # 色度（max-min）：棋盘格/白底接近 0，绿色植株与枯黄茎秆都显著 > 0
    mx = a.max(axis=2)
    mn = a.min(axis=2)
    chroma = mx - mn
    # 亮度辅助：纯白背景亮度极高且色度低
    alpha = np.clip((chroma - 9.0) / 16.0, 0.0, 1.0)

    # 闭运算补掉叶片高光造成的小洞
    ai = np2img(alpha * 255).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))
    alpha = np.array(ai, dtype=np.float32) / 255.0

    # 从四边 flood fill，去掉背景板上的水印文字等孤立残留
    m = (alpha > 0.35).astype(np.uint8) * 255
    mi = Image.fromarray(m, "L")
    for seed in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1),
                 (W // 2, 0), (W // 2, H - 1), (0, H // 2), (W - 1, H // 2)]:
        if mi.getpixel(seed) == 0:
            ImageDraw.floodfill(mi, seed, 128, thresh=8)
    outside = np.array(mi) == 128
    alpha[outside] = 0.0

    solid = (alpha > 0.5)
    rgb = a.copy()

    # 透明区回填前景平均色（消除 mipmap 白边），而非白色
    if solid.any():
        mean_rgb = a[solid].mean(axis=0)
        rgb[~solid] = mean_rgb

    # 根颈线：找"绿色起始行"。根系是去饱和的浅褐白色，不含绿色；
    # 茎叶含绿 → 从底部向上第一个出现足量绿色像素的行即根颈。
    g = a[..., 1] - np.maximum(a[..., 0], a[..., 2])
    green_rows = ((g > 20) & solid).sum(axis=1)
    bottom = H
    if trim_root:
        need = max(6, W * 0.012)
        r = H - 1
        limit = int(H * 0.78)          # 最多裁掉底部 22%
        while r > limit and green_rows[r] < need:
            r -= 1
        if r < H - 3:
            # 再多留 1.5% 作为根颈过渡
            bottom = min(H, r + int(H * 0.015))

    ys, xs = np.where(solid[:bottom])
    if len(xs) == 0:
        raise SystemExit("empty cutout: " + name)
    x0, x1 = xs.min(), xs.max() + 1
    y0, y1 = ys.min(), bottom
    bw, bh = x1 - x0, y1 - y0

    # 等比 fit 到 正方形，底部对齐，水平居中
    inner = int(out_size * (1 - pad * 2))
    scale = min(inner / bw, inner / bh)
    nw, nh = max(1, int(bw * scale)), max(1, int(bh * scale))
    rgba = np.dstack([rgb, alpha * 255.0]).astype(np.uint8)
    sprite = Image.fromarray(rgba, "RGBA").crop((x0, y0, x1, y1)).resize((nw, nh), Image.LANCZOS)

    canvas = Image.new("RGBA", (out_size, out_size), (0, 0, 0, 0))
    ox = (out_size - nw) // 2
    oy = out_size - nh - int(out_size * pad * 0.35)
    canvas.alpha_composite(sprite, (ox, oy))
    key = os.path.splitext(name)[0]
    canvas.save(os.path.join(OUT, f"{key}.png"), optimize=True)
    meta[key] = {"w": nw, "h": nh, "size": out_size}
    print("sprite", key, "bbox", (int(x0), int(y0), int(x1), int(y1)), "->", (nw, nh),
          "root_trim", trim_root and bottom < H)


GROUND = [
    ("paddy_soil.png", "soil_paddy"),
    ("dry_soil.png", "soil_dry"),
    ("grass_ground.png", "ground_grass"),
    ("field_rice_top.png", "field_rice"),
]
meta = {}
SPRITES = [
    ("plant_rice_young.png", True),
    ("plant_rice_ripe.png", True),
    ("plant_wheat_young.png", True),
    ("plant_wheat_ripe.png", True),
    ("plant_corn_young.png", True),
    ("plant_corn_ripe.png", True),
    ("plant_soybean_young.png", True),
    ("plant_soybean_ripe.png", True),
    ("plant_tree.png", False),
    ("plant_grass_tuft.png", False),
]

if __name__ == "__main__":
    for s, o in GROUND:
        proc_ground(s, o)
    for s, tr in SPRITES:
        cutout(s, trim_root=tr, out_size=512)
    # 天空
    sky = Image.open(os.path.join(SRC, "sky.png")).convert("RGB")
    w, h = sky.size
    sky = sky.crop((0, 0, int(w * 0.87), int(h * 0.91))).resize((2048, 1024), Image.LANCZOS)
    sky.save(os.path.join(OUT, "sky.jpg"), quality=86, optimize=True)
    print("sky", sky.size)
    import json
    with open(os.path.join(OUT, "sprites.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print("meta", meta)
