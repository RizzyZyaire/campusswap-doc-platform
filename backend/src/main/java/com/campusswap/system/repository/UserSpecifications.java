package com.campusswap.system.repository;

import com.campusswap.entity.User;
import com.campusswap.entity.enums.UserStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * 用户列表的动态查询条件（课件 3.1 §3：{@code JpaSpecificationExecutor} + Criteria 组合）。
 *
 * <p><b>严禁</b>字符串 SQL 拼接与 {@code 1=1} 技巧：条件为空就跳过该谓词，全程类型安全。</p>
 *
 * @author Zyaire
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /**
     * 组装用户列表查询条件。
     *
     * @param keyword 关键词（模糊匹配 {@code username} / {@code realName}，可空）
     * @param status  账号状态（可空）
     * @param deptId  部门 ID（可空）
     * @return 动态条件
     */
    public static Specification<User> of(String keyword, UserStatus status, Long deptId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("username"), like),
                        cb.like(root.get("realName"), like)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (deptId != null) {
                predicates.add(cb.equal(root.get("deptId"), deptId));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
