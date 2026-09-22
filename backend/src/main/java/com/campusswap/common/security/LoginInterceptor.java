package com.campusswap.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器（鉴权关卡①认证 + 关卡②上下文）。
 *
 * <p>校验 {@code Authorization: Bearer <token>} 是否存在于 Redis（key = {@code login:token:{token}}），
 * 通过则写入 {@link SecurityContext}，请求结束在 {@code afterCompletion} 里清理 ThreadLocal。</p>
 *
 * <p>注意：这里**手写** 401 响应体而不是注入 ObjectMapper —— 少一个 Jackson 版本耦合点，
 * 出错路径也不依赖序列化配置。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 请求进入前的登录校验。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  处理器
     * @return true = 放行；false = 已写回 401
     * @throws Exception IO 异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检请求不带 token，必须放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String token = BearerToken.parse(request.getHeader("Authorization"));
        if (token == null) {
            writeUnauthorized(response);
            return false;
        }

        String userId = stringRedisTemplate.opsForValue().get(RedisKeys.token(token));
        if (userId == null) {
            log.debug("token 已失效或不存在，拒绝访问: {}", request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }

        SecurityContext.set(Long.valueOf(userId), token);
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal —— 不清理会导致线程复用时的越权。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  处理器
     * @param ex       处理过程中的异常（可能为 null）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        SecurityContext.clear();
    }

    /**
     * 写回 401（未登录或 token 失效）。
     *
     * @param response 当前响应
     * @throws Exception IO 异常
     */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录状态已失效，请重新登录\",\"data\":null}");
    }
}
