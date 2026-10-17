package com.campusswap.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.exception.GlobalExceptionHandler;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.controller.DocumentController;
import com.campusswap.document.dto.DocumentDestroyDtoReq;
import com.campusswap.document.service.DocumentService;
import com.campusswap.document.service.impl.DocumentServiceImpl;
import com.campusswap.support.UnitTestSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 提交前 P0 回归测试：彻底删除二次确认 + 框架异常状态码。
 *
 * <p>不启动完整 Spring 容器：用 standalone MockMvc 只验证 MVC 参数解析/校验与
 * {@link GlobalExceptionHandler} 的返回结构；Service 兜底用一个不带依赖的直接实例验证。</p>
 *
 * @author Zyaire
 */
class P0RegressionTest {

    /** 被删除文档 ID（不需要真实存在，校验失败时不会进 Service）。 */
    private static final long DOCUMENT_ID = 1L;

    private DocumentService documentService;

    private MockMvc mvc;

    /**
     * 搭建只含被测 Controller 与生产异常处理器的 MockMvc。
     */
    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentService), new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 清理 ThreadLocal 登录上下文。
     */
    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    /**
     * 缺 confirm 字段：400，且不会调用 Service。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    @DisplayName("P0-1 彻底删除：缺 confirm 返回 400，Service 不被调用")
    void destroyWithoutConfirmIsRejected() throws Exception {
        mvc.perform(delete("/api/documents/{id}/destroy", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("彻底删除需要二次确认"));

        verifyNoInteractions(documentService);
    }

    /**
     * confirm=false：400，且不会调用 Service。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    @DisplayName("P0-1 彻底删除：confirm=false 返回 400，Service 不被调用")
    void destroyWithFalseConfirmIsRejected() throws Exception {
        mvc.perform(delete("/api/documents/{id}/destroy", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("彻底删除需要二次确认"));

        verifyNoInteractions(documentService);
    }

    /**
     * Service 兜底：即使绕过 DTO 校验直接调用，null / false 也必须被 400 拒绝。
     */
    @Test
    @DisplayName("P0-1 彻底删除：Service 兜底拒绝 null / false 的 confirm")
    void serviceDestroyGuardRejectsInvalidConfirm() {
        SecurityContext.set(1L, "p0-regression-token");
        DocumentServiceImpl service = new DocumentServiceImpl(
                null, null, null, null, null, null, null, null, null, null);

        BusinessException missing = UnitTestSupport.businessFailure(() -> service.destroy(DOCUMENT_ID, null));
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(missing.getMessage()).isEqualTo("彻底删除需要二次确认");

        BusinessException falsy = UnitTestSupport.businessFailure(
                () -> service.destroy(DOCUMENT_ID, new DocumentDestroyDtoReq(false)));
        assertThat(falsy.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(falsy.getMessage()).isEqualTo("彻底删除需要二次确认");

        assertThat(UnitTestSupport.validator().validate(new DocumentDestroyDtoReq(true))).isEmpty();
    }

    /**
     * HTTP 方法不支持：405 + body code=405。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    @DisplayName("P0-2 方法不支持：返回 405，响应体 code 也是 405")
    void methodNotAllowedReturns405() throws Exception {
        mvc.perform(post("/probe/param"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持该接口")));
    }

    /**
     * 缺必填查询参数：400，而不是 500。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    @DisplayName("P0-2 缺参数：返回 400，而不是 500")
    void missingParameterReturns400() throws Exception {
        mvc.perform(get("/probe/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("缺少必要的请求参数"));
    }

    /**
     * 非法 JSON：400，而不是 500。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    @DisplayName("P0-2 非法 JSON：返回 400，而不是 500")
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/probe/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    /**
     * 不支持的 Content-Type：415 + body code=415。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    @DisplayName("P0-2 媒体类型不支持：返回 415")
    void unsupportedMediaTypeReturns415() throws Exception {
        mvc.perform(post("/probe/media")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()));
    }

    /**
     * 测试专用探针接口（只用于触发框架异常，不进入生产代码）。
     */
    @RestController
    static class ProbeController {

        /**
         * 必填查询参数探针。
         *
         * @param value 参数值
         * @return 参数值
         */
        @GetMapping("/probe/param")
        String param(@RequestParam("q") String value) {
            return value;
        }

        /**
         * JSON 请求体探针。
         *
         * @param body 请求体
         * @return 名称
         */
        @PostMapping(value = "/probe/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String json(@Valid @RequestBody ProbeBody body) {
            return body.name();
        }

        /**
         * 媒体类型探针。
         *
         * @param body 请求体
         * @return 名称
         */
        @PostMapping(value = "/probe/media", consumes = MediaType.APPLICATION_JSON_VALUE)
        String media(@RequestBody ProbeBody body) {
            return body.name();
        }
    }

    /**
     * 测试请求体。
     *
     * @param name 名称
     */
    record ProbeBody(@NotBlank(message = "名称不能为空") String name) {
    }
}
