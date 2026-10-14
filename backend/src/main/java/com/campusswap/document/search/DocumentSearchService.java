package com.campusswap.document.search;

import com.campusswap.document.dto.DocumentSearchEsDtoReq;
import com.campusswap.document.search.mq.SearchRebuildTask;
import com.campusswap.document.vo.SearchEngineStatusVo;
import com.campusswap.document.vo.SearchRebuildVo;
import com.campusswap.document.vo.SearchResultVo;

/**
 * 全文检索服务（课件 10.1 落地 + 用户追加的两条要求）。
 *
 * <p><b>引擎策略（用户拍板：双引擎降级）</b>：ES 优先；ES 未启动、连不上或查询异常时，
 * 自动回落到既有的 MySQL 检索实现，接口照常返回结果，只是 {@code engine=mysql}、
 * 相关度分数为 null、高亮基于标题与摘要（正文不在列表投影里，见课件 3.1 红线三）。
 * 这样"别人解压提交包但没装 ES"也能正常演示。</p>
 *
 * @author Zyaire
 */
public interface DocumentSearchService {

    /**
     * 全文检索（带检索范围与关键词高亮）。
     *
     * @param req 关键词 / 范围 / 分类 / 分页
     * @return 检索结果（含实际使用的引擎说明）
     */
    SearchResultVo search(DocumentSearchEsDtoReq req);

    /**
     * 提交全量重建任务（课件 13.1：走 RabbitMQ 异步，接口立即返回 SUBMITTED + taskId）。
     *
     * @param operatorId 触发人 ID
     * @return 任务（消息队列不可用时 status=FAILED 并带原因）
     */
    SearchRebuildTask submitRebuildTask(Long operatorId);

    /**
     * 查询重建任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务
     */
    SearchRebuildTask getRebuildTask(String taskId);

    /**
     * 全量重建索引：把主库里所有 PUBLISHED 文档同步进 ES（课件 10.1 §5.2 的
     * {@code rebuildAllPublishedDocuments()}）。
     *
     * @return 重建结果（条数、耗时、失败原因）
     */
    SearchRebuildVo rebuildAll();

    /**
     * 增量同步单篇文档（发布/编辑/归档/恢复后调用；非 PUBLISHED 状态会从索引里删除）。
     *
     * <p>本方法**绝不向调用方抛异常**：检索索引是派生数据，同步失败不能影响业务写入。</p>
     *
     * @param documentId 文档 ID
     */
    void syncOne(Long documentId);

    /**
     * 从索引里删除一篇文档（删除/移入回收站后调用）。
     *
     * @param documentId 文档 ID
     */
    void removeOne(Long documentId);

    /**
     * 查询当前检索引擎状态（界面用来显示"ES 已连接 / 已降级"）。
     *
     * @return 引擎状态
     */
    SearchEngineStatusVo status();
}
