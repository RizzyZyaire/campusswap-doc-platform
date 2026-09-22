package com.campusswap.document.repository;

import com.campusswap.entity.enums.DocumentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 文档列表查询条件（Criteria 组合的入参对象）。
 *
 * <p>条件为空即跳过该谓词，<b>没有</b> {@code 1=1} 拼接、没有字符串 SQL（课件 3.1 §3）。</p>
 *
 * @param keyword     关键词（对 title / summary 模糊匹配，可空）
 * @param statuses    允许的状态集合（EXCLUDED 之外的状态由调用方给出）
 * @param authorId    作者 ID（null = 不限作者，管理员/检索场景用）
 * @param categoryIds 分类 ID 集合（已含子孙分类；null 或空 = 不限分类）
 * @param tagIds      标签 ID 列表（AND 命中：需同时包含全部）
 * @param startTime   起始时间（可空，按 created_at 过滤）
 * @param endTime     结束时间（可空）
 * @author Zyaire
 */
public record DocumentListQuery(
        String keyword,
        Collection<DocumentStatus> statuses,
        Long authorId,
        Collection<Long> categoryIds,
        List<Long> tagIds,
        LocalDateTime startTime,
        LocalDateTime endTime) {
}
