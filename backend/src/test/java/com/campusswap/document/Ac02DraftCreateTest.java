package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.exception.GlobalExceptionHandler;
import com.campusswap.common.security.LoginInterceptor;
import com.campusswap.common.security.RedisKeys;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.controller.DocumentController;
import com.campusswap.document.dto.DocumentCreateDtoReq;
import com.campusswap.document.repository.CategoryRepository;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.repository.DocumentTagRelRepository;
import com.campusswap.document.repository.DocumentVersionRepository;
import com.campusswap.document.repository.FavoriteRepository;
import com.campusswap.document.repository.TagRepository;
import com.campusswap.document.service.impl.DocumentServiceImpl;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.entity.Document;
import com.campusswap.entity.DocumentVersion;
import com.campusswap.entity.enums.ChangeType;
import com.campusswap.entity.enums.DocumentStatus;
import com.campusswap.support.UnitTestSupport;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * US-02 创建并保存文档草稿（AC-02.1 / AC-02.2 / AC-02.3）。
 *
 * <p><b>三条断言分别落在哪一层</b>（每条断言都要落在"真正承担这条规则的那一层"）：</p>
 * <ul>
 *   <li><b>AC-02.1 → Service 层</b>：状态 DRAFT、versionNum=1、priceCents=0、作者即创建人、
 *       版本留痕 —— 全部是 {@code DocumentServiceImpl.create} 的职责，直接调它并用
 *       {@link ArgumentCaptor} 断言"写进仓储的实体字段值"与写库次数；</li>
 *   <li><b>AC-02.2 → Controller 参数校验层</b>：{@code DocumentCreateDtoReq} 上的
 *       {@code @NotBlank/@Size(message="文档标题不能为空且不超过128字")} 是这条规则的唯一落点
 *       （Service 不校验标题长度），因此用 {@code Validation.buildDefaultValidatorFactory()}
 *       的 Validator 校验 DTO，并把违规交给生产的 {@link GlobalExceptionHandler} 断言
 *       "400 + 中文提示"；同时用反射断言生产端点上确实挂了 {@code @Valid}，
 *       以此证明"校验失败 ⇒ 请求根本进不了 Service ⇒ 库中无新增记录"；</li>
 *   <li><b>AC-02.3 → 认证关卡（拦截器）+ Service 兜底</b>：未认证时 {@link LoginInterceptor}
 *       写回 401 并拒绝放行（{@link SecurityContext} 不写入用户），
 *       即便请求穿过拦截器直达 Service，{@code SecurityContext.requireUserId()} 也会抛
 *       {@code UNAUTHORIZED}（401），两条路径都断言"零仓储交互"。</li>
 * </ul>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m4-http.ps1} 的
 * <i>[US-02] create draft</i> 段 —— {@code US02.create.http/status/versionNum/authorIsCreator/priceCents}
 * （AC-02.1）、{@code US02.empty-title.http} + {@code US02.title-129.http} + {@code US02.no-insert-after-400.total}
 * （AC-02.2，含"无新增记录"）、{@code US02.no-token.http}（AC-02.3）。
 * 本类与之互补：机检脚本从 HTTP 侧看结果，本类断言脚本看不到的内部口径 ——
 * 交给仓储的实体字段值与写库次数、{@code doc_version} 快照内容、{@code created_by} 的取值来源
 * （{@code JpaAuditConfig#auditorAware}）、以及拦截器两条拒绝分支与"零仓储交互"。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac02DraftCreateTest {

    /** 当前登录用户。 */
    private static final Long ME = 1001L;

    /** mock 环境下的自增主键。 */
    private static final Long NEW_DOC_ID = 5001L;

    /** 当前请求的 token（拦截器写入上下文用）。 */
    private static final String TOKEN = "8f14e45fceea167a5a36dedd4bea2543";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentVersionRepository documentVersionRepository;

    @Mock
    private DocumentTagRelRepository documentTagRelRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private UserService userService;

    @Mock
    private PermissionCacheService permissionCacheService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private DocumentServiceImpl documentService;

    /**
     * 清 ThreadLocal 登录上下文，避免用例之间互相串味。
     */
    @AfterEach
    void tearDown() {
        UnitTestSupport.clearSecurityContext();
    }

    /**
     * AC-02.1（正常流）：创建草稿成功，且落库字段与版本留痕完全符合契约。
     */
    @Test
    @DisplayName("AC-02.1 创建草稿：状态 DRAFT、versionNum=1、createdBy=当前用户ID、priceCents=0，并写出 CREATE 版本记录")
    void ac0201_createDraftPersistsContractFields() {
        SecurityContext.set(ME, TOKEN);
        DocumentCreateDtoReq req = new DocumentCreateDtoReq(
                "  接口规范 v1  ", "统一接口约定", "# 接口规范\n正文", "0", List.of(), null);
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            return UnitTestSupport.emulateInsert(doc, NEW_DOC_ID);
        });
        when(userService.realNameOf(ME)).thenReturn("张三");

        DocumentDetailVo vo = documentService.create(req);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).saveAndFlush(docCaptor.capture());
        Document saved = docCaptor.getValue();

        assertThat(saved.getStatus()).as("AC-02.1 新建状态恒为 DRAFT").isEqualTo(DocumentStatus.DRAFT);
        assertThat(saved.getVersionNum()).as("AC-02.1 versionNum 从 1 起").isEqualTo(1);
        assertThat(saved.getPriceCents()).as("AC-02.1 不传价格标记时 priceCents=0（整数分）").isZero();
        assertThat(saved.getCreatedBy()).as("AC-02.1 作者即创建人 = 当前登录用户").isEqualTo(ME);
        assertThat(UnitTestSupport.currentAuditor())
                .as("AC-02.1 审计操作人由 JpaAuditConfig#auditorAware 取 SecurityContext，即当前用户")
                .isEqualTo(ME);
        assertThat(saved.getTitle()).as("标题两端空格被 trim").isEqualTo("接口规范 v1");
        assertThat(saved.getSummary()).isEqualTo("统一接口约定");
        assertThat(saved.getContentMd()).isEqualTo("# 接口规范\n正文");
        assertThat(saved.getCategoryId()).as("分类传 0 = 未分类").isZero();
        assertThat(saved.getViewCount()).isZero();
        assertThat(saved.getFavoriteCount()).isZero();

        // 副作用：doc_version 追加一条 CREATE 快照（留痕是 BR-07 的硬要求）
        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionRepository).saveAndFlush(versionCaptor.capture());
        DocumentVersion version = versionCaptor.getValue();
        assertThat(version.getDocumentId()).isEqualTo(NEW_DOC_ID);
        assertThat(version.getVersionNum()).isEqualTo(1);
        assertThat(version.getChangeType()).isEqualTo(ChangeType.CREATE);
        assertThat(version.getTitle()).isEqualTo("接口规范 v1");
        assertThat(version.getContentMd()).isEqualTo("# 接口规范\n正文");
        // 无标签也要清空旧绑定（标签关系走中间表，先删后插）
        verify(documentTagRelRepository).deleteByDocumentId(NEW_DOC_ID);

        assertThat(vo.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(vo.getVersionNum()).isEqualTo(1);
        assertThat(vo.getId()).isEqualTo("5001");
        assertThat(vo.getAuthorId()).as("作者 ID = created_by，JSON 里是字符串").isEqualTo("1001");
        assertThat(vo.getAuthorName()).isEqualTo("张三");
        assertThat(vo.getContentMd()).isEqualTo("# 接口规范\n正文");
    }

    /**
     * AC-02.2（异常流）：标题为空 / 超 128 字 → 400 +「文档标题不能为空且不超过128字」，且库中无新增。
     */
    @Test
    @DisplayName("AC-02.2 标题为空或超过128字：返回 400 与中文提示「文档标题不能为空且不超过128字」，且数据库中无新增记录")
    void ac0202_blankOrTooLongTitleRejectedByValidationLayer() throws Exception {
        Validator validator = UnitTestSupport.validator();

        // ① 标题为空：@NotBlank 命中
        DocumentCreateDtoReq blankTitle = new DocumentCreateDtoReq("", "摘要", "正文", "0", List.of(), 0);
        Set<ConstraintViolation<DocumentCreateDtoReq>> blankViolations = validator.validate(blankTitle);
        assertThat(blankViolations).as("AC-02.2 空标题必须被拦下").isNotEmpty();
        assertThat(blankViolations).extracting(violation -> violation.getMessage())
                .as("AC-02.2 提示文案")
                .contains("文档标题不能为空且不超过128字");

        // ② 标题 129 字：@Size(max=128) 命中
        Set<ConstraintViolation<DocumentCreateDtoReq>> tooLongViolations =
                validator.validate(new DocumentCreateDtoReq("标".repeat(129), null, "正文", null, null, null));
        assertThat(tooLongViolations).hasSize(1);
        assertThat(tooLongViolations.iterator().next().getMessage()).isEqualTo("文档标题不能为空且不超过128字");

        // ③ 对照组：128 字边界是合法的 —— 证明上面的拒绝不是"常数拒绝"
        assertThat(validator.validate(new DocumentCreateDtoReq("标".repeat(128), null, "正文", null, null, null)))
                .as("AC-02.2 128 字是允许的上界").isEmpty();

        // ④ 违规 → 生产全局异常处理器 → 400 + 中文提示（错误码即 HTTP 状态码）
        Method create = DocumentController.class.getMethod("create", DocumentCreateDtoReq.class);
        MethodArgumentNotValidException exception = UnitTestSupport.asArgumentNotValid(
                create, 0, blankTitle, blankViolations);
        ResponseEntity<ResponseResult<Void>> invalid = new GlobalExceptionHandler()
                .handleMethodArgumentNotValid(exception);
        assertThat(invalid.getStatusCode().value()).as("AC-02.2 返回 400").isEqualTo(400);
        assertThat(invalid.getBody()).isNotNull();
        assertThat(invalid.getBody().code()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(invalid.getBody().message()).isEqualTo("文档标题不能为空且不超过128字");

        // ⑤ 校验确实挂在生产端点上（@Valid），因此请求体进不了 Service ⇒ 库里不可能有新增行
        assertThat(create.getParameters()[0].isAnnotationPresent(Valid.class))
                .as("AC-02.2 端点必须挂 @Valid，否则 DTO 约束形同虚设").isTrue();
        verifyNoInteractions(documentRepository, documentVersionRepository, documentTagRelRepository);
    }

    /**
     * AC-02.3（异常流）：未携带有效 token → 401，且不产生任何数据。
     */
    @Test
    @DisplayName("AC-02.3 未携带有效 token：认证关卡返回 401 且不写登录上下文，Service 侧同样 401，全链路零写库")
    void ac0203_missingOrUnknownTokenReturnsUnauthorizedWithoutSideEffect() throws Exception {
        LoginInterceptor interceptor = new LoginInterceptor(stringRedisTemplate);
        StringWriter body = new StringWriter();
        when(request.getMethod()).thenReturn("POST");
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));

        // ① 完全没带 Authorization 头 → 拦截器写 401 并拒绝放行
        assertThat(interceptor.preHandle(request, response, new Object()))
                .as("AC-02.3 未认证请求不得放行").isFalse();
        assertThat(body.toString())
                .as("AC-02.3 响应体是统一的 401 结构").contains("\"code\":401")
                .contains("登录状态已失效，请重新登录");
        assertThat(SecurityContext.currentUserId()).as("AC-02.3 认证失败不得写入登录上下文").isNull();

        // ② 带了 token 但 Redis 中不存在（伪造 / 已失效）→ 同样 401
        when(request.getHeader("Authorization")).thenReturn("Bearer not-a-real-token");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.token("not-a-real-token"))).thenReturn(null);
        body.getBuffer().setLength(0);

        assertThat(interceptor.preHandle(request, response, new Object()))
                .as("AC-02.3 Redis 查不到的 token 一律按未登录处理").isFalse();
        assertThat(body.toString()).contains("\"code\":401");
        assertThat(SecurityContext.currentUserId()).isNull();
        verify(response, times(2)).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        // ③ 没有登录上下文时即便"绕过"拦截器直接调 Service，也只能 401，且不落任何库
        DocumentCreateDtoReq req = new DocumentCreateDtoReq("越权草稿", null, "正文", "0", List.of(), null);
        BusinessException failure = UnitTestSupport.businessFailure(() -> documentService.create(req));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(failure.getErrorCode().getCode()).as("AC-02.3 HTTP 401").isEqualTo(401);
        verifyNoInteractions(documentRepository, documentVersionRepository, documentTagRelRepository);
    }
}
