package com.campusswap.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑分类入参（API_SPECIFICATION §4.8.3，GLOSSARY §3.7 的 {@code CategoryUpdateDtoReq}）。
 *
 * @param name      分类名称
 * @param parentId  上级分类 ID（不能是自己或自己的子孙）
 * @param sortOrder 同级排序号（≥ 0）
 * @author Zyaire
 */
public record CategoryUpdateDtoReq(

        @NotBlank(message = "分类名称不能为空且不超过64个字符")
        @Size(max = 64, message = "分类名称不能为空且不超过64个字符")
        String name,

        @NotBlank(message = "上级分类不存在，请重新选择")
        String parentId,

        @Min(value = 0, message = "排序号必须大于等于0")
        Integer sortOrder) {
}
