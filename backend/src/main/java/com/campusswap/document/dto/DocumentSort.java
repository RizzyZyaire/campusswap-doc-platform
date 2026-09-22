package com.campusswap.document.dto;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import java.util.List;
import org.springframework.data.domain.Sort;

/**
 * 列表排序方式（GLOSSARY §3.7 类型别名 {@code DocumentSort}）。
 *
 * <p>对外取值是 {@code updatedAt_desc} / {@code publishAt_desc} / {@code viewCount_desc}；
 * 本枚举刻意不用 {@code @Enumerated} 那套命名（大写常量）—— Query 参数是前端约定的小驼峰串，
 * 由 {@link #from(String)} 统一解析，非法值返回文档约定的中文提示。</p>
 *
 * @author Zyaire
 */
public enum DocumentSort {

    /** 最后更新时间倒序（默认）。 */
    UPDATED_AT_DESC("updatedAt_desc", Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))),

    /** 发布时间倒序。 */
    PUBLISH_AT_DESC("publishAt_desc", Sort.by(Sort.Order.desc("publishAt"), Sort.Order.desc("id"))),

    /** 阅读量倒序。 */
    VIEW_COUNT_DESC("viewCount_desc", Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("id")));

    /** 对外取值。 */
    private final String value;

    /** 对应的 Spring Data 排序。 */
    private final Sort sort;

    DocumentSort(String value, Sort sort) {
        this.value = value;
        this.sort = sort;
    }

    /**
     * 获取对外取值。
     *
     * @return 取值字符串
     */
    public String value() {
        return value;
    }

    /**
     * 获取排序规则。
     *
     * @return Spring Data 排序
     */
    public Sort sort() {
        return sort;
    }

    /**
     * 解析前端传入的排序串（空值取默认）。
     *
     * @param text 排序串（可空）
     * @return 排序枚举
     */
    public static DocumentSort from(String text) {
        if (text == null || text.isBlank()) {
            return UPDATED_AT_DESC;
        }
        String value = text.trim();
        for (DocumentSort sort : values()) {
            if (sort.value.equalsIgnoreCase(value)) {
                return sort;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "排序方式仅支持 " + String.join("、", List.of(UPDATED_AT_DESC.value, PUBLISH_AT_DESC.value, VIEW_COUNT_DESC.value)));
    }
}
