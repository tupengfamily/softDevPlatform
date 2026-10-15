package com.learning.springboot.service.doc;

import com.learning.springboot.exception.BusinessException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Office 文档编解码总入口
 * <p>
 * 统一把"磁盘上的 Office 文件"与"前端编辑器模型 JSON"互相转换。
 * <b>三种格式一律走"原生 OOXML 就地编辑"</b>(原始包字节级保留, 只补丁被改动的节点),
 * 这是业界高保真方案(OnlyOffice / Apryse)的共同做法, 详见 {@link DocxCodec} 类注释。
 */
@Component
public class OfficeDocService {

    private final DocxCodec docxCodec;
    private final XlsxCodec xlsxCodec;
    private final PptxCodec pptxCodec;

    public OfficeDocService(DocxCodec docxCodec, XlsxCodec xlsxCodec, PptxCodec pptxCodec) {
        this.docxCodec = docxCodec;
        this.xlsxCodec = xlsxCodec;
        this.pptxCodec = pptxCodec;
    }

    /**
     * 文件 -> 编辑器模型
     *
     * @param imageDir      Word 内嵌图片落地目录(仅 Word 用到)
     * @param imageUrlBase  Word 内嵌图片对外 URL 前缀
     */
    public Map<String, Object> parse(MyDocType type, byte[] bytes, Path imageDir, String imageUrlBase) {
        if (bytes == null || bytes.length == 0) throw new BusinessException("文件内容为空");
        return switch (type) {
            case WORD -> docxCodec.parse(bytes, imageDir, imageUrlBase);
            case EXCEL -> xlsxCodec.parse(bytes);
            case PPT -> pptxCodec.parse(bytes);
        };
    }

    /**
     * 编辑器模型 -> 文件字节
     * <p>
     * 三个格式都<b>需要 original</b>: 保存是在用户原文件上打补丁, 不是重建。
     *
     * @param original  磁盘上的当前文件(就地补丁的基础)
     * @param imageBase /uploads 对应的磁盘根目录
     */
    public byte[] save(MyDocType type, byte[] original, Map<String, Object> model, Path imageBase) {
        if (model == null) throw new BusinessException("保存内容为空");
        if (original == null || original.length == 0) {
            throw new BusinessException("原文件缺失, 无法就地保存; 请重新上传该文档");
        }
        return switch (type) {
            case WORD -> docxCodec.save(original, model, imageBase);
            case EXCEL -> xlsxCodec.save(original, model);
            case PPT -> pptxCodec.save(original, model);
        };
    }

    /**
     * 上传时校验文件确实是目标格式且能被解析(读一遍 POI, 不落任何东西)。
     * 避免把"改了扩展名的垃圾文件"存进用户文档库。
     */
    public void validate(MyDocType type, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new BusinessException("文件内容为空");
        try {
            switch (type) {
                case WORD -> {
                    try (XWPFDocument doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
                        doc.getBodyElements();
                    }
                }
                case EXCEL -> {
                    try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
                        wb.getNumberOfSheets();
                    }
                }
                default -> {
                    try (XMLSlideShow ppt = new XMLSlideShow(new java.io.ByteArrayInputStream(bytes))) {
                        ppt.getSlides();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("文件无法解析, 请确认是有效的 " + type.label()
                    + " (." + type.ext() + ") 文件, 且未加密或损坏");
        }
    }

    /** 新建空白文档 */
    public byte[] createBlank(MyDocType type) {        try {
            switch (type) {
                case WORD: {
                    // 空白模板自带 numbering.xml(项目符号 + 编号列表), 后续所有保存都在这份文件上就地补丁
                    byte[] blank = docxCodec.blankDocx();
                    Map<String, Object> model = new LinkedHashMap<>();
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("type", "heading");
                    h.put("level", 1);
                    h.put("text", "标题");
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "paragraph");
                    p.put("text", "开始输入正文…");
                    p.put("align", "left");
                    model.put("blocks", List.of(h, p));
                    return docxCodec.save(blank, model, Path.of("data", "uploads"));
                }
                case EXCEL: {
                    try (XSSFWorkbook wb = new XSSFWorkbook()) {
                        var sheet = wb.createSheet("Sheet1");
                        sheet.createRow(0).createCell(0);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        wb.write(out);
                        return out.toByteArray();
                    }
                }
                default: {
                    try (XMLSlideShow ppt = new XMLSlideShow()) {
                        XSLFSlide slide = ppt.createSlide();
                        XSLFTextBox title = slide.createTextBox();
                        title.setAnchor(new Rectangle2D.Double(40, 30, 640, 80));
                        title.addNewTextParagraph().addNewTextRun().setText("标题");
                        XSLFTextBox body = slide.createTextBox();
                        body.setAnchor(new Rectangle2D.Double(40, 130, 640, 360));
                        body.addNewTextParagraph().addNewTextRun().setText("开始输入内容…");
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        ppt.write(out);
                        return out.toByteArray();
                    }
                }
            }
        } catch (IOException e) {
            throw new BusinessException("模板文件生成失败: " + e.getMessage());
        }
    }
}
