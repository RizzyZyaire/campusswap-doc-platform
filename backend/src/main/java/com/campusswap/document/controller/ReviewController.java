package com.campusswap.document.controller;

import com.campusswap.common.api.PageVo;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.document.dto.DocumentAuditDtoReq;
import com.campusswap.document.dto.DocumentRejectDtoReq;
import com.campusswap.document.dto.ReviewPageDtoReq;
import com.campusswap.document.service.ReviewService;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核与治理接口（API_SPECIFICATION §4.7，共 5 条）。
 *
 * <p>{@code STAFF} 角色没有 {@code doc:review} / {@code doc:audit} 等权限点，
 * 由 {@code PermissionAspect} 统一拦成 403（US-07 AC-07.2）。</p>
 *
 * @author Zyaire
 */
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * 待治理列表。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping("/api/review/documents")
    @RequiresPermission("doc:review")
    public ResponseResult<PageVo<DocumentVo>> list(@Valid @ModelAttribute ReviewPageDtoReq req) {
        return ResponseResult.ok(reviewService.reviewList(req));
    }

    /**
     * 审核通过。
     *
     * @param id  文档 ID
     * @param req 审核意见
     * @return 文档详情
     */
    @PostMapping("/api/documents/{id}/audit")
    @RequiresPermission("doc:audit")
    public ResponseResult<DocumentDetailVo> audit(@PathVariable("id") Long id,
                                                  @Valid @RequestBody DocumentAuditDtoReq req) {
        return ResponseResult.ok(reviewService.audit(id, req));
    }

    /**
     * 驳回文档。
     *
     * @param id  文档 ID
     * @param req 驳回理由
     * @return 文档详情
     */
    @PostMapping("/api/documents/{id}/reject")
    @RequiresPermission("doc:reject")
    public ResponseResult<DocumentDetailVo> reject(@PathVariable("id") Long id,
                                                   @Valid @RequestBody DocumentRejectDtoReq req) {
        return ResponseResult.ok(reviewService.reject(id, req));
    }

    /**
     * 归档文档。
     *
     * @param id  文档 ID
     * @param req 归档意见
     * @return 文档详情
     */
    @PostMapping("/api/documents/{id}/archive")
    @RequiresPermission("doc:archive")
    public ResponseResult<DocumentDetailVo> archive(@PathVariable("id") Long id,
                                                    @Valid @RequestBody DocumentAuditDtoReq req) {
        return ResponseResult.ok(reviewService.archive(id, req));
    }

    /**
     * 恢复上架。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @PostMapping("/api/documents/{id}/republish")
    @RequiresPermission("doc:archive")
    public ResponseResult<DocumentDetailVo> republish(@PathVariable("id") Long id) {
        return ResponseResult.ok(reviewService.republish(id));
    }
}
