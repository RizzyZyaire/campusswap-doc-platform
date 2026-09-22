package com.campusswap.entity;

import com.campusswap.entity.enums.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 文档主表（doc_document）：平台核心业务实体。
 *
 * <p><b>写 ID、读关联</b>（课件 3.1 §1）：写入走 {@code categoryId} 普通列；
 * 查询走只读关联 {@link #category}、{@link #tags}（均 {@code LAZY} + {@code insertable/updatable = false}）。
 * 全仓禁止 {@code EAGER}，禁止对本实体的集合调用 {@code add/remove}（标签增删走 {@code doc_document_tag_rel} 显式实体）。</p>
 *
 * <p><b>性能红线</b>：{@code content_md} 为大文本，列表查询严禁读取该列（课件 3.1 红线三）——
 * 列表一律走 DTO 构造器投影，只 SELECT 需要的列。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "doc_document")
@SQLDelete(sql = "UPDATE doc_document SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
public class Document extends BaseEntity {

    /** 所属分类 ID（0 = 未分类）——写入用列。 */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 文档标题（1-128 字符）。 */
    @Column(name = "title", length = 128, nullable = false)
    private String title;

    /** 纯文本摘要（可空）。 */
    @Column(name = "summary", length = 255)
    private String summary;

    /** Markdown 正文（最大 16MB）——仅详情/编辑接口读取，列表接口禁止触碰。 */
    @Column(name = "content_md", columnDefinition = "MEDIUMTEXT")
    private String contentMd;

    /** 生命周期状态：DRAFT / PUBLISHED / ARCHIVED / TRASH。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private DocumentStatus status;

    /** 业务版本号（从 1 起单调递增）。 */
    @Column(name = "version_num", nullable = false)
    private Integer versionNum;

    /** 价格标记（单位：分，0 = 免费），整数存储，禁浮点。 */
    @Column(name = "price_cents", columnDefinition = "INT UNSIGNED", nullable = false)
    private Integer priceCents;

    /** 阅读量（仅 PUBLISHED 累加，Redis 去重后原子自增）。 */
    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    /** 收藏数（冗余计数，原子自增/自减）。 */
    @Column(name = "favorite_count", nullable = false)
    private Integer favoriteCount;

    /** 派生来源文档 ID（空 = 原创）。 */
    @Column(name = "derived_from_id")
    private Long derivedFromId;

    /** 审核驳回理由（通过后清空）。 */
    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    /** 首次发布时间。 */
    @Column(name = "publish_at")
    private LocalDateTime publishAt;

    /**
     * 只读关联：所属分类。显式 {@code JOIN FETCH} / {@code @EntityGraph} 才会取，默认 LAZY 不会触发额外 SQL。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    @ToString.Exclude
    private Category category;

    /**
     * 只读关联：标签列表。写入一律走 {@code doc_document_tag_rel}，本集合永不参与 flush。
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "doc_document_tag_rel",
            joinColumns = @JoinColumn(name = "document_id", insertable = false, updatable = false),
            inverseJoinColumns = @JoinColumn(name = "tag_id", insertable = false, updatable = false))
    @ToString.Exclude
    private List<Tag> tags;
}
