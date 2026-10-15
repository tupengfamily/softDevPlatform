package com.learning.springboot.service.doc;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Office 编解码往返测试: 验证"编辑器模型 -> 文件 -> 模型"闭环, 以及就地回写是否保留样式/公式/版式。
 */
class OfficeCodecTest {

    private final DocxCodec docx = new DocxCodec();
    private final XlsxCodec xlsx = new XlsxCodec();
    private final PptxCodec pptx = new PptxCodec();
    private final OfficeDocService office = new OfficeDocService(docx, xlsx, pptx);

    @TempDir
    Path tmp;

    @Test
    @DisplayName("新建空白文档: 三种类型都能生成且能被重新解析")
    void blankDocsRoundTrip() {
        for (MyDocType type : MyDocType.values()) {
            byte[] bytes = office.createBlank(type);
            assertTrue(bytes.length > 0, type + " 模板为空");
            office.validate(type, bytes);
            Map<String, Object> model = office.parse(type, bytes, tmp.resolve(type.code()), "/uploads/x");
            assertEquals(type.code(), model.get("docType"));
            String key = switch (type) {
                case WORD -> "blocks";
                case EXCEL -> "sheets";
                case PPT -> "slides";
            };
            assertNotNull(model.get(key), type + " 缺少 " + key);
            assertFalse(((List<?>) model.get(key)).isEmpty(), type + " 模型内容为空");
        }
    }

    @Test
    @DisplayName("Word: 标题/正文/列表/表格/加粗 往返一致")
    void wordRoundTrip() throws Exception {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("blocks", List.of(
                block("heading", "level", 1, "text", "第一章 概述"),
                block("paragraph", "text", "这是正文段落", "align", "center", "bold", true, "italic", false, "underline", false),
                block("bullet", "text", "第一项", "level", 0),
                block("bullet", "text", "子项", "level", 1),
                block("table", "rows", List.of(
                        List.of(cell("姓名"), cell("分数")),
                        List.of(cell("张三"), cell("98"))
                )),
                block("paragraph", "text", "结尾段落", "align", "left", "bold", false, "italic", false, "underline", false)
        ));

        // Word 走"就地补丁": 先建一份空白模板, 再在它上面应用模型
        byte[] docxBytes = docx.save(docx.blankDocx(), model, tmp);
        assertTrue(docxBytes.length > 1000, "生成的 docx 过小, 可能没写成功");

        // 生成的文档必须能被 POI 重新打开(结构合法)
        Map<String, Object> back = docx.parse(docxBytes, tmp.resolve("imgs"), "/uploads/x");
        List<?> blocks = (List<?>) back.get("blocks");
        List<String> types = new ArrayList<>();
        for (Object o : blocks) types.add(String.valueOf(((Map<?, ?>) o).get("type")));
        assertTrue(types.contains("heading"), "标题块丢失: " + types);
        assertTrue(types.contains("paragraph"), "段落块丢失: " + types);
        assertTrue(types.contains("bullet"), "列表块丢失: " + types);
        assertTrue(types.contains("table"), "表格块丢失: " + types);

        // 文本与内容抽查
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String all = String.join("\n", doc.getParagraphs().stream().map(XWPFParagraph::getText).toList());
            assertTrue(all.contains("第一章 概述"), "标题文本丢失: " + all);
            assertTrue(all.contains("这是正文段落"), "正文文本丢失");
            assertTrue(all.contains("第一项"), "列表项丢失");
            assertEquals(1, doc.getTables().size(), "表格数量不对");
            XWPFTable t = doc.getTables().get(0);
            assertEquals("姓名", t.getRow(0).getCell(0).getText());
            assertEquals("98", t.getRow(1).getCell(1).getText());
            // 加粗保留
            boolean boldFound = doc.getParagraphs().stream()
                    .anyMatch(p -> "这是正文段落".equals(p.getText())
                            && !p.getRuns().isEmpty() && p.getRuns().get(0).isBold());
            assertTrue(boldFound, "正文加粗格式丢失");
            // 居中对齐保留
            boolean centerFound = doc.getParagraphs().stream()
                    .anyMatch(p -> "这是正文段落".equals(p.getText())
                            && p.getAlignment() != null && "CENTER".equals(p.getAlignment().toString()));
            assertTrue(centerFound, "居中对齐丢失");
            // 标题: 应当套用文档里的 Heading1 样式(而不是靠硬编码的直接格式),
            // 这样在 Word 里能进目录、能改样式主题 —— 这才是"不像正文"的正确做法
            XWPFParagraph h = doc.getParagraphs().stream()
                    .filter(p -> "第一章 概述".equals(p.getText())).findFirst().orElseThrow();
            assertEquals("Heading1", h.getStyle(), "标题没有套用 Heading1 样式");
            assertNotNull(doc.getStyles().getStyle("Heading1"), "文档缺少 Heading1 样式定义");
        }
    }

    @Test
    @DisplayName("Word: 内嵌图片解析成图片块, 并在重建后重新嵌入")
    void wordImageRoundTrip() throws Exception {
        // 造一张真实 PNG
        Path png = tmp.resolve("dot.png");
        BufferedImage img = new BufferedImage(6, 6, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", png.toFile());

        byte[] original;
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("图片之前");
            var run = doc.createParagraph().createRun();
            try (var in = Files.newInputStream(png)) {
                run.addPicture(in, XWPFDocument.PICTURE_TYPE_PNG, "dot.png",
                        org.apache.poi.util.Units.toEMU(20), org.apache.poi.util.Units.toEMU(20));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            original = out.toByteArray();
        }

        Path imgDir = tmp.resolve("uploads").resolve("my-doc").resolve("1");
        Map<String, Object> model = docx.parse(original, imgDir, "/uploads/my-doc/1");
        List<?> blocks = (List<?>) model.get("blocks");
        boolean hasImage = blocks.stream()
                .anyMatch(o -> "image".equals(((Map<?, ?>) o).get("type")));
        assertTrue(hasImage, "未解析出图片块");
        assertTrue(Files.list(imgDir).findAny().isPresent(), "图片未落盘");

        // 就地保存 + 回读: 图片应仍然在原位(未改动就不重写 media)
        byte[] rebuilt = docx.save(original, model, tmp.resolve("uploads"));
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(rebuilt))) {
            assertEquals(1, doc.getAllPackagePictures().size(), "保存后图片丢失");
        }
    }

    @Test
    @DisplayName("Excel: 改值/加行 保留原有字体样式, 公式保留且缓存值被重算")
    void excelInPlaceSave() throws Exception {
        byte[] original;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("预算");
            Cell a1 = s.createRow(0).createCell(0);
            a1.setCellValue("旧标题");
            CellStyle cs = wb.createCellStyle();
            Font ft = wb.createFont();
            ft.setBold(true);
            ft.setColor(IndexedColors.RED.getIndex());
            cs.setFont(ft);
            a1.setCellStyle(cs);
            s.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            s.createRow(1).createCell(0).setCellValue(10);
            s.getRow(1).createCell(1).setCellValue(20);
            s.createRow(2).createCell(0).setCellFormula("SUM(A2:B2)");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            original = out.toByteArray();
        }

        Map<String, Object> model = xlsx.parse(original);
        List<?> sheets = (List<?>) model.get("sheets");
        Map<String, Object> sheet = (Map<String, Object>) sheets.get(0);
        assertEquals("预算", sheet.get("name"));

        List<List<Object>> grid = (List<List<Object>>) sheet.get("grid");
        // A1 改文本
        grid.get(0).set(0, Map.of("t", "s", "v", "新标题", "d", "新标题"));
        // 加一行
        List<Object> newRow = new ArrayList<>();
        newRow.add(Map.of("t", "s", "v", "备注", "d", "备注"));
        grid.add(newRow);
        sheet.put("grid", grid);
        sheet.put("rowCount", grid.size());

        byte[] saved = xlsx.save(original, model);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(saved))) {
            Sheet s = wb.getSheetAt(0);
            Cell a1 = s.getRow(0).getCell(0);
            assertEquals("新标题", a1.getStringCellValue(), "值未更新");
            assertTrue(wb.getFontAt(a1.getCellStyle().getFontIndex()).getBold(), "原字体加粗样式丢失");
            assertEquals(IndexedColors.RED.getIndex(), wb.getFontAt(a1.getCellStyle().getFontIndex()).getColor(),
                    "原字体颜色丢失");
            assertEquals(1, s.getMergedRegions().size(), "合并单元格丢失");
            Cell f = s.getRow(2).getCell(0);
            assertEquals("SUM(A2:B2)", f.getCellFormula(), "公式丢失");
            double v = wb.getCreationHelper().createFormulaEvaluator().evaluate(f).getNumberValue();
            assertEquals(30.0, v, 0.001, "公式缓存值未重算");
            assertEquals("备注", s.getRow(3).getCell(0).getStringCellValue(), "新增行未写入");
        }
    }

    @Test
    @DisplayName("Excel: 清空单元格生效, 多表增删按索引对应")
    void excelClearAndSheets() throws Exception {
        byte[] original;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("S1").createRow(0).createCell(0).setCellValue("保留");
            wb.getSheetAt(0).getRow(0).createCell(1).setCellValue("删掉我");
            wb.createSheet("S2").createRow(0).createCell(0).setCellValue("第二表");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            original = out.toByteArray();
        }
        Map<String, Object> model = xlsx.parse(original);
        List<?> sheets = (List<?>) model.get("sheets");
        Map<String, Object> s1 = (Map<String, Object>) sheets.get(0);
        List<List<Object>> grid = (List<List<Object>>) s1.get("grid");
        grid.get(0).set(1, null);                    // 清空
        ((Map<String, Object>) sheets.get(1)).put("name", "改过名");
        sheets = sheets.subList(0, 1);               // 删掉第二张表

        Map<String, Object> toSave = new LinkedHashMap<>();
        toSave.put("sheets", sheets);
        byte[] saved = xlsx.save(original, toSave);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(saved))) {
            assertEquals(1, wb.getNumberOfSheets(), "多余工作表未删除");
            Sheet s = wb.getSheetAt(0);
            assertEquals("保留", s.getRow(0).getCell(0).getStringCellValue());
            assertTrue(s.getRow(0).getCell(1) == null
                            || s.getRow(0).getCell(1).getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK,
                    "被清空的单元格仍有值");
        }
    }

    @Test
    @DisplayName("PPT: 改文字/删页/加页 都能生效且保留原页数关系")
    void pptInPlaceSave() throws Exception {
        byte[] original;
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            for (int i = 1; i <= 3; i++) {
                XSLFSlide s = ppt.createSlide();
                XSLFTextBox box = s.createTextBox();
                box.setAnchor(new Rectangle2D.Double(40, 40, 500, 100));
                box.addNewTextParagraph().addNewTextRun().setText("第" + i + "页标题");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            original = out.toByteArray();
        }

        Map<String, Object> model = pptx.parse(original);
        List<?> slides = (List<?>) model.get("slides");
        assertEquals(3, slides.size());

        // 改第 1 页第一个形状的文字
        Map<String, Object> s0 = (Map<String, Object>) slides.get(0);
        List<Object> shapes0 = (List<Object>) s0.get("shapes");
        Map<String, Object> sh0 = (Map<String, Object>) shapes0.get(0);
        sh0.put("paragraphs", List.of(p("改过的标题"), p("第二段")));

        // 删除第 2 页, 新增一页
        List<Object> keep = new ArrayList<>();
        keep.add(s0);
        keep.add(slides.get(2));
        Map<String, Object> brandNew = new LinkedHashMap<>();
        brandNew.put("src", -1);
        brandNew.put("shapes", List.of(
                shape("title", List.of(p("新页标题"))),
                shape("body", List.of(p("新页正文")))
        ));
        keep.add(brandNew);

        Map<String, Object> toSave = new LinkedHashMap<>();
        toSave.put("slides", keep);
        byte[] saved = pptx.save(original, toSave);

        Map<String, Object> back = pptx.parse(saved);
        List<?> after = (List<?>) back.get("slides");
        assertEquals(3, after.size(), "页数不对(应为 改1 + 保留3 + 新增1)");
        String first = textOf((Map<String, Object>) after.get(0));
        assertTrue(first.contains("改过的标题"), "第 1 页文字未更新: " + first);
        String second = textOf((Map<String, Object>) after.get(1));
        assertTrue(second.contains("第3页标题"), "删页后顺序错乱: " + second);
        String third = textOf((Map<String, Object>) after.get(2));
        assertTrue(third.contains("新页标题"), "新增页标题丢失: " + third);
        assertTrue(third.contains("新页正文"), "新增页正文丢失: " + third);
    }

    @Test
    @DisplayName("非法内容要给出可读错误, 而不是 500")
    void invalidContentRejected() {
        byte[] junk = "this is not an office file".getBytes();
        for (MyDocType t : MyDocType.values()) {
            var ex = assertThrows(Exception.class, () -> office.validate(t, junk));
            assertTrue(ex.getMessage().contains("无法解析"), "错误信息不友好: " + ex.getMessage());
        }
    }

    // ===================== 构造模型的小工具 =====================

    private static Map<String, Object> block(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> cell(String text) {
        return Map.of("text", text);
    }

    private static Map<String, Object> p(String text) {
        return Map.of("text", text, "level", 0);
    }

    private static Map<String, Object> shape(String kind, List<Object> paragraphs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", -1);
        m.put("kind", kind);
        m.put("paragraphs", paragraphs);
        return m;
    }

    private static String textOf(Map<String, Object> slide) {
        StringBuilder sb = new StringBuilder();
        for (Object o : (List<?>) slide.get("shapes")) {
            for (Object po : (List<?>) ((Map<?, ?>) o).get("paragraphs")) {
                sb.append(((Map<?, ?>) po).get("text")).append('|');
            }
        }
        return sb.toString();
    }
}
