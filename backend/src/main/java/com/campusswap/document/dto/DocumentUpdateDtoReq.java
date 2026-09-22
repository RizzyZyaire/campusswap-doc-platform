package com.campusswap.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 编辑文档入参（API_SPECIFICATION §4.6.6）：业务字段与新建<b>同规则全量提交</b>，额外要求 {@code id} 与路径一致。
 *
 * <p>{@code tagIds} 传空数组 = 清空标签。</p>
 *
 * @param id         文档 ID（必须与路径 {id} 一致）
 * @param title      标题
 * @param summary    摘要
 * @param contentMd  正文
 * @param categoryId 分类 ID
 * @param tagIds     标签 ID 数组（最多 5 个；空数组 = 清空）
 * @param priceCents 价格标记
 * @author Zyaire
 */
public record DocumentUpdateDtoReq(

        @NotBlank(message = "请求体中的文档ID与路径不一致")
        String id,

        @NotBlank(message = "文档标题不能为空且不超过128字")
        @Size(max = 128, message = "文档标题不能为空且不超过128字")
        String title,

        @Size(max = 255, message = "文档摘要不能超过255字")
        String summary,

        @Size(max = 100000, message = "文档正文不能超过100000字")
        String contentMd,

        String categoryId,

        List<String> tagIds,

        @Min(value = 0, message = "价格标记不能为负数")
        Integer priceCents) {
}
