package com.campusswap.common.security;

import com.campusswap.system.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器（鉴权关卡①认证 + 关卡②上下文）。
 *
 * <p><b>为什么验签落在这里</b>（2026-10-15 接入，补上 M15 的遗留项）：{@code /api/**} 上
 * <b>唯一</b>的认证入口就是本拦截器（由 {@code com.campusswap.config.WebMvcConfig} 注册），所以"每个请求先验签再查 Redis"
 * 这句话只有在拦截器里调用验签才成立 —— 之前只有 {@code TokenService.resolve} 会验签，而它没有调用点，
 * 等于验签在真实请求路径上是死代码。</p>
 *
 * <p><b>验签逻辑不重复实现</b>：直接调 {@link TokenService#resolve(String)}，它内部就是三步
 * （{@code TokenServiceImpl.resolve}）：① 本地 JWT 验签（签名 / 过期 / issuer / 格式，见
 * {@link JwtService#verify}）→ ② 查 Redis {@code login:token:{token}}（撤销判定）→ ③ 比对 userId。
 * 本类只负责把它的结果映射成 HTTP 语义。</p>
 *
 * <p>认证顺序与失败语义（三条互不混淆）：</p>
 * <ol>
 *   <li><b>没带 token</b> → <b>401</b>「{@value #MSG_SESSION_GONE}」；</li>
 *   <li><b>验签不过</b>（伪造 / 篡改 payload / 已过期 / issuer 不符 / 格式非法）→ <b>401</b>
 *       「{@value #MSG_INVALID_TOKEN}」—— 这一步在本地算完就拒，<b>不查 Redis</b>，所以攻击者
 *       往 Redis 里塞一张假票也放不进来；</li>
 *   <li><b>验签通过但 Redis 里没有</b>（已登出 / 被踢 / 改密 / 服务端会话过期）→ <b>401</b>
 *       「{@value #MSG_SESSION_GONE}」；</li>
 *   <li><b>Redis 本身连不上</b> → <b>500</b>「登录状态校验失败，请稍后重试」（M4 实测缺陷⑥：
 *       一次 Redis 抖动被当成"登录过期"，前端把用户集体踢回登录页 —— 这条红线不能回退）。</li>
 * </ol>
 *
 * <p>注意：这里**手写** 401/500 响应体而不是注入 ObjectMapper —— 少一个 Jackson 版本耦合点，
 * 出错路径也不依赖序列化配置。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    /** 验签不过的提示：token 本身不合法（伪造 / 篡改 / 过期 / 签发者不符 / 格式非法）。 */
    private static final String MSG_INVALID_TOKEN = "登录凭证无效，请重新登录";

    /** 会话不在（或压根没带 token）的提示：验签没问题，是服务端这一侧没有这张票了。 */
    private static final String MSG_SESSION_GONE = "登录状态已失效，请重新登录";

    /** token 服务（验签 + Redis 撤销检查的唯一定义处，本类不重复实现验签）。 */
    private final TokenService tokenService;

    /** JWT 校验（仅在 resolve() 失败时用来区分"验签不过"与"Redis 里没有"两种原因）。 */
    private final JwtService jwtService;

    /**
     * 请求进入前的登录校验。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  处理器
     * @return true = 放行；false = 已写回 401 / 500
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
            writeUnauthorized(response, MSG_SESSION_GONE);
            return false;
        }

        Long userId;
        try {
            // 一行覆盖"验签 + 查 Redis + 比对 userId"：先本地验签（伪造/篡改/过期在这一步就被拒，
            // 连 Redis 都不用查），验签过了才去 Redis 判"这张票是否已被撤销"。
            userId = tokenService.resolve(token);
        } catch (Exception ex) {
            // Redis 故障必须与「token 无效」分开：否则一次抖动会被前端当成"登录过期"，
            // 用户被集体踢回登录页，真实原因（连接不可用）被掩盖成 401。
            log.error("读取登录 token 失败（Redis 不可用）: {}", ex.getMessage());
            writeServerError(response);
            return false;
        }
        if (userId == null) {
            writeUnauthorized(response, resolveFailureMessage(request, token));
            return false;
        }

        SecurityContext.set(userId, token);
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
     * {@code resolve()} 返回 null 时，判明到底是哪种失败并给出对应文案（同时留日志）。
     *
     * <p>{@code resolve()} 用一个 null 表示两种失败原因，对外必须分开：验签失败说明
     * "这张票是假的"，而 Redis 未命中说明"票是真的，但服务端已经把它撤销了" ——
     * 前端提示与排障方向都不一样。这里再单独调一次 {@link JwtService#verify}（纯本地 HMAC 计算，
     * 不查 Redis，且<b>只有失败路径</b>才会走第二次）就是为了把两者分开。</p>
     *
     * @param request 当前请求（仅用于日志里的 uri）
     * @param token   请求携带的 token（日志只记前 8 位）
     * @return 该失败原因对应的中文提示
     */
    private String resolveFailureMessage(HttpServletRequest request, String token) {
        String tokenPrefix = token.substring(0, Math.min(8, token.length()));
        if (jwtService.verify(token) == null) {
            log.warn("JWT 验签未通过（签名/过期/issuer/格式），按未登录处理: uri={}, tokenPrefix={}",
                    request.getRequestURI(), tokenPrefix);
            return MSG_INVALID_TOKEN;
        }
        // 记 WARN 而不是 DEBUG：出现过"同一 token 前后都有效、中间一次 401"的瞬时现象，
        // 留一个可检索的痕迹（只记前 8 位，不落完整 token）。
        log.warn("token 验签通过但 Redis 中无此会话（已登出/被踢/改密/服务端已过期）: uri={}, tokenPrefix={}",
                request.getRequestURI(), tokenPrefix);
        return MSG_SESSION_GONE;
    }

    /**
     * 写回 401（未登录 / 验签不过 / 会话已失效）。
     *
     * @param response 当前响应
     * @param message  中文提示（验签失败与会话失效用不同文案，便于前端与排障区分）
     * @throws Exception IO 异常
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }

    /**
     * 写回 500（Redis 不可用，无法判定 token 有效性）。
     *
     * @param response 当前响应
     * @throws Exception IO 异常
     */
    private void writeServerError(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":500,\"message\":\"登录状态校验失败，请稍后重试\",\"data\":null}");
    }
}
