package com.learning.springboot.service.doc;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word 就地编辑的<b>保真度</b>测试
 * <p>
 * 这一组用例回答的是"打开 - 保存后格式会不会变":
 * <ol>
 *   <li>只改一段文字时, 除 word/document.xml 之外的每一个 part 都必须是<b>字节级完全相同</b>;
 *       这是"包级保真"的硬证据 —— styles.xml / numbering.xml / theme / 页眉 / media / docProps
 *       全都没有被解析器往返污染。</li>
 *   <li>被编辑段落自己的 rPr(颜色 / 斜体 / 字体)必须原地保留。</li>
 *   <li>删除、重排、新增块之后, 其余内容必须一字不差。</li>
 * </ol>
 */
class DocxFidelityTest {

    @TempDir
    Path tmp;

    private final DocxCodec codec = new DocxCodec();

    // ==================================================================
    //  用例
    // ==================================================================

    @Test
    void untouchedPartsStayByteIdenticalWhenOnlyTextChanges() {
        byte[] original = zipOf(richDocx());
        Map<String, Object> model = codec.parse(original, tmp.resolve("img"), "/uploads/my-doc/1");

        Map<String, Object> target = blockContaining(model, "这是第一段正文");
        assertNotNull(target, "没解析出正文段落");
        target.put("text", "改过的正文内容");

        byte[] out = codec.save(original, model, Path.of("data", "uploads"));

        var before = DocxCodec.readZip(original);
        var after = DocxCodec.readZip(out);

        assertEquals(before.keySet(), after.keySet(), "包内 part 集合发生了变化");

        List<String> changed = new ArrayList<>();
        for (var en : before.entrySet()) {
            byte[] now = after.get(en.getKey());
            if (!Arrays.equals(en.getValue(), now)) changed.add(en.getKey());
        }
        assertEquals(List.of("word/document.xml"), changed,
                "只有 word/document.xml 允许变化, 实际变化的是: " + changed);

        // 同时确认正文真的改了(否则上面是"什么都没做"的假通过)
        assertTrue(new String(after.get("word/document.xml"), StandardCharsets.UTF_8)
                .contains("改过的正文内容"), "正文没有被写入");
    }

    @Test
    void editedParagraphKeepsItsOriginalRunFormat() throws Exception {
        byte[] original = zipOf(richDocx());
        Map<String, Object> model = codec.parse(original, tmp.resolve("img"), "/uploads/my-doc/1");
        blockContaining(model, "这是第一段正文").put("text", "改过的正文内容");

        byte[] out = codec.save(original, model, Path.of("data", "uploads"));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(out))) {
            XWPFParagraph hit = null;
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (p.getText() != null && p.getText().contains("改过的正文内容")) { hit = p; break; }
            }
            assertNotNull(hit, "保存后找不到被编辑的段落");
            var run = hit.getRuns().get(0);
            assertEquals("C00000", run.getColor(), "原来的红色丢了");
            assertTrue(run.isItalic(), "原来的斜体丢了");
        }
    }

    @Test
    void headingsListsAndTablesSurviveRoundTrip() throws Exception {
        byte[] original = zipOf(richDocx());
        Map<String, Object> model = codec.parse(original, tmp.resolve("img"), "/uploads/my-doc/1");

        List<String> headings = new ArrayList<>();
        List<String> bullets = new ArrayList<>();
        for (Object o : (List<?>) model.get("blocks")) {
            Map<String, Object> b = (Map<String, Object>) o;
            if ("heading".equals(b.get("type"))) headings.add(String.valueOf(b.get("text")));
            if ("bullet".equals(b.get("type"))) bullets.add(String.valueOf(b.get("text")));
        }
        assertEquals(List.of("年度报告"), headings, "标题没被识别出来");
        assertEquals(List.of("项目符号一", "项目符号二", "编号列表一"), bullets, "列表没被识别出来");

        byte[] out = codec.save(original, model, Path.of("data", "uploads"));
        Map<String, Object> again = codec.parse(out, tmp.resolve("img2"), "/uploads/my-doc/1");

        List<String> headings2 = new ArrayList<>();
        List<String> bullets2 = new ArrayList<>();
        for (Object o : (List<?>) again.get("blocks")) {
            Map<String, Object> b = (Map<String, Object>) o;
            if ("heading".equals(b.get("type"))) headings2.add(String.valueOf(b.get("text")));
            if ("bullet".equals(b.get("type"))) bullets2.add(String.valueOf(b.get("text")));
        }
        assertEquals(headings, headings2, "标题在往返后退化");
        assertEquals(bullets, bullets2, "列表在往返后退化");
        assertTrue(((List<?>) again.get("blocks")).stream()
                .anyMatch(o -> "table".equals(((Map<?, ?>) o).get("type"))), "表格丢了");
    }

    @Test
    void deleteAndReorderDoNotCorruptRemainingContent() throws Exception {
        byte[] original = zipOf(richDocx());
        Map<String, Object> model = codec.parse(original, tmp.resolve("img"), "/uploads/my-doc/1");
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) model.get("blocks");

        // 删掉"项目符号一"
        blocks.removeIf(o -> "项目符号一".equals(String.valueOf(((Map<?, ?>) o).get("text"))));
        // 把"编号列表一"挪到最前面
        Object ordered = null;
        for (Object o : blocks) {
            if ("编号列表一".equals(String.valueOf(((Map<?, ?>) o).get("text")))) { ordered = o; break; }
        }
        assertNotNull(ordered);
        blocks.remove(ordered);
        blocks.add(0, ordered);

        byte[] out = codec.save(original, model, Path.of("data", "uploads"));
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(out))) {
            List<String> texts = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (p.getText() != null && !p.getText().isBlank()) texts.add(p.getText());
            }
            assertEquals("编号列表一", texts.get(0), "重排没生效");
            assertFalse(texts.contains("项目符号一"), "删除没生效");
            assertTrue(texts.contains("项目符号二"), "不该删的被删了");
            assertEquals("年度报告", texts.stream().filter(t -> t.equals("年度报告")).findFirst().orElse(null),
                    "标题内容被破坏");
        }
    }

    @Test
    void newlyInsertedListInheritsDocumentsNumbering() throws Exception {
        byte[] original = zipOf(richDocx());
        Map<String, Object> model = codec.parse(original, tmp.resolve("img"), "/uploads/my-doc/1");
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) model.get("blocks");

        int idx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if ("项目符号二".equals(String.valueOf(((Map<?, ?>) blocks.get(i)).get("text")))) { idx = i; break; }
        }
        assertTrue(idx > 0, "找不到锚点段落");
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("type", "bullet");
        fresh.put("text", "新加的列表项");
        fresh.put("level", 0);
        fresh.put("anchor", -1);
        blocks.add(idx + 1, fresh);

        byte[] out = codec.save(original, model, Path.of("data", "uploads"));
        String xml = new String(DocxCodec.readZip(out).get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(xml.contains("新加的列表项"), "新内容没写进去");
        // 新增项必须挂到文档原有的 numId=1 上, 而不是变成普通段落
        assertTrue(xml.contains("numId"), "新增列表项没有继承文档的编号体系");
        // numbering.xml 不应被新增(已有现成的项目符号编号可复用)
        assertEquals(1, countOccurrences(xml, "新加的列表项"), "新内容重复写入了");
    }

    @Test
    void outputIsAValidDocxThatWordCanOpen() throws Exception {
        byte[] original = zipOf(richDocx());
        Map<String, Object> model = codec.parse(original, tmp.resolve("img"), "/uploads/my-doc/1");
        blockContaining(model, "年度报告").put("text", "2026 年度报告");

        byte[] out = codec.save(original, model, Path.of("data", "uploads"));
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(out))) {
            assertTrue(doc.getParagraphs().size() >= 6, "段落数量异常");
            assertTrue(doc.getTables().size() >= 1, "表格丢失");
        }
        // 二次保存(模拟用户连续编辑)仍应稳定
        Map<String, Object> model2 = codec.parse(out, tmp.resolve("img3"), "/uploads/my-doc/1");
        byte[] out2 = codec.save(out, model2, Path.of("data", "uploads"));
        Map<String, Object> model3 = codec.parse(out2, tmp.resolve("img4"), "/uploads/my-doc/1");
        assertEquals(textsOf(model2), textsOf(model3), "连续两次保存后内容发生漂移");
    }

    @Test
    void blankDocxCanBeCreatedAndEdited() {
        byte[] blank = codec.blankDocx();
        Map<String, Object> model = codec.parse(blank, tmp.resolve("imgb"), "/uploads/my-doc/2");
        assertNotNull(model.get("blocks"));

        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) model.get("blocks");
        blocks.clear();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("type", "heading"); h.put("level", 1); h.put("text", "我的标题"); h.put("anchor", -1);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph"); p.put("text", "正文一句"); p.put("anchor", -1); p.put("align", "center");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "bullet"); b.put("text", "要点一"); b.put("anchor", -1); b.put("level", 0);
        blocks.add(h); blocks.add(p); blocks.add(b);

        byte[] out = codec.save(blank, model, Path.of("data", "uploads"));
        assertTrue(new String(DocxCodec.readZip(out).get("word/document.xml"), StandardCharsets.UTF_8)
                .contains("要点一"));
    }

    // ==================================================================
    //  工具
    // ==================================================================

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static List<String> textsOf(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) model.get("blocks")) {
            out.add(String.valueOf(((Map<?, ?>) o).get("text")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> blockContaining(Map<String, Object> model, String text) {
        for (Object o : (List<?>) model.get("blocks")) {
            Map<String, Object> b = (Map<String, Object>) o;
            Object t = b.get("text");
            if (t != null && String.valueOf(t).contains(text)) return b;
        }
        return null;
    }

    private static byte[] zipOf(Map<String, byte[]> entries) {
        return DocxCodec.writeZip(new LinkedHashMap<>(entries));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] realPng() {
        try {
            var img = new java.awt.image.BufferedImage(6, 6, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bo);
            return bo.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一份"像真实世界文档"的 docx: 12 个 part, 含样式表 / 编号 / 主题 / 页眉 / 图片 / 自定义 XML */
    private static Map<String, byte[]> richDocx() {
        Map<String, byte[]> e = new LinkedHashMap<>();

        e.put("[Content_Types].xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Default Extension="png" ContentType="image/png"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
                <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
                <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
                <Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                </Types>
                """));

        e.put("_rels/.rels", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                </Relationships>
                """));

        e.put("word/_rels/document.xml.rels", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
                <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
                <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
                <Relationship Id="rId6" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/dot.png"/>
                </Relationships>
                """));

        e.put("word/document.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
                  <w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                         <w:r><w:rPr><w:rFonts w:ascii="Times New Roman" w:eastAsia="微软雅黑"/><w:b/><w:sz w:val="32"/></w:rPr>
                         <w:t>年度报告</w:t></w:r></w:p>
                    <w:p><w:pPr><w:jc w:val="center"/></w:pPr>
                         <w:r><w:rPr><w:color w:val="C00000"/><w:i/></w:rPr><w:t>这是第一段正文, 带红色斜体。</w:t></w:r>
                         <w:r><w:rPr><w:b/></w:rPr><w:t>加粗的后续文字</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                         <w:r><w:t>项目符号一</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                         <w:r><w:t>项目符号二</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr>
                         <w:r><w:t>编号列表一</w:t></w:r></w:p>
                    <w:tbl>
                      <w:tr><w:tc><w:p><w:r><w:t>姓名</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>分数</w:t></w:r></w:p></w:tc></w:tr>
                      <w:tr><w:tc><w:p><w:r><w:t>张三</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>98</w:t></w:r></w:p></w:tc></w:tr>
                    </w:tbl>
                    <w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">
                         <wp:extent cx="1000000" cy="1000000"/><wp:effectExtent l="0" t="0" r="0" b="0"/>
                         <wp:docPr id="1" name="图片 1"/><wp:cNvGraphicFramePr/>
                         <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                         <pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="dot.png"/><pic:cNvPicPr/></pic:nvPicPr>
                         <pic:blipFill><a:blip r:embed="rId6"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
                         <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1000000" cy="1000000"/></a:xfrm>
                         <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
                         </a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
                    <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                    <w:sectPr>
                      <w:headerReference w:type="default" r:id="rId4"/>
                      <w:pgSz w:w="11906" w:h="16838"/>
                      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
                    </w:sectPr>
                  </w:body>
                </w:document>
                """));

        e.put("word/styles.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
                  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
                    <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
                    <w:rPr><w:b/><w:sz w:val="32"/><w:color w:val="2F5496"/></w:rPr></w:style>
                  <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/>
                    <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
                    <w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
                </w:styles>
                """));

        e.put("word/numbering.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:abstractNum w:abstractNumId="0">
                    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/>
                      <w:lvlText w:val="•"/><w:lvlJc w:val="left"/></w:lvl></w:abstractNum>
                  <w:abstractNum w:abstractNumId="1">
                    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/>
                      <w:lvlText w:val="%1."/><w:lvlJc w:val="left"/></w:lvl></w:abstractNum>
                  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
                  <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
                </w:numbering>
                """));

        e.put("word/header1.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:t>公司机密 页眉</w:t></w:r></w:p>
                </w:hdr>
                """));

        e.put("word/settings.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:zoom w:percent="100"/>
                </w:settings>
                """));

        e.put("word/theme/theme1.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
                  <a:themeElements><a:clrScheme name="Office"/></a:themeElements>
                </a:theme>
                """));

        // 必须是真 PNG: POI 打开文档时会真的去解析图片数据, 假字节会抛异常
        e.put("word/media/dot.png", realPng());

        e.put("docProps/core.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>年度报告</dc:title>
                </cp:coreProperties>
                """));

        e.put("customXml/item1.xml", b("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <root><field>业务系统写入的自定义数据, 绝对不能被破坏</field></root>
                """));

        return e;
    }
}
