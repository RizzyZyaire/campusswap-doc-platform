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
 * 文档收藏（doc_favorite）：复合主键天然去重，重复收藏幂等（收藏 = 有则忽略，取消 = 无则忽略）。
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = {"userId", "documentId"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(FavoriteId.class)
@Table(name = "doc_favorite")
public class Favorite {

    /** 用户 ID（关联 sys_user.id）。 */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 文档 ID（关联 doc_document.id）。 */
    @Id
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 收藏时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 业务构造器：只给两个主键字段（{@code created_at} 由 {@code @CreationTimestamp} 填充）。
     *
     * @param userId     用户 ID
     * @param documentId 文档 ID
     */
    public Favorite(Long userId, Long documentId) {
        this.userId = userId;
        this.documentId = documentId;
    }
}
