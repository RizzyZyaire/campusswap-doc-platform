package com.campusswap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置。
 *
 * <p>全平台统一使用 {@link StringRedisTemplate}（键值都是字符串）：token、权限码集合、阅读量去重标记。
 * 刻意不使用 Java 对象序列化 —— 类结构一变就会反序列化失败，字符串则永远兼容（见 ARCHITECTURE §7）。</p>
 *
 * @author Zyaire
 */
@Configuration
public class RedisConfig {

    /**
     * 显式声明字符串模板（覆盖自动配置的同名 Bean，行为完全一致，便于后续统一加序列化策略）。
     *
     * @param connectionFactory 由 Boot 自动配置的 Redis 连接工厂
     * @return 字符串 Redis 模板
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
