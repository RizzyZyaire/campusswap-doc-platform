package com.campusswap.entity.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 文档生命周期状态（对应 doc_document.status，VARCHAR(32)）。
 *
 * <p>状态机与出边见 docs/01-requirements/PRD.md §4：每个状态都至少有一条出边，无死胡同。</p>
 *
 * @author Zyaire
 */
public enum DocumentStatus {

    /** 草稿：可编辑、不可被检索。 */
    DRAFT("草稿"),

    /** 已发布：可编辑、可被检索。 */
    PUBLISHED("已发布"),

    /** 已归档：只读、可被检索。 */
    ARCHIVED("已归档"),

    /** 回收站：不可编辑、不可检索，可恢复或彻底删除。 */
    TRASH("回收站");

    private final String label;

    DocumentStatus(String label) {
        this.label = label;
    }

    /**
     * 获取中文名称。
     *
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按枚举名解析（用于把查询参数里的字符串安全转成枚举）。
     *
     * @param name 枚举名，忽略大小写
     * @return 枚举值；非法时为空
     */
    public static Optional<DocumentStatus> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(name.trim())).findFirst();
    }
}
