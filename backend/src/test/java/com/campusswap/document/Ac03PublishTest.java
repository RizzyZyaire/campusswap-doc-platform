package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
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
 * US-03 提交发布文档（AC-03.1 / AC-03.2 / AC-03.3）。
 *
 * <p><b>为什么在这一层测</b>：发布是纯状态机动作 —— {@code DRAFT → PUBLISHED} + 版本号自增 +
 * 写 {@code doc_version} + 刷新 {@code updated_at}。判定与落库全部发生在
 * {@code DocumentServiceImpl.publish}，控制器只做权限注解与响应包装
 * （{@code @RequiresPermission("doc:publish")} 由 AC-07.2 的切面层用例统一证明其有效）。
 * 因此这里 mock 仓储，用 {@link ArgumentCaptor} 断言"写进 {@code doc_version} 的那条记录长什么样"，
 * 以及冲突分支下"一行都不许写"。</p>
 *
 * <p>{@code updated_at} 由 JPA 审计监听器（{@code @LastModifiedDate}）填充，纯单元测试里没有
 * Hibernate，因此由 mock 的 {@code saveAndFlush} 应答回调按生产口径补值
 * （见 {@link UnitTestSupport#emulateUpdate}），断言"该 UPDATE 确实发生且时间被刷新"。</p>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m4-http.ps1} 的发布段 ——
 * {@code US03.publish.http/status/versionNum}(200/PUBLISHED/2)、{@code US03.republish.http}(409)、
 * {@code US03.publish-empty-content.http}(409)、{@code US03.publish-others-doc.http}(403)、
 * {@code US03.versions.newest-first}(最新版本 {@code changeType=PUBLISH})、
 * 以及 {@code US03.publish-trashed.http}(409，AC-03.3)；
 * 另有 {@code docs/03-qa-review/TEST_CHECKLIST.md} 的 US-03 手工清单。
 * 本类补的是脚本断言不到的：写进 {@code doc_version} 的快照内容、冲突分支下"一次写库都没有"、
 * 以及回收站分支的中文提示原文。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac03PublishTest {

    /** 属主（当前登录用户）。 */
    private static final Long OWNER = 1001L;

    /** 文档 ID。 */
    private static final Long DOC_ID = 7001L;

    /** 文档的"旧"更新时间，用于断言 updatedAt 被刷新。 */
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2026, 9, 1, 10, 0, 0);

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
     * AC-03.1（正常流）：DRAFT 且是属主 → 发布成功并写 PUBLISH 版本记录。
     */
    @Test
    @DisplayName("AC-03.1 属主发布 DRAFT：状态变 PUBLISHED、versionNum 1→2、doc_version 新增 PUBLISH 记录、updatedAt 刷新")
    void ac0301_publishDraftFlipsStatusAndWritesVersionSnapshot() {
        SecurityContext.set(OWNER, "tok-owner");
        Document doc = draftDocument();
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document saved = invocation.getArgument(0);
            return UnitTestSupport.emulateUpdate(saved);
        });
        when(userService.realNameOf(OWNER)).thenReturn("张三");

        DocumentDetailVo vo = documentService.publish(DOC_ID);

        assertThat(doc.getStatus()).as("AC-03.1 状态变为 PUBLISHED").isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(doc.getVersionNum()).as("AC-03.1 版本号 1→2").isEqualTo(2);
        assertThat(doc.getUpdatedAt()).as("AC-03.1 updated_at 刷新（@LastModifiedDate 随 UPDATE 落库）")
                .isAfter(OLD_UPDATED_AT);
        assertThat(doc.getUpdatedBy()).isEqualTo(OWNER);
        assertThat(doc.getPublishAt()).as("AC-03.1 首次发布时间被写入").isNotNull();
        verify(documentRepository).saveAndFlush(doc);

        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionRepository).saveAndFlush(versionCaptor.capture());
        DocumentVersion version = versionCaptor.getValue();
        assertThat(version.getDocumentId()).isEqualTo(DOC_ID);
        assertThat(version.getVersionNum()).as("AC-03.1 版本记录用的是发布后的版本号").isEqualTo(2);
        assertThat(version.getChangeType()).isEqualTo(ChangeType.PUBLISH);
        assertThat(version.getTitle()).isEqualTo("接口规范 v1");
        assertThat(version.getContentMd()).isEqualTo("# 正文");
        assertThat(version.getChangeRemark()).isEqualTo("提交发布");

        assertThat(vo.getStatus()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(vo.getVersionNum()).isEqualTo(2);
    }

    /**
     * AC-03.2（异常流）：已发布文档再次发布 → 409 CONFLICT_STATUS。
     */
    @Test
    @DisplayName("AC-03.2 已是 PUBLISHED 再次发布：返回 409 CONFLICT_STATUS，状态与版本号不变且不写任何版本记录")
    void ac0302_publishPublishedDocumentConflicts() {
        SecurityContext.set(OWNER, "tok-owner");
        Document doc = draftDocument();
        doc.setStatus(DocumentStatus.PUBLISHED);
        doc.setVersionNum(2);
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.of(doc));

        BusinessException failure = UnitTestSupport.businessFailure(() -> documentService.publish(DOC_ID));

        assertThat(failure.getErrorCode()).as("AC-03.2 错误码 CONFLICT_STATUS")
                .isEqualTo(ErrorCode.CONFLICT_STATUS);
        assertThat(failure.getErrorCode().getCode()).as("AC-03.2 HTTP 409").isEqualTo(409);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(doc.getVersionNum()).as("AC-03.2 版本号不得自增").isEqualTo(2);
        assertThat(doc.getUpdatedAt()).as("AC-03.2 不得触发 UPDATE").isEqualTo(OLD_UPDATED_AT);
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verify(documentVersionRepository, never()).saveAndFlush(any(DocumentVersion.class));
    }

    /**
     * AC-03.3（异常流）：TRASH 文档发布 → 409 +「回收站文档需先恢复为草稿」。
     */
    @Test
    @DisplayName("AC-03.3 回收站文档发布：返回 409 与提示「回收站文档需先恢复为草稿」，不写任何数据")
    void ac0303_publishTrashedDocumentConflictsWithRestoreHint() {
        SecurityContext.set(OWNER, "tok-owner");
        // 进回收站后 deleted = 1，实体查询（@SQLRestriction）看不见它，只能命中原生回收站查询
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.empty());
        when(documentRepository.findTrashById(DOC_ID)).thenReturn(List.<Object[]>of(new Object[] {DOC_ID}));

        BusinessException failure = UnitTestSupport.businessFailure(() -> documentService.publish(DOC_ID));

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFLICT_STATUS);
        assertThat(failure.getErrorCode().getCode()).as("AC-03.3 HTTP 409").isEqualTo(409);
        assertThat(failure.getMessage()).as("AC-03.3 提示文案").isEqualTo("回收站文档需先恢复为草稿");
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verifyNoInteractions(documentVersionRepository);
    }

    /**
     * 构造一份 DRAFT 文档（版本号 1，属主为 {@link #OWNER}）。
     *
     * @return 文档实体
     */
    private static Document draftDocument() {
        Document doc = Document.builder()
                .categoryId(0L)
                .title("接口规范 v1")
                .summary("统一接口约定")
                .contentMd("# 正文")
                .status(DocumentStatus.DRAFT)
                .versionNum(1)
                .priceCents(0)
                .viewCount(0)
                .favoriteCount(0)
                .build();
        doc.setId(DOC_ID);
        doc.setCreatedAt(LocalDateTime.of(2026, 8, 20, 9, 0, 0));
        doc.setCreatedBy(OWNER);
        doc.setUpdatedAt(OLD_UPDATED_AT);
        doc.setUpdatedBy(OWNER);
        return doc;
    }
}
