package com.campusswap.config;

import com.campusswap.common.security.LoginInterceptor;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录拦截器 + 映射上传目录为静态资源。
 *
 * <p>拦截范围 {@code /api/**}，只有登录接口放行（其余接口一律要求 token）。
 * 上传文件按 {@code uploads/yyyy/MM/xxx.png} 落盘，通过 {@code /uploads/**} 对外访问（见 ARCHITECTURE §11）。</p>
 *
 * @author Zyaire
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    /**
     * 注册登录拦截器（鉴权关卡①②）。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                // 登录：无需 token
                // 登出：故意放行到 Controller —— 拦截器对无效 token 一律 401，
                //       而 API_SPECIFICATION §4.1.2 要求「重复登出幂等返回 200」，
                //       所以由 Controller 判空后调 TokenService（DEL 不存在的键是安全空操作）。
                .excludePathPatterns("/api/auth/login", "/api/auth/logout");
    }

    /**
     * 把本地上传目录挂成静态资源（图片回显用）。
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadPath = Paths.get("uploads").toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }
}
