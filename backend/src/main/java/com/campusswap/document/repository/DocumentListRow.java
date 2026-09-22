package com.campusswap.document.repository;

import com.campusswap.entity.enums.DocumentStatus;
import java.time.LocalDateTime;

/**
 * 文档列表的 DTO 构造器投影行（课件 3.1 §2.4）。
 *
 * <p><b>刻意不含 {@code content_md}</b>：列表只查展示列，20 条 × 数万字正文会让响应体与堆内存双爆
 * （课件 3.1 红线三）。Criteria 查询用 {@code cb.construct} 直接产出本 record；
 * 原生查询（回收站/收藏）用 {@link #of(Object[])} 按下标映射。</p>
 *
 * @param id            文档 ID
 * @param title         标题
 * @param summary       摘要
 * @param categoryId    分类 ID
 * @param authorId      作者 ID（= created_by）
 * @param status        状态
 * @param versionNum    版本号
 * @param priceCents    价格标记（分）
 * @param viewCount     阅读量
 * @param favoriteCount 收藏数
 * @param createdAt     创建时间
 * @param updatedAt     最后更新时间
 * @param updatedBy     最后修改人
 * @author Zyaire
 */
public record DocumentListRow(
        Long id,
        String title,
        String summary,
        Long categoryId,
        Long authorId,
        DocumentStatus status,
        Integer versionNum,
        Integer priceCents,
        Integer viewCount,
        Integer favoriteCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long updatedBy) {

    /**
     * 由原生查询行组装（列顺序见 {@link DocumentColumns#COLUMNS}）。
     *
     * @param row 原生查询结果行
     * @return 列表投影行
     */
    public static DocumentListRow of(Object[] row) {
        return new DocumentListRow(
                DocumentColumns.longVal(row, DocumentColumns.ID),
                DocumentColumns.str(row, DocumentColumns.TITLE),
                DocumentColumns.str(row, DocumentColumns.SUMMARY),
                DocumentColumns.longVal(row, DocumentColumns.CATEGORY_ID),
                DocumentColumns.longVal(row, DocumentColumns.CREATED_BY),
                DocumentStatus.valueOf(DocumentColumns.str(row, DocumentColumns.STATUS)),
                DocumentColumns.intVal(row, DocumentColumns.VERSION_NUM),
                DocumentColumns.intVal(row, DocumentColumns.PRICE_CENTS),
                DocumentColumns.intVal(row, DocumentColumns.VIEW_COUNT),
                DocumentColumns.intVal(row, DocumentColumns.FAVORITE_COUNT),
                DocumentColumns.time(row, DocumentColumns.CREATED_AT),
                DocumentColumns.time(row, DocumentColumns.UPDATED_AT),
                DocumentColumns.longVal(row, DocumentColumns.UPDATED_BY));
    }
}
