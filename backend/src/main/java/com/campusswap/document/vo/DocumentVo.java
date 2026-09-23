package com.campusswap.document.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.document.repository.DocumentListRow;
import com.campusswap.entity.enums.DocumentStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档列表出参（API_SPECIFICATION §2.7.3，GLOSSARY §3.7 的 {@code DocumentVo}）。
 *
 * <p><b>不含 {@code contentMd}</b>（课件 3.1 红线三）：列表只承载展示列，
 * 数据由 {@link DocumentListRow} 的 DTO 构造器投影直接填充，不经过实体。</p>
 *
 * <p>{@code authorId} / {@code authorName} 是展示层命名，对应数据库列 {@code created_by}
 * （与继承来的 {@code createdBy} 同源同值，见 API_SPECIFICATION §2.7.1 铁律 4）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentVo extends AuditVo {

    /** 文档 ID（字符串）。 */
    private String id;

    /** 标题。 */
    private String title;

    /** 纯文本摘要（可空）。 */
    private String summary;

    /** 分类 ID（"0" = 未分类）。 */
    private String categoryId;

    /** 分类名称（未分类时为「未分类」）。 */
    private String categoryName;

    /** 作者 ID（= created_by）。 */
    private String authorId;

    /** 作者姓名。 */
    private String authorName;

    /** 状态：DRAFT / PUBLISHED / ARCHIVED / TRASH。 */
    private DocumentStatus status;

    /** 业务版本号（从 1 起）。 */
    private Integer versionNum;

    /** 价格标记（分，0 = 免费）。 */
    private Integer priceCents;

    /** 阅读量。 */
    private Integer viewCount;

    /** 收藏数。 */
    private Integer favoriteCount;

    /** 当前用户是否可编辑（属主 且 状态为 DRAFT/PUBLISHED）。 */
    private Boolean canEdit;

    /**
     * 命中的正文片段（API_SPECIFICATION §9.3 新增）。
     *
     * <p>命中词两侧各 30 字，命中词以 {@code <em>} 包裹；由全文检索分支填充，
     * 正文里没有命中词（或未走全文检索）时为 {@code null}。</p>
     */
    private String highlight;

    /**
     * 命中字段（API_SPECIFICATION §9.3 新增）：{@code title} / {@code summary} / {@code content}。
     *
     * <p>仅全文检索分支填充，其余列表接口为 {@code null}。</p>
     */
    private String matchedIn;

    /**
     * 由列表行（DTO 投影）组装。
     *
     * @param row          列表投影行
     * @param categoryName 分类名（批量补齐，未分类传「未分类」）
     * @param authorName   作者姓名（批量补齐）
     * @param canEdit      当前用户是否可编辑
     * @return 文档列表出参
     */
    public static DocumentVo of(DocumentListRow row, String categoryName, String authorName, boolean canEdit) {
        return of(row, categoryName, authorName, canEdit, null, null);
    }

    /**
     * 由列表行（DTO 投影）组装，并带上全文检索的高亮信息。
     *
     * @param row          列表投影行
     * @param categoryName 分类名（批量补齐，未分类传「未分类」）
     * @param authorName   作者姓名（批量补齐）
     * @param canEdit      当前用户是否可编辑
     * @param highlight    命中的正文片段（可空）
     * @param matchedIn    命中字段 title / summary / content（可空）
     * @return 文档列表出参
     */
    public static DocumentVo of(DocumentListRow row, String categoryName, String authorName, boolean canEdit,
                                String highlight, String matchedIn) {
        DocumentVo vo = new DocumentVo();
        vo.setCreatedAt(TimeUtil.format(row.createdAt()));
        vo.setCreatedBy(IdUtil.toStr(row.authorId()));
        vo.setUpdatedAt(TimeUtil.format(row.updatedAt()));
        vo.setUpdatedBy(IdUtil.toStr(row.updatedBy()));
        vo.id = IdUtil.toStr(row.id());
        vo.title = row.title();
        vo.summary = row.summary();
        vo.categoryId = IdUtil.toStr(row.categoryId());
        vo.categoryName = categoryName;
        vo.authorId = IdUtil.toStr(row.authorId());
        vo.authorName = authorName;
        vo.status = row.status();
        vo.versionNum = row.versionNum();
        vo.priceCents = row.priceCents();
        vo.viewCount = row.viewCount();
        vo.favoriteCount = row.favoriteCount();
        vo.canEdit = canEdit;
        vo.highlight = highlight;
        vo.matchedIn = matchedIn;
        return vo;
    }
}
