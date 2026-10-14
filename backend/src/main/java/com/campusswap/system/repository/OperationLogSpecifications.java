package com.campusswap.system.repository;

import com.campusswap.entity.SysOperationLog;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * 操作日志的动态查询条件（M14，Criteria 组合，禁字符串 SQL 拼接）。
 *
 * @author Zyaire
 */
public final class OperationLogSpecifications {

    private OperationLogSpecifications() {
    }

    /**
     * 组装操作日志查询条件。
     *
     * @param username  操作人账号（模糊，可空）
     * @param module    模块（精确，可空）
     * @param action    动作（精确，可空）
     * @param success   是否成功（1/0，可空）
     * @param startTime 起始时间（闭区间，可空）
     * @param endTime   结束时间（闭区间，可空）
     * @return 动态条件
     */
    public static Specification<SysOperationLog> of(String username, String module, String action, Integer success,
                                                    LocalDateTime startTime, LocalDateTime endTime) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(username)) {
                predicates.add(cb.like(root.get("username"), "%" + username.trim() + "%"));
            }
            if (StringUtils.hasText(module)) {
                predicates.add(cb.equal(root.get("module"), module.trim()));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (success != null) {
                predicates.add(cb.equal(root.get("success"), success));
            }
            if (startTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startTime));
            }
            if (endTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endTime));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
