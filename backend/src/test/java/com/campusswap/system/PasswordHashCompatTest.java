package com.campusswap.system;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 密码哈希兼容性验证（课件 5.1 DoD①：必须使用 {@code BCryptPasswordEncoder}）。
 *
 * <p>换实现最怕的事是「存量账号登不进来」。库里的 {@code password_hash} 是早期用 Hutool
 * {@code BCrypt} 生成的 {@code $2b$10$} 哈希，本测试<b>直接拿库里的真实哈希</b>验证官方
 * {@code BCryptPasswordEncoder} 能否校验通过，以及改密新生成的哈希是否自校验通过、能否被
 * Hutool 反向校验（换回去也不会把账号锁死）。</p>
 *
 * <p>种子账号口令见仓库 README：{@code admin / Admin@123}。</p>
 *
 * @author Zyaire
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordHashCompatTest {

    /** 库中 admin 的明文口令（README 已公开的演示账号）。 */
    private static final String ADMIN_RAW_PASSWORD = "Admin@123";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 官方实现必须能校验库中既有的 Hutool 哈希。
     */
    @Test
    @DisplayName("官方 BCryptPasswordEncoder 能校验库里既有的 $2b$10$ 哈希（存量账号不受影响）")
    void officialEncoderVerifiesExistingHutoolHash() {
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM sys_user WHERE username = 'admin'", String.class);

        assertThat(hash).as("存量哈希仍是 Hutool 时代生成的 $2b$ 前缀").startsWith("$2b$10$");
        assertThat(passwordEncoder.matches(ADMIN_RAW_PASSWORD, hash))
                .as("正确口令必须通过").isTrue();
        assertThat(passwordEncoder.matches("definitely-wrong", hash))
                .as("错误口令必须被拒绝").isFalse();
        assertThat(passwordEncoder.upgradeEncoding(hash))
                .as("不需要强制重算哈希（cost 与算法一致）").isFalse();
    }

    /**
     * 新哈希由官方实现生成，且双向兼容。
     */
    @Test
    @DisplayName("新哈希由官方实现生成、自校验通过，且 Hutool 也能反向校验（双向兼容）")
    void newHashIsOfficialAndBidirectional() {
        String fresh = passwordEncoder.encode(ADMIN_RAW_PASSWORD);

        assertThat(fresh).as("官方实现默认版本标记为 $2a$，强度仍为 10")
                .startsWith("$2a$10$").hasSize(60);
        assertThat(passwordEncoder.matches(ADMIN_RAW_PASSWORD, fresh)).isTrue();
        assertThat(passwordEncoder.matches("definitely-wrong", fresh)).isFalse();

        // 反向兼容验证（仅测试代码使用 Hutool）：说明即使把实现换回去，也不会把账号锁死。
        assertThat(BCrypt.checkpw(ADMIN_RAW_PASSWORD, fresh)).isTrue();
    }
}
