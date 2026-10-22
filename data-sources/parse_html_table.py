#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通用 HTML <table> 解析器（用于安徽省统计年鉴 2025 官方网页版表格）
安徽年鉴为 Vue + Excel 导出的 HTML table，列带 colspan/rowspan，表头为多行合并。
策略：先把每行按 colspan/rowspan 展开成矩形网格，再做列名归并。
"""
import re, html as htmlmod


def parse_html_table(path_or_html, with_anchor=False):
    """返回 rows（已展开 rowspan/colspan 的矩形网格）
    with_anchor=True 时返回 (rows, anchor)，anchor[r][c] 标记原始单元格锚点列
    """
    t = path_or_html
    if isinstance(t, bytes):
        t = t.decode('utf-8', errors='ignore')
    trs = re.findall(r'<tr[^>]*>(.*?)</tr>', t, re.S | re.I)
    grid = []
    for tr in trs:
        cells = []
        for m in re.finditer(r'<td([^>]*)>(.*?)</td>|<th([^>]*)>(.*?)</th>', tr, re.S | re.I):
            attrs = m.group(1) or m.group(3) or ''
            body = m.group(2) if m.group(2) is not None else m.group(4)
            txt = re.sub(r'<[^>]+>', '', body)
            txt = htmlmod.unescape(txt).replace('\xa0', ' ').strip()
            cs = int((re.search(r'colspan\s*=\s*["\']?(\d+)', attrs, re.I) or [0, 1])[1]) if 'colspan' in attrs.lower() else 1
            rs = int((re.search(r'rowspan\s*=\s*["\']?(\d+)', attrs, re.I) or [0, 1])[1]) if 'rowspan' in attrs.lower() else 1
            cells.append({'text': txt, 'cs': cs, 'rs': rs, 'row': len(grid)})
        if cells:
            grid.append(cells)
    values, anchor = _expand(grid)
    return (values, anchor) if with_anchor else values


def _expand(grid):
    """展开 rowspan/colspan 为矩形
    返回 (values_grid, anchor_grid)：anchor_grid[r][c] 为 True 表示该格是原始单元格的锚点
    （数据值只应从锚点列读取，避免合并单元格造成重复值）
    """
    if not grid:
        return [], []
    ncols = 0
    for cells in grid:
        c = 0
        for cell in cells:
            c += cell['cs']
        ncols = max(ncols, c)
    out = [[''] * ncols for _ in grid]
    anchor = [[False] * ncols for _ in grid]
    occupied = {}
    for ri, cells in enumerate(grid):
        ci = 0
        for cell in cells:
            while (ri, ci) in occupied:
                ci += 1
            for dr in range(cell['rs']):
                for dc in range(cell['cs']):
                    r, c = ri + dr, ci + dc
                    if r < len(out) and c < ncols:
                        out[r][c] = cell['text']
                        occupied[(r, c)] = True
                        if dr == 0 and dc == 0:
                            anchor[r][c] = True
            ci += cell['cs']
    return out, anchor


def clean(s):
    s = re.sub(r'\s+', ' ', (s or '')).strip()
    return s


def is_cn(s):
    return bool(re.search(r'[\u4e00-\u9fff]', s))


def is_num(s):
    s = clean(s).replace(',', '')
    if not s or s in ('-', '—', '..', '…', '－'):
        return False
    return bool(re.match(r'^-?\d+(\.\d+)?$', s))


def num(s):
    s = clean(s).replace(',', '')
    if not is_num(s):
        return None
    return float(s)


def build_header(grid, header_end, label_col=0, skip_words=('地区', '地 区', '指标', '指 标', '项目', '县 市', '县（市）', '市名', '年份', '年 份')):
    """把 header_end 之前的若干行合并成每个数据列的列名
    返回 {col_index: 列名}"""
    ncols = len(grid[0]) if grid else 0
    names = {}
    for c in range(ncols):
        parts = []
        for r in range(header_end):
            v = clean(grid[r][c])
            if v and is_cn(v) and v not in skip_words:
                parts.append(v)
        if parts:
            names[c] = ''.join(parts)
    return names


def find_data_start(grid, from_row=0):
    """找到第一行含数字的行"""
    for r in range(from_row, len(grid)):
        if any(is_num(grid[r][c]) for c in range(1, len(grid[r]))):
            return r
    return from_row
