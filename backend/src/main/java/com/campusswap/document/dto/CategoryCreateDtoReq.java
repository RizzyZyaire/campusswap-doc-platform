package com.campusswap.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增分类入参（API_SPECIFICATION §4.8.2，GLOSSARY §3.7 的 {@code CategoryCreateDtoReq}）。
 *
 * @param name      分类名称（同一父分类下不可重名）
 * @param parentId  上级分类 ID（根分类传 "0"）
 * @param sortOrder 同级排序号（≥ 0，默认 0）
 * @author Zyaire
 */
public record CategoryCreateDtoReq(

        @NotBlank(message = "分类名称不能为空且不超过64个字符")
        @Size(max = 64, message = "分类名称不能为空且不超过64个字符")
        String name,

        @NotBlank(message = "上级分类不存在，请重新选择")
        String parentId,

        @Min(value = 0, message = "排序号必须大于等于0")
        Integer sortOrder) {
}
