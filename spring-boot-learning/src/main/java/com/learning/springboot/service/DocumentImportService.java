package com.learning.springboot.service;

import com.learning.springboot.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * Wiki 文档导入解析: 把用户上传的 Markdown / Word(.docx) / Excel(.xlsx)
 * 统一转换为可入库、可渲染的 Wiki 文章内容。
 * <p>
 * - .md / .markdown  → 保留原文, contentType = markdown
 * - .docx            → 结构化转 HTML(标题/段落/粗斜体/列表/表格), contentType = richtext
 * - .xlsx            → 每个 Sheet 转一张 HTML 表格, contentType = richtext
 * <p>
 * 安全约束: 扩展名白名单 + 10MB 体积上限 + 行列/总长截断;
 * 转换出的 HTML 由调用方入库前经 HtmlSanitizer 消毒(防御纵深)。
 */
@Service
public class DocumentImportService {

    /** 上传文件体积上限(10MB) */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** 转换后正文字符上限(超出截断并标注) */
    public static final int MAX_CONTENT_CHARS = 200_000;
    /** Excel 每个 Sheet 最多转换行数/列数(防超大表格拖垮页面) */
    private static final int MAX_SHEET_ROWS = 500;
    private static final int MAX_SHEET_COLS = 40;

    /** 解析结果: 标题(取文件名)+ 正文 + 内容格式 */
    public record ImportedDoc(String title, String content, String contentType) {}

    /**
     * 解析上传文件为 Wiki 文章内容。
     *
     * @param originalFilename 原始文件名(用于识别扩展名与生成标题)
     * @param bytes            文件字节
     */
    public ImportedDoc parse(String originalFilename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new BusinessException("文件内容为空");
        if (bytes.length > MAX_FILE_BYTES) throw new BusinessException("文件过大, 最大支持 10MB");
        String name = originalFilename == null ? "" : originalFilename.trim();
        String ext = extension(name);
        String title = stripExtension(name);
        if (title.isEmpty()) title = "导入文档";

        return switch (ext) {
            case "md", "markdown" -> new ImportedDoc(title, parseMarkdown(bytes), "markdown");
            case "docx" -> new ImportedDoc(title, truncate(parseDocx(bytes)), "richtext");
            case "xlsx" -> new ImportedDoc(title, truncate(parseXlsx(bytes)), "richtext");
            case "doc", "xls" -> throw new BusinessException("暂不支持旧版 .doc/.xls 格式, 请用 Office/WPS 另存为 .docx/.xlsx 后导入");
            default -> throw new BusinessException("仅支持 .md / .docx / .xlsx 文件");
        };
    }

    // ==================== Markdown ====================

    private String parseMarkdown(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 去 UTF-8 BOM
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        text = text.trim();
        if (text.isEmpty()) throw new BusinessException("Markdown 文件内容为空");
        if (text.length() > MAX_CONTENT_CHARS) {
            throw new BusinessException("文档内容过大(超过 200000 字符), 请拆分后导入");
        }
        return text;
    }

    // ==================== Word(.docx) ====================

    private String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 1) 先把 docx 内嵌的所有图片解到 data/uploads/wiki/import/{ts}/, 并建立
            //    rId -> 公开 URL 的映射; 后续 body 解析时按 rId 替换为 <img src=...>
            String importBatch = String.valueOf(System.currentTimeMillis()) + "_" + UUID.randomUUID().toString().substring(0, 8);
            java.util.Map<String, String> picUrls = extractPictures(doc, importBatch);

            StringBuilder sb = new StringBuilder();
            boolean listOpen = false;
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    // 段落可能既含文字 run 又含 inline 图片; 这里输出混合内容
                    String inner = paragraphMixedHtml(p, picUrls);
                    if (inner.isBlank()) continue;
                    String style = p.getStyle() == null ? "" : p.getStyle().toLowerCase(Locale.ROOT);
                    int heading = headingLevel(style);
                    if (heading > 0) {
                        listOpen = closeList(sb, listOpen); listOpen = false;
                        sb.append("<h").append(heading).append('>').append(inner).append("</h").append(heading).append('>');
                    } else if (p.getNumID() != null || style.contains("list")) {
                        if (!listOpen) { sb.append("<ul>"); listOpen = true; }
                        sb.append("<li>").append(inner).append("</li>");
                    } else {
                        listOpen = closeList(sb, listOpen); listOpen = false;
                        sb.append("<p>").append(inner).append("</p>");
                    }
                } else if (el instanceof XWPFTable table) {
                    listOpen = closeList(sb, listOpen); listOpen = false;
                    sb.append(tableToHtml(table, picUrls));
                }
            }
            closeList(sb, listOpen);
            String html = sb.toString().trim();
            if (html.isEmpty()) throw new BusinessException("Word 文档没有可转换的文本内容");
            return html;
        } catch (IOException e) {
            throw new BusinessException("Word 文档解析失败, 文件可能已损坏或并非真正的 .docx");
        }
    }

    /**
     * 把 docx 内嵌图片全部落盘, 返回 pictureData 唯一标识(包内 partName) -> /uploads/... 的映射。
     * 同一图片被多次引用(指向同一 partName)也只落盘一次, 由调用方复用。
     */
    private java.util.Map<String, String> extractPictures(XWPFDocument doc, String importBatch) throws IOException {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        Path dir = Paths.get("data", "uploads", "wiki", "import", importBatch);
        Files.createDirectories(dir);

        // POI 5.x: XWPFDocument#getAllPackagePictures() 返回 List<XWPFPictureData>(全包内图片);
        // 段内 run#getEmbeddedPictures() 返回 List<XWPFPicture>; 通过 picData.getPackagePart().getPartName() 关联
        java.util.List<XWPFPictureData> all = doc.getAllPackagePictures();
        for (int i = 0; i < all.size(); i++) {
            XWPFPictureData data = all.get(i);
            if (data == null) continue;
            String ext = suggestImageExt(data.getPictureTypeEnum(), data.suggestFileExtension());
            String name = "img_" + (i + 1) + "." + ext;
            Path target = dir.resolve(name);
            try (var out = Files.newOutputStream(target)) {
                out.write(data.getData());
            }
            String key = data.getPackagePart().getPartName().toString();
            map.put(key, "/uploads/wiki/import/" + importBatch + "/" + name);
        }
        return map;
    }

    private String suggestImageExt(org.apache.poi.common.usermodel.PictureType type, String poiSuggested) {
        // PictureType 枚举仅 EMF/WMF/PICT/JPEG/PNG(POI 5.2.5); 其它格式依赖 suggestFileExtension
        if (type != null) {
            switch (type) {
                case PNG: return "png";
                case JPEG: return "jpg";
                case EMF: return "emf";
                case WMF: return "wmf";
                case PICT: return "pict";
                default: break;
            }
        }
        String s = poiSuggested == null ? "" : poiSuggested.toLowerCase(Locale.ROOT).trim();
        if (!s.isEmpty()) return s;
        // 兜底: png 广泛兼容
        return "png";
    }

    /**
     * 段落混合输出: 把每段里所有 run 按文档顺序平铺输出, 含文字+内嵌图片(按 partName 替换 URL)。
     * 注意 POI 的 getEmbeddedPictures() 在某些版本可能抛 NPE, 故用 try 包住。
     */
    private String paragraphMixedHtml(XWPFParagraph p, java.util.Map<String, String> picUrls) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            try {
                for (XWPFPicture pic : run.getEmbeddedPictures()) {
                    XWPFPictureData d = pic.getPictureData();
                    if (d == null) continue;
                    String key = d.getPackagePart().getPartName().toString();
                    String url = picUrls.get(key);
                    if (url == null) continue;
                    sb.append("<img src=\"").append(escapeHtml(url)).append("\" alt=\"\">");
                }
            } catch (Throwable ignore) { /* 段落无图片时此调用在部分版本会 NPE */ }
            String text = run.text();
            if (text == null || text.isEmpty()) continue;
            String esc = escapeHtml(text).replace("\n", "<br>").replace("\t", "&emsp;&emsp;");
            if (run.isBold()) esc = "<strong>" + esc + "</strong>";
            if (run.isItalic()) esc = "<em>" + esc + "</em>";
            var underline = run.getUnderline();
            if (underline != null && !"none".equalsIgnoreCase(underline.toString())) {
                esc = "<u>" + esc + "</u>";
            }
            sb.append(esc);
        }
        return sb.toString();
    }

    private boolean closeList(StringBuilder sb, boolean listOpen) {
        if (listOpen) sb.append("</ul>");
        return false;
    }

    /** 样式名转标题级别(heading1..heading6), 非标题返回 0 */
    private int headingLevel(String style) {
        if (!style.contains("heading") && !style.contains("标题")) return 0;
        for (int i = 1; i <= 6; i++) {
            if (style.contains(String.valueOf(i))) return i;
        }
        return 2;
    }

    private String tableToHtml(XWPFTable table, java.util.Map<String, String> picUrls) {
        StringBuilder sb = new StringBuilder("<table>");
        boolean first = true;
        for (XWPFTableRow row : table.getRows()) {
            sb.append("<tr>");
            String tag = first ? "th" : "td";
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append('<').append(tag).append('>');
                StringBuilder cellText = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    String inner = paragraphMixedHtml(p, picUrls);
                    if (inner.isBlank()) continue;
                    if (cellText.length() > 0) cellText.append("<br>");
                    cellText.append(inner);
                }
                sb.append(cellText);
                sb.append("</").append(tag).append('>');
            }
            sb.append("</tr>");
            first = false;
        }
        return sb.append("</table>").toString();
    }

    // ==================== Excel(.xlsx) ====================

    private String parseXlsx(byte[] bytes) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            StringBuilder sb = new StringBuilder();
            int sheetCount = wb.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = wb.getSheetAt(i);
                if (sheetCount > 1) {
                    sb.append("<h3>").append(escapeHtml(sheet.getSheetName())).append("</h3>");
                }
                sb.append(sheetToHtml(sheet, fmt, evaluator));
            }
            String html = sb.toString().trim();
            if (html.isEmpty()) throw new BusinessException("Excel 文件没有可转换的数据");
            return html;
        } catch (IOException e) {
            throw new BusinessException("Excel 文件解析失败, 文件可能已损坏或并非真正的 .xlsx");
        }
    }

    private String sheetToHtml(Sheet sheet, DataFormatter fmt, FormulaEvaluator evaluator) {
        StringBuilder sb = new StringBuilder("<table>");
        int lastRow = Math.min(sheet.getLastRowNum(), MAX_SHEET_ROWS - 1);
        boolean headerDone = false;
        int shown = 0;
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String tag = (!headerDone && shown == 0) ? "th" : "td";
            StringBuilder cells = new StringBuilder();
            boolean hasValue = false;
            int lastCol = Math.min(Math.max(row.getLastCellNum(), 0), MAX_SHEET_COLS);
            for (int c = 0; c < lastCol; c++) {
                Cell cell = row.getCell(c);
                String val = cell == null ? "" : escapeHtml(fmt.formatCellValue(cell, evaluator)).replace("\n", "<br>");
                if (!val.isBlank()) hasValue = true;
                cells.append('<').append(tag).append('>').append(val).append("</").append(tag).append('>');
            }
            if (!hasValue) continue;   // 整行空值跳过
            sb.append("<tr>").append(cells).append("</tr>");
            headerDone = true;
            shown++;
        }
        if (!headerDone) return "";
        if (sheet.getLastRowNum() >= MAX_SHEET_ROWS) {
            sb.append("<tr><td colspan=\"").append(MAX_SHEET_COLS)
              .append("\" style=\"color:#888\">⚠ 行数过多, 仅显示前 ").append(MAX_SHEET_ROWS).append(" 行</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    // ==================== 工具 ====================

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot <= 0 ? filename : filename.substring(0, dot)).trim();
    }

    /** 超长截断并标注(HTML 输出) */
    private String truncate(String html) {
        if (html.length() <= MAX_CONTENT_CHARS) return html;
        return html.substring(0, MAX_CONTENT_CHARS) + "<p>⚠ 文档过长, 内容已截断</p>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
