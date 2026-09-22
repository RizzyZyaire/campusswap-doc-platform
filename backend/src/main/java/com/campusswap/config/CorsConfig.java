package com.campusswap.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发环境跨域配置：允许 Vite dev server（http://localhost:5173）访问后端。
 *
 * <p>只在 {@code dev} profile 生效；生产环境由 Nginx 同源部署，不需要 CORS。</p>
 *
 * @author Zyaire
 */
@Configuration
@Profile("dev")
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 开放本地前端开发服务器的跨域访问。
     *
     * @param registry CORS 注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
