package com.learning.springboot.security;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * HTML 消毒工具(服务端纵深防御,防存储型 XSS)
 * <p>
 * 对富文本(richtext)内容做白名单过滤:
 * - 允许常见排版标签(p/br/strong/em/ul/ol/li/blockquote/code/pre/h1-h6/table 等)与 a/img
 * - 剥离 script/style/iframe、事件属性(on*)、javascript: 协议
 * - markdown 内容不需要消毒(渲染端经 DOMPurify),本工具只处理 richtext
 * <p>
 * 注意: 这是第二道防线;前端渲染端(v-html 前)仍必须做 DOMPurify 消毒。
 */
public final class HtmlSanitizer {

    /** 白名单: relaxed(常见排版) + 链接 target/rel */
    private static final Safelist SAFE = Safelist.relaxed()
        .preserveRelativeLinks(true)
        .addAttributes("a", "target", "rel");

    private HtmlSanitizer() {
    }

    /**
     * 消毒富文本 HTML(空/null 原样返回)
     */
    public static String sanitizeRichText(String html) {
        if (html == null || html.isEmpty()) return html;
        return Jsoup.clean(html, "http://safe.local", SAFE, new org.jsoup.nodes.Document.OutputSettings()
            .prettyPrint(false));
    }
}
