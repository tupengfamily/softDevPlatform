package com.learning.springboot.service.doc;

import com.learning.springboot.exception.BusinessException;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PPT(.pptx) 编解码器
 * <p>
 * <b>解析</b>: 每页只抽取"文本框"(占位符/普通文本框)的段落文本, 并记录形状在页内的下标,
 * 用于保存时精确定位回同一个形状。图片、图表、SmartArt 等非文本形状只读保留、不进入编辑器。
 * <p>
 * <b>保存</b>: <b>就地回写</b>——在用户上传的原演示文稿上只重写被编辑形状的文字,
 * 因此母版、版式、主题配色、动画、图片、备注等全部原样保留(已实测)。
 * 支持: 改文字 / 删除整页 / 追加新页(新建标题+正文文本框)。
 */
@Component
public class PptxCodec {

    /** 单页可编辑形状数上限 */
    private static final int MAX_SHAPES_PER_SLIDE = 60;
    /** 页数上限 */
    private static final int MAX_SLIDES = 200;
    /** 单个形状段落数上限 */
    private static final int MAX_PARAS = 200;

    private static final Rectangle2D RECT_TITLE = new Rectangle2D.Double(40, 30, 640, 80);
    private static final Rectangle2D RECT_BODY = new Rectangle2D.Double(40, 130, 640, 360);

    // ======================================================================
    //  解析
    // ======================================================================

    public Map<String, Object> parse(byte[] bytes) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("docType", MyDocType.PPT.code());
        List<Object> slides = new ArrayList<>();

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            List<XSLFSlide> list = ppt.getSlides();
            int limit = Math.min(list.size(), MAX_SLIDES);
            for (int i = 0; i < limit; i++) {
                XSLFSlide slide = list.get(i);
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("src", i);
                sm.put("title", slideTitle(slide));
                List<Object> shapes = new ArrayList<>();
                List<XSLFShape> all = slide.getShapes();
                int shown = 0;
                for (int j = 0; j < all.size() && shown < MAX_SHAPES_PER_SLIDE; j++) {
                    if (all.get(j) instanceof XSLFTextShape ts) {
                        shapes.add(shapeModel(j, ts));
                        shown++;
                    }
                }
                sm.put("shapes", shapes);
                sm.put("hasNonText", all.size() > shown);
                slides.add(sm);
            }
            model.put("slides", slides);
            model.put("totalSlides", list.size());
            model.put("truncated", list.size() > limit);
        } catch (IOException e) {
            throw new BusinessException("PPT 解析失败, 文件可能已损坏或不是真正的 .pptx");
        } catch (RuntimeException e) {
            throw new BusinessException("PPT 解析失败, 文件可能已损坏或不是真正的 .pptx (" + e.getClass().getSimpleName() + ")");
        }
        return model;
    }

    private Map<String, Object> shapeModel(int index, XSLFTextShape ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", index);
        m.put("kind", kindOf(ts));
        String name;
        try {
            name = ts.getShapeName();
        } catch (RuntimeException e) {
            name = null;
        }
        m.put("name", name == null || name.isBlank() ? "文本框 " + (index + 1) : name);
        try {
            var r = ts.getAnchor();
            if (r != null) {
                Map<String, Object> rect = new LinkedHashMap<>();
                rect.put("x", round(r.getX()));
                rect.put("y", round(r.getY()));
                rect.put("w", round(r.getWidth()));
                rect.put("h", round(r.getHeight()));
                m.put("rect", rect);
            }
        } catch (RuntimeException ignored) { }

        List<Object> paras = new ArrayList<>();
        List<XSLFTextParagraph> tps = ts.getTextParagraphs();
        int limit = Math.min(tps.size(), MAX_PARAS);
        for (int i = 0; i < limit; i++) {
            XSLFTextParagraph tp = tps.get(i);
            Map<String, Object> pm = new LinkedHashMap<>();
            String text;
            try {
                text = tp.getText();
            } catch (RuntimeException e) {
                text = "";
            }
            pm.put("text", text == null ? "" : text);
            int level;
            try {
                level = tp.getIndentLevel();
            } catch (RuntimeException e) {
                level = 0;
            }
            pm.put("level", Math.max(0, Math.min(8, level)));
            putRunFormat(pm, tp);
            paras.add(pm);
        }
        m.put("paragraphs", paras);
        return m;
    }

    /** 抽取段落首个文字 run 的字符格式 + 段落对齐 */
    private void putRunFormat(Map<String, Object> pm, XSLFTextParagraph tp) {
        try {
            var runs = tp.getTextRuns();
            if (runs.isEmpty()) return;
            XSLFTextRun r = runs.get(0);
            Boolean b = r.isBold();
            Boolean i = r.isItalic();
            Boolean u = r.isUnderlined();
            if (b != null) pm.put("bold", b);
            if (i != null) pm.put("italic", i);
            if (u != null) pm.put("underline", u);
            Double size = r.getFontSize();
            if (size != null && size > 0) pm.put("size", (int) Math.round(size));
            String fam = r.getFontFamily();
            if (fam != null && !fam.isEmpty()) pm.put("font", fam);
            var paint = r.getFontColor();
            if (paint instanceof PaintStyle.SolidPaint sp) {
                java.awt.Color c = sp.getSolidColor().getColor();
                if (c != null) {
                    pm.put("color", String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            var align = tp.getTextAlign();
            if (align != null) pm.put("align", align.name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) { }
    }

    /** 段落对齐: 只有模型给了且与现状不同才写(避免无谓改动) */
    private void applyParagraphFormat(XSLFTextParagraph p, Map<String, Object> pm) {
        Object align = pm.get("align");
        if (align != null) {
            String want = asString(align).toUpperCase(Locale.ROOT);
            try {
                var cur = p.getTextAlign();
                if (cur == null || !cur.name().equals(want)) {
                    p.setTextAlign(TextParagraph.TextAlign.valueOf(want));
                }
            } catch (RuntimeException ignored) { }
        }
    }

    /** 形状归类: 标题占位符 / 正文占位符 / 普通文本框 */
    private String kindOf(XSLFTextShape ts) {
        try {
            Placeholder ph = ts.getPlaceholder();
            if (ph == Placeholder.TITLE || ph == Placeholder.CENTERED_TITLE || ph == Placeholder.SUBTITLE) {
                return "title";
            }
            if (ph == Placeholder.BODY) return "body";
        } catch (RuntimeException ignore) {
            // 无占位符信息
        }
        return "text";
    }

    private String slideTitle(XSLFSlide slide) {
        for (XSLFShape sh : slide.getShapes()) {
            if (sh instanceof XSLFTextShape ts && "title".equals(kindOf(ts))) {
                try {
                    String t = ts.getText();
                    if (t != null && !t.isBlank()) return t.replace('\n', ' ').trim();
                } catch (RuntimeException ignore) {
                    // 忽略
                }
            }
        }
        return "";
    }

    // ======================================================================
    //  保存(就地回写)
    // ======================================================================

    public byte[] save(byte[] original, Map<String, Object> model) {
        List<?> slides = asList(model.get("slides"));
        if (slides.isEmpty()) throw new BusinessException("至少需要保留一页幻灯片");

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(original))) {
            int originCount = ppt.getSlides().size();
            Set<Integer> keep = new HashSet<>();
            // 先记下每一页的版式, 供"复制幻灯片"复用(删除页之后下标会漂移, 不能再用下标取)
            List<XSLFSlideLayout> layouts = new ArrayList<>();
            for (XSLFSlide s : ppt.getSlides()) {
                try {
                    layouts.add(s.getSlideLayout());
                } catch (RuntimeException e) {
                    layouts.add(null);
                }
            }

            // 1) 先按原下标更新文字(此时下标仍是原始位置, 不受删除影响)
            for (Object o : slides) {
                Map<String, Object> sm = asMap(o);
                int src = asInt(sm.get("src"), -1);
                if (src < 0 || src >= originCount) continue;
                keep.add(src);
                writeSlideText(ppt.getSlides().get(src), asList(sm.get("shapes")));
            }

            // 2) 删除模型里已经不存在的原始页(从后往前删, 避免下标漂移)
            for (int i = originCount - 1; i >= 0; i--) {
                if (!keep.contains(i)) ppt.removeSlide(i);
            }

            // 3) 追加新页(src = -1)。若带了 copyOf, 就复用被复制页的版式(而不是默认版式)
            for (Object o : slides) {
                Map<String, Object> sm = asMap(o);
                if (asInt(sm.get("src"), -1) >= 0) continue;
                int copyOf = asInt(sm.get("copyOf"), -1);
                XSLFSlide slide = null;
                if (copyOf >= 0 && copyOf < layouts.size() && layouts.get(copyOf) != null) {
                    try {
                        slide = ppt.createSlide(layouts.get(copyOf));
                    } catch (RuntimeException ignored) { }
                }
                if (slide == null) slide = ppt.createSlide();
                writeNewSlide(slide, asList(sm.get("shapes")));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("PPT 保存失败: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new BusinessException("PPT 保存失败 (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    /** 在原页上按形状下标回写文本 */
    private void writeSlideText(XSLFSlide slide, List<?> shapes) {
        List<XSLFShape> all = slide.getShapes();
        for (Object o : shapes) {
            Map<String, Object> sm = asMap(o);
            int idx = asInt(sm.get("idx"), -1);
            if (idx < 0 || idx >= all.size()) continue;
            if (!(all.get(idx) instanceof XSLFTextShape ts)) continue;
            applyParagraphs(ts, asList(sm.get("paragraphs")));
            applyRect(ts, sm.get("rect"));
        }
    }

    /** 位置/尺寸: 只有模型给了且确实不同才写 */
    private void applyRect(XSLFTextShape ts, Object rectObj) {
        if (!(rectObj instanceof Map<?, ?> rm)) return;
        try {
            var cur = ts.getAnchor();
            double x = dbl(rm.get("x"), cur == null ? 0 : cur.getX());
            double y = dbl(rm.get("y"), cur == null ? 0 : cur.getY());
            double w = dbl(rm.get("w"), cur == null ? 300 : cur.getWidth());
            double h = dbl(rm.get("h"), cur == null ? 100 : cur.getHeight());
            if (w <= 0) w = 300;
            if (h <= 0) h = 100;
            if (cur == null || Math.abs(cur.getX() - x) > 0.5 || Math.abs(cur.getY() - y) > 0.5
                    || Math.abs(cur.getWidth() - w) > 0.5 || Math.abs(cur.getHeight() - h) > 0.5) {
                ts.setAnchor(new java.awt.geom.Rectangle2D.Double(x, y, w, h));
            }
        } catch (RuntimeException ignored) { }
    }

    /** 新页: 按 kind 放置标题/正文文本框 */
    private void writeNewSlide(XSLFSlide slide, List<?> shapes) {
        boolean titleDone = false;
        boolean bodyDone = false;
        for (Object o : shapes) {
            Map<String, Object> sm = asMap(o);
            String kind = asString(sm.get("kind"));
            Rectangle2D rect;
            if ("title".equals(kind) && !titleDone) {
                rect = RECT_TITLE;
                titleDone = true;
            } else if (!bodyDone) {
                rect = RECT_BODY;
                bodyDone = true;
            } else {
                rect = new Rectangle2D.Double(40, 130, 640, 360);
            }
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(rect);
            applyParagraphs(box, asList(sm.get("paragraphs")));
        }
    }

    /** 清空形状文字后按模型重建段落(形状本身的位置/尺寸/填充保持不变) */
    private void applyParagraphs(XSLFTextShape ts, List<?> paragraphs) {
        ts.clearText();
        boolean first = true;
        for (Object o : paragraphs) {
            Map<String, Object> pm = asMap(o);
            String text = asString(pm.get("text"));
            XSLFTextParagraph p = first ? firstParagraph(ts) : ts.addNewTextParagraph();
            first = false;
            try {
                p.setIndentLevel(Math.max(0, Math.min(8, asInt(pm.get("level"), 0))));
            } catch (RuntimeException ignore) {
                // 个别版式不支持缩进级别
            }
            XSLFTextRun lastRun = null;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) p.addLineBreak();
                lastRun = p.addNewTextRun();
                lastRun.setText(lines[i]);
            }
            if (lastRun != null) applyRunFormat(lastRun, pm);
            applyParagraphFormat(p, pm);
        }
        if (ts.getTextParagraphs().isEmpty()) {
            ts.addNewTextParagraph().addNewTextRun().setText("");
        }
    }

    /** 把模型里的字符格式落到 run 上(只在显式给了值时写) */
    private void applyRunFormat(XSLFTextRun run, Map<String, Object> pm) {
        try {
            if (pm.get("bold") != null) run.setBold(asBool(pm.get("bold")));
            if (pm.get("italic") != null) run.setItalic(asBool(pm.get("italic")));
            if (pm.get("underline") != null) run.setUnderlined(asBool(pm.get("underline")));
            if (pm.get("size") != null) {
                int sz = asInt(pm.get("size"), 0);
                if (sz > 0) run.setFontSize((double) sz);
            }
            if (pm.get("font") != null) {
                String f = asString(pm.get("font"));
                if (!f.isEmpty()) run.setFontFamily(f);
            }
            if (pm.get("color") != null) {
                String c = asString(pm.get("color"));
                if (c.length() == 6) run.setFontColor(java.awt.Color.decode("#" + c));
            }
        } catch (RuntimeException ignored) { }
    }

    /** clearText() 后形状会保留一个空段落, 复用它避免多出空行 */
    private XSLFTextParagraph firstParagraph(XSLFTextShape ts) {
        List<XSLFTextParagraph> ps = ts.getTextParagraphs();
        return ps.isEmpty() ? ts.addNewTextParagraph() : ps.get(0);
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

    /** 坐标保留两位小数, 避免每次保存都因浮点误差产生"改动" */
    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double dbl(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return def;
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
