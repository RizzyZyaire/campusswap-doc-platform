package com.campusswap.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusswap.common.log.OperationLogAspect;
import com.campusswap.common.log.OperationLogWriter;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.controller.CategoryController;
import com.campusswap.document.controller.DocumentController;
import com.campusswap.document.dto.CategoryCreateDtoReq;
import com.campusswap.entity.SysOperationLog;
import com.campusswap.system.dto.LoginDtoReq;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * M14 操作日志切面单测：模块/动作推断、入参脱敏、失败留痕、非写操作不记。
 *
 * <p>不连数据库：切面只负责"组装日志"，落库由 {@link OperationLogWriter} 异步完成，
 * 所以这里 mock 掉 writer 并断言它收到的实体内容 —— 这正是审计质量的三个关键点：
 * <b>模块动作看得懂、密码不落库、失败有原因</b>。</p>
 *
 * @author Zyaire
 */
@DisplayName("M14 操作日志切面")
class Ac09OperationLogTest {

    private OperationLogWriter writer;

    private OperationLogAspect aspect;

    private ProceedingJoinPoint pjp;

    private MethodSignature signature;

    @BeforeEach
    void setUp() {
        writer = mock(OperationLogWriter.class);
        aspect = new OperationLogAspect(writer, JsonMapper.builder().build());
        pjp = mock(ProceedingJoinPoint.class);
        signature = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(signature);
        SecurityContext.set(1L, "token-for-test");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/documents")));
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 让切面绑定到指定控制器方法上。
     *
     * @param type   控制器类
     * @param name   方法名
     * @param params 参数类型
     * @throws NoSuchMethodException 方法不存在
     */
    private void bind(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        Method method = type.getMethod(name, params);
        when(signature.getMethod()).thenReturn(method);
    }

    /**
     * 设置请求路径。
     *
     * @param method HTTP 方法
     * @param uri    路径
     */
    private void request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("写操作自动留痕：模块与动作按路径推断，操作人与 IP 一并记录")
    void logWriteOperation() throws Throwable {
        request("POST", "/api/categories");
        bind(CategoryController.class, "create", CategoryCreateDtoReq.class);
        CategoryCreateDtoReq req = new CategoryCreateDtoReq("教学改革", "1", 1);
        when(pjp.getArgs()).thenReturn(new Object[] {req});
        when(pjp.proceed()).thenReturn(null);

        aspect.around(pjp);

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(writer).write(captor.capture());
        SysOperationLog entity = captor.getValue();
        assertThat(entity.getModule()).isEqualTo("分类");
        assertThat(entity.getAction()).isEqualTo("新增");
        assertThat(entity.getMethod()).isEqualTo("POST");
        assertThat(entity.getUri()).isEqualTo("/api/categories");
        assertThat(entity.getUserId()).isEqualTo(1L);
        assertThat(entity.getIp()).isEqualTo("127.0.0.1");
        assertThat(entity.getSuccess()).isEqualTo(1);
        assertThat(entity.getSummary()).contains("分类").contains("新增");
        assertThat(entity.getParams()).contains("教学改革");
    }

    @Test
    @DisplayName("动作名优先用路径关键词：/publish 记成「发布」而不是「新增」")
    void actionFromPathKeyword() throws Throwable {
        request("POST", "/api/documents/12/publish");
        bind(DocumentController.class, "publish", Long.class);
        when(pjp.getArgs()).thenReturn(new Object[] {12L});
        when(pjp.proceed()).thenReturn(null);

        aspect.around(pjp);

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getModule()).isEqualTo("文档");
        assertThat(captor.getValue().getAction()).isEqualTo("发布");
        assertThat(captor.getValue().getTargetId()).isEqualTo("12");
    }

    @Test
    @DisplayName("入参脱敏：密码字段落库前被打码")
    void passwordMasked() throws Throwable {
        // 登录时还没有登录态：清掉上下文才能走到"从入参取账号"的分支
        SecurityContext.clear();
        request("POST", "/api/auth/login");
        bind(com.campusswap.system.controller.AuthController.class, "login", LoginDtoReq.class);
        LoginDtoReq req = new LoginDtoReq("admin", "Admin@123");
        when(pjp.getArgs()).thenReturn(new Object[] {req});
        when(pjp.proceed()).thenReturn(null);

        aspect.around(pjp);

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(writer).write(captor.capture());
        SysOperationLog entity = captor.getValue();
        assertThat(entity.getModule()).isEqualTo("认证");
        assertThat(entity.getAction()).isEqualTo("登录");
        assertThat(entity.getParams()).contains("admin").contains("***").doesNotContain("Admin@123");
        // 未登录时账号从入参里取，登录审计才有"谁在登录"
        assertThat(entity.getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("失败也要留痕：异常原样抛出，日志记下失败原因")
    void failureIsRecorded() throws Throwable {
        request("POST", "/api/categories");
        bind(CategoryController.class, "create", CategoryCreateDtoReq.class);
        when(pjp.getArgs()).thenReturn(new Object[] {new CategoryCreateDtoReq("教学改革", "1", 1)});
        when(pjp.proceed()).thenThrow(new com.campusswap.common.exception.BusinessException(
                com.campusswap.common.api.ErrorCode.CONFLICT_STATUS, "同级下已存在同名分类"));

        try {
            aspect.around(pjp);
        } catch (Exception expected) {
            assertThat(expected).hasMessage("同级下已存在同名分类");
        }

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getSuccess()).isZero();
        assertThat(captor.getValue().getErrorMsg()).contains("同级下已存在同名分类");
    }

    @Test
    @DisplayName("读接口不记日志（避免把日志表写成访问日志）")
    void readOperationNotLogged() throws Throwable {
        request("GET", "/api/categories");
        // tree() 是 @GetMapping：读接口不该产生操作日志（否则日志表就变成访问日志了）
        when(signature.getMethod()).thenReturn(CategoryController.class.getMethod("tree"));
        when(pjp.proceed()).thenReturn("ok");
        assertThat(aspect.around(pjp)).isEqualTo("ok");
        verify(writer, never()).write(any(SysOperationLog.class));
    }
}
