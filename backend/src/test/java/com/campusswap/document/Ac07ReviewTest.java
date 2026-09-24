package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.exception.GlobalExceptionHandler;
import com.campusswap.common.security.PermissionAspect;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.controller.ReviewController;
import com.campusswap.document.dto.DocumentAuditDtoReq;
import com.campusswap.document.dto.DocumentRejectDtoReq;
import com.campusswap.document.dto.ReviewPageDtoReq;
import com.campusswap.document.repository.CategoryRepository;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.repository.DocumentTagRelRepository;
import com.campusswap.document.repository.DocumentVersionRepository;
import com.campusswap.document.repository.FavoriteRepository;
import com.campusswap.document.repository.TagRepository;
import com.campusswap.document.service.impl.DocumentServiceImpl;
import com.campusswap.document.service.impl.ReviewServiceImpl;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVersionVo;
import com.campusswap.entity.Document;
import com.campusswap.entity.DocumentVersion;
import com.campusswap.entity.enums.ChangeType;
import com.campusswap.entity.enums.DocumentStatus;
import com.campusswap.support.UnitTestSupport;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.UserService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * US-07 文档管理员审核与治理（AC-07.1 / AC-07.2 / AC-07.3）。
 *
 * <p><b>三条断言分别落在哪一层</b>：</p>
 * <ul>
 *   <li><b>AC-07.1 → Service 层</b>：归档的状态流转（{@code PUBLISHED → ARCHIVED}、版本号 +1）与
 *       {@code doc_version} 留痕是 {@code ReviewServiceImpl.archive} 的职责。这里把
 *       {@link DocumentServiceImpl} 以<b>真实对象</b>（仓储仍为 mock）注入 {@code ReviewServiceImpl}，
 *       让 {@code writeVersion} 真正产出 {@link DocumentVersion}，再用 {@link ArgumentCaptor}
 *       断言"写进 doc_version 的那条记录"的 {@code changeType=ARCHIVE}、{@code changeRemark=审核意见}、
 *       操作人 {@code created_by}；最后用 {@link DocumentVersionVo} 断言属主在版本历史里看到的意见。</li>
 *   <li><b>AC-07.2 → AOP 权限切面层</b>：{@code @RequiresPermission("doc:review")} 的拦截点是
 *       {@link PermissionAspect#check(RequiresPermission)}，与 Controller 方法体无关。
 *       因此直接构造切面（mock 掉它的 {@code PermissionCacheService} 依赖），
 *       用 {@link UnitTestSupport#requiresPermissionOn} 取生产端点上的<b>真实注解</b>喂给它，
 *       断言抛 {@code BusinessException(NO_PERMISSION)}（= HTTP 403），并做正向对照。</li>
 *   <li><b>AC-07.3 → Controller 参数校验层</b>：{@code DocumentRejectDtoReq.reason} 上的
 *       {@code @NotBlank(message="驳回理由不能为空")} 是这条规则的落点（Service 不校验理由非空），
 *       用 Validator + 生产的 {@link GlobalExceptionHandler} 断言"400 + 中文提示"，
 *       并用反射确认端点挂了 {@code @Valid}，以此证明校验失败时请求根本进不了 Service。</li>
 * </ul>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m4-http.ps1} 的
 * <i>[US-07] review / reject / archive / trash state machine</i> 段 ——
 * {@code US07.review-list.staff.http}(403) + {@code US07.audit.staff.http}(403)（AC-07.2）、
 * {@code US07.reject-reason-blank.http}(400)（AC-07.3）、
 * {@code US06.archive.http/status} + {@code US07.archive-remark-in-version}
 * （AC-07.1：{@code changeType=ARCHIVE} 且 {@code changeRemark} 等于归档意见）；
 * 另有 {@code docs/03-qa-review/verify-m3-http.ps1} 步骤 <i>[4] Guard rails: 403 for missing permission point</i>
 * 逐接口断言"缺权限点 → 403 NO_PERMISSION"，以及 {@code docs/03-qa-review/TEST_CHECKLIST.md} 的 US-07 清单。
 * 本类补的是脚本断言不到的：doc_version 记录逐字段内容与操作人、切面在"有/无权限"两种输入下的分支、
 * 以及中文校验提示与 400 的映射。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac07ReviewTest {

    /** 文档管理员（当前登录用户）。 */
    private static final Long DOC_ADMIN = 2001L;

    /** 仅持 STAFF 权限的用户。 */
    private static final Long STAFF_USER = 3001L;

    /** 文档 ID。 */
    private static final Long DOC_ID = 9001L;

    /** 版本记录 ID（mock 环境下的自增主键）。 */
    private static final Long VERSION_ID = 6001L;

    /** 引用来源：USER_STORIES.md §2 US-07 <b>AC-07.2</b> 明确写了 {@code @RequiresPermission("doc:review")}。 */
    private static final String REVIEW_PERMISSION = "doc:review";

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

    /** 真实的文档服务实现（仓储为 mock）—— 让 writeVersion 真正产出 doc_version 记录。 */
    @InjectMocks
    private DocumentServiceImpl documentService;

    /** 被测的审核服务。 */
    private ReviewServiceImpl reviewService;

    /** 被测的权限切面。 */
    private PermissionAspect permissionAspect;

    /**
     * 手工装配两个被测对象（AC-07.1 用真 DocumentService，AC-07.2 用真切面）。
     */
    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(documentRepository, documentService);
        permissionAspect = new PermissionAspect(permissionCacheService);
    }

    /**
     * 清 ThreadLocal 登录上下文。
     */
    @AfterEach
    void tearDown() {
        UnitTestSupport.clearSecurityContext();
    }

    /**
     * AC-07.1（正常流）：DOC_ADMIN 归档 PUBLISHED 文档 → ARCHIVED，意见与操作人写入 doc_version。
     */
    @Test
    @DisplayName("AC-07.1 DOC_ADMIN 归档已发布文档并填写意见：状态变 ARCHIVED，审核意见与操作人写入 doc_version（changeType=ARCHIVE）")
    void ac0701_archivePublishedDocumentWritesOpinionIntoVersionSnapshot() {
        SecurityContext.set(DOC_ADMIN, "tok-docadmin");
        String opinion = "内容已过时，归档留档";
        Document doc = publishedDocument();
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document saved = invocation.getArgument(0);
            return UnitTestSupport.emulateUpdate(saved);
        });
        when(documentVersionRepository.saveAndFlush(any(DocumentVersion.class))).thenAnswer(invocation -> {
            DocumentVersion version = invocation.getArgument(0);
            return UnitTestSupport.emulateInsert(version, VERSION_ID);
        });
        when(userService.realNameOf(1001L)).thenReturn("张三");

        DocumentDetailVo vo = reviewService.archive(DOC_ID, new DocumentAuditDtoReq(opinion));

        assertThat(doc.getStatus()).as("AC-07.1 状态变为 ARCHIVED").isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(doc.getVersionNum()).as("AC-07.1 治理动作同样让版本号 +1").isEqualTo(6);
        verify(documentRepository).saveAndFlush(doc);

        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionRepository).saveAndFlush(versionCaptor.capture());
        DocumentVersion version = versionCaptor.getValue();
        assertThat(version.getChangeType()).as("AC-07.1 changeType = ARCHIVE").isEqualTo(ChangeType.ARCHIVE);
        assertThat(version.getChangeRemark()).as("AC-07.1 审核意见写入版本记录").isEqualTo(opinion);
        assertThat(version.getDocumentId()).isEqualTo(DOC_ID);
        assertThat(version.getVersionNum()).isEqualTo(6);
        assertThat(version.getCreatedBy())
                .as("AC-07.1 操作人写入版本记录（created_by 由 JpaAuditConfig#auditorAware 取当前登录用户）")
                .isEqualTo(DOC_ADMIN);

        // 属主在详情页/版本历史里能看到这条意见
        DocumentVersionVo versionVo = DocumentVersionVo.of(version, "张三");
        assertThat(versionVo.getChangeRemark()).isEqualTo(opinion);
        assertThat(versionVo.getChangeType()).isEqualTo(ChangeType.ARCHIVE);
        assertThat(versionVo.getOperatorId()).isEqualTo("2001");
        assertThat(vo.getStatus()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(vo.getVersionNum()).isEqualTo(6);
    }

    /**
     * AC-07.2（异常流）：仅 STAFF 权限调用审核接口 → 被 {@code @RequiresPermission("doc:review")} 切面拦成 403。
     */
    @Test
    @DisplayName("AC-07.2 仅有 STAFF 权限调用审核接口：切面拦截抛 BusinessException NO_PERMISSION（HTTP 403），治理端点全部受权限码保护")
    void ac0702_reviewEndpointBlockedByPermissionAspect() {
        SecurityContext.set(STAFF_USER, "tok-staff");
        // STAFF 的权限集合里没有 doc:review（角色→权限码绑定见 backend/sql/data.sql，
        // 由 docs/03-qa-review/verify-db-deep.ps1 对账「角色权限集合 = PRD §3.3」）
        when(permissionCacheService.hasPermission(STAFF_USER, REVIEW_PERMISSION)).thenReturn(false);

        RequiresPermission annotation = UnitTestSupport.requiresPermissionOn(
                ReviewController.class, "list", ReviewPageDtoReq.class);
        assertThat(annotation.value())
                .as("AC-07.2 审核接口的权限码必须是 doc:review（USER_STORIES §2 AC-07.2 原文）")
                .isEqualTo(REVIEW_PERMISSION);

        BusinessException failure = UnitTestSupport.businessFailure(() -> permissionAspect.check(annotation));

        assertThat(failure.getErrorCode()).as("AC-07.2 错误码 NO_PERMISSION").isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(failure.getErrorCode().getCode()).as("AC-07.2 HTTP 403").isEqualTo(403);
        verify(permissionCacheService).hasPermission(STAFF_USER, REVIEW_PERMISSION);

        // 正向对照：授予 doc:review 后同一调用放行 —— 证明拦截确实由权限码驱动，而不是"恒定抛异常"
        when(permissionCacheService.hasPermission(STAFF_USER, REVIEW_PERMISSION)).thenReturn(true);
        assertThatCode(() -> permissionAspect.check(annotation)).doesNotThrowAnyException();

        // 旁证：ReviewController 的 5 个治理端点全部挂了 doc:* 权限码，没有裸奔的入口
        assertThat(UnitTestSupport.requiresPermissionOn(ReviewController.class, "audit", Long.class,
                DocumentAuditDtoReq.class).value()).isEqualTo("doc:audit");
        assertThat(UnitTestSupport.requiresPermissionOn(ReviewController.class, "reject", Long.class,
                DocumentRejectDtoReq.class).value()).isEqualTo("doc:reject");
        assertThat(UnitTestSupport.requiresPermissionOn(ReviewController.class, "archive", Long.class,
                DocumentAuditDtoReq.class).value()).isEqualTo("doc:archive");
        assertThat(UnitTestSupport.requiresPermissionOn(ReviewController.class, "republish", Long.class).value())
                .isEqualTo("doc:archive");
    }

    /**
     * AC-07.3（异常流）：驳回未填理由 → 400 +「驳回理由不能为空」，且不产生任何写库。
     */
    @Test
    @DisplayName("AC-07.3 驳回未填写理由：返回 400 与提示「驳回理由不能为空」，请求进不了 Service，零写库")
    void ac0703_rejectWithoutReasonRejectedByValidationLayer() throws Exception {
        Validator validator = UnitTestSupport.validator();

        // ① 理由为 null
        DocumentRejectDtoReq noReason = new DocumentRejectDtoReq(null);
        Set<ConstraintViolation<DocumentRejectDtoReq>> violations = validator.validate(noReason);
        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .as("AC-07.3 命中的是 reason 字段").contains("reason");
        assertThat(violations).extracting(violation -> violation.getMessage())
                .as("AC-07.3 中文提示").contains("驳回理由不能为空");

        // ② 纯空白（@NotBlank 而非 @NotNull 的差别就在这里）
        assertThat(validator.validate(new DocumentRejectDtoReq("   ")))
                .extracting(violation -> violation.getMessage()).contains("驳回理由不能为空");

        // ③ 对照组：填了理由就通过（证明上面的拒绝不是"常数拒绝"）
        assertThat(validator.validate(new DocumentRejectDtoReq("内容与标题不符，请补充数据来源"))).isEmpty();

        // ④ 400 + 中文提示（错误码即 HTTP 状态码）
        Method reject = ReviewController.class.getMethod("reject", Long.class, DocumentRejectDtoReq.class);
        MethodArgumentNotValidException exception =
                UnitTestSupport.asArgumentNotValid(reject, 1, noReason, violations);
        ResponseEntity<ResponseResult<Void>> invalid =
                new GlobalExceptionHandler().handleMethodArgumentNotValid(exception);
        assertThat(invalid.getStatusCode().value()).as("AC-07.3 返回 400").isEqualTo(400);
        assertThat(invalid.getBody()).isNotNull();
        assertThat(invalid.getBody().code()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(invalid.getBody().message()).isEqualTo("驳回理由不能为空");

        // ⑤ @Valid 挂在端点上 ⇒ 校验失败时请求体进不了 Service ⇒ 库里不会有任何变化
        assertThat(reject.getParameters()[1].isAnnotationPresent(Valid.class)).isTrue();
        verifyNoInteractions(documentRepository, documentVersionRepository, documentTagRelRepository);
    }

    /**
     * 构造一份 PUBLISHED 文档（属主是别人，管理员来治理）。
     *
     * @return 文档实体
     */
    private static Document publishedDocument() {
        Document doc = Document.builder()
                .categoryId(0L)
                .title("接口规范 v1")
                .summary("统一接口约定")
                .contentMd("# 正文")
                .status(DocumentStatus.PUBLISHED)
                .versionNum(5)
                .priceCents(0)
                .viewCount(30)
                .favoriteCount(2)
                .build();
        doc.setId(DOC_ID);
        doc.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0, 0));
        doc.setCreatedBy(1001L);
        doc.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
        doc.setUpdatedBy(1001L);
        return doc;
    }
}
