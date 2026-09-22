package com.campusswap.system.dto;

import com.campusswap.common.api.PageDtoReq;
import com.campusswap.entity.enums.UserStatus;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户列表查询入参（API_SPECIFICATION §4.2.1，GLOSSARY §3.7 的 {@code UserPageDtoReq}）。
 *
 * <p>Query 参数绑定，因此继承 {@link PageDtoReq}（JavaBean）而不是 record。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class UserPageDtoReq extends PageDtoReq {

    /** 关键词：匹配 {@code username} 或 {@code real_name} 模糊。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 账号状态筛选。 */
    private UserStatus status;

    /** 部门筛选（数字字符串；为空 = 全部部门）。 */
    @Pattern(regexp = "^\\d{1,19}$", message = "部门ID格式不正确")
    private String deptId;
}
