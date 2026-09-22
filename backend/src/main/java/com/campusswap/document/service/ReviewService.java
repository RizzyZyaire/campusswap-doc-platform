package com.campusswap.document.service;

import com.campusswap.common.api.PageVo;
import com.campusswap.document.dto.DocumentAuditDtoReq;
import com.campusswap.document.dto.DocumentRejectDtoReq;
import com.campusswap.document.dto.ReviewPageDtoReq;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVo;

/**
 * 审核与治理服务（US-07）：待治理列表 + 审核通过 / 驳回 / 归档 / 恢复上架。
 *
 * <p>状态机（PRD §4.2）：审核通过不改状态只留痕；驳回 {@code PUBLISHED → DRAFT}（T11）；
 * 归档 {@code PUBLISHED → ARCHIVED}（T5）；恢复上架 {@code ARCHIVED → PUBLISHED}（T7）。</p>
 *
 * @author Zyaire
 */
public interface ReviewService {

    /**
     * 待治理列表（管理员可见全部用户文档；{@code canEdit} 恒为 false）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<DocumentVo> reviewList(ReviewPageDtoReq req);

    /**
     * 审核通过（不改状态，仅写 AUDIT 版本与意见）。
     *
     * @param id  文档 ID
     * @param req 审核意见
     * @return 文档详情
     */
    DocumentDetailVo audit(Long id, DocumentAuditDtoReq req);

    /**
     * 驳回（{@code PUBLISHED → DRAFT}，写入 {@code reject_reason} 回传作者）。
     *
     * @param id  文档 ID
     * @param req 驳回理由
     * @return 文档详情
     */
    DocumentDetailVo reject(Long id, DocumentRejectDtoReq req);

    /**
     * 归档（{@code PUBLISHED → ARCHIVED}，之后只读）。
     *
     * @param id  文档 ID
     * @param req 归档意见
     * @return 文档详情
     */
    DocumentDetailVo archive(Long id, DocumentAuditDtoReq req);

    /**
     * 恢复上架（{@code ARCHIVED → PUBLISHED}，{@code publish_at} 保持不变）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    DocumentDetailVo republish(Long id);
}
