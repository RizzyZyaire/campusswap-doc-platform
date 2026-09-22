package com.campusswap.system.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色列表查询入参（API_SPECIFICATION §4.3.1，GLOSSARY §3.7 的 {@code RolePageDtoReq}）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class RolePageDtoReq extends PageDtoReq {

    /** 关键词：匹配 {@code name} 或 {@code code}。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;
}
