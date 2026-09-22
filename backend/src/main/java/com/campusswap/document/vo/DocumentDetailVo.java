package com.campusswap.document.vo;

import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.document.repository.DocumentColumns;
import com.campusswap.entity.Document;
import com.campusswap.entity.enums.DocumentStatus;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档详情出参（API_SPECIFICATION §4.6.4/§4.6.5，GLOSSARY §3.7 的 {@code DocumentDetailVo}）。
 *
 * <p>在 {@link DocumentVo} 的全部字段之上追加详情专属字段：正文、派生来源、驳回理由、是否已收藏、标签集合。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentDetailVo extends DocumentVo {

    /** Markdown 正文（仅详情接口返回）。 */
    private String contentMd;

    /** 派生来源文档 ID（原创为 null）。 */
    private String derivedFromId;

    /** 驳回理由（无驳回为 null）。 */
    private String rejectReason;

    /** 当前用户是否已收藏。 */
    private Boolean favorited;

    /** 标签集合（最多 5 个）。 */
    private List<TagVo> tags;

    /**
     * 由实体组装（作者名、分类名、标签、收藏态由 Service 补齐）。
     *
     * @param doc          文档实体（已通过可见性与归属校验）
     * @param categoryName 分类名
     * @param authorName   作者姓名
     * @param canEdit      当前用户是否可编辑
     * @param favorited    当前用户是否已收藏
     * @param tags         标签集合
     * @return 文档详情出参
     */
    public static DocumentDetailVo of(Document doc, String categoryName, String authorName,
                                      boolean canEdit, boolean favorited, List<TagVo> tags) {
        DocumentDetailVo vo = new DocumentDetailVo();
        vo.fillAudit(doc);
        vo.setId(IdUtil.toStr(doc.getId()));
        vo.setTitle(doc.getTitle());
        vo.setSummary(doc.getSummary());
        vo.setCategoryId(IdUtil.toStr(doc.getCategoryId()));
        vo.setCategoryName(categoryName);
        vo.setAuthorId(IdUtil.toStr(doc.getCreatedBy()));
        vo.setAuthorName(authorName);
        vo.setStatus(doc.getStatus());
        vo.setVersionNum(doc.getVersionNum());
        vo.setPriceCents(doc.getPriceCents());
        vo.setViewCount(doc.getViewCount());
        vo.setFavoriteCount(doc.getFavoriteCount());
        vo.setCanEdit(canEdit);
        vo.contentMd = doc.getContentMd();
        vo.derivedFromId = IdUtil.toStr(doc.getDerivedFromId());
        vo.rejectReason = doc.getRejectReason();
        vo.favorited = favorited;
        vo.tags = tags == null ? List.of() : tags;
        return vo;
    }

    /**
     * 由「回收站原生查询行」组装为详情（回收站文档走原生 SQL，拿不到实体）。
     *
     * <p>列顺序与类型转换统一由 {@link DocumentColumns} 兜住，避免改查询时静默错位。</p>
     *
     * @param row          回收站投影行（列见 {@link DocumentColumns#COLUMNS}）
     * @param categoryName 分类名
     * @param authorName   作者姓名
     * @param tags         标签集合
     * @return 文档详情出参
     */
    public static DocumentDetailVo ofTrash(Object[] row, String categoryName, String authorName, List<TagVo> tags) {
        DocumentDetailVo vo = new DocumentDetailVo();
        vo.setId(IdUtil.toStr(DocumentColumns.longVal(row, DocumentColumns.ID)));
        vo.setTitle(DocumentColumns.str(row, DocumentColumns.TITLE));
        vo.setSummary(DocumentColumns.str(row, DocumentColumns.SUMMARY));
        vo.setCategoryId(IdUtil.toStr(DocumentColumns.longVal(row, DocumentColumns.CATEGORY_ID)));
        vo.setCategoryName(categoryName);
        vo.setAuthorId(IdUtil.toStr(DocumentColumns.longVal(row, DocumentColumns.CREATED_BY)));
        vo.setAuthorName(authorName);
        vo.setStatus(DocumentStatus.valueOf(DocumentColumns.str(row, DocumentColumns.STATUS)));
        vo.setVersionNum(DocumentColumns.intVal(row, DocumentColumns.VERSION_NUM));
        vo.setPriceCents(DocumentColumns.intVal(row, DocumentColumns.PRICE_CENTS));
        vo.setViewCount(DocumentColumns.intVal(row, DocumentColumns.VIEW_COUNT));
        vo.setFavoriteCount(DocumentColumns.intVal(row, DocumentColumns.FAVORITE_COUNT));
        vo.setCreatedAt(TimeUtil.format(DocumentColumns.time(row, DocumentColumns.CREATED_AT)));
        vo.setCreatedBy(IdUtil.toStr(DocumentColumns.longVal(row, DocumentColumns.CREATED_BY)));
        vo.setUpdatedAt(TimeUtil.format(DocumentColumns.time(row, DocumentColumns.UPDATED_AT)));
        vo.setUpdatedBy(IdUtil.toStr(DocumentColumns.longVal(row, DocumentColumns.UPDATED_BY)));
        vo.setCanEdit(true);
        vo.contentMd = DocumentColumns.str(row, DocumentColumns.CONTENT_MD);
        vo.derivedFromId = IdUtil.toStr(DocumentColumns.longVal(row, DocumentColumns.DERIVED_FROM_ID));
        vo.rejectReason = DocumentColumns.str(row, DocumentColumns.REJECT_REASON);
        vo.favorited = false;
        vo.tags = tags == null ? List.of() : tags;
        return vo;
    }
}
