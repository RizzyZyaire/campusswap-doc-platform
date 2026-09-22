package com.campusswap.document.repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 文档列表的定制查询片段（Criteria API + DTO 构造器投影）。
 *
 * <p>为什么不用 {@code JpaSpecificationExecutor} 直接返回实体：
 * ① 实体带 {@code content_md}，列表查询会读出大文本（课件 3.1 红线三）；
 * ② {@code Page<Entity>} 配集合型 {@code JOIN FETCH} 会退化成内存分页（{@code HHH000104}）。
 * 所以这里用 Criteria 的 {@code multiselect + construct} 只取展示列，一条 SQL 完成「过滤 + 排序 + 分页」。</p>
 *
 * @author Zyaire
 */
public interface DocumentQueryRepository {

    /**
     * 按动态条件分页查询文档列表（只查展示列，不含正文）。
     *
     * @param query    动态条件
     * @param pageable 分页与排序
     * @return 分页结果（元素为列表投影行）
     */
    Page<DocumentListRow> search(DocumentListQuery query, Pageable pageable);

    /**
     * 取某文档的标签 ID 列表（回收站等原生链路复用，避免与实体加载耦合）。
     *
     * @param documentId 文档 ID
     * @return 标签 ID 列表
     */
    List<Long> findTagIds(Long documentId);
}
