package com.campusswap.document.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.log.OperationLog;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.dto.DocumentSearchEsDtoReq;
import com.campusswap.document.search.DocumentSearchService;
import com.campusswap.document.search.mq.SearchRebuildTask;
import com.campusswap.document.search.mq.SearchRebuildTaskStore;
import com.campusswap.document.vo.SearchEngineStatusVo;
import com.campusswap.document.vo.SearchResultVo;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全文检索接口（课件 10.1 + 用户追加要求：检索范围可选、结果关键词高亮；课件 13.1：异步重建）。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /api/search/documents} —— 全文检索（ES 引擎，未连接时自动降级 MySQL）；</li>
 *   <li>{@code GET /api/search/status} —— 当前检索引擎状态；</li>
 *   <li>{@code POST /api/search/rebuild} —— <b>提交</b>全量重建任务（RabbitMQ 异步，立即返回 taskId）；</li>
 *   <li>{@code GET /api/search/rebuild/{taskId}} —— 查询任务状态；</li>
 *   <li>{@code GET /api/search/rebuild/recent} —— 最近几次重建任务（界面初始化用）。</li>
 * </ul>
 *
 * <p>鉴权沿用四道关卡：检索要 {@code doc:search}，重建与任务查询要 {@code doc:manage}。</p>
 *
 * @author Zyaire
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final DocumentSearchService documentSearchService;

    private final SearchRebuildTaskStore rebuildTaskStore;

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
     * 提交全量重建检索索引任务（课件 13.1：走 RabbitMQ 异步，接口在 20ms 内返回 SUBMITTED + taskId）。
     *
     * @return 任务（status=SUBMITTED；若消息队列不可用则 status=FAILED 并带原因）
     */
    @PostMapping("/api/search/rebuild")
    @RequiresPermission("doc:manage")
    @OperationLog(module = "检索", action = "提交索引重建任务", params = false)
    public ResponseResult<SearchRebuildTask> submitRebuild() {
        Long operator = SecurityContext.requireUserId();
        return ResponseResult.ok(documentSearchService.submitRebuildTask(operator));
    }

    /**
     * 查询重建任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务（不存在时抛 404，由统一异常处理转成约定响应）
     */
    @GetMapping("/api/search/rebuild/{taskId}")
    @RequiresPermission("doc:manage")
    public ResponseResult<SearchRebuildTask> rebuildTask(@PathVariable("taskId") String taskId) {
        return ResponseResult.ok(documentSearchService.getRebuildTask(taskId));
    }

    /**
     * 最近的重建任务列表（新→旧，界面用来显示"上次重建结果"）。
     *
     * @return 任务列表
     */
    @GetMapping("/api/search/rebuild/recent")
    @RequiresPermission("doc:manage")
    public ResponseResult<List<SearchRebuildTask>> recentRebuildTasks() {
        return ResponseResult.ok(rebuildTaskStore.recent());
    }
}
