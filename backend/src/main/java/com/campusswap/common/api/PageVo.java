package com.campusswap.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 统一分页出参。
 *
 * <p>与 docs/02-design/API_SPECIFICATION.md §2.2 一致：{@code {list,total,pageNum,pageSize}}。
 * 禁止直接把 Spring Data 的 {@code Page} 返回给前端（会带出大量 Hibernate 内部字段）。</p>
 *
 * @param <T> 列表元素类型
 * @author Zyaire
 */
public record PageVo<T>(List<T> list, long total, int pageNum, int pageSize) {

    /**
     * 由 Spring Data 的 {@code Page} 转换而来（页码从 1 开始，与前端一致）。
     *
     * @param page Spring Data 分页结果
     * @param <T>  元素类型
     * @return 统一分页出参
     */
    public static <T> PageVo<T> of(Page<T> page) {
        return new PageVo<>(page.getContent(), page.getTotalElements(),
                page.getNumber() + 1, page.getSize());
    }

    /**
     * 由已转换好的列表组装（用于先查实体再批量补名称的场景）。
     *
     * @param list     当前页数据
     * @param total    总条数
     * @param pageNum  当前页码（从 1 开始）
     * @param pageSize 每页条数
     * @param <T>      元素类型
     * @return 统一分页出参
     */
    public static <T> PageVo<T> of(List<T> list, long total, int pageNum, int pageSize) {
        return new PageVo<>(list, total, pageNum, pageSize);
    }

    /**
     * 空分页结果（查询无命中时使用，配合 BR-05：返回 200 + 空列表，而不是 404）。
     *
     * @param pageNum  当前页码
     * @param pageSize 每页条数
     * @param <T>      元素类型
     * @return 空分页出参
     */
    public static <T> PageVo<T> empty(int pageNum, int pageSize) {
        return new PageVo<>(List.of(), 0L, pageNum, pageSize);
    }
}
