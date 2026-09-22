package com.campusswap.system.repository;

import com.campusswap.entity.Role;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * 角色列表的动态查询条件（Criteria 组合，禁字符串 SQL 拼接）。
 *
 * @author Zyaire
 */
public final class RoleSpecifications {

    private RoleSpecifications() {
    }

    /**
     * 组装角色列表查询条件。
     *
     * @param keyword 关键词（模糊匹配 {@code name} / {@code code}，可空）
     * @return 动态条件
     */
    public static Specification<Role> of(String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("name"), like),
                        cb.like(root.get("code"), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
