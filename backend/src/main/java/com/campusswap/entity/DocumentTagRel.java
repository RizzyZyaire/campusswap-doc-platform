package com.campusswap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 文档-标签关联（doc_document_tag_rel）：复合主键，标签增删一律走本实体（清空重插），禁走集合 add/remove。
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = {"documentId", "tagId"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(DocumentTagRelId.class)
@Table(name = "doc_document_tag_rel")
public class DocumentTagRel {

    /** 文档 ID（关联 doc_document.id）。 */
    @Id
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 标签 ID（关联 doc_tag.id）。 */
    @Id
    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    /** 打标签时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 业务构造器：只给两个主键字段（{@code created_at} 由 {@code @CreationTimestamp} 填充）。
     *
     * @param documentId 文档 ID
     * @param tagId      标签 ID
     */
    public DocumentTagRel(Long documentId, Long tagId) {
        this.documentId = documentId;
        this.tagId = tagId;
    }
}
