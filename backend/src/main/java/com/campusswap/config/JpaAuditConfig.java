package com.campusswap.config;

import com.campusswap.common.security.SecurityContext;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

/**
 * JPA 审计配置：把 {@code created_by / updated_by} 自动填成当前登录用户。
 *
 * <p>配合实体上的 {@code @CreatedBy/@CreatedDate/@LastModifiedBy/@LastModifiedDate} 与
 * {@code BaseEntity} 的 {@code @EntityListeners(AuditingEntityListener.class)} 使用；
 * 启动类上有 {@code @EnableJpaAuditing(auditorAwareRef = "auditorAware")}。</p>
 *
 * <p>系统初始化场景（无登录上下文）返回 0L，与 DDL 的 {@code DEFAULT 0} 语义一致。</p>
 *
 * @author Zyaire
 */
@Configuration
public class JpaAuditConfig {

    /** 系统操作的占位用户 ID（对应 DDL 中的 0 = 系统初始化）。 */
    private static final Long SYSTEM_USER_ID = 0L;

    /**
     * 提供审计操作人。
     *
     * @return 当前登录用户 ID；未登录时返回 0
     */
    @Bean
    public AuditorAware<Long> auditorAware() {
        return () -> Optional.ofNullable(SecurityContext.currentUserId()).or(() -> Optional.of(SYSTEM_USER_ID));
    }
}
