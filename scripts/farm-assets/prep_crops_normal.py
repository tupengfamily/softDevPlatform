#!/usr/bin/env python3
"""
作物精灵**法线贴图**生成（2026-10-24 画质升级）。

**为什么需要法线贴图**：

  作物贴图是从真实照片/AI 图切出来的**纯色度**（RGB），
  三通道贴图里没有"凹凸"信息。three 的 MeshStandardMaterial 只知道
  贴图每个像素的颜色，不知道叶脉、卷边、褶皱的高度差。

  于是受光时：
    · 球面化法线只能给出**整株**级别的体积感（一团绿有明暗）
    · 叶片**内部**的纹理完全是平的 —— 叶脉不产生阴影、卷边不产生高光

  法线贴图（normal map）把 RGB 编码成"该点法线的 XYZ 偏移"，
  叶片内部的叶脉/褶皱就能吃到光照 → 画面从"色块贴纸"变成"有细节的植物"。

**算法**（标准 Sobel 高度场 → 法线）：

  1. 取 albedo 的 **亮度** 作为高度场 h(x, y)
     （叶片越亮的地方在照片里通常越"高"，够用；不是物理正确的重建）
  2. Sobel 算 ∂h/∂x、∂h/∂y
  3. 法线 = normalize(-∂h/∂x * strength, -∂h/∂y * strength, 1)
  4. 编码成 RGB：(n*0.5+0.5)*255
     ⚠️ three 的 normalMap 约定是 **OpenGL 风格**（Y 向上），
        而图像坐标系 Y 向下 → Y 通道必须翻转，否则凹凸变成反的。

  5. alpha 通道与 albedo 完全一致（边缘羽化必须一致，
     否则法线图会超出剪影范围 → 出现"边缘的立体边框"）。

**⚠️ three 只读法线贴图的 RGB 三个通道**（normal_fragment_maps 里是
   `texture2D(normalMap, uv).xyz`），**alpha 完全不参与**。
   所以法线贴图存成**无 alpha 的 RGB PNG** —— 体积直接少 1/4。

**⚠️ 分辨率降到 512 高**（albedo 是 768）：
   法线承载的是"叶片内部纹理"，人眼在屏幕上分辨不出 512 与 768 的差别；
   但 PNG 体积对边长非常敏感（近似 ∝ 边长²），768→512 体积降到约 44%。
   截图对比过：512 足够。

**输出**：
  public/farm/tex/web/plant_<cropId>_<stage>_n.png   （RGB，无 alpha）
"""
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "web")

# 法线强度：太大 → 叶片像被敲打的金属片；太小 → 看不出凹凸。
# 3.0 是给这类"亮度当高度"的粗糙重建用的经验值。
NORMAL_STRENGTH = 3.0

# 法线贴图的目标高度。⚠️ 比 albedo 低是刻意的：
#   法线承载"叶片内部纹理"，屏幕上 512 与 768 分辨不出差别；
#   但 PNG 体积近似 ∝ 边长²，768→512 体积降到 ~44%。
NORMAL_TARGET_H = 512


def normal_map_from(albedo: Image.Image, strength: float = NORMAL_STRENGTH) -> Image.Image:
    """从 albedo 的亮度场生成 OpenGL 风格法线贴图（RGB，无 alpha）。"""
    rgba = np.array(albedo.convert("RGBA")).astype(np.float32)
    rgb = rgba[:, :, :3]

    # 亮度 → 高度场（Rec.709 权重）
    h = (rgb[:, :, 0] * 0.2126 + rgb[:, :, 1] * 0.7152 + rgb[:, :, 2] * 0.0722) / 255.0

    # Sobel 卷积（边界用 replicate 填充，避免边缘出现黑边）
    p = np.pad(h, 1, mode="edge")
    kx = np.array([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]], dtype=np.float32)
    ky = np.array([[-1, -2, -1], [0, 0, 0], [1, 2, 1]], dtype=np.float32)

    # 手工做 3×3 卷积（不引入 scipy）
    def conv(kernel):
        out = np.zeros_like(h)
        for dy in range(3):
            for dx in range(3):
                k = kernel[dy, dx]
                if k == 0:
                    continue
                out += k * p[dy:dy + h.shape[0], dx:dx + h.shape[1]]
        return out

    dx = conv(kx)
    dy = conv(ky)

    # 法线：n = normalize(-∂h/∂x, -∂h/∂y, 1)
    nx = -dx * strength
    ny = dy * strength  # ⚠️ 图像 Y 向下，梯度要翻回来（OpenGL 约定 Y 向上）
    nz = np.ones_like(nx)

    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx /= ln
    ny /= ln
    nz /= ln

    out = np.zeros((h.shape[0], h.shape[1], 3), dtype=np.uint8)
    out[:, :, 0] = (nx * 0.5 + 0.5) * 255
    out[:, :, 1] = (ny * 0.5 + 0.5) * 255
    out[:, :, 2] = (nz * 0.5 + 0.5) * 255
    return Image.fromarray(out, "RGB")


def main():
    if not os.path.isdir(WEB):
        print("贴图目录不存在：", WEB)
        return

    names = sorted(
        f for f in os.listdir(WEB)
        if f.startswith("plant_") and f.endswith(".png")
        and not f.endswith("_n.png")
        # 旧的通用族图（leafy/vine/bush/flower）是 560 高的旧素材，
        # 它们只是降级链备用，不需要高精度法线图
        and not f.startswith(("plant_leafy", "plant_vine", "plant_bush", "plant_flower"))
        and f not in ("plant_tree.png", "plant_grass_tuft.png")
    )

    made = []
    for name in names:
        src = os.path.join(WEB, name)
        dst = os.path.join(WEB, name[:-4] + "_n.png")
        albedo = Image.open(src)
        if albedo.mode != "RGBA":
            albedo = albedo.convert("RGBA")
        nmap = normal_map_from(albedo)

        # 缩到 512 高（宽高比必须保持，否则 UV 与 albedo 不一致）
        w, h = nmap.size
        nh = NORMAL_TARGET_H
        nw = max(1, round(w * nh / h))
        nmap = nmap.resize((nw, nh), Image.LANCZOS)

        nmap.save(dst, optimize=True)
        made.append((name[:-4] + "_n", nw, nh))
        print("%-40s %4dx%-4d %6.1fKB"
              % (name[:-4] + "_n", nw, nh, os.path.getsize(dst) / 1024))

    print("\n=== 生成法线贴图 %d 张 ===" % len(made))


if __name__ == "__main__":
    main()
