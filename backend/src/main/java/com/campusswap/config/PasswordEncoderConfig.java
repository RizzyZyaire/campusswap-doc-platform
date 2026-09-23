package com.campusswap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希器（课件 5.1 DoD①：数据库严禁明文存密码，统一走官方 {@link PasswordEncoder}）。
 *
 * <p>只引入 {@code spring-security-crypto} 这一个模块 —— 不引 {@code spring-security-web/config}，
 * 因此<b>不会</b>带来过滤器链、也不会触发 Spring Security 的 Web 自动配置；认证仍是
 * {@code LoginInterceptor} + Redis 不透明 token（ARCHITECTURE §5.2；DIFF-VS-TEACHER §9 已结案）。</p>
 *
 * <p>强度固定 10（BR-20）：与库中既有的 {@code $2b$10$} 哈希同强度；且已用探针实测
 * Spring 的 {@code BCryptPasswordEncoder} 能直接校验 Hutool 生成的历史哈希
 * （{@code docs/03-qa-review/probes/PwProbe.java}，{@code matches = true}、{@code upgradeEncoding = false}），
 * 因此<strong>存量账号与 data.sql 种子都不用改</strong>。</p>
 *
 * @author Zyaire
 */
@Configuration
public class PasswordEncoderConfig {

    /** BCrypt 强度（BR-20，cost = 10）。 */
    private static final int STRENGTH = 10;

    /**
     * 密码编码器：BCrypt（自带随机盐、单向不可逆）。
     *
     * @return 官方 PasswordEncoder 实现
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(STRENGTH);
    }
}
