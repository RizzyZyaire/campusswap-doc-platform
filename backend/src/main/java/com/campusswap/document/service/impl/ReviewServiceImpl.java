package com.campusswap.document.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.dto.DocumentAuditDtoReq;
import com.campusswap.document.dto.DocumentRejectDtoReq;
import com.campusswap.document.dto.ReviewPageDtoReq;
import com.campusswap.document.repository.DocumentListQuery;
import com.campusswap.document.repository.DocumentListRow;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.service.DocumentService;
import com.campusswap.document.service.ReviewService;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVo;
import com.campusswap.entity.Document;
import com.campusswap.entity.enums.ChangeType;
import com.campusswap.entity.enums.DocumentStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 审核与治理服务实现（US-07）。
 *
 * <p>性能口径：待治理列表与检索列表共用同一套 Criteria 投影查询（SQL 预算 3~4 条常数），
 * 只是把「作者」条件放开、状态限定为 {@code PUBLISHED} / {@code ARCHIVED}。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;

    /**
     * 待治理列表。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVo> reviewList(ReviewPageDtoReq req) {
        Long me = SecurityContext.requireUserId();
        DocumentStatus status = DocumentStatus.PUBLISHED;
        if (StringUtils.hasText(req.getStatus())) {
            try {
                status = DocumentStatus.valueOf(req.getStatus().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文档状态取值非法");
            }
            if (status != DocumentStatus.PUBLISHED && status != DocumentStatus.ARCHIVED) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文档状态取值非法");
            }
        }
        DocumentListQuery query = new DocumentListQuery(req.getKeyword(), List.of(status), null, null, null, null, null);
        Page<DocumentListRow> page = documentRepository.search(query,
                req.toPageable(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        // 管理员用审核/归档接口治理，不在列表里直接改他人正文 → canEdit 恒为 false
        return documentService.buildPage(page.getContent(), page.getTotalElements(),
                page.getNumber() + 1, page.getSize(), me, false);
    }

    /**
     * 审核通过：不改状态，仅写 AUDIT 版本（versionNum +1）。
     *
     * @param id  文档 ID
     * @param req 审核意见
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo audit(Long id, DocumentAuditDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentService.getDocumentOrThrow(id);
        assertPublished(doc, "仅已发布文档可以审核通过");
        doc.setVersionNum(doc.getVersionNum() + 1);
        documentRepository.saveAndFlush(doc);
        documentService.writeVersion(doc, ChangeType.AUDIT, req.remark().trim());
        log.info("文档审核通过: id={}, 审核人={}", id, me);
        return documentService.buildDetail(doc, me, false);
    }

    /**
     * 驳回：{@code PUBLISHED → DRAFT}，理由写入 {@code reject_reason}。
     *
     * @param id  文档 ID
     * @param req 驳回理由
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo reject(Long id, DocumentRejectDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentService.getDocumentOrThrow(id);
        assertPublished(doc, "仅已发布文档可以驳回");
        doc.setStatus(DocumentStatus.DRAFT);
        doc.setRejectReason(req.reason().trim());
        doc.setVersionNum(doc.getVersionNum() + 1);
        documentRepository.saveAndFlush(doc);
        documentService.writeVersion(doc, ChangeType.REJECT, req.reason().trim());
        log.info("文档驳回: id={}, 审核人={}", id, me);
        return documentService.buildDetail(doc, me, false);
    }

    /**
     * 归档：{@code PUBLISHED → ARCHIVED}。
     *
     * @param id  文档 ID
     * @param req 归档意见
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo archive(Long id, DocumentAuditDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentService.getDocumentOrThrow(id);
        assertPublished(doc, "仅已发布文档可以归档");
        doc.setStatus(DocumentStatus.ARCHIVED);
        doc.setVersionNum(doc.getVersionNum() + 1);
        documentRepository.saveAndFlush(doc);
        documentService.writeVersion(doc, ChangeType.ARCHIVE, req.remark().trim());
        log.info("文档归档: id={}, 操作人={}", id, me);
        return documentService.buildDetail(doc, me, false);
    }

    /**
     * 恢复上架：{@code ARCHIVED → PUBLISHED}（{@code publish_at} 不动）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo republish(Long id) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentService.getDocumentOrThrow(id);
        if (doc.getStatus() != DocumentStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "仅已归档文档可以恢复上架");
        }
        doc.setStatus(DocumentStatus.PUBLISHED);
        doc.setRejectReason(null);
        doc.setVersionNum(doc.getVersionNum() + 1);
        documentRepository.saveAndFlush(doc);
        documentService.writeVersion(doc, ChangeType.PUBLISH, "恢复上架");
        log.info("文档恢复上架: id={}, 操作人={}", id, me);
        return documentService.buildDetail(doc, me, false);
    }

    /**
     * 断言文档处于 PUBLISHED（否则 409）。
     *
     * @param doc     文档
     * @param message 中文提示
     */
    private void assertPublished(Document doc, String message) {
        if (doc.getStatus() != DocumentStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, message);
        }
    }
}
