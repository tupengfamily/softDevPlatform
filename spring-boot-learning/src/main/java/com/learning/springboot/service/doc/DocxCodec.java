package com.learning.springboot.service.doc;

import com.learning.springboot.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.stereotype.Component;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Word(.docx) 编解码器 —— <b>原生 OOXML 就地编辑</b>
 * <p>
 * 设计原则(对齐 OnlyOffice / Apryse 的"native OOXML"路线, 见 README 调研):
 * <ol>
 *   <li><b>包级保真</b>: .docx 本质是一个 zip。保存时把 {@code word/document.xml} 单独取出做补丁,
 *       <b>其余所有 part(styles.xml / numbering.xml / theme / 页眉页脚 / 脚注 / media / docProps /
 *       自定义 XML / 宏)一律按原字节写回</b>, 不经过任何解析器往返。</li>
 *   <li><b>元素级保真</b>: 改文字只改 {@code <w:t>} 的文本, 保留所在 run 的 {@code <w:rPr>}
 *       (POI 的 {@code XWPFRun#setText} 会清空 rPr, 是"格式丢失"的头号元凶, 这里完全绕开)。</li>
 *   <li><b>不重建</b>: 段落 / 表格节点是原文档里的同一个 DOM 节点, 不重新生成;
 *       排序变化时用 DOM 的 {@code appendChild} 搬移<b>同一节点对象</b>(不是克隆), 内容零损失。</li>
 *   <li><b>最小差异</b>: 文字没变的段落连 {@code <w:t>} 都不写, 保持字节原样。</li>
 * </ol>
 * 新增段落时克隆<b>相邻同类段落</b>的 {@code <w:pPr>}(继承样式与编号), 见 docx4j 的官方建议。
 */
@Component
public class DocxCodec {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String XML_NS = XMLConstants.XML_NS_URI;

    private static final String DOC_PART = "word/document.xml";
    private static final String RELS_PART = "word/_rels/document.xml.rels";
    private static final String NUMBERING_PART = "word/numbering.xml";
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String IMAGE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";

    /** 单次解析区块上限(防御超大文档) */
    private static final int MAX_BLOCKS = 20000;
    /** 表格上限(行/列) */
    private static final int MAX_TABLE_ROWS = 200;
    private static final int MAX_TABLE_COLS = 30;
    /** 各标题级别的字号(磅), 仅在文档没有标题样式时作为直接格式兜底 */
    private static final int[] HEADING_PT = {0, 16, 14, 13, 12, 11, 11};

    /** 默认图片尺寸(EMU): 约 11cm × 7.3cm */
    private static final long DEFAULT_IMG_CX = 4191000L;
    private static final long DEFAULT_IMG_CY = 2794000L;

    // ==================================================================
    //  解析: docx -> 编辑器模型
    // ==================================================================

    /**
     * @param imageOutDir  内嵌图片落地目录(不存在会被创建)
     * @param imageUrlBase 图片对外 URL 前缀, 例如 /uploads/my-doc/12
     */
    public Map<String, Object> parse(byte[] bytes, Path imageOutDir, String imageUrlBase) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("docType", MyDocType.WORD.code());
        model.put("mode", "native");

        LinkedHashMap<String, byte[]> entries = readZip(bytes);
        byte[] docXml = entries.get(DOC_PART);
        if (docXml == null) throw new BusinessException("不是有效的 .docx: 缺少 word/document.xml");

        Document dom = parseXml(docXml);
        Element body = firstChild(dom.getDocumentElement(), W, "body");
        if (body == null) throw new BusinessException("不是有效的 .docx: 缺少正文节点");

        StyleTable styles = StyleTable.load(entries.get("word/styles.xml"));
        NumberingTable numbering = NumberingTable.load(entries.get(NUMBERING_PART));
        Map<String, String> rels = readRels(entries.get(RELS_PART));

        List<Object> blocks = new ArrayList<>();
        int count = 0;
        for (Element el : childElements(body)) {
            if ("sectPr".equals(el.getLocalName())) continue;
            if (count++ > MAX_BLOCKS) {
                blocks.add(placeholderBlock("文档区块过多, 仅展示前 " + MAX_BLOCKS + " 个区块"));
                break;
            }
            String ln = el.getLocalName();
            if ("tbl".equals(ln)) {
                Map<String, Object> b = tableBlock(el);
                b.put("anchor", count - 1);
                blocks.add(b);
            } else if ("p".equals(ln)) {
                appendParagraphBlocks(el, blocks, count - 1, styles, numbering, entries, rels,
                        imageOutDir, imageUrlBase);
            }
        }
        model.put("blocks", blocks);
        model.put("styles", styles.headingOptions());
        model.put("hasNumbering", !numbering.formats.isEmpty());
        return model;
    }

    private void appendParagraphBlocks(Element p, List<Object> blocks, int anchor,
                                       StyleTable styles, NumberingTable numbering,
                                       Map<String, byte[]> entries, Map<String, String> rels,
                                       Path imageOutDir, String imageUrlBase) {
        String text = collectText(p);
        boolean blank = text.trim().isEmpty();

        // 1) 分页符
        if (blank) {
            if (hasPageBreak(p)) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("type", "pagebreak");
                b.put("anchor", anchor);
                blocks.add(b);
            } else if (hasDrawing(p)) {
                blocks.add(imageBlockOf(p, anchor, entries, rels, imageOutDir, imageUrlBase));
            } else {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("type", "paragraph");
                b.put("anchor", anchor);
                b.put("text", "");
                b.put("align", "left");
                blocks.add(b);
            }
            return;
        }

        // 2) 内嵌图片单独成块(图文混排时先给图, 再给文字)
        if (hasDrawing(p)) {
            blocks.add(imageBlockOf(p, anchor, entries, rels, imageOutDir, imageUrlBase));
        }

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("anchor", anchor);

        String styleId = pStyleVal(p);
        int heading = styles.headingLevel(styleId);
        Element pPr = firstChild(p, W, "pPr");
        int numId = pPr == null ? -1 : numIdOf(pPr);

        if (heading > 0) {
            b.put("type", "heading");
            b.put("level", heading);
            b.put("text", text);
            b.put("styleId", styleId);
            b.put("styleName", styles.nameOf(styleId));
        } else if (numId >= 0) {
            b.put("type", "bullet");
            b.put("text", text);
            b.put("level", numLevelOf(pPr));
            b.put("numId", numId);
            b.put("ordered", numbering.isOrdered(numId));
        } else {
            b.put("type", "paragraph");
            b.put("text", text);
            b.put("align", jcOf(pPr));
        }
        if (styleId != null) {
            b.put("styleId", styleId);
            b.put("styleName", styles.nameOf(styleId));
        }
        putRunFormat(b, p);
        blocks.add(b);
    }

    private Map<String, Object> imageBlockOf(Element p, int anchor, Map<String, byte[]> entries,
                                             Map<String, String> rels, Path imageOutDir, String imageUrlBase) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "image");
        b.put("anchor", anchor);
        b.put("url", extractFirstImage(p, entries, rels, imageOutDir, imageUrlBase));
        return b;
    }

    private Map<String, Object> placeholderBlock(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "paragraph");
        b.put("anchor", -1);
        b.put("text", text);
        b.put("align", "left");
        b.put("bold", true);
        return b;
    }

    private Map<String, Object> tableBlock(Element tbl) {
        List<Element> trs = children(tbl, W, "tr");
        List<Object> rows = new ArrayList<>();
        int rowLimit = Math.min(trs.size(), MAX_TABLE_ROWS);
        for (int i = 0; i < rowLimit; i++) {
            List<Element> tcs = children(trs.get(i), W, "tc");
            List<Object> cells = new ArrayList<>();
            int colLimit = Math.min(tcs.size(), MAX_TABLE_COLS);
            for (int j = 0; j < colLimit; j++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("text", cellText(tcs.get(j)));
                cells.add(cell);
            }
            rows.add(cells);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "table");
        b.put("rows", rows);
        if (trs.size() > rowLimit) b.put("truncated", true);
        return b;
    }

    // ==================================================================
    //  保存: 编辑器模型 -> docx(就地补丁)
    // ==================================================================

    /**
     * @param original  磁盘上的当前 .docx 字节(必须存在, 就地补丁依赖它)
     * @param model     编辑器模型
     * @param imageBase /uploads 对应的磁盘根目录, 用于把 /uploads/xxx 还原成文件
     */
    public byte[] save(byte[] original, Map<String, Object> model, Path imageBase) {
        if (original == null || original.length == 0) {
            throw new BusinessException("Word 文档缺少原始文件, 无法就地保存");
        }
        LinkedHashMap<String, byte[]> entries = readZip(original);
        byte[] docXml = entries.get(DOC_PART);
        if (docXml == null) throw new BusinessException("不是有效的 .docx: 缺少 word/document.xml");

        Document dom = parseXml(docXml);
        Element body = firstChild(dom.getDocumentElement(), W, "body");
        if (body == null) throw new BusinessException("不是有效的 .docx: 缺少正文节点");
        Element sectPr = firstChild(body, W, "sectPr");
        List<Element> content = new ArrayList<>();
        for (Element e : childElements(body)) {
            if (e != sectPr) content.add(e);
        }

        NumberingCtx numbering = new NumberingCtx(entries);
        ImageCtx images = new ImageCtx(entries, imageBase);
        StyleTable styles = StyleTable.load(entries.get("word/styles.xml"));

        List<?> blocks = asList(model.get("blocks"));
        List<Element> ordered = new ArrayList<>();
        Set<Element> taken = new HashSet<>();
        Map<Integer, Element> byAnchor = new LinkedHashMap<>();

        for (Object o : blocks) {
            Map<String, Object> b = asMap(o);
            Element node = pickOrCreate(dom, b, content, ordered, taken, byAnchor);
            applyBlock(dom, node, b, numbering, images, styles);
            ordered.add(node);
        }

        // 1) 删掉模型里已不存在的原始区块
        for (Element e : content) {
            if (!taken.contains(e)) body.removeChild(e);
        }
        // 2) 按模型顺序重排: appendChild 对已在树上的节点是"移动", 不产生任何内容损失
        for (Element e : ordered) body.appendChild(e);
        // 3) sectPr 必须永远是 body 的最后一个子元素
        if (sectPr != null) body.appendChild(sectPr);

        entries.put(DOC_PART, toXml(dom));
        numbering.flush();
        images.flush();
        return writeZip(entries);
    }

    /**
     * 取回模型指向的原始节点; 找不到(新增块 / 类型不匹配)就克隆相邻同类节点。
     * <p>
     * 同一个 anchor 会复用<b>同一个</b>节点: 解析时"图文混排段落"会产出图片块 + 文字块两个块,
     * 它们必须落回同一个 {@code <w:p>}, 否则保存后图片或文字会丢一个。
     */
    private Element pickOrCreate(Document dom, Map<String, Object> b, List<Element> content,
                                 List<Element> ordered, Set<Element> taken, Map<Integer, Element> byAnchor) {
        int anchor = asInt(b.get("anchor"), -1);
        String want = "table".equals(asString(b.get("type"))) ? "tbl" : "p";
        if (anchor >= 0 && anchor < content.size()) {
            Element reuse = byAnchor.get(anchor);
            if (reuse != null && want.equals(reuse.getLocalName())) return reuse;
            Element cand = content.get(anchor);
            if (!taken.contains(cand) && want.equals(cand.getLocalName())) {
                taken.add(cand);
                byAnchor.put(anchor, cand);
                return cand;
            }
        }
        // 新增: 克隆最近的同类节点(继承它的 pPr / tblPr, 也就是继承样式与编号)
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (want.equals(ordered.get(i).getLocalName())) return cloneEmpty(dom, ordered.get(i));
        }
        for (Element e : content) {
            if (want.equals(e.getLocalName())) return cloneEmpty(dom, e);
        }
        return dom.createElementNS(W, want);
    }

    private Element cloneEmpty(Document dom, Element src) {
        Element copy = (Element) src.cloneNode(true);
        dom.adoptNode(copy);
        clearText(copy);
        return copy;
    }

    private void applyBlock(Document dom, Element node, Map<String, Object> b,
                            NumberingCtx numbering, ImageCtx images, StyleTable styles) {
        String type = asString(b.get("type"));
        if ("tbl".equals(node.getLocalName())) {
            if ("table".equals(type)) applyTable(dom, node, b);
            return;
        }
        switch (type) {
            case "pagebreak" -> applyPageBreak(dom, node);
            case "image" -> applyImage(dom, node, b, images);
            default -> applyParagraph(dom, node, b, numbering, styles);
        }
    }

    // ---------- 段落 ----------

    private void applyParagraph(Document dom, Element p, Map<String, Object> b,
                                NumberingCtx numbering, StyleTable styles) {
        String type = asString(b.get("type"));
        String text = asString(b.get("text"));

        // 文字没变就一个字节都不动(最常见的"只改了别处"场景)
        if ("image".equals(type) || "pagebreak".equals(type)) {
            // 这两个类型不改正文
        } else if (!text.equals(collectText(p))) {
            setParaText(p, text);
        }

        Element pPr = firstChild(p, W, "pPr");

        // 对齐: 目标值与现状一致就不写(否则会凭空给原本没有 pPr 的段落塞一个)
        String align = b.get("align") == null ? null : asString(b.get("align"));
        if (align != null && !align.isEmpty() && !align.equals(jcOf(pPr))) {
            setValEl(dom, ensurePPr(dom, p), "jc", toJc(align));
        }

        // 标题 / 列表语义
        if ("heading".equals(type)) {
            int level = Math.max(1, Math.min(6, asInt(b.get("level"), 1)));
            removeChildIf(pPr, W, "numPr");
            String styleId = styles.headingStyleIdFor(level, asString(b.get("styleId")));
            if (styleId != null) {
                if (!styleId.equals(pStyleVal(p))) setValEl(dom, ensurePPr(dom, p), "pStyle", styleId);
            } else {
                // 文档里根本没有标题样式(例如新建的空白文档) → 退化为直接格式,
                // 保证"标题"在 Word 里看上去确实是标题, 而不是和正文一样
                b.put("bold", Boolean.TRUE);
                b.put("fontSize", HEADING_PT[level]);
            }
        } else if ("bullet".equals(type)) {
            boolean ordered = asBool(b.get("ordered"));
            int level = Math.max(0, Math.min(8, asInt(b.get("level"), 0)));
            Integer numId = numbering.resolve(ordered);
            if (numId != null && (numIdOf(pPr) != numId || numLevelOf(pPr) != level)) {
                Element numPr = ensureChild(dom, ensurePPr(dom, p), "numPr", false);
                setValEl(dom, numPr, "ilvl", String.valueOf(level));
                setValEl(dom, numPr, "numId", String.valueOf(numId));
            }
        } else {
            removeChildIf(pPr, W, "numPr");
            // 编辑器显式指定为"正文"时, 若节点是从标题克隆来的(Word 里回车会延续样式),
            // 必须把标题样式摘掉, 否则新段落会莫名其妙变成标题
            Element curPr = firstChild(p, W, "pPr");
            if (curPr != null) {
                String curStyle = pStyleVal(p);
                if (curStyle != null && styles.headingLevel(curStyle) > 0) {
                    removeChild(curPr, W, "pStyle");
                }
            }
        }

        applyRunFormat(dom, p, b);
    }

    /**
     * 字符格式: <b>逐项与当前值比对, 只有真的变了才落笔</b>。
     * 这样"用户只改了另一个段落"时, 本段落连 rPr 都不会被创建, 真正零改动。
     */
    private void applyRunFormat(Document dom, Element p, Map<String, Object> b) {
        Element run = firstTextRun(p);
        if (run == null) return;
        Element rPr = firstChild(run, W, "rPr");

        writeFlag(dom, run, rPr, "b", b.get("bold"));
        writeFlag(dom, run, rPr, "i", b.get("italic"));
        writeFlag(dom, run, rPr, "strike", b.get("strike"));

        Object underline = b.get("underline");
        if (underline != null) {
            boolean cur = rPr != null && valOf(rPr, "u") != null && !"none".equalsIgnoreCase(valOf(rPr, "u"));
            if (asBool(underline) != cur) {
                setValEl(dom, ensureRPr(dom, run), "u", asBool(underline) ? "single" : null);
            }
        }
        Object fontSize = b.get("fontSize");
        if (fontSize != null) {
            int pt = asInt(fontSize, 0);
            String curSz = rPr == null ? null : valOf(rPr, "sz");
            int curPt = 0;
            if (curSz != null) {
                try { curPt = Integer.parseInt(curSz.trim()) / 2; } catch (NumberFormatException ignored) { }
            }
            if (pt > 0 && pt != curPt) setValEl(dom, ensureRPr(dom, run), "sz", String.valueOf(pt * 2));
        }
        Object color = b.get("color");
        if (color != null) {
            String want = normalizeColor(asString(color));
            String cur = rPr == null ? null : valOf(rPr, "color");
            if (cur != null && "auto".equalsIgnoreCase(cur)) cur = null;
            if (!want.isEmpty() && !want.equals(cur)) {
                setValEl(dom, ensureRPr(dom, run), "color", want);
            }
        }
        Object family = b.get("fontFamily");
        if (family != null) {
            String want = asString(family);
            String cur = null;
            if (rPr != null) {
                Element fonts = firstChild(rPr, W, "rFonts");
                if (fonts != null) {
                    cur = fonts.getAttributeNS(W, "w:ascii");
                    if (cur == null || cur.isEmpty()) cur = fonts.getAttribute("ascii");
                }
            }
            if (!want.isEmpty() && !want.equals(cur)) {
                Element fonts = ensureChild(dom, ensureRPr(dom, run), "rFonts", true);
                fonts.setAttributeNS(W, "w:ascii", want);
                fonts.setAttributeNS(W, "w:eastAsia", want);
                fonts.setAttributeNS(W, "w:hAnsi", want);
            }
        }
    }

    private void writeFlag(Document dom, Element run, Element rPr, String local, Object value) {
        if (value == null) return;
        boolean want = asBool(value);
        boolean cur = rPr != null && firstChild(rPr, W, local) != null;
        if (want == cur) return;
        if (want) {
            ensureChild(dom, ensureRPr(dom, run), local, true);
        } else if (rPr != null) {
            removeChild(rPr, W, local);
        }
    }

    private static Element ensureRPr(Document dom, Element run) {
        return ensureFirstChild(dom, run, "rPr");
    }

    private void applyPageBreak(Document dom, Element p) {
        clearText(p);
        Element run = ensureFirstRun(dom, p);
        Element br = dom.createElementNS(W, "w:br");
        br.setAttributeNS(W, "w:type", "page");
        run.appendChild(br);
    }

    // ---------- 表格 ----------

    private void applyTable(Document dom, Element tbl, Map<String, Object> b) {
        List<?> rows = asList(b.get("rows"));
        int wantRows = Math.min(rows.size(), MAX_TABLE_ROWS);
        int wantCols = 0;
        for (int i = 0; i < wantRows; i++) wantCols = Math.max(wantCols, asList(rows.get(i)).size());
        wantCols = Math.min(wantCols, MAX_TABLE_COLS);

        List<Element> trs = children(tbl, W, "tr");
        if (trs.isEmpty()) {
            // 全新表格(编辑器里"插入表格"): 从零搭骨架并给一套可见边框, 否则 Word 里是一条没有格线的空表
            ensureTableBorders(dom, tbl);
            for (int r = 0; r < wantRows; r++) {
                Element tr = buildRow(dom, wantCols);
                trs.add(tr);
                tbl.appendChild(tr);
            }
        }
        // 行数对齐: 多则克隆末行(保留行样式), 少则删尾部
        while (trs.size() > wantRows) {
            Element last = trs.remove(trs.size() - 1);
            tbl.removeChild(last);
        }
        while (trs.size() < wantRows && !trs.isEmpty()) {
            Element clone = (Element) trs.get(trs.size() - 1).cloneNode(true);
            trs.add(clone);
            tbl.appendChild(clone);
        }
        if (trs.isEmpty()) return;

        for (int r = 0; r < trs.size(); r++) {
            Element tr = trs.get(r);
            List<Element> tcs = children(tr, W, "tc");
            while (tcs.size() > wantCols) {
                Element last = tcs.remove(tcs.size() - 1);
                tr.removeChild(last);
            }
            while (tcs.size() < wantCols && !tcs.isEmpty()) {
                Element clone = (Element) tcs.get(tcs.size() - 1).cloneNode(true);
                tcs.add(clone);
                tr.appendChild(clone);
            }
            List<?> cells = r < rows.size() ? asList(rows.get(r)) : List.of();
            for (int c = 0; c < tcs.size(); c++) {
                String txt = c < cells.size() ? asString(asMap(cells.get(c)).get("text")) : "";
                setCellText(dom, tcs.get(c), txt);
            }
        }
    }

    private Element buildRow(Document dom, int cols) {
        Element tr = dom.createElementNS(W, "w:tr");
        for (int i = 0; i < cols; i++) {
            Element tc = dom.createElementNS(W, "w:tc");
            tc.appendChild(dom.createElementNS(W, "w:p"));
            tr.appendChild(tc);
        }
        return tr;
    }

    /** 给没有任何 tblPr 的表格补一份"全部单线边框", 让 Word 里看得出是表格 */
    private void ensureTableBorders(Document dom, Element tbl) {
        if (firstChild(tbl, W, "tblPr") != null) return;
        Element tblPr = ensureFirstChild(dom, tbl, "tblPr");
        Element w = dom.createElementNS(W, "w:tblW");
        w.setAttributeNS(W, "w:w", "5000");
        w.setAttributeNS(W, "w:type", "pct");
        tblPr.appendChild(w);
        Element borders = dom.createElementNS(W, "w:tblBorders");
        for (String side : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            Element bd = dom.createElementNS(W, side);
            bd.setAttributeNS(W, "w:val", "single");
            bd.setAttributeNS(W, "w:sz", "4");
            bd.setAttributeNS(W, "w:space", "0");
            bd.setAttributeNS(W, "w:color", "999999");
            borders.appendChild(bd);
        }
        tblPr.appendChild(borders);
    }

    private void setCellText(Document dom, Element tc, String text) {
        List<Element> ps = children(tc, W, "p");
        if (ps.isEmpty()) {
            Element p = dom.createElementNS(W, "w:p");
            tc.appendChild(p);
            ps = List.of(p);
        }
        Element first = ps.get(0);
        if (!text.equals(cellText(first))) setParaText(first, text);
        for (int i = 1; i < ps.size(); i++) tc.removeChild(ps.get(i));
    }

    // ---------- 图片 ----------

    private void applyImage(Document dom, Element p, Map<String, Object> b, ImageCtx images) {
        String url = asString(b.get("url"));
        if (url.isEmpty()) return;

        Element drawing = firstDescendant(p, W, "drawing");
        if (drawing != null) {
            Element blip = firstDescendant(drawing, A, "blip");
            if (blip != null) {
                String rid = attr(blip, R, "embed");
                // 图片没换就一个字节都不动(否则每次保存都会往包里塞一份新 media)
                if (rid != null && images.isSameImage(rid, url)) return;
                String newRid = images.embed(url);
                if (newRid != null) blip.setAttributeNS(R, "r:embed", newRid);
                return;
            }
        }
        String newRid = images.embed(url);
        if (newRid == null) return;
        // 原来没有图 → 造一个内联图片段落(尺寸默认, 沿用原段落的 pPr)
        clearText(p);
        Element run = ensureFirstRun(dom, p);
        run.appendChild(buildInlineDrawing(dom, newRid));
    }

    /** 构造一个 inline 图片 <w:drawing>, 只挂引用, 尺寸取默认值 */
    private Element buildInlineDrawing(Document dom, String rid) {
        Element drawing = dom.createElementNS(W, "w:drawing");
        Element inline = dom.createElementNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "wp:inline");
        inline.setAttribute("distT", "0");
        inline.setAttribute("distB", "0");
        inline.setAttribute("distL", "0");
        inline.setAttribute("distR", "0");
        drawing.appendChild(inline);

        Element extent = dom.createElementNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "wp:extent");
        extent.setAttribute("cx", String.valueOf(DEFAULT_IMG_CX));
        extent.setAttribute("cy", String.valueOf(DEFAULT_IMG_CY));
        inline.appendChild(extent);

        Element eff = dom.createElementNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "wp:effectExtent");
        eff.setAttribute("l", "0"); eff.setAttribute("t", "0");
        eff.setAttribute("r", "0"); eff.setAttribute("b", "0");
        inline.appendChild(eff);

        int docPrId = Math.abs(UUID.randomUUID().hashCode() % 100000) + 1;
        Element docPr = dom.createElementNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "wp:docPr");
        docPr.setAttribute("id", String.valueOf(docPrId));
        docPr.setAttribute("name", "图片");
        inline.appendChild(docPr);

        Element graphic = dom.createElementNS(A, "a:graphic");
        inline.appendChild(graphic);
        Element graphicData = dom.createElementNS(A, "a:graphicData");
        graphicData.setAttribute("uri", "http://schemas.openxmlformats.org/drawingml/2006/picture");
        graphic.appendChild(graphicData);

        String picNs = "http://schemas.openxmlformats.org/drawingml/2006/picture";
        Element pic = dom.createElementNS(picNs, "pic:pic");
        graphicData.appendChild(pic);
        Element nvPicPr = dom.createElementNS(picNs, "pic:nvPicPr");
        pic.appendChild(nvPicPr);
        Element cNvPr = dom.createElementNS(picNs, "pic:cNvPr");
        cNvPr.setAttribute("id", "0");
        cNvPr.setAttribute("name", "img");
        nvPicPr.appendChild(cNvPr);
        nvPicPr.appendChild(dom.createElementNS(picNs, "pic:cNvPicPr"));

        Element blipFill = dom.createElementNS(picNs, "pic:blipFill");
        pic.appendChild(blipFill);
        Element blip = dom.createElementNS(A, "a:blip");
        blip.setAttributeNS(R, "r:embed", rid);
        blipFill.appendChild(blip);
        Element stretch = dom.createElementNS(A, "a:stretch");
        stretch.appendChild(dom.createElementNS(A, "a:fillRect"));
        blipFill.appendChild(stretch);

        Element spPr = dom.createElementNS(picNs, "pic:spPr");
        pic.appendChild(spPr);
        Element xfrm = dom.createElementNS(A, "a:xfrm");
        spPr.appendChild(xfrm);
        Element off = dom.createElementNS(A, "a:off");
        off.setAttribute("x", "0"); off.setAttribute("y", "0");
        xfrm.appendChild(off);
        Element ext = dom.createElementNS(A, "a:ext");
        ext.setAttribute("cx", String.valueOf(DEFAULT_IMG_CX));
        ext.setAttribute("cy", String.valueOf(DEFAULT_IMG_CY));
        xfrm.appendChild(ext);
        Element prst = dom.createElementNS(A, "a:prstGeom");
        prst.setAttribute("prst", "rect");
        prst.appendChild(dom.createElementNS(A, "a:avLst"));
        spPr.appendChild(prst);
        return drawing;
    }

    // ==================================================================
    //  文本读写(DOM 层面, 绝不重建段落)
    // ==================================================================

    private static void setParaText(Element p, String text) {
        List<Element> ts = descendants(p, W, "t");
        Element first = ts.isEmpty() ? null : ts.get(0);
        Document dom = p.getOwnerDocument();
        if (first == null) {
            Element run = ensureFirstRun(dom, p);
            first = dom.createElementNS(W, "w:t");
            run.appendChild(first);
        }
        Element host = (Element) first.getParentNode();
        String[] lines = text == null ? new String[]{""} : text.split("\n", -1);
        setPreserve(first);
        first.setTextContent(lines.length > 0 ? lines[0] : "");
        // Word 的换行是 <w:br/> + 另一个 <w:t>, 同一个 run 内并列, 这样能完整继承 rPr
        for (int i = 1; i < lines.length; i++) {
            Element br = dom.createElementNS(W, "w:br");
            host.appendChild(br);
            Element t = dom.createElementNS(W, "w:t");
            setPreserve(t);
            t.setTextContent(lines[i]);
            host.appendChild(t);
        }
        // 其余 <w:t> 清掉; run 空了就移除 run(带图片的 run 没有 w:t, 不受影响)
        for (int i = 1; i < ts.size(); i++) {
            Element t = ts.get(i);
            Node run = t.getParentNode();
            run.removeChild(t);
            if (run.getParentNode() != null && !hasElementChild((Element) run)) {
                run.getParentNode().removeChild(run);
            }
        }
    }

    private static void clearText(Element node) {
        for (Element t : descendants(node, W, "t")) {
            Node run = t.getParentNode();
            run.removeChild(t);
            if (run.getParentNode() != null && !hasElementChild((Element) run)) {
                run.getParentNode().removeChild(run);
            }
        }
    }

    /** 按 OOXML 语义取文本: w:t 拼接, w:tab -> \t, w:br/cr -> \n, w:delText 跳过 */
    private static String collectText(Element node) {
        StringBuilder sb = new StringBuilder();
        walkText(node, sb);
        return sb.toString();
    }

    private static void walkText(Node n, StringBuilder sb) {
        if (n.getNodeType() == Node.TEXT_NODE) {
            // 元素之间的换行/缩进是排版空白, 不是正文。
            // 一旦把它算进文本, 标题样式和列表编号就都识别不出来了(真实 docx 里常有缩进)。
            // 真正的文字一律由下面的 <w:t> 分支用 getTextContent() 取。
            return;
        }
        if (n.getNodeType() != Node.ELEMENT_NODE) return;
        Element e = (Element) n;
        if (W.equals(e.getNamespaceURI())) {
            switch (e.getLocalName()) {
                case "t" -> sb.append(e.getTextContent());
                case "tab" -> sb.append('\t');
                case "br", "cr" -> sb.append('\n');
                case "delText" -> { }
                default -> walkChildren(e, sb);
            }
            return;
        }
        walkChildren(e, sb);
    }

    private static void walkChildren(Element e, StringBuilder sb) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) walkText(kids.item(i), sb);
    }

    private static String cellText(Element tc) {
        List<Element> ps = children(tc, W, "p");
        if (ps.isEmpty()) return collectText(tc);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(collectText(ps.get(i)));
        }
        return sb.toString();
    }

    // ==================================================================
    //  rPr / pPr 小工具
    // ==================================================================

    private void putRunFormat(Map<String, Object> b, Element p) {
        Element run = firstTextRun(p);
        if (run == null) return;
        Element rPr = firstChild(run, W, "rPr");
        if (rPr == null) return;
        b.put("bold", hasChild(rPr, W, "b"));
        b.put("italic", hasChild(rPr, W, "i"));
        b.put("strike", hasChild(rPr, W, "strike"));
        String u = valOf(rPr, "u");
        b.put("underline", u != null && !"none".equalsIgnoreCase(u));
        String sz = valOf(rPr, "sz");
        if (sz != null) {
            try {
                int half = Integer.parseInt(sz.trim());
                if (half > 0) b.put("fontSize", half / 2);
            } catch (NumberFormatException ignored) { }
        }
        String color = valOf(rPr, "color");
        if (color != null && !"auto".equalsIgnoreCase(color)) b.put("color", normalizeColor(color));
        Element fonts = firstChild(rPr, W, "rFonts");
        if (fonts != null) {
            String f = fonts.getAttributeNS(W, "w:ascii");
            if (f == null || f.isEmpty()) f = fonts.getAttribute("w:ascii");
            if (f != null && !f.isEmpty()) b.put("fontFamily", f);
        }
    }

    private static Element ensurePPr(Document dom, Element p) {
        return ensureFirstChild(dom, p, "pPr");
    }

    private static Element ensureFirstRun(Document dom, Element p) {
        Element run = firstDescendant(p, W, "r");
        if (run != null) return run;
        Element created = dom.createElementNS(W, "w:r");
        p.appendChild(created);
        return created;
    }

    private static Element firstTextRun(Element p) {
        for (Element r : descendants(p, W, "r")) {
            if (!descendants(r, W, "t").isEmpty()) return r;
        }
        Element first = firstDescendant(p, W, "r");
        return first;
    }

    /** 保证 {@code w:local} 存在并返回它; {@code first=true} 时必须是首个子元素(pPr/rPr 的位置约束) */
    private static Element ensureChild(Document dom, Element parent, String local, boolean first) {
        Element exist = firstChild(parent, W, local);
        if (exist != null) return exist;
        Element created = dom.createElementNS(W, "w:" + local);
        if (first && parent.getFirstChild() != null) {
            parent.insertBefore(created, parent.getFirstChild());
        } else {
            parent.appendChild(created);
        }
        return created;
    }

    private static Element ensureFirstChild(Document dom, Element parent, String local) {
        return ensureChild(dom, parent, local, true);
    }

    /** 设置 {@code <w:local w:val="v"/>}; v 为空/null 则删除该元素 */
    private static void setValEl(Document dom, Element parent, String local, String v) {
        Element exist = firstChild(parent, W, local);
        if (v == null || v.isEmpty()) {
            if (exist != null) parent.removeChild(exist);
            return;
        }
        if (exist == null) {
            exist = dom.createElementNS(W, "w:" + local);
            parent.appendChild(exist);
        }
        exist.setAttributeNS(W, "w:val", v);
    }

    /** parent 为 null(段落本来就没有 pPr)时安全地什么都不做 */
    private static void removeChildIf(Element parent, String ns, String local) {
        if (parent == null) return;
        removeChild(parent, ns, local);
    }

    private static void removeChild(Element parent, String ns, String local) {
        Element e = firstChild(parent, ns, local);
        if (e != null) parent.removeChild(e);
    }

    private static String valOf(Element parent, String local) {
        Element e = firstChild(parent, W, local);
        if (e == null) return null;
        String v = e.getAttributeNS(W, "w:val");
        if (v == null || v.isEmpty()) v = e.getAttribute("w:val");
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static boolean hasChild(Element parent, String ns, String local) {
        return firstChild(parent, ns, local) != null;
    }

    private static String pStyleVal(Element p) {
        Element pPr = firstChild(p, W, "pPr");
        if (pPr == null) return null;
        return valOf(pPr, "pStyle");
    }

    private static int numIdOf(Element pPr) {
        Element numPr = firstChild(pPr, W, "numPr");
        if (numPr == null) return -1;
        String v = valOf(numPr, "numId");
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int numLevelOf(Element pPr) {
        Element numPr = firstChild(pPr, W, "numPr");
        if (numPr == null) return 0;
        String v = valOf(numPr, "ilvl");
        if (v == null) return 0;
        try {
            return Math.max(0, Math.min(8, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String jcOf(Element pPr) {
        if (pPr == null) return "left";
        String v = valOf(pPr, "jc");
        if (v == null) return "left";
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "center" -> "center";
            case "right" -> "right";
            case "both", "distribute" -> "justify";
            default -> "left";
        };
    }

    private static String toJc(String align) {
        return switch (align.toLowerCase(Locale.ROOT)) {
            case "center" -> "center";
            case "right" -> "right";
            case "justify", "both" -> "both";
            default -> "left";
        };
    }

    private static boolean hasPageBreak(Element p) {
        for (Element br : descendants(p, W, "br")) {
            String t = br.getAttributeNS(W, "w:type");
            if (t == null || t.isEmpty()) t = br.getAttribute("w:type");
            if ("page".equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    private static boolean hasDrawing(Element p) {
        return firstDescendant(p, W, "drawing") != null
                || firstDescendant(p, W, "pict") != null;
    }

    private static void setPreserve(Element t) {
        t.setAttributeNS(XML_NS, "xml:space", "preserve");
    }

    private static String normalizeColor(String c) {
        String s = c == null ? "" : c.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 8) s = s.substring(2);      // ARGB -> RGB
        return s.toUpperCase(Locale.ROOT);
    }

    // ==================================================================
    //  图片落地 / 嵌入
    // ==================================================================

    private String extractFirstImage(Element p, Map<String, byte[]> entries, Map<String, String> rels,
                                     Path imageOutDir, String imageUrlBase) {
        Element blip = firstDescendant(p, A, "blip");
        if (blip == null) return "";
        String rid = attr(blip, R, "embed");
        if (rid == null || rid.isEmpty()) rid = attr(blip, R, "link");
        if (rid == null || rid.isEmpty()) return "";
        String target = rels.get(rid);
        if (target == null) return "";
        String part = target.startsWith("/") ? target.substring(1) : "word/" + target;
        byte[] data = entries.get(part);
        if (data == null || data.length == 0) return "";
        String name = part.substring(part.lastIndexOf('/') + 1);
        try {
            Files.createDirectories(imageOutDir);
            Path out = imageOutDir.resolve(name);
            if (!Files.exists(out)) Files.write(out, data);
            return imageUrlBase + "/" + name;
        } catch (IOException e) {
            return "";
        }
    }

    /** 新增图片的落地: 写 media part + rels + [Content_Types].xml */
    private static final class ImageCtx {
        private final LinkedHashMap<String, byte[]> entries;
        private final Path imageBase;
        private final Map<String, String> cache = new LinkedHashMap<>();

        ImageCtx(LinkedHashMap<String, byte[]> entries, Path imageBase) {
            this.entries = entries;
            this.imageBase = imageBase;
        }

        /** @return 关系 Id; 无法处理返回 null */
        String embed(String url) {
            String hit = cache.get(url);
            if (hit != null) return hit;
            if (imageBase == null) return null;
            String rel = url.startsWith("/") ? url.substring(1) : url;
            if (rel.startsWith("uploads/")) rel = rel.substring("uploads/".length());
            Path file;
            try {
                file = imageBase.resolve(rel).normalize();
                if (!file.startsWith(imageBase.normalize()) || !Files.isReadable(file)) return null;
            } catch (RuntimeException e) {
                return null;
            }
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "png";
            byte[] data;
            try {
                data = Files.readAllBytes(file);
            } catch (IOException e) {
                return null;
            }
            String part = "word/media/mydoc_" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
            entries.put(part, data);
            String rid = addRelationship(part);
            ensureContentType(ext);
            cache.put(url, rid);
            return rid;
        }

        private String addRelationship(String target) {
            byte[] raw = entries.get(RELS_PART);
            Document dom;
            Element root;
            if (raw == null || raw.length == 0) {
                dom = newDom();
                root = dom.createElementNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationships");
                dom.appendChild(root);
            } else {
                dom = parseXml(raw);
                root = dom.getDocumentElement();
            }
            String rid = "rIdImg" + (root.getChildNodes().getLength() + 1000);
            while (relsContain(root, rid)) rid = rid + "x";
            Element r = dom.createElementNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship");
            r.setAttribute("Id", rid);
            r.setAttribute("Type", IMAGE_REL_TYPE);
            r.setAttribute("Target", "media/" + target.substring(target.lastIndexOf('/') + 1));
            root.appendChild(r);
            entries.put(RELS_PART, toXml(dom));
            return rid;
        }

        private boolean relsContain(Element root, String rid) {
            NodeList nl = root.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n instanceof Element e && rid.equals(e.getAttribute("Id"))) return true;
            }
            return false;
        }

        private void ensureContentType(String ext) {
            byte[] raw = entries.get(CONTENT_TYPES);
            if (raw == null || raw.length == 0) return;
            Document dom = parseXml(raw);
            Element root = dom.getDocumentElement();
            if (root == null) return;
            String mime = switch (ext) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "gif" -> "image/gif";
                case "bmp" -> "image/bmp";
                case "tif", "tiff" -> "image/tiff";
                case "emf" -> "image/x-emf";
                case "wmf" -> "image/x-wmf";
                case "svg" -> "image/svg+xml";
                default -> "image/png";
            };
            NodeList nl = root.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/package/2006/content-types", "Default");
            for (int i = 0; i < nl.getLength(); i++) {
                if (ext.equalsIgnoreCase(((Element) nl.item(i)).getAttribute("Extension"))) return;
            }
            Element d = dom.createElementNS(
                    "http://schemas.openxmlformats.org/package/2006/content-types", "Default");
            d.setAttribute("Extension", ext);
            d.setAttribute("ContentType", mime);
            root.appendChild(d);
            entries.put(CONTENT_TYPES, toXml(dom));
        }

        /** 该 rId 指向的 media 与编辑器里的 url 是同一张图吗(按文件名比对, 不做字节比对以省 IO) */
        boolean isSameImage(String rid, String url) {
            String target = targetOf(rid);
            if (target == null) return false;
            String name = target.substring(target.lastIndexOf('/') + 1);
            return !name.isEmpty() && url.endsWith("/" + name);
        }

        String targetOf(String rid) {
            byte[] raw = entries.get(RELS_PART);
            if (raw == null || raw.length == 0) return null;
            Document dom;
            try {
                dom = parseXml(raw);
            } catch (BusinessException e) {
                return null;
            }
            Element root = dom.getDocumentElement();
            if (root == null) return null;
            NodeList nl = root.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i) instanceof Element e && "Relationship".equals(e.getLocalName())
                        && rid.equals(e.getAttribute("Id"))) {
                    String t = e.getAttribute("Target");
                    return t == null || t.isEmpty() ? null : t;
                }
            }
            return null;
        }

        void flush() { }
    }

    // ==================================================================
    //  编号(列表)
    // ==================================================================

    /** 列表编号上下文: 优先复用文档已有的 numId, 实在没有才往 numbering.xml 里补一条 */
    private static final class NumberingCtx {
        private final LinkedHashMap<String, byte[]> entries;
        private Document dom;
        private boolean loaded;
        /** 只有当真的往 numbering.xml 里补了新定义时才回写, 复用现成编号绝不动这个文件 */
        private boolean dirty;
        private final Map<Boolean, Integer> resolved = new LinkedHashMap<>();

        NumberingCtx(LinkedHashMap<String, byte[]> entries) {
            this.entries = entries;
        }

        /** @return 可用的 numId; 文档完全没有编号体系时返回 null(退化为普通段落) */
        Integer resolve(boolean ordered) {
            Integer cached = resolved.get(ordered);
            if (cached != null) return cached;
            load();
            if (dom == null) return null;
            Element root = dom.getDocumentElement();
            if (root == null) return null;
            Integer found = findExisting(root, ordered);
            if (found == null) found = createNum(root, ordered);
            if (found != null) resolved.put(ordered, found);
            return found;
        }

        private void load() {
            if (loaded) return;
            loaded = true;
            byte[] raw = entries.get(NUMBERING_PART);
            if (raw == null || raw.length == 0) return;
            try {
                dom = parseXml(raw);
            } catch (BusinessException e) {
                dom = null;
            }
        }

        /** 找一个现成的"项目符号 / 十进制" numId, 沿用文档自己的列表样式 */
        private Integer findExisting(Element root, boolean ordered) {
            Map<Integer, Integer> numToAbs = new LinkedHashMap<>();
            for (Element num : children(root, W, "num")) {
                Integer id = intAttr(num, "numId");
                Integer abs = intVal(num, "abstractNumId");
                if (id != null && abs != null) numToAbs.put(id, abs);
            }
            for (Element an : children(root, W, "abstractNum")) {
                Integer absId = intAttr(an, "abstractNumId");
                if (absId == null) continue;
                Element lvl0 = null;
                for (Element l : children(an, W, "lvl")) {
                    if (intAttr(l, "ilvl") != null && intAttr(l, "ilvl") == 0) { lvl0 = l; break; }
                }
                if (lvl0 == null) continue;
                String fmt = valOf(lvl0, "numFmt");
                if (fmt == null) continue;
                boolean isBullet = "bullet".equalsIgnoreCase(fmt);
                if (isBullet == ordered) continue;
                for (var en : numToAbs.entrySet()) {
                    if (en.getValue().equals(absId)) return en.getKey();
                }
            }
            return null;
        }

        /** 在 numbering.xml 里补一个 abstractNum + num(只在原文档已有编号体系时才会走到) */
        private Integer createNum(Element root, boolean ordered) {
            int seed = 1000 + Math.abs(UUID.randomUUID().hashCode() % 9000);
            Element an = dom.createElementNS(W, "w:abstractNum");
            an.setAttributeNS(W, "w:abstractNumId", String.valueOf(seed));
            Element lvl = dom.createElementNS(W, "w:lvl");
            lvl.setAttributeNS(W, "w:ilvl", "0");
            an.appendChild(lvl);
            setValEl(dom, lvl, "start", "1");
            setValEl(dom, lvl, "numFmt", ordered ? "decimal" : "bullet");
            setValEl(dom, lvl, "lvlText", ordered ? "%1." : "•");
            setValEl(dom, lvl, "lvlJc", "left");
            Element pPr = dom.createElementNS(W, "w:pPr");
            lvl.appendChild(pPr);
            Element ind = dom.createElementNS(W, "w:ind");
            ind.setAttributeNS(W, "w:left", "720");
            ind.setAttributeNS(W, "w:hanging", "360");
            pPr.appendChild(ind);
            // abstractNum 必须排在全部 num 之前
            Element firstNum = null;
            for (Element n : children(root, W, "num")) { firstNum = n; break; }
            if (firstNum != null) root.insertBefore(an, firstNum);
            else root.appendChild(an);

            Element num = dom.createElementNS(W, "w:num");
            num.setAttributeNS(W, "w:numId", String.valueOf(seed));
            setValEl(dom, num, "abstractNumId", String.valueOf(seed));
            root.appendChild(num);
            dirty = true;
            return seed;
        }

        void flush() {
            if (dirty && dom != null) entries.put(NUMBERING_PART, toXml(dom));
        }
    }

    // ==================================================================
    //  样式表 / 编号表(只读, 不参与保存)
    // ==================================================================

    private static final class StyleTable {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, Integer> headingLevels = new LinkedHashMap<>();

        static StyleTable load(byte[] raw) {
            StyleTable t = new StyleTable();
            if (raw == null || raw.length == 0) return t;
            Document dom;
            try {
                dom = parseXml(raw);
            } catch (BusinessException e) {
                return t;
            }
            Element root = dom.getDocumentElement();
            if (root == null) return t;
            for (Element s : children(root, W, "style")) {
                String id = attr(s, W, "styleId");
                if (id == null || id.isEmpty()) continue;
                String nm = valOf(s, "name");
                if (nm != null) t.names.put(id, nm);
                Integer lvl = headingLevelFrom(s, id, nm);
                if (lvl != null) t.headingLevels.put(id, lvl);
            }
            return t;
        }

        private static Integer headingLevelFrom(Element s, String id, String name) {
            String outline = valOf(s, "outlineLvl");
            if (outline != null) {
                try {
                    int v = Integer.parseInt(outline.trim());
                    if (v >= 0 && v <= 8) return Math.min(6, v + 1);
                } catch (NumberFormatException ignored) { }
            }
            String key = (id + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
            boolean isHeading = key.contains("heading") || key.contains("标题") || key.matches("^h[1-6]\\b.*");
            if (!isHeading) return null;
            for (int i = 1; i <= 6; i++) {
                if (key.contains(String.valueOf(i))) return i;
            }
            return 2;
        }

        int headingLevel(String styleId) {
            if (styleId == null) return 0;
            Integer lvl = headingLevels.get(styleId);
            return lvl == null ? 0 : lvl;
        }

        String nameOf(String styleId) {
            return styleId == null ? null : names.get(styleId);
        }

        /** 该标题级别在本文档里真实存在的样式 id; 找不到就沿用原样式(绝不写一个不存在的样式引用) */
        String headingStyleIdFor(int level, String current) {
            for (var en : headingLevels.entrySet()) {
                if (en.getValue() == level) return en.getKey();
            }
            return (current == null || current.isEmpty()) ? null : current;
        }

        List<Object> headingOptions() {
            List<Object> out = new ArrayList<>();
            for (var en : headingLevels.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", en.getKey());
                m.put("level", en.getValue());
                m.put("name", names.get(en.getKey()));
                out.add(m);
            }
            return out;
        }
    }

    private static final class NumberingTable {
        private final Map<Integer, String> formats = new LinkedHashMap<>();

        static NumberingTable load(byte[] raw) {
            NumberingTable t = new NumberingTable();
            if (raw == null || raw.length == 0) return t;
            Document dom;
            try {
                dom = parseXml(raw);
            } catch (BusinessException e) {
                return t;
            }
            Element root = dom.getDocumentElement();
            if (root == null) return t;
            Map<Integer, Integer> numToAbs = new LinkedHashMap<>();
            for (Element num : children(root, W, "num")) {
                Integer id = intAttr(num, "numId");
                Integer abs = intVal(num, "abstractNumId");
                if (id != null && abs != null) numToAbs.put(id, abs);
            }
            Map<Integer, String> absFmt = new LinkedHashMap<>();
            for (Element an : children(root, W, "abstractNum")) {
                Integer absId = intAttr(an, "abstractNumId");
                if (absId == null) continue;
                for (Element l : children(an, W, "lvl")) {
                    Integer ilvl = intAttr(l, "ilvl");
                    if (ilvl == null || ilvl != 0) continue;
                    String fmt = valOf(l, "numFmt");
                    if (fmt != null) absFmt.put(absId, fmt);
                }
            }
            for (var en : numToAbs.entrySet()) {
                String fmt = absFmt.get(en.getValue());
                if (fmt != null) t.formats.put(en.getKey(), fmt);
            }
            return t;
        }

        boolean isOrdered(int numId) {
            String fmt = formats.get(numId);
            return fmt != null && !"bullet".equalsIgnoreCase(fmt);
        }
    }

    // ==================================================================
    //  空白模板(仅新建文档时用; 之后所有保存都走就地补丁)
    // ==================================================================

    public byte[] blankDocx() {
        try (XWPFDocument doc = new XWPFDocument()) {
            // 1) 标题样式: 没有它, "标题"在空白文档里就无法作为语义保存(重新打开会退化成普通段落)
            XWPFStyles styles = doc.createStyles();
            for (int lvl = 1; lvl <= 6; lvl++) {
                CTStyle ct = CTStyle.Factory.newInstance();
                ct.setType(STStyleType.PARAGRAPH);
                ct.setStyleId("Heading" + lvl);
                ct.addNewName().setVal("heading " + lvl);
                ct.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(lvl - 1L));
                CTRPr rPr = ct.addNewRPr();
                rPr.addNewB();
                rPr.addNewSz().setVal(BigInteger.valueOf(HEADING_PT[lvl] * 2L));
                styles.addStyle(new XWPFStyle(ct));
            }
            // 2) 编号体系: 项目符号 + 编号列表, 新建的列表直接挂上去
            XWPFNumbering numbering = doc.createNumbering();
            BigInteger bulletAbs = numbering.addAbstractNum(new XWPFAbstractNum(bulletAbstract(0)));
            numbering.addNum(bulletAbs);
            BigInteger decAbs = numbering.addAbstractNum(new XWPFAbstractNum(decimalAbstract(1)));
            numbering.addNum(decAbs);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("Word 空白模板生成失败: " + e.getMessage());
        }
    }

    private static CTAbstractNum bulletAbstract(int id) {
        CTAbstractNum an = CTAbstractNum.Factory.newInstance();
        an.setAbstractNumId(BigInteger.valueOf(id));
        CTLvl lvl = an.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);
        lvl.addNewStart().setVal(BigInteger.ONE);
        lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
        lvl.addNewLvlText().setVal("\u2022");
        lvl.addNewLvlJc().setVal(STJc.LEFT);
        return an;
    }

    private static CTAbstractNum decimalAbstract(int id) {
        CTAbstractNum an = CTAbstractNum.Factory.newInstance();
        an.setAbstractNumId(BigInteger.valueOf(id));
        CTLvl lvl = an.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);
        lvl.addNewStart().setVal(BigInteger.ONE);
        lvl.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
        lvl.addNewLvlText().setVal("%1.");
        lvl.addNewLvlJc().setVal(STJc.LEFT);
        return an;
    }

    // ==================================================================
    //  zip / xml / DOM 基础
    // ==================================================================

    static LinkedHashMap<String, byte[]> readZip(byte[] data) {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                out.put(e.getName(), bo.toByteArray());
            }
        } catch (IOException ex) {
            throw new BusinessException("无法读取 .docx 压缩包, 文件可能已损坏");
        }
        if (out.isEmpty()) throw new BusinessException("无法读取 .docx 压缩包, 文件可能已损坏");
        return out;
    }

    static byte[] writeZip(LinkedHashMap<String, byte[]> entries) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bo)) {
            for (var en : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(en.getKey());
                zos.putNextEntry(ze);
                zos.write(en.getValue());
                zos.closeEntry();
            }
        } catch (IOException ex) {
            throw new BusinessException(".docx 打包失败: " + ex.getMessage());
        }
        return bo.toByteArray();
    }

    static Document parseXml(byte[] b) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = f.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(b));
        } catch (Exception e) {
            throw new BusinessException("文档 XML 解析失败: " + e.getMessage());
        }
    }

    static byte[] toXml(Document d) {
        try {
            TransformerFactory f = TransformerFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = f.newTransformer();
            // 必须 no-indent: 一旦缩进就会往 <w:t> 里注入空白, 文字全乱
            t.setOutputProperty(OutputKeys.INDENT, "no");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.STANDALONE, "yes");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            t.transform(new DOMSource(d), new StreamResult(bo));
            return bo.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("文档 XML 序列化失败: " + e.getMessage());
        }
    }

    private static Document newDom() {
        return parseXml("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                .concat("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>").getBytes());
    }

    private static Map<String, String> readRels(byte[] raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.length == 0) return map;
        Document dom;
        try {
            dom = parseXml(raw);
        } catch (BusinessException e) {
            return map;
        }
        Element root = dom.getDocumentElement();
        if (root == null) return map;
        NodeList nl = root.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && "Relationship".equals(e.getLocalName())) {
                String id = e.getAttribute("Id");
                String target = e.getAttribute("Target");
                if (!id.isEmpty() && !target.isEmpty()) map.put(id, target);
            }
        }
        return map;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e) out.add(e);
        }
        return out;
    }

    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && local.equals(e.getLocalName())
                    && (ns == null || ns.equals(e.getNamespaceURI()))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String ns, String local) {
        List<Element> l = children(parent, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Element> descendants(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getElementsByTagNameNS(ns, local);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    private static Element firstDescendant(Element parent, String ns, String local) {
        List<Element> l = descendants(parent, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static boolean hasElementChild(Element e) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element) return true;
        }
        return false;
    }

    private static String attr(Element e, String ns, String local) {
        String v = e.getAttributeNS(ns, "w:" + local);
        if (v == null || v.isEmpty()) v = e.getAttributeNS(ns, local);
        if (v == null || v.isEmpty()) v = e.getAttribute(local);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static Integer intAttr(Element e, String local) {
        String v = attr(e, W, local);
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer intVal(Element e, String local) {
        String v = valOf(e, local);
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ==================================================================
    //  取值工具(Map 来自 JSON, 类型不确定)
    // ==================================================================

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

    private static boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
