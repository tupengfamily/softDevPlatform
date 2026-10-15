package com.learning.springboot.service.doc;

import com.learning.springboot.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Excel(.xlsx) 编解码器
 * <p>
 * <b>解析</b>: 把每个 Sheet 的"已用区域"读成二维值矩阵。每个单元格是
 * {@code {t: 类型, v: 原始值, d: 显示文本}}; 空单元格为 null。
 * t 取值: s=文本 / n=数字 / d=日期(原始值为序列号) / b=布尔 / f=公式。
 * <p>
 * <b>保存</b>: <b>就地回写</b>——在用户上传的原工作簿上按坐标写值,
 * 因此字体、填充、边框、列宽、合并单元格、条件格式等全部原样保留(已实测)。
 * 只覆盖编辑器窗口内(前 {@link #MAX_EDIT_ROWS} 行 × 前 {@link #MAX_EDIT_COLS} 列)的单元格,
 * 窗口之外的内容一律不动。
 */
@Component
public class XlsxCodec {

    /** 编辑器可编辑的行数上限(超出部分只读保留, 不动原文件) */
    public static final int MAX_EDIT_ROWS = 800;
    /** 编辑器可编辑的列数上限 */
    public static final int MAX_EDIT_COLS = 50;
    /** 单表单元格总数上限(防御超大表) */
    private static final int MAX_CELLS = 60000;

    // ======================================================================
    //  解析
    // ======================================================================

    public Map<String, Object> parse(byte[] bytes) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("docType", MyDocType.EXCEL.code());
        List<Object> sheets = new ArrayList<>();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            int totalCells = 0;
            boolean truncated = false;

            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", sheet.getSheetName());

                int lastRow = sheet.getLastRowNum();
                int rows = Math.min(Math.max(lastRow + 1, 1), MAX_EDIT_ROWS);
                int cols = 0;
                for (int r = 0; r < rows; r++) {
                    Row row = sheet.getRow(r);
                    if (row != null) cols = Math.max(cols, row.getLastCellNum());
                }
                cols = Math.min(Math.max(cols, 1), MAX_EDIT_COLS);
                if (lastRow + 1 > MAX_EDIT_ROWS || (cols == MAX_EDIT_COLS && hasContentBeyond(sheet, rows, cols))) {
                    truncated = true;
                }

                List<Object> grid = new ArrayList<>();
                for (int r = 0; r < rows; r++) {
                    Row row = sheet.getRow(r);
                    List<Object> line = new ArrayList<>(cols);
                    for (int c = 0; c < cols; c++) {
                        Cell cell = row == null ? null : row.getCell(c);
                        if (cell == null || cell.getCellType() == CellType.BLANK) {
                            line.add(null);
                            continue;
                        }
                        if (++totalCells > MAX_CELLS) {
                            line.add(null);
                            truncated = true;
                            continue;
                        }
                        line.add(cellModel(cell, fmt, evaluator));
                    }
                    grid.add(line);
                }

                sm.put("rowCount", rows);
                sm.put("colCount", cols);
                sm.put("grid", grid);
                sm.put("merges", mergeList(sheet));
                sm.put("colWidths", colWidths(sheet, cols));
                sheets.add(sm);
            }

            model.put("sheets", sheets);
            model.put("truncated", truncated);
            model.put("maxRows", MAX_EDIT_ROWS);
            model.put("maxCols", MAX_EDIT_COLS);
        } catch (IOException e) {
            throw new BusinessException("Excel 解析失败, 文件可能已损坏或不是真正的 .xlsx");
        } catch (RuntimeException e) {
            throw new BusinessException("Excel 解析失败, 文件可能已损坏或不是真正的 .xlsx (" + e.getClass().getSimpleName() + ")");
        }
        return model;
    }

    private List<Object> mergeList(Sheet sheet) {
        List<Object> merges = new ArrayList<>();
        for (CellRangeAddress a : sheet.getMergedRegions()) {
            merges.add(a.formatAsString());
            if (merges.size() >= 200) break;
        }
        return merges;
    }

    /** 列宽(字符数), 前端据此还原表格观感 */
    private List<Object> colWidths(Sheet sheet, int cols) {
        List<Object> out = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            int w = sheet.getColumnWidth(c);
            out.add(w <= 0 ? 0 : Math.round(w / 256.0 * 100.0) / 100.0);
        }
        return out;
    }

    private boolean hasContentBeyond(Sheet sheet, int rows, int cols) {
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > cols) return true;
        }
        return false;
    }

    private Map<String, Object> cellModel(Cell cell, DataFormatter fmt, FormulaEvaluator evaluator) {
        Map<String, Object> m = new LinkedHashMap<>();
        String display = safeDisplay(fmt, cell, evaluator);
        switch (cell.getCellType()) {
            case FORMULA -> {
                m.put("t", "f");
                m.put("v", cell.getCellFormula());
            }
            case BOOLEAN -> {
                m.put("t", "b");
                m.put("v", cell.getBooleanCellValue());
            }
            case NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    m.put("t", "d");
                } else {
                    m.put("t", "n");
                }
                m.put("v", cell.getNumericCellValue());
            }
            default -> {
                m.put("t", "s");
                m.put("v", cell.getStringCellValue());
            }
        }
        m.put("d", display);
        Map<String, Object> st = styleModel(cell);
        if (!st.isEmpty()) m.put("s", st);
        return m;
    }

    // ---------- 单元格样式: 读取 ----------

    /** 把单元格现有的字体/颜色/对齐/底色/数字格式抽成可编辑的规格 */
    private Map<String, Object> styleModel(Cell cell) {
        Map<String, Object> s = new LinkedHashMap<>();
        CellStyle cs = cell.getCellStyle();
        Font f;
        try {
            f = cell.getSheet().getWorkbook().getFontAt(cs.getFontIndexAsInt());
        } catch (RuntimeException e) {
            f = null;
        }
        if (f != null) {
            final Font ff = f;
            if (f.getBold()) s.put("b", true);
            if (f.getItalic()) s.put("i", true);
            if (FontUnderline.valueOf(f.getUnderline()) != FontUnderline.NONE) s.put("u", true);
            if (f.getStrikeout()) s.put("strike", true);
            int h = f.getFontHeightInPoints();
            if (h > 0) s.put("size", h);
            String name = f.getFontName();
            if (name != null && !name.isEmpty()) s.put("font", name);
            String rgb = argbOf(() -> ((org.apache.poi.xssf.usermodel.XSSFFont) ff).getXSSFColor());
            if (rgb != null) s.put("color", rgb);
        }
        try {
            String a = cs.getAlignment().name();
            if (!"GENERAL".equals(a)) s.put("align", a.toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) { }
        try {
            String v = cs.getVerticalAlignment().name();
            if (!"BOTTOM".equals(v)) s.put("valign", v.toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) { }
        if (cs.getWrapText()) s.put("wrap", true);
        String bg = argbOf(() -> ((org.apache.poi.xssf.usermodel.XSSFCellStyle) cs).getFillForegroundXSSFColor());
        if (bg != null) s.put("bg", bg);
        try {
            String fmt = cs.getDataFormatString();
            if (fmt != null && !"General".equals(fmt)) s.put("fmt", fmt);
        } catch (RuntimeException ignored) { }
        return s;
    }

    private interface ColorSupplier {
        org.apache.poi.xssf.usermodel.XSSFColor get();
    }

    /** 取 ARGB 十六进制的后 6 位; 拿不到(主题色/自动)返回 null */
    private String argbOf(ColorSupplier sup) {
        try {
            var c = sup.get();
            if (c == null || c.isAuto()) return null;
            String hex = c.getARGBHex();
            if (hex == null || hex.length() < 6) return null;
            return hex.substring(hex.length() - 6).toUpperCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeDisplay(DataFormatter fmt, Cell cell, FormulaEvaluator evaluator) {
        try {
            return fmt.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            try {
                return fmt.formatCellValue(cell);
            } catch (RuntimeException e2) {
                return "";
            }
        }
    }

    // ======================================================================
    //  保存(就地回写)
    // ======================================================================

    /**
     * @param original .xlsx 原字节(用户导入或上次保存后的文件)
     * @param model    编辑器模型
     */
    public byte[] save(byte[] original, Map<String, Object> model) {
        List<?> sheets = asList(model.get("sheets"));
        if (sheets.isEmpty()) throw new BusinessException("至少需要保留一个工作表");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(original))) {
            Map<String, CellStyle> styleCache = new LinkedHashMap<>();
            for (int i = 0; i < sheets.size(); i++) {
                Map<String, Object> sm = asMap(sheets.get(i));
                String name = sanitizeSheetName(asString(sm.get("name")), i);
                Sheet sheet;
                if (i < wb.getNumberOfSheets()) {
                    sheet = wb.getSheetAt(i);
                    if (!sheet.getSheetName().equals(name)) wb.setSheetName(i, name);
                } else {
                    sheet = wb.createSheet(name);
                }
                boolean removedRows = applyRowOps(sheet, asList(sm.get("rowOps")));
                boolean removedCols = applyColOps(sheet, asList(sm.get("colOps")));
                List<?> grid = asList(sm.get("grid"));
                writeGrid(sheet, grid, wb, styleCache);
                // 删除行/列后, 尾部会残留被挤出来的旧内容, 必须清掉
                if (removedRows) trimTrailingRows(sheet, grid.size());
                if (removedCols) trimTrailingCols(sheet, grid.isEmpty() ? 0 : widest(grid));
                syncMerges(sheet, asList(sm.get("merges")));
                applyColWidths(sheet, asList(sm.get("colWidths")));
            }
            // 模型里的表更少 → 删掉多余的尾部工作表(至少保留 1 个)
            while (wb.getNumberOfSheets() > sheets.size() && wb.getNumberOfSheets() > 1) {
                wb.removeSheetAt(wb.getNumberOfSheets() - 1);
            }
            writeFormulaCache(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("Excel 保存失败: " + e.getMessage());
        }
    }

    private void applyColWidths(Sheet sheet, List<?> widths) {
        for (int c = 0; c < widths.size(); c++) {
            Object o = widths.get(c);
            if (o == null) continue;
            double w = 0;
            if (o instanceof Number n) w = n.doubleValue();
            else {
                try { w = Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { continue; }
            }
            if (w <= 0) continue;
            sheet.setColumnWidth(c, (int) Math.round(w * 256));
        }
    }

    /** 删除行之后, 原来最后一行的内容会被留在表尾 —— 逐格清空, 但保留行样式 */
    private void trimTrailingRows(Sheet sheet, int keep) {
        for (int r = sheet.getLastRowNum(); r >= keep; r--) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < MAX_EDIT_COLS; c++) {
                Cell cell = row.getCell(c);
                if (cell != null) cell.setBlank();
            }
        }
    }

    /** 删除列之后同理: 清掉被挤出到右侧的残留 */
    private void trimTrailingCols(Sheet sheet, int keep) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = keep; c < MAX_EDIT_COLS; c++) {
                Cell cell = row.getCell(c);
                if (cell != null) cell.setBlank();
            }
        }
    }

    private int widest(List<?> grid) {
        int w = 0;
        for (Object o : grid) w = Math.max(w, asList(o).size());
        return w;
    }

    /** 插入 / 删除行: 用 shiftRows 保证下方内容和行样式一起移动。返回是否发生过删除 */
    private boolean applyRowOps(Sheet sheet, List<?> ops) {
        boolean removed = false;
        for (int k = 0; k < ops.size(); k++) {
            Map<String, Object> m = asMap(ops.get(k));
            int at = asInt(m.get("at"), -1);
            String op = asString(m.get("op"));
            if (at < 0 || at > MAX_EDIT_ROWS) continue;
            int last = Math.max(sheet.getLastRowNum(), at);
            try {
                if ("insert".equals(op)) sheet.shiftRows(at, last, 1, true, false);
                else if ("delete".equals(op)) {
                    sheet.shiftRows(at + 1, Math.max(last, at + 1), -1, true, false);
                    removed = true;
                }
            } catch (RuntimeException ignored) {
                // 空表或越界时忽略
            }
        }
        return removed;
    }

    /** 插入 / 删除列: POI 没有 shiftColumns, 这里手动搬单元格(连样式一起搬)。返回是否发生过删除 */
    private boolean applyColOps(Sheet sheet, List<?> ops) {
        boolean removed = false;
        for (int k = 0; k < ops.size(); k++) {
            Map<String, Object> m = asMap(ops.get(k));
            int at = asInt(m.get("at"), -1);
            String op = asString(m.get("op"));
            if (at < 0 || at > MAX_EDIT_COLS) continue;
            int lastRow = sheet.getLastRowNum();
            if ("insert".equals(op)) {
                for (int r = lastRow; r >= 0; r--) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (int c = MAX_EDIT_COLS - 1; c > at; c--) moveCell(row, c - 1, c);
                    row.removeCell(row.getCell(at));
                }
            } else if ("delete".equals(op)) {
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (int c = at; c < MAX_EDIT_COLS - 1; c++) moveCell(row, c + 1, c);
                }
                removed = true;
            }
        }
        return removed;
    }

    /** 把 from 列的单元格搬到 to 列(值 + 样式), 原位置清空 */
    private void moveCell(Row row, int from, int to) {
        Cell src = row.getCell(from);
        Cell dst = row.getCell(to);
        if (src == null) {
            if (dst != null && dst.getCellType() != CellType.BLANK) dst.setBlank();
            return;
        }
        if (dst == null) dst = row.createCell(to);
        switch (src.getCellType()) {
            case FORMULA -> dst.setCellFormula(src.getCellFormula());
            case NUMERIC -> dst.setCellValue(src.getNumericCellValue());
            case BOOLEAN -> dst.setCellValue(src.getBooleanCellValue());
            case STRING -> dst.setCellValue(src.getStringCellValue());
            default -> dst.setBlank();
        }
        try {
            dst.setCellStyle(src.getCellStyle());
        } catch (RuntimeException ignored) { }
        src.setBlank();
    }

    /** 合并单元格: 按模型补齐 / 移除, 不做全量重建 */
    private void syncMerges(Sheet sheet, List<?> merges) {
        Set<String> want = new LinkedHashSet<>();
        for (Object o : merges) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) want.add(s.toUpperCase(Locale.ROOT));
        }
        for (int i = sheet.getNumMergedRegions() - 1; i >= 0; i--) {
            try {
                if (!want.contains(sheet.getMergedRegion(i).formatAsString().toUpperCase(Locale.ROOT))) {
                    sheet.removeMergedRegion(i);
                }
            } catch (RuntimeException ignored) { }
        }
        for (String s : want) {
            try {
                CellRangeAddress addr = CellRangeAddress.valueOf(s.replace(':', ':'));
                boolean exists = false;
                for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                    if (sheet.getMergedRegion(i).formatAsString().equalsIgnoreCase(s)) { exists = true; break; }
                }
                if (!exists) sheet.addMergedRegion(addr);
            } catch (RuntimeException ignored) {
                // 非法地址或与已有区域重叠时跳过
            }
        }
    }

    private void writeGrid(Sheet sheet, List<?> grid, XSSFWorkbook wb, Map<String, CellStyle> styleCache) {
        int rows = Math.min(grid.size(), MAX_EDIT_ROWS);
        for (int r = 0; r < rows; r++) {
            List<?> line = asList(grid.get(r));
            int cols = Math.min(line.size(), MAX_EDIT_COLS);
            Row row = sheet.getRow(r);
            for (int c = 0; c < cols; c++) {
                Object o = line.get(c);
                Cell cell = row == null ? null : row.getCell(c);
                if (o == null) {
                    // 编辑器里被清空的单元格 → 原文件有值就清掉(保留样式)
                    if (cell != null && cell.getCellType() != CellType.BLANK) {
                        cell.setBlank();
                    }
                    continue;
                }
                Map<String, Object> cm = asMap(o);
                if (row == null) row = sheet.createRow(r);
                if (cell == null) cell = row.createCell(c);
                applyCell(cell, cm);
                applyStyle(wb, cell, asMap(cm.get("s")), styleCache);
            }
        }
    }

    /**
     * 套用单元格样式。
     * <b>注意</b>: 绝不能直接改 {@code cell.getCellStyle()} —— POI 的样式是工作簿级共享对象,
     * 直接改会把同款样式的所有单元格一起改掉。这里一律"克隆原样式 → 改 → 写回",
     * 并按规格缓存, 避免每个单元格都生成一个新样式(Excel 有样式数量上限)。
     */
    private void applyStyle(XSSFWorkbook wb, Cell cell, Map<String, Object> spec, Map<String, CellStyle> cache) {
        if (spec.isEmpty()) return;
        String key = cell.getCellStyle().getIndex() + "|" + spec;
        CellStyle style = cache.get(key);
        if (style == null) {
            style = wb.createCellStyle();
            style.cloneStyleFrom(cell.getCellStyle());
            Font base = wb.getFontAt(style.getFontIndexAsInt());
            Font font = wb.createFont();
            copyFont(font, base);

            if (spec.get("b") != null) font.setBold(asBool(spec.get("b")));
            if (spec.get("i") != null) font.setItalic(asBool(spec.get("i")));
            if (spec.get("u") != null) {
                font.setUnderline(asBool(spec.get("u")) ? FontUnderline.SINGLE.getByteValue() : FontUnderline.NONE.getByteValue());
            }
            if (spec.get("strike") != null) font.setStrikeout(asBool(spec.get("strike")));
            if (spec.get("size") != null) {
                int sz = asInt(spec.get("size"), 0);
                if (sz > 0) font.setFontHeightInPoints((short) sz);
            }
            if (spec.get("font") != null) {
                String n = asString(spec.get("font"));
                if (!n.isEmpty()) font.setFontName(n);
            }
            if (spec.get("color") != null) setFontColor((XSSFFont) font, asString(spec.get("color")));
            style.setFont(font);

            if (spec.get("align") != null) {
                HorizontalAlignment ha = parseHAlign(asString(spec.get("align")));
                if (ha != null) style.setAlignment(ha);
            }
            if (spec.get("valign") != null) {
                VerticalAlignment va = parseVAlign(asString(spec.get("valign")));
                if (va != null) style.setVerticalAlignment(va);
            }
            if (spec.get("wrap") != null) style.setWrapText(asBool(spec.get("wrap")));
            if (spec.get("bg") != null) {
                String bg = asString(spec.get("bg"));
                if (!bg.isEmpty()) {
                    try {
                        ((XSSFCellStyle) style).setFillForegroundColor(new XSSFColor(java.awt.Color.decode("#" + bg), null));
                        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    } catch (RuntimeException ignored) { }
                }
            }
            if (spec.get("fmt") != null) {
                String fmt = asString(spec.get("fmt"));
                if (!fmt.isEmpty()) {
                    DataFormat df = wb.createDataFormat();
                    style.setDataFormat(df.getFormat(fmt));
                }
            }
            cache.put(key, style);
        }
        cell.setCellStyle(style);
    }

    private void setFontColor(XSSFFont font, String rgb) {
        try {
            font.setColor(new XSSFColor(java.awt.Color.decode("#" + rgb), null));
        } catch (RuntimeException ignored) { }
    }

    private void copyFont(Font to, Font from) {
        to.setBold(from.getBold());
        to.setItalic(from.getItalic());
        to.setUnderline(from.getUnderline());
        to.setStrikeout(from.getStrikeout());
        to.setTypeOffset(from.getTypeOffset());
        to.setFontHeight(from.getFontHeight());
        String n = from.getFontName();
        if (n != null) to.setFontName(n);
    }

    private HorizontalAlignment parseHAlign(String v) {
        if (v == null) return null;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "left" -> HorizontalAlignment.LEFT;
            case "center", "centre" -> HorizontalAlignment.CENTER;
            case "right" -> HorizontalAlignment.RIGHT;
            case "justify" -> HorizontalAlignment.JUSTIFY;
            default -> null;
        };
    }

    private VerticalAlignment parseVAlign(String v) {
        if (v == null) return null;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "top" -> VerticalAlignment.TOP;
            case "center", "centre", "middle" -> VerticalAlignment.CENTER;
            case "bottom" -> VerticalAlignment.BOTTOM;
            default -> null;
        };
    }

    private void applyCell(Cell cell, Map<String, Object> cm) {
        String type = asString(cm.get("t"));
        Object v = cm.get("v");
        switch (type) {
            case "n" -> {
                Double d = asDouble(v);
                if (d == null) cell.setBlank(); else cell.setCellValue(d);
            }
            case "d" -> {
                Double d = asDouble(v);
                if (d == null) cell.setBlank(); else cell.setCellValue(d);
            }
            case "b" -> {
                if (v instanceof Boolean b) cell.setCellValue(b);
                else cell.setCellValue("true".equalsIgnoreCase(String.valueOf(v)));
            }
            case "f" -> {
                String f = asString(v);
                if (f.isBlank()) cell.setBlank();
                else cell.setCellFormula(f.startsWith("=") ? f.substring(1) : f);
            }
            default -> {
                String s = asString(v);
                if (s.isEmpty()) cell.setBlank(); else cell.setCellValue(s);
            }
        }
    }

    /** 重算一次公式, 让保存后的文件里缓存值与公式一致(避免 Excel 打开显示旧值) */
    private void writeFormulaCache(XSSFWorkbook wb) {
        try {
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        } catch (RuntimeException ignore) {
            // 公式里引用了外部工作簿/不支持函数时忽略, 交给 Excel 打开时重算
        }
    }

    private String sanitizeSheetName(String name, int index) {
        String n = name == null ? "" : name.replaceAll("[\\\\/*?\\[\\]:]", "").trim();
        if (n.isEmpty()) n = "Sheet" + (index + 1);
        if (n.length() > 31) n = n.substring(0, 31);
        return n;
    }

    // ======================================================================

    private static String asString(Object o) {
        if (o == null) return "";
        return o instanceof String s ? s : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }
}
