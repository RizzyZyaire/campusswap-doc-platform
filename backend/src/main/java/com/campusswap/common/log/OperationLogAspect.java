package com.campusswap.common.log;

import com.campusswap.common.security.SecurityContext;
import com.campusswap.entity.SysOperationLog;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * 操作日志切面（M14，老师《作业说明》功能模块"操作日志：AOP 记录重要写操作"）。
 *
 * <p><b>切点选择</b>：控制器包下所有方法，但只对写操作（{@code POST} / {@code PUT} / {@code DELETE}）落日志
 * —— 不逐个接口加注解，避免"新加接口忘了记日志"（漏记是审计最常见的失效方式）。
 * 重点接口用 {@link OperationLog} 补充中文模块与动作名。</p>
 *
 * <p><b>三个不影响业务的设计</b>：</p>
 * <ol>
 *   <li>记日志在 {@code finally} 里且**整体 try/catch**：日志写失败绝不能让业务请求失败；</li>
 *   <li>落库走 {@link OperationLogWriter} 的 {@code @Async} 线程池：不占请求线程、不拖慢接口；</li>
 *   <li>入参**脱敏 + 截断**：密码、token 类字段一律打码，超长正文截到 1000 字符（日志表不是数据仓库）。</li>
 * </ol>
 *
 * @author Zyaire
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationLogAspect {

    /** 入参摘要最大长度（与 DB 列一致）。 */
    private static final int PARAMS_MAX = 1000;

    /** 失败原因最大长度（与 DB 列一致）。 */
    private static final int ERROR_MAX = 500;

    /** 路径段 → 中文模块名（覆盖本项目全部写接口的所属模块）。 */
    private static final Map<String, String> MODULE_BY_SEGMENT = Map.ofEntries(
            Map.entry("documents", "文档"),
            Map.entry("categories", "分类"),
            Map.entry("tags", "标签"),
            Map.entry("users", "用户"),
            Map.entry("roles", "角色"),
            Map.entry("depts", "机构"),
            Map.entry("permissions", "权限点"),
            Map.entry("search", "检索"),
            Map.entry("files", "文件"),
            Map.entry("auth", "认证"),
            Map.entry("logs", "操作日志"));

    /** 路径关键词 → 动作名（命中优先于 HTTP 方法推断）。 */
    private static final Map<String, String> ACTION_BY_KEYWORD = Map.ofEntries(
            Map.entry("publish", "发布"),
            Map.entry("republish", "重新发布"),
            Map.entry("audit", "审核"),
            Map.entry("approve", "审核通过"),
            Map.entry("reject", "驳回"),
            Map.entry("archive", "归档"),
            Map.entry("restore", "恢复"),
            Map.entry("trash", "移入回收站"),
            Map.entry("destroy", "彻底删除"),
            Map.entry("derive", "派生新版本"),
            Map.entry("rebuild", "重建索引"),
            Map.entry("grant", "角色授权"),
            Map.entry("disable", "启停"),
            Map.entry("reset", "重置密码"),
            Map.entry("favorite", "收藏"),
            Map.entry("upload", "上传图片"),
            Map.entry("logout", "退出登录"),
            Map.entry("login", "登录"));

    /** 参数里需要打码的字段关键字（大小写不敏感）。 */
    private static final List<String> SENSITIVE = List.of("password", "token", "secret");

    private final OperationLogWriter writer;

    private final JsonMapper jsonMapper;

    /** 控制器层切点。 */
    @Pointcut("execution(public * com.campusswap..controller..*(..))")
    public void controllerMethods() {
        // 切点声明，无方法体
    }

    /**
     * 环绕通知：执行目标方法并记录写操作日志。
     *
     * @param pjp 连接点
     * @return 目标方法返回值
     * @throws Throwable 目标方法抛出的异常（原样向上抛，不影响既有异常处理链）
     */
    @Around("controllerMethods()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String httpMethod = writeMethodOf(method);
        if (httpMethod == null) {
            return pjp.proceed();
        }
        long start = System.currentTimeMillis();
        Throwable error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                writer.write(build(pjp, method, httpMethod, (int) (System.currentTimeMillis() - start), error));
            } catch (Exception e) {
                // 日志失败绝不冒泡：业务已经成功/失败，不能被审计功能带崩
                log.warn("操作日志组装失败: {} {}", httpMethod, method.getName(), e);
            }
        }
    }

    /**
     * 判断是否写操作（只给 POST / PUT / DELETE 记日志）。
     *
     * @param method 控制器方法
     * @return HTTP 方法名，非写操作返回 null
     */
    private String writeMethodOf(Method method) {
        if (method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)) {
            return "POST";
        }
        if (method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)) {
            return "PUT";
        }
        if (method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)) {
            return "DELETE";
        }
        return null;
    }

    /**
     * 组装日志实体。
     *
     * @param pjp       连接点
     * @param method    控制器方法
     * @param httpMethod HTTP 方法
     * @param costMs    耗时
     * @param error     异常（成功为 null）
     * @return 日志实体（未落库）
     */
    private SysOperationLog build(ProceedingJoinPoint pjp, Method method, String httpMethod, int costMs,
                                  Throwable error) {
        HttpServletRequest request = currentRequest();
        String uri = request == null ? "" : request.getRequestURI();
        OperationLog annotation = method.getAnnotation(OperationLog.class);
        String module = annotation != null ? annotation.module() : moduleOf(uri);
        String action = annotation != null ? annotation.action() : actionOf(uri, httpMethod);
        String targetId = targetIdOf(uri);
        Long userId = SecurityContext.currentUserId();

        SysOperationLog entity = SysOperationLog.builder()
                .userId(userId)
                .username(usernameFromArgs(pjp.getArgs()))
                .module(module)
                .action(action)
                .method(httpMethod)
                .uri(truncate(uri, 255))
                .targetId(targetId)
                .summary(summaryOf(module, action, targetId))
                .params(annotation != null && !annotation.params() ? null : paramsOf(pjp.getArgs()))
                .ip(clientIp(request))
                .success(error == null ? 1 : 0)
                .errorMsg(error == null ? null : truncate(errorText(error), ERROR_MAX))
                .costMs(costMs)
                .build();
        // 审计列显式赋值：异步线程里没有 ThreadLocal，JPA 审计取不到操作人
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        return entity;
    }

    /**
     * 未登录场景（登录接口）从入参里取账号：登录时 SecurityContext 还是空的，
     * 但"谁尝试登录/谁登录失败"恰恰是审计最关心的信息。
     *
     * @param args 方法入参
     * @return 账号或 null
     */
    private String usernameFromArgs(Object[] args) {
        if (SecurityContext.currentUserId() != null) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            // JavaBean（getUsername）与 record（username）两种访问器都试一遍：
            // 本项目登录入参 LoginDtoReq 是 record，只认 getXxx 会漏掉"谁在登录"
            for (String accessor : new String[] {"getUsername", "username"}) {
                try {
                    Object value = arg.getClass().getMethod(accessor).invoke(arg);
                    if (value instanceof String text && !text.isBlank()) {
                        return truncate(text, 64);
                    }
                } catch (ReflectiveOperationException ignored) {
                    // 换下一种访问器
                }
            }
        }
        return null;
    }
    /**
     * 取当前请求（异步/非 Web 场景下可能为空）。
     *
     * @return 请求或 null
     */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /**
     * 由路径推断模块名。
     *
     * @param uri 请求路径
     * @return 中文模块名
     */
    private String moduleOf(String uri) {
        for (String segment : uri.split("/")) {
            String hit = MODULE_BY_SEGMENT.get(segment.toLowerCase());
            if (hit != null) {
                return hit;
            }
        }
        return "其他";
    }

    /**
     * 由路径关键词与 HTTP 方法推断动作名。
     *
     * @param uri        请求路径
     * @param httpMethod HTTP 方法
     * @return 中文动作名
     */
    private String actionOf(String uri, String httpMethod) {
        String lower = uri.toLowerCase();
        for (Map.Entry<String, String> entry : ACTION_BY_KEYWORD.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return switch (httpMethod) {
            case "POST" -> "新增";
            case "PUT" -> "修改";
            case "DELETE" -> "删除";
            default -> httpMethod;
        };
    }

    /**
     * 取路径末尾的数字段作为目标 ID。
     *
     * @param uri 请求路径
     * @return 目标 ID 或 null
     */
    private String targetIdOf(String uri) {
        String[] segments = uri.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (segments[i].matches("\\d{1,19}")) {
                return segments[i];
            }
        }
        return null;
    }

    /**
     * 生成中文摘要。
     *
     * @param module   模块
     * @param action   动作
     * @param targetId 目标 ID
     * @return 摘要
     */
    private String summaryOf(String module, String action, String targetId) {
        return targetId == null ? module + " · " + action : module + " · " + action + "（#" + targetId + "）";
    }

    /**
     * 入参摘要（JSON、脱敏、截断）。
     *
     * @param args 方法入参
     * @return JSON 摘要或 null
     */
    private String paramsOf(Object[] args) {
        List<Object> printable = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null || arg instanceof MultipartFile || arg instanceof jakarta.servlet.ServletRequest
                    || arg instanceof jakarta.servlet.ServletResponse) {
                continue;
            }
            printable.add(arg);
        }
        if (printable.isEmpty()) {
            return null;
        }
        try {
            String json = jsonMapper.writeValueAsString(printable.size() == 1 ? printable.get(0) : printable);
            return truncate(mask(json), PARAMS_MAX);
        } catch (Exception e) {
            return truncate(mask(String.valueOf(Arrays.toString(args))), PARAMS_MAX);
        }
    }

    /**
     * 敏感字段打码（密码/token 不落日志）。
     *
     * @param json 原始 JSON
     * @return 打码后的 JSON
     */
    private String mask(String json) {
        String masked = json;
        for (String key : SENSITIVE) {
            masked = masked.replaceAll("(?i)(\"" + key + "\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
        }
        return masked;
    }

    /**
     * 取客户端 IP（优先反代头）。
     *
     * @param request 请求
     * @return IP 或 null
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 45);
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    /**
     * 失败原因文本（业务异常取中文消息，其它取"类名: 消息"）。
     *
     * @param error 异常
     * @return 文本
     */
    private String errorText(Throwable error) {
        if (error instanceof com.campusswap.common.exception.BusinessException) {
            return error.getMessage();
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * 截断到指定长度。
     *
     * @param text   文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
