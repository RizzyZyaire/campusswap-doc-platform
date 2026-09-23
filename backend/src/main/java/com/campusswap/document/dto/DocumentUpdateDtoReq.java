package com.campusswap.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 编辑文档入参（API_SPECIFICATION §4.6.6）：业务字段与新建<b>同规则全量提交</b>，额外要求
 * {@code id} 与路径一致、{@code versionNum} 与库中当前版本一致。
 *
 * <p>{@code tagIds} 传空数组 = 清空标签。</p>
 *
 * <p><b>为什么必须有 {@code versionNum}（课件 4.1「陈旧表单校验」）</b>：编辑是"整篇全量提交"，
 * 若不做版本比对，两个人同时打开同一篇文档、后保存的人会把先保存者的改动<b>静默覆盖</b>
 * （第一类丢失更新）。前端把"打开时读到的那一版"带回来，Service 与库中当前值比对，
 * 不一致立即 409，让用户刷新后重做 —— 这一层不依赖 JPA 的 {@code @Version}，
 * 用的是文档自带的业务版本号 {@code version_num}（每次写入 +1，ARCHITECTURE §17 ADR-07）。</p>
 *
 * @param id         文档 ID（必须与路径 {id} 一致）
 * @param versionNum 文档版本号（必填；带详情接口返回的那一版，不一致 → 409）
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

        @NotNull(message = "缺少文档版本号，请刷新页面后重试")
        @Min(value = 1, message = "文档版本号不合法")
        Integer versionNum,

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
