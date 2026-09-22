package com.campusswap.system.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑部门入参（API_SPECIFICATION §4.5.3）。
 *
 * @param name      部门名称（同一父节点下不可重名）
 * @param parentId  上级部门 ID（不能是自己或自己的子孙）
 * @param sortOrder 同级排序号（≥ 0）
 * @author Zyaire
 */
public record DeptUpdateDtoReq(

        @NotBlank(message = "部门名称不能为空且不超过64个字符")
        @Size(max = 64, message = "部门名称不能为空且不超过64个字符")
        String name,

        @NotBlank(message = "上级部门不存在，请重新选择")
        String parentId,

        @Min(value = 0, message = "排序号必须大于等于0")
        Integer sortOrder) {
}
