package com.tickwise.email;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Converts an HTML email body to plain text. We never store or render customer HTML: the
 * dashboard shows text only, which removes a whole class of stored-XSS risk.
 */
final class HtmlToText {

    /** Private-use code point marking line breaks through jsoup's whitespace normalisation. */
    private static final String BREAK = "";

    private HtmlToText() {}

    static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html.replace(BREAK, ""));
        doc.select("script, style, head, title").remove();
        for (Element br : doc.select("br")) {
            br.after(BREAK);
        }
        for (Element block : doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6, blockquote")) {
            block.append(BREAK);
        }
        String text = doc.text().replace(BREAK, "\n");
        return text.lines().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse("")
                .replaceAll("\n{3,}", "\n\n").strip();
    }
}
