package com.campusswap.system.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作日志查询入参（API_SPECIFICATION §4.10.1，M14）。
 *
 * <p>组合查询条件：账号（模糊）+ 模块 + 动作 + 成功与否 + 时间范围，配合分页（继承
 * {@link PageDtoReq} 的 {@code pageNum}/{@code pageSize} 与深分页保护）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class OperationLogPageDtoReq extends PageDtoReq {

    /** 操作人账号（模糊匹配）。 */
    @Size(max = 64, message = "账号长度不能超过64个字符")
    private String username;

    /** 模块（精确匹配，如"文档"）。 */
    @Size(max = 32, message = "模块名长度不能超过32个字符")
    private String module;

    /** 动作（精确匹配，如"发布"）。 */
    @Size(max = 32, message = "动作名长度不能超过32个字符")
    private String action;

    /** 是否成功（1 成功 / 0 失败 / 空 全部）。 */
    private Integer success;

    /** 起始时间（闭区间，{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String startTime;

    /** 结束时间（闭区间）。 */
    private String endTime;
}
