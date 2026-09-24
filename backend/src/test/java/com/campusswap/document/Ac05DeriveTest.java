package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.dto.DocumentDeriveDtoReq;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * US-05 派生复用已有文档（AC-05.1 / AC-05.2 / AC-05.3）。
 *
 * <p><b>为什么在这一层测</b>：派生规则的三条分支（可见 → 建副本 / 不可见 → 403 / 回收站 → 409）
 * 全部由 {@code DocumentServiceImpl.derive} 的 {@code assertVisible} + 状态判定决定，
 * 控制器只有 {@code @RequiresPermission("doc:derive")} 与响应包装。
 * "源文档不被修改"这条副作用也只有在这一层才能证明：{@link ArgumentCaptor} 抓住真正传给
 * {@code saveAndFlush} 的那个实体，断言它是<b>新对象</b>且源对象逐字段不变、写库只发生一次。</p>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m4-http.ps1} 的
 * <i>[US-05] derive + favorite + view count</i> 段 —— {@code US05.derive.http/status}、
 * {@code US05.derive.derivedFromId}、{@code US05.derive.title-copy}、{@code US05.derive.versionNum}、
 * {@code US05.derive.content-prefilled}、{@code US05.derive.source-version-unchanged}（AC-05.1）、
 * {@code US05.derive-invisible-source.http}(403，AC-05.2)、
 * {@code US03.derive-trashed.http}(409，AC-05.3)；
 * 另有 {@code docs/03-qa-review/TEST_CHECKLIST.md} 的 US-05 清单。
 * 本类补的是脚本断言不到的：落库实体逐字段（新对象 vs 源对象）、"源文档一个字段都没改"、
 * 以及派生版本留痕的备注原文。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac05DeriveTest {

    /** 当前登录用户（派生者）。 */
    private static final Long ME = 1001L;

    /** 源文档作者。 */
    private static final Long OTHER = 1002L;

    /** 源文档 ID。 */
    private static final Long SOURCE_ID = 8001L;

    /** 派生出的新文档 ID。 */
    private static final Long NEW_ID = 8002L;

    /** 源文档正文。 */
    private static final String SOURCE_CONTENT = "# 接口规范\n1. 统一返回体";

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

    @InjectMocks
    private DocumentServiceImpl documentService;

    /**
     * 清 ThreadLocal 登录上下文。
     */
    @AfterEach
    void tearDown() {
        UnitTestSupport.clearSecurityContext();
    }

    /**
     * AC-05.1（正常流）：派生 PUBLISHED 文档 → 新草稿预填正文与血缘，源文档一个字段都不动。
     */
    @Test
    @DisplayName("AC-05.1 派生他人已发布文档：新文档 DRAFT + derivedFromId=D.id + 正文预填 + 标题「D原标题（副本）」+ versionNum=1，源文档完全不变")
    void ac0501_derivePublishedDocumentCreatesDraftCopyWithoutTouchingSource() {
        SecurityContext.set(ME, "tok-me");
        Document source = sourceDocument(DocumentStatus.PUBLISHED);
        source.setVersionNum(4);
        when(documentRepository.findDetailById(SOURCE_ID)).thenReturn(Optional.of(source));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document created = invocation.getArgument(0);
            return UnitTestSupport.emulateInsert(created, NEW_ID);
        });
        when(userService.realNameOf(ME)).thenReturn("张三");

        DocumentDetailVo vo = documentService.derive(SOURCE_ID, new DocumentDeriveDtoReq(null));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(1)).saveAndFlush(captor.capture());
        Document created = captor.getValue();
        assertThat(created).as("AC-05.1 落库的是新对象，不是源文档").isNotSameAs(source);
        assertThat(created.getStatus()).as("AC-05.1 新文档状态 DRAFT").isEqualTo(DocumentStatus.DRAFT);
        assertThat(created.getDerivedFromId()).as("AC-05.1 血缘 derivedFromId = D.id").isEqualTo(SOURCE_ID);
        assertThat(created.getContentMd()).as("AC-05.1 正文预填源文档 contentMd").isEqualTo(SOURCE_CONTENT);
        assertThat(created.getTitle()).as("AC-05.1 默认标题「D原标题（副本）」").isEqualTo("接口规范（副本）");
        assertThat(created.getVersionNum()).as("AC-05.1 新文档版本号从 1 起").isEqualTo(1);
        assertThat(created.getCategoryId()).as("AC-05.1 继承源文档分类").isEqualTo(source.getCategoryId());
        assertThat(created.getCreatedBy()).as("AC-05.1 派生者即新文档作者").isEqualTo(ME);

        // 源文档不被修改（逐字段核对，并确认唯一一次写库写的是新对象）
        assertThat(source.getTitle()).isEqualTo("接口规范");
        assertThat(source.getStatus()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(source.getVersionNum()).as("AC-05.1 源文档版本号不得变").isEqualTo(4);
        assertThat(source.getContentMd()).isEqualTo(SOURCE_CONTENT);
        assertThat(source.getDerivedFromId()).isNull();
        assertThat(source.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 10, 12, 0, 0));

        // 派生也留痕：CREATE + "由文档 X 派生"
        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionRepository).saveAndFlush(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getDocumentId()).isEqualTo(NEW_ID);
        assertThat(versionCaptor.getValue().getChangeType()).isEqualTo(ChangeType.CREATE);
        assertThat(versionCaptor.getValue().getChangeRemark()).isEqualTo("由文档 8001 派生");
        assertThat(versionCaptor.getValue().getVersionNum()).isEqualTo(1);

        assertThat(vo.getId()).isEqualTo("8002");
        assertThat(vo.getDerivedFromId()).as("AC-05.1 出参带血缘").isEqualTo("8001");
        assertThat(vo.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(vo.getContentMd()).isEqualTo(SOURCE_CONTENT);
    }

    /**
     * AC-05.2（异常流）：他人的 DRAFT 不可见 → 403 NO_PERMISSION，且不产生任何写操作。
     */
    @Test
    @DisplayName("AC-05.2 派生他人 DRAFT（不可见）：返回 403 NO_PERMISSION，不建副本、不写版本、不绑标签")
    void ac0502_deriveInvisibleDraftForbidden() {
        SecurityContext.set(ME, "tok-me");
        Document othersDraft = sourceDocument(DocumentStatus.DRAFT);
        when(documentRepository.findDetailById(SOURCE_ID)).thenReturn(Optional.of(othersDraft));
        when(permissionCacheService.hasPermission(ME, "doc:manage")).thenReturn(false);

        BusinessException failure = UnitTestSupport.businessFailure(
                () -> documentService.derive(SOURCE_ID, new DocumentDeriveDtoReq(null)));

        assertThat(failure.getErrorCode()).as("AC-05.2 错误码 NO_PERMISSION").isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(failure.getErrorCode().getCode()).as("AC-05.2 HTTP 403").isEqualTo(403);
        assertThat(failure.getMessage()).isEqualTo("无权限查看该文档");
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verifyNoInteractions(documentVersionRepository, documentTagRelRepository);
        assertThat(othersDraft.getStatus()).isEqualTo(DocumentStatus.DRAFT);
    }

    /**
     * AC-05.3（异常流）：TRASH 文档派生 → 409 CONFLICT_STATUS。
     */
    @Test
    @DisplayName("AC-05.3 派生回收站文档：返回 409 CONFLICT_STATUS 与「回收站文档不可派生，请先恢复」，不写任何数据")
    void ac0503_deriveTrashedDocumentConflicts() {
        SecurityContext.set(ME, "tok-me");
        when(documentRepository.findDetailById(SOURCE_ID)).thenReturn(Optional.empty());
        when(documentRepository.findTrashById(SOURCE_ID)).thenReturn(List.<Object[]>of(new Object[] {SOURCE_ID}));

        BusinessException failure = UnitTestSupport.businessFailure(
                () -> documentService.derive(SOURCE_ID, new DocumentDeriveDtoReq(null)));

        assertThat(failure.getErrorCode()).as("AC-05.3 错误码 CONFLICT_STATUS")
                .isEqualTo(ErrorCode.CONFLICT_STATUS);
        assertThat(failure.getErrorCode().getCode()).as("AC-05.3 HTTP 409").isEqualTo(409);
        assertThat(failure.getMessage()).isEqualTo("回收站文档不可派生，请先恢复");
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verifyNoInteractions(documentVersionRepository, documentTagRelRepository);
    }

    /**
     * 构造源文档（作者为 {@link #OTHER}，非当前用户）。
     *
     * @param status 文档状态
     * @return 源文档实体
     */
    private static Document sourceDocument(DocumentStatus status) {
        Document doc = Document.builder()
                .categoryId(20L)
                .title("接口规范")
                .summary("统一接口约定")
                .contentMd(SOURCE_CONTENT)
                .status(status)
                .versionNum(2)
                .priceCents(0)
                .viewCount(8)
                .favoriteCount(1)
                .build();
        doc.setId(SOURCE_ID);
        doc.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0, 0));
        doc.setCreatedBy(OTHER);
        doc.setUpdatedAt(LocalDateTime.of(2026, 9, 10, 12, 0, 0));
        doc.setUpdatedBy(OTHER);
        return doc;
    }
}
