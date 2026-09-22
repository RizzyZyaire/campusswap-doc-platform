package com.campusswap.document.repository;

import com.campusswap.entity.Tag;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * 标签列表的动态查询条件（Criteria 组合，禁字符串 SQL 拼接）。
 *
 * @author Zyaire
 */
public final class TagSpecifications {

    private TagSpecifications() {
    }

    /**
     * 组装标签查询条件。
     *
     * @param keyword 关键词（模糊匹配 name，可空）
     * @return 动态条件
     */
    public static Specification<Tag> of(String keyword) {
        return (root, query, cb) -> StringUtils.hasText(keyword)
                ? cb.like(root.get("name"), "%" + keyword.trim() + "%")
                : cb.conjunction();
    }
}
