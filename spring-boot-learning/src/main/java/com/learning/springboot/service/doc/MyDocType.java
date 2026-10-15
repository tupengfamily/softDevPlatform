package com.learning.springboot.service.doc;

import com.learning.springboot.exception.BusinessException;

import java.util.Locale;

/**
 * 个人文档支持的类型(docType ↔ 扩展名 ↔ 展示名)
 */
public enum MyDocType {

    WORD("word", "docx", "Word 文档"),
    EXCEL("excel", "xlsx", "Excel 表格"),
    PPT("ppt", "pptx", "PPT 演示文稿");

    private final String code;
    private final String ext;
    private final String label;

    MyDocType(String code, String ext, String label) {
        this.code = code;
        this.ext = ext;
        this.label = label;
    }

    public String code() { return code; }
    public String ext() { return ext; }
    public String label() { return label; }

    /** 由 docType 代码解析(word/excel/ppt), 非法抛业务异常 */
    public static MyDocType ofCode(String code) {
        if (code != null) {
            String c = code.trim().toLowerCase(Locale.ROOT);
            for (MyDocType t : values()) {
                if (t.code.equals(c)) return t;
            }
        }
        throw new BusinessException("不支持的文档类型: " + code + " (仅支持 word / excel / ppt)");
    }

    /** 由扩展名解析(docx/xlsx/pptx), 非法抛业务异常 */
    public static MyDocType ofExt(String ext) {
        if (ext != null) {
            String e = ext.trim().toLowerCase(Locale.ROOT);
            for (MyDocType t : values()) {
                if (t.ext.equals(e)) return t;
            }
        }
        throw new BusinessException("不支持的文件格式: ." + ext + " (仅支持 .docx / .xlsx / .pptx)");
    }
}
