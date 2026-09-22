package com.campusswap.entity;

import com.campusswap.entity.enums.ChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 文档版本快照（doc_version）：追加型留痕表，只增不改。
 *
 * <p>表内保留完整审计五列（对齐 DDL），但业务上 {@code updated_*} 恒等于 {@code created_*}。</p>
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
@Table(name = "doc_version")
@SQLDelete(sql = "UPDATE doc_version SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
public class DocumentVersion extends BaseEntity {

    /** 文档 ID（关联 doc_document.id）。 */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 该次操作后的文档版本号。 */
    @Column(name = "version_num", nullable = false)
    private Integer versionNum;

    /** 标题快照。 */
    @Column(name = "title", length = 128, nullable = false)
    private String title;

    /** 正文快照（Markdown）。 */
    @Column(name = "content_md", columnDefinition = "MEDIUMTEXT")
    private String contentMd;

    /** 变更类型：CREATE / EDIT / PUBLISH / AUDIT / REJECT / ARCHIVE / RESTORE / DELETE / DERIVE。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 32, nullable = false)
    private ChangeType changeType;

    /** 变更备注 / 审核意见（驳回时必填）。 */
    @Column(name = "change_remark", length = 255)
    private String changeRemark;
}
