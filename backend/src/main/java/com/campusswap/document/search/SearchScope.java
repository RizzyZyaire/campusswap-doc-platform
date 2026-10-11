package com.campusswap.document.search;

/**
 * 检索范围（用户要求："搜索前能选择标题 / 内容 / 标题+内容"）。
 *
 * <p>每个范围对应一条独立的 ES 查询（见 {@link DocumentSearchRepository}），
 * 字段权重沿用课件 10.1 §5.1 的 {@code title^3 / summary^2 / content}：</p>
 * <ul>
 *   <li>{@link #TITLE}：只查标题（找"标题里就是这个主题"的文档，最精准）；</li>
 *   <li>{@link #CONTENT}：只查正文（找"正文里提到过"的文档，召回最广）；</li>
 *   <li>{@link #ALL}：标题 + 摘要 + 正文联合检索（默认，标题命中权重最高）。</li>
 * </ul>
 *
 * @author Zyaire
 */
public enum SearchScope {

    /** 只看标题。 */
    TITLE,
    /** 只看正文。 */
    CONTENT,
    /** 标题 + 摘要 + 正文（默认）。 */
    ALL;

    /** 默认范围。 */
    public static final SearchScope DEFAULT = ALL;

    /**
     * 宽松解析：空值或无法识别一律回落到 {@link #DEFAULT}（入参校验由 DTO 的 {@code @Pattern} 负责）。
     *
     * @param raw 请求里的范围字符串，可空
     * @return 检索范围枚举
     */
    public static SearchScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        for (SearchScope scope : values()) {
            if (scope.name().equalsIgnoreCase(raw.trim())) {
                return scope;
            }
        }
        return DEFAULT;
    }

    /** 中文显示名（写进检索结果的提示文案）。 */
    public String label() {
        return switch (this) {
            case TITLE -> "标题";
            case CONTENT -> "正文";
            case ALL -> "标题 + 摘要 + 正文";
        };
    }
}
