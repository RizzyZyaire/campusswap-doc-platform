package com.campusswap.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 列表接口公共分页入参（GLOSSARY §3.7「分页查询入参」的基类）。
 *
 * <p>Query 参数用 JavaBean 绑定（Spring MVC {@code @ModelAttribute}），因此这里是类而不是 record。
 * 各模块的 {@code *PageDtoReq} 继承本类并追加自己的筛选字段。</p>
 *
 * <p>校验口径（API_SPECIFICATION §2.6 + ARCHITECTURE §10 规约 17）：</p>
 * <ul>
 *   <li>{@code pageNum ≥ 1}，{@code pageSize} 1~100，越界返回 400，不静默纠正；</li>
 *   <li>{@code pageNum > 100} 直接拒绝（深分页保护，禁无限 OFFSET）。</li>
 * </ul>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class PageDtoReq {

    /** 页码，从 1 开始。 */
    @Min(value = 1, message = "页码必须大于等于1")
    @Max(value = 100, message = "页码不能超过100，请缩小筛选范围后再试")
    private Integer pageNum = 1;

    /** 每页条数，1~100。 */
    @Min(value = 1, message = "每页条数必须在1到100之间")
    @Max(value = 100, message = "每页条数必须在1到100之间")
    private Integer pageSize = 10;

    /**
     * 转成 Spring Data 的 {@link Pageable}（默认按 {@code updated_at} 倒序）。
     *
     * @return 分页参数
     */
    public Pageable toPageable() {
        return toPageable(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    /**
     * 转成 Spring Data 的 {@link Pageable}。
     *
     * @param sort 排序规则
     * @return 分页参数
     */
    public Pageable toPageable(Sort sort) {
        int num = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? 10 : pageSize;
        return PageRequest.of(num - 1, size, sort);
    }
}
