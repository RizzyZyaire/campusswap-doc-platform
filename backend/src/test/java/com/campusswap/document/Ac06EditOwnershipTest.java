package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.document.dto.DocumentUpdateDtoReq;
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
 * US-06 编辑自己的文档（含越权防护）（AC-06.1 / AC-06.2 / AC-06.3）。
 *
 * <p><b>为什么在这一层测</b>：编辑接口的"归属判定"是防平行越权（IDOR）的第四道关卡，
 * 它是 {@code DocumentServiceImpl.assertOwnerOrManage} 的职责 —— 控制器只有
 * {@code @RequiresPermission("doc:edit")}（谁都可能拥有该权限点），真正的"只能改自己的"只有 Service 知道。
 * 因此 AC-06.2 必须在这里测：断言 403 {@code NO_PERMISSION} 之外，还要断言<b>实体逐字段未被改动</b>
 * 且 {@code saveAndFlush} / {@code doc_version} 写库次数为 0 —— 这才是"防 IDOR"的实质。</p>
 *
 * <p>{@code updated_by/updated_at} 由 JPA 审计监听器填充（{@code @LastModifiedBy/@LastModifiedDate}），
 * 纯单元测试里由 mock 的 {@code saveAndFlush} 应答回调按生产口径补值
 * （见 {@link UnitTestSupport#emulateUpdate}），并由 {@code JpaAuditConfig#auditorAware} 决定操作人。</p>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m4-http.ps1} 的编辑段 ——
 * {@code US06.edit.http/versionNum}(200、2→3)、{@code US06.edit.priceCents}、
 * {@code US06.edit-others-doc.http}(403) + {@code US06.content-unchanged-after-403}（AC-06.2）、
 * {@code US06.edit-archived.http}(409，AC-06.3)、{@code US06.edit-stale-version.http}(409) +
 * {@code US06.edit-stale-content-unchanged}、{@code US06.edit-no-version.http}(400)；
 * 另有 {@code docs/03-qa-review/TEST_CHECKLIST.md} 的 US-06 清单。
 * 本类补的是脚本断言不到的：{@code updated_by} 的取值来源、{@code doc_version} 快照内容、
 * 以及越权/只读分支下"主表与版本表零写库"。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac06EditOwnershipTest {

    /** 当前登录用户。 */
    private static final Long ME = 1001L;

    /** 他人（文档属主）。 */
    private static final Long OTHER = 1002L;

    /** 文档 ID。 */
    private static final Long DOC_ID = 9001L;

    /** 文档编辑前的时间戳。 */
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2026, 9, 5, 10, 0, 0);

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
     * AC-06.1（正常流）：属主编辑 DRAFT → 版本号自增、审计列刷新、写 EDIT 版本记录。
     */
    @Test
    @DisplayName("AC-06.1 属主编辑自己的 DRAFT：保存成功、versionNum 2→3、updatedBy/updatedAt 刷新、doc_version 新增 EDIT 记录")
    void ac0601_ownerEditsOwnDraftBumpsVersionAndAuditColumns() {
        SecurityContext.set(ME, "tok-me");
        Document doc = document(DocumentStatus.DRAFT, ME);
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document saved = invocation.getArgument(0);
            return UnitTestSupport.emulateUpdate(saved);
        });
        when(userService.realNameOf(ME)).thenReturn("张三");

        DocumentDetailVo vo = documentService.update(DOC_ID, updateRequest(2, "新标题", "新摘要", "# 新正文", 100));

        assertThat(doc.getVersionNum()).as("AC-06.1 版本号 2→3").isEqualTo(3);
        assertThat(doc.getUpdatedBy()).as("AC-06.1 updated_by 刷新为当前用户").isEqualTo(ME);
        assertThat(doc.getUpdatedAt()).as("AC-06.1 updated_at 刷新").isAfter(OLD_UPDATED_AT);
        assertThat(doc.getTitle()).isEqualTo("新标题");
        assertThat(doc.getSummary()).isEqualTo("新摘要");
        assertThat(doc.getContentMd()).isEqualTo("# 新正文");
        assertThat(doc.getPriceCents()).isEqualTo(100);
        assertThat(doc.getStatus()).as("AC-06.1 编辑不改状态").isEqualTo(DocumentStatus.DRAFT);
        verify(documentRepository).saveAndFlush(doc);

        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(documentVersionRepository).saveAndFlush(versionCaptor.capture());
        DocumentVersion version = versionCaptor.getValue();
        assertThat(version.getDocumentId()).isEqualTo(DOC_ID);
        assertThat(version.getVersionNum()).as("AC-06.1 版本快照记的是编辑后的版本号").isEqualTo(3);
        assertThat(version.getChangeType()).isEqualTo(ChangeType.EDIT);
        assertThat(version.getTitle()).isEqualTo("新标题");
        assertThat(version.getContentMd()).isEqualTo("# 新正文");
        // 标签差值为空也要清空旧绑定（先删后插）
        verify(documentTagRelRepository).deleteByDocumentId(DOC_ID);

        assertThat(vo.getVersionNum()).isEqualTo(3);
        assertThat(vo.getUpdatedAt()).isEqualTo(TimeUtil.format(doc.getUpdatedAt()));
        assertThat(vo.getUpdatedBy()).isEqualTo("1001");
        assertThat(vo.getCanEdit()).as("AC-06.1 属主可编辑").isTrue();
    }

    /**
     * AC-06.2（异常流）：改他人文档 → 403 NO_PERMISSION，且库中该文档内容完全不变（防 IDOR）。
     */
    @Test
    @DisplayName("AC-06.2 越权修改他人文档：返回 403 NO_PERMISSION，且库中该文档内容完全不变（零写库，防平行越权 IDOR）")
    void ac0602_editOthersDocumentForbiddenWithoutTouchingRow() {
        SecurityContext.set(ME, "tok-me");
        Document doc = document(DocumentStatus.DRAFT, OTHER);
        String before = snapshot(doc);
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.of(doc));
        when(permissionCacheService.hasPermission(ME, "doc:manage")).thenReturn(false);

        BusinessException failure = UnitTestSupport.businessFailure(
                () -> documentService.update(DOC_ID, updateRequest(2, "越权改标题", "越权摘要", "# 越权正文", 999)));

        assertThat(failure.getErrorCode()).as("AC-06.2 错误码 NO_PERMISSION").isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(failure.getErrorCode().getCode()).as("AC-06.2 HTTP 403").isEqualTo(403);
        assertThat(failure.getMessage()).isEqualTo("无权限修改该文档");

        // 库里那一行逐字段没变（含标题/摘要/正文/版本号/价格/审计列/状态）
        assertThat(snapshot(doc)).as("AC-06.2 被越权的文档必须一个字段都没改").isEqualTo(before);
        assertThat(doc.getTitle()).isEqualTo("原标题");
        assertThat(doc.getContentMd()).isEqualTo("# 原文");
        assertThat(doc.getVersionNum()).isEqualTo(2);
        assertThat(doc.getUpdatedAt()).isEqualTo(OLD_UPDATED_AT);
        assertThat(doc.getUpdatedBy()).isEqualTo(OTHER);

        // 零写库：既不 UPDATE 主表，也不追加版本、也不动标签关系
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verify(documentVersionRepository, never()).saveAndFlush(any(DocumentVersion.class));
        verify(documentTagRelRepository, never()).deleteByDocumentId(any());
    }

    /**
     * AC-06.3（异常流）：ARCHIVED 文档只读 → 409 +「归档文档为只读」。
     */
    @Test
    @DisplayName("AC-06.3 编辑已归档文档：返回 409 与提示「归档文档为只读」，版本号与内容不变")
    void ac0603_editArchivedDocumentConflictsAsReadOnly() {
        SecurityContext.set(ME, "tok-me");
        Document doc = document(DocumentStatus.ARCHIVED, ME);
        String before = snapshot(doc);
        when(documentRepository.findDetailById(DOC_ID)).thenReturn(Optional.of(doc));

        BusinessException failure = UnitTestSupport.businessFailure(
                () -> documentService.update(DOC_ID, updateRequest(2, "改归档稿", "摘要", "# 正文", 0)));

        assertThat(failure.getErrorCode()).as("AC-06.3 错误码 CONFLICT_STATUS")
                .isEqualTo(ErrorCode.CONFLICT_STATUS);
        assertThat(failure.getErrorCode().getCode()).as("AC-06.3 HTTP 409").isEqualTo(409);
        assertThat(failure.getMessage()).as("AC-06.3 提示文案").contains("归档文档为只读");
        assertThat(snapshot(doc)).as("AC-06.3 只读文档不得被改动").isEqualTo(before);
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verify(documentVersionRepository, never()).saveAndFlush(any(DocumentVersion.class));
    }

    /**
     * 构造文档（版本号 2）。
     *
     * @param status  状态
     * @param ownerId 属主 ID
     * @return 文档实体
     */
    private static Document document(DocumentStatus status, Long ownerId) {
        Document doc = Document.builder()
                .categoryId(0L)
                .title("原标题")
                .summary("原摘要")
                .contentMd("# 原文")
                .status(status)
                .versionNum(2)
                .priceCents(0)
                .viewCount(3)
                .favoriteCount(0)
                .build();
        doc.setId(DOC_ID);
        doc.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0, 0));
        doc.setCreatedBy(ownerId);
        doc.setUpdatedAt(OLD_UPDATED_AT);
        doc.setUpdatedBy(ownerId);
        return doc;
    }

    /**
     * 构造编辑入参（{@code id} 与路径一致、版本号与库中当前值一致，否则会先撞 400/409）。
     *
     * @param versionNum 版本号
     * @param title      标题
     * @param summary    摘要
     * @param contentMd  正文
     * @param priceCents 价格标记
     * @return 编辑入参
     */
    private static DocumentUpdateDtoReq updateRequest(int versionNum, String title, String summary,
                                                      String contentMd, int priceCents) {
        return new DocumentUpdateDtoReq("9001", versionNum, title, summary, contentMd, "0", List.of(), priceCents);
    }

    /**
     * 文档的可持久化字段快照（用于断言"一个字段都没改"）。
     *
     * @param doc 文档实体
     * @return 快照字符串
     */
    private static String snapshot(Document doc) {
        return String.join("|",
                String.valueOf(doc.getId()),
                doc.getTitle(),
                String.valueOf(doc.getSummary()),
                String.valueOf(doc.getContentMd()),
                String.valueOf(doc.getCategoryId()),
                String.valueOf(doc.getStatus()),
                String.valueOf(doc.getVersionNum()),
                String.valueOf(doc.getPriceCents()),
                String.valueOf(doc.getViewCount()),
                String.valueOf(doc.getFavoriteCount()),
                String.valueOf(doc.getDerivedFromId()),
                String.valueOf(doc.getRejectReason()),
                String.valueOf(doc.getPublishAt()),
                String.valueOf(doc.getCreatedAt()),
                String.valueOf(doc.getCreatedBy()),
                String.valueOf(doc.getUpdatedAt()),
                String.valueOf(doc.getUpdatedBy()),
                String.valueOf(doc.getDeleted()));
    }
}
