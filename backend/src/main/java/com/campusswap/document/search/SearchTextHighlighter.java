package com.campusswap.document.search;

/**
 * 检索结果高亮工具（用户要求："关键词检索出的文档会高亮显示出关键词"）。
 *
 * <p><b>为什么高亮在 Java 侧做、而不是用 ES 的 highlight 参数</b>：</p>
 * <ol>
 *   <li>ES 返回的高亮片段是**原始文本 + {@code <em>} 包裹**，直接塞给前端就是 XSS 通道，
 *       必须把除标记以外的内容转义——转义逻辑一样要自己写；</li>
 *   <li>MySQL 降级引擎（无 ES 时）同样要能高亮，两套引擎共用一份实现才不会出现
 *       "ES 下有高亮、降级后没高亮"的行为差异；</li>
 *   <li>高亮结果可以单元测试（本项目 30 个用例的测试风格），不依赖 ES 是否在跑。</li>
 * </ol>
 *
 * <p>输出统一用 {@code <mark>} 包裹（前端直接 {@code v-html} 渲染即可，样式走 Tailwind 的
 * {@code prose mark} 定义）。<b>所有输出都先转义再插标记</b>，见 {@link #mark(String, String)}。</p>
 *
 * @author Zyaire
 */
public final class SearchTextHighlighter {

    /** 高亮标签（前端白名单里只有这一个，见 DocumentListView）。 */
    private static final String OPEN = "<mark>";
    private static final String CLOSE = "</mark>";

    /** 片段截取的上下文半径（关键词前后各取多少字符）。 */
    private static final int SNIPPET_RADIUS = 48;

    private SearchTextHighlighter() {
    }

    /**
     * HTML 转义（&amp; &lt; &gt; &quot; &#39;）。
     *
     * @param raw 原始文本，可空
     * @return 转义后的文本；入参为空时返回空串
     */
    public static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 转义 + 把命中的关键词用 {@code <mark>} 包起来（大小写不敏感；中文无大小写概念，天然命中）。
     *
     * @param raw     原始文本，可空
     * @param keyword 关键词，可空（为空时等价于纯转义）
     * @return 可安全交给前端 {@code v-html} 的 HTML 片段
     */
    public static String mark(String raw, String keyword) {
        String text = raw == null ? "" : raw;
        if (keyword == null || keyword.isBlank()) {
            return escape(text);
        }
        String needle = keyword.trim();
        String lowerText = text.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        StringBuilder sb = new StringBuilder(text.length() + 32);
        int from = 0;
        while (true) {
            int hit = lowerText.indexOf(lowerNeedle, from);
            if (hit < 0) {
                sb.append(escape(text.substring(from)));
                break;
            }
            sb.append(escape(text.substring(from, hit)));
            sb.append(OPEN).append(escape(text.substring(hit, hit + needle.length()))).append(CLOSE);
            from = hit + needle.length();
        }
        return sb.toString();
    }

    /**
     * 从正文里截一段包含关键词的片段（前后各 {@value #SNIPPET_RADIUS} 个字符），并高亮。
     *
     * <p>正文里找不到关键词时返回**开头一段**（说明命中的是标题/摘要），使列表每行都有上下文。</p>
     *
     * @param content 正文原文，可空
     * @param keyword 关键词，可空
     * @return 已转义并高亮的片段（带省略号）
     */
    public static String snippet(String content, String keyword) {
        String text = content == null ? "" : content;
        if (text.isBlank()) {
            return "";
        }
        // 正文含 Markdown 结构符，先压掉换行让片段读起来是一句话
        String flat = text.replaceAll("\\s+", " ").trim();
        String needle = keyword == null ? "" : keyword.trim().toLowerCase();
        int hit = needle.isEmpty() ? -1 : flat.toLowerCase().indexOf(needle);
        int start;
        int end;
        if (hit < 0) {
            start = 0;
            end = Math.min(flat.length(), SNIPPET_RADIUS * 2);
        } else {
            start = Math.max(0, hit - SNIPPET_RADIUS);
            end = Math.min(flat.length(), hit + needle.length() + SNIPPET_RADIUS);
        }
        String piece = flat.substring(start, end);
        String marked = mark(piece, keyword);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("…");
        }
        sb.append(marked);
        if (end < flat.length()) {
            sb.append("…");
        }
        return sb.toString();
    }

    /**
     * 文本是否包含关键词（大小写不敏感），用于判断该字段是否命中。
     *
     * @param text    文本，可空
     * @param keyword 关键词，可空
     * @return 是否包含
     */
    public static boolean contains(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return text.toLowerCase().contains(keyword.trim().toLowerCase());
    }
}
