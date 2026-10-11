package com.campusswap.document.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.document.dto.DocumentSearchEsDtoReq;
import com.campusswap.document.search.DocumentSearchService;
import com.campusswap.document.vo.SearchEngineStatusVo;
import com.campusswap.document.vo.SearchRebuildVo;
import com.campusswap.document.vo.SearchResultVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全文检索接口（课件 10.1 + 用户追加要求：检索范围可选、结果关键词高亮）。
 *
 * <p>三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/search/documents} —— 全文检索（ES 引擎，未连接时自动降级 MySQL）；</li>
 *   <li>{@code GET /api/search/status} —— 当前检索引擎状态（界面显示"ES / 已降级"）；</li>
 *   <li>{@code POST /api/search/rebuild} —— 管理员全量重建索引（课件 §5.2 的
 *       {@code rebuildAllPublishedDocuments()}）。</li>
 * </ul>
 *
 * <p>鉴权沿用四道关卡：检索要 {@code doc:search}，重建要 {@code doc:manage}。</p>
 *
 * @author Zyaire
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final DocumentSearchService documentSearchService;

    /**
     * 全文检索。
     *
     * @param req 关键词 / 检索范围 / 分类 / 排序 / 分页
     * @return 带高亮的检索结果（含实际引擎说明）
     */
    @GetMapping("/api/search/documents")
    @RequiresPermission("doc:search")
    public ResponseResult<SearchResultVo> search(@Valid @ModelAttribute DocumentSearchEsDtoReq req) {
        return ResponseResult.ok(documentSearchService.search(req));
    }

    /**
     * 当前检索引擎状态。
     *
     * @return ES 是否可用、索引文档数、中文说明
     */
    @GetMapping("/api/search/status")
    @RequiresPermission("doc:search")
    public ResponseResult<SearchEngineStatusVo> status() {
        return ResponseResult.ok(documentSearchService.status());
    }

    /**
     * 全量重建检索索引（管理员；把主库所有 PUBLISHED 文档同步进 ES）。
     *
     * @return 重建结果（条数 / 耗时 / 失败原因）
     */
    @PostMapping("/api/search/rebuild")
    @RequiresPermission("doc:manage")
    public ResponseResult<SearchRebuildVo> rebuild() {
        return ResponseResult.ok(documentSearchService.rebuildAll());
    }
}
