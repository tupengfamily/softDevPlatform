package com.learning.springboot.service.doc;

import com.learning.springboot.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 个人文档支持的类型(docType ↔ 扩展名 ↔ 展示名 ↔ 是否可编辑)
 * <p>
 * 设计: 一份文件只属于一种类型, 走两套编辑路径
 * <ul>
 *   <li>可编辑: word / excel / ppt → OnlyOffice (OO enabled) 或 v5 自绘</li>
 *   <li>在线预览: image / pdf / markdown / richtext / html / text / code → 文件本体直接渲染</li>
 *   <li>富文本 / Markdown 文件可直接编辑(应用内 textarea + 实时预览)</li>
 * </ul>
 */
public enum MyDocType {

    WORD("word", "docx", "Word 文档", Mode.OFFICE),
    EXCEL("excel", "xlsx", "Excel 表格", Mode.OFFICE),
    PPT("ppt", "pptx", "PPT 演示文稿", Mode.OFFICE),

    MARKDOWN("markdown", "md", "Markdown 文档", Mode.MD_RT),
    RICHTEXT("richtext", "html", "富文本", Mode.MD_RT),

    IMAGE("image", "png", "图片", Mode.PREVIEW_ONLY),
    PDF("pdf", "pdf", "PDF 文档", Mode.PREVIEW_ONLY),
    HTML("html", "html", "HTML 网页", Mode.PREVIEW_ONLY),
    TEXT("text", "txt", "纯文本", Mode.PREVIEW_ONLY),
    CODE("code", "txt", "代码片段", Mode.PREVIEW_ONLY);

    public enum Mode { OFFICE, MD_RT, PREVIEW_ONLY }

    private static final Set<String> IMG_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");
    private static final Set<String> TEXT_EXT = Set.of("txt", "log", "csv", "json", "xml", "yml", "yaml", "ini", "conf", "properties");
    private static final Set<String> CODE_EXT = Set.of("java", "py", "js", "ts", "jsx", "tsx", "vue", "html", "css", "scss", "less", "sh", "bash", "sql", "go", "rs", "c", "cpp", "h", "hpp", "kt", "rb", "php", "swift", "dart", "json", "xml", "yml", "yaml");

    private final String code;
    private final String ext;
    private final String label;
    private final Mode mode;

    MyDocType(String code, String ext, String label, Mode mode) {
        this.code = code;
        this.ext = ext;
        this.label = label;
        this.mode = mode;
    }

    public String code() { return code; }
    public String ext() { return ext; }
    public String label() { return label; }
    public Mode mode() { return mode; }

    /** 是否可在应用内编辑(Word/Excel/PPT 走 OO, MD/RT 走 textarea) */
    public boolean editable() { return mode == Mode.OFFICE || mode == Mode.MD_RT; }

    /** 是否只在预览层渲染(只读, 不能改文件内容) */
    public boolean previewOnly() { return mode == Mode.PREVIEW_ONLY; }

    /** 由 docType 代码解析; 非法抛业务异常 */
    public static MyDocType ofCode(String code) {
        if (code != null) {
            String c = code.trim().toLowerCase(Locale.ROOT);
            for (MyDocType t : values()) {
                if (t.code.equals(c)) return t;
            }
        }
        throw new BusinessException("不支持的文档类型: " + code);
    }

    /**
     * 由扩展名解析(宽口径): 不仅 Office 三件套, 还包括常见图片 / PDF / Markdown / 文本 / 代码。
     * 命中分支:
     * <ul>
     *   <li>docx/xlsx/pptx → office 三件套</li>
     *   <li>md → markdown</li>
     *   <li>html/htm → richtext (HTML 子集)</li>
     *   <li>pdf → pdf</li>
     *   <li>图片后缀 → image</li>
     *   <li>代码后缀 → code</li>
     *   <li>其余纯文本后缀 → text</li>
     * </ul>
     */
    public static MyDocType ofExt(String ext) {
        if (ext == null) throw new BusinessException("不支持的文件格式: 空扩展名");
        String e = ext.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) throw new BusinessException("不支持的文件格式: 空扩展名");

        switch (e) {
            case "docx": return WORD;
            case "xlsx": return EXCEL;
            case "pptx": return PPT;
            case "md": case "markdown": return MARKDOWN;
            case "html": case "htm": return RICHTEXT;
            case "pdf": return PDF;
        }
        if (IMG_EXT.contains(e)) return IMAGE;
        if (CODE_EXT.contains(e)) return CODE;
        if (TEXT_EXT.contains(e)) return TEXT;
        // 兜底: 当作文本(让前端走纯文本预览, 不会卡在"格式不支持")
        return TEXT;
    }

    /** 允许通过前端 type=code 创建空白文件时使用的扩展名(默认/建议名) */
    public String suggestedExt() {
        // image 默认 png, 但用户可在新建时换 jpg/webp 等
        return ext;
    }
}
