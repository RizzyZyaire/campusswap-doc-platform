package com.campusswap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 所有业务实体的审计基类：主键 + 五列公共审计字段（对齐 backend/sql/schema.sql）。
 *
 * <p>字段与 DDL 逐列对应：{@code id / created_at / created_by / updated_at / updated_by / deleted}。
 * 审计值由 Spring Data JPA 的 {@code AuditingEntityListener} 自动填充，
 * 操作人来自 {@code JpaAuditConfig#auditorAware}（即当前登录用户）。</p>
 *
 * <p>注意（老师课件 2.1 七戒律）：禁用 {@code @Data}；主键用包装类 {@code Long}；
 * 软删除列由各实体的 {@code @SQLDelete} + {@code @SQLRestriction} 维护，业务代码不手写 {@code deleted = 0}。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    /** 主键：BIGINT AUTO_INCREMENT，程序侧用 Long（包装类）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 创建时间：由审计监听器填充，不可更新。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 创建人用户 ID：由审计监听器填充（未登录场景为 0）。 */
    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    /** 最后更新时间：由审计监听器填充。 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 最后修改人用户 ID：由审计监听器填充。 */
    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    /** 软删除标记：0 正常 / 1 已删除，由 @SQLDelete 维护。 */
    @Column(name = "deleted", columnDefinition = "TINYINT", nullable = false)
    private Integer deleted = 0;
}
