package com.campusswap.config;

import com.campusswap.common.security.LoginInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Web MVC 配置：注册登录拦截器 + 映射上传目录为静态资源 + 托管打包进 jar 的前端。
 *
 * <p>拦截范围 {@code /api/**}，只有登录接口放行（其余接口一律要求 token）。
 * 上传文件按 {@code uploads/yyyy/MM/xxx.png} 落盘，通过 {@code /uploads/**} 对外访问（见 ARCHITECTURE §11）。</p>
 *
 * <p><b>单 jar 模式（2026-10-09 新增）</b>：{@code mvnw package} 会把 {@code frontend/dist} 拷进
 * {@code BOOT-INF/classes/static/}，于是同一个 jar 既提供接口又提供页面 —— 别人不用装 Node/pnpm，
 * 打开 {@code http://localhost:10087} 就是完整站点（见 ARCHITECTURE §15 部署视图）。
 * 前端路由是 history 模式，所以非 {@code /api}、非 {@code /uploads}、且不带扩展名的路径
 * 一律回落到 {@code index.html}，刷新深链接（如 {@code /docs/5}）才不会 404。
 * 开发态没有 {@code static/index.html} 时该处理器什么都不做，行为与以前完全一致。</p>
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

        // 页面（HTML）一律不缓存：浏览器"继续上次会话"或启发式缓存可能留下**旧版 index.html**，
        // 而它引用的是旧版 hash bundle —— 于是服务端已经修好的前端，用户那边看到的仍是老行为
        // （2026-10-11 排查"启动后依旧跳 403"时确认这是必须堵死的一条路：
        //  带 hash 的 assets/ 可以放心缓存，只有 HTML 必须每次回源）。
        registry.addInterceptor(new HandlerInterceptor() {
            /**
             * 给 HTML 响应打上禁缓存头。
             *
             * @param request  当前请求
             * @param response 当前响应
             * @param handler  处理器
             * @return 恒为 true（只加响应头，不拦截）
             */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) {
                String path = request.getRequestURI();
                int dot = path.lastIndexOf('.');
                boolean html = dot < 0 || path.endsWith(".html");
                if (html) {
                    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                    response.setHeader("Pragma", "no-cache");
                }
                return true;
            }
        }).addPathPatterns("/**").excludePathPatterns("/api/**", "/uploads/**");
    }

    /**
     * 把本地上传目录挂成静态资源（图片回显用），并在打包模式下托管前端 SPA。
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadPath = Paths.get("uploads").toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");

        // 打包进 jar 的前端（存在才注册，开发态 classpath 下没有它）
        ClassPathResource index = new ClassPathResource("static/index.html");
        if (index.exists()) {
            registry.addResourceHandler("/**")
                    .addResourceLocations("classpath:/static/")
                    .resourceChain(true)
                    .addResolver(new PathResourceResolver() {
                        /**
                         * 命中真实文件就返回它；接口与上传路径不接管；其余前端路由回落到 index.html。
                         *
                         * @param resourcePath 请求路径（相对 static/）
                         * @param location     资源根
                         * @return 命中或回落后的资源；返回 null 表示交给后续处理器（404）
                         */
                        @Override
                        protected Resource getResource(String resourcePath, Resource location)
                                throws java.io.IOException {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable()) {
                                return requested;
                            }
                            if (resourcePath.startsWith("api/") || resourcePath.startsWith("uploads/")) {
                                return null;
                            }
                            // 带扩展名却没命中 = 真的资源缺失，保持 404，不要回落到 HTML
                            if (resourcePath.contains(".")) {
                                return null;
                            }
                            Resource fallback = new ClassPathResource("static/index.html");
                            return fallback.exists() ? fallback : null;
                        }
                    });
        }
    }
}

