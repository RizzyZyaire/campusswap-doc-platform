package com.campusswap.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建文档草稿入参（API_SPECIFICATION §4.6.4）。
 *
 * @param title      标题（1~128 字）
 * @param summary    纯文本摘要（可空，≤255 字）
 * @param contentMd  Markdown 正文（可空，≤100000 字）
 * @param categoryId 分类 ID（可空，默认 "0" = 未分类）
 * @param tagIds     标签 ID 数组（可空，最多 5 个）
 * @param priceCents 价格标记（分，≥0，默认 0）
 * @author Zyaire
 */
public record DocumentCreateDtoReq(

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
