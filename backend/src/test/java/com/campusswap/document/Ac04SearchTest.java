package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.document.dto.DocumentSearchDtoReq;
import com.campusswap.document.repository.CategoryRepository;
import com.campusswap.document.repository.DocumentFullTextQuery;
import com.campusswap.document.repository.DocumentListQuery;
import com.campusswap.document.repository.DocumentListRow;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.repository.DocumentSearchRow;
import com.campusswap.document.repository.DocumentTagRelRepository;
import com.campusswap.document.repository.DocumentVersionRepository;
import com.campusswap.document.repository.FavoriteRepository;
import com.campusswap.document.repository.TagRepository;
import com.campusswap.document.service.impl.DocumentServiceImpl;
import com.campusswap.document.vo.DocumentVo;
import com.campusswap.entity.enums.DocumentStatus;
import com.campusswap.support.UnitTestSupport;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.UserService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * US-04 检索文档（AC-04.1 / AC-04.2 / AC-04.3）。
 *
 * <p><b>为什么在这一层测</b>：检索的"数据权限口径"完全由 {@code DocumentServiceImpl.search}
 * 决定 —— 它无条件把状态收窄成 {@code [PUBLISHED]}、把作者条件留空，再交给仓储；
 * 仓储（Criteria 投影 / ngram 原生 SQL）只是执行者。因此这里 mock 仓储，
 * 用 {@link ArgumentCaptor} 断言"到底把什么条件交给了仓储"，并用一份<b>仅含他人草稿与他人已发布</b>
 * 的内存数据集充当仓储应答，断言返回结果里只有已发布文档（= 他人的 DRAFT 一定进不来）。
 * 分页/排序/高亮的出参组装同样在这一层完成，也在这里断言。</p>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m4-http.ps1} 的
 * <i>[US-04] search / category / tag</i> 段 —— {@code US04.search.http/total/has-authorName/no-contentMd}
 * （AC-04.1）、{@code US04.search-no-hit.http} + {@code US04.search-no-hit.total}(0)（AC-04.2）、
 * {@code US04.search-excludes-others-draft.total}(0) + {@code US04.detail-others-draft.http}(403) +
 * {@code US04.status-draft.http}(400)（AC-04.3）。
 * 本类补的是脚本断言不到的：交给仓储的动态条件本身（{@code statuses=[PUBLISHED]}、
 * 默认 {@code updated_at desc} 的排序键与方向）、空结果不做作者名回表补齐、全文检索表达式口径。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac04SearchTest {

    /** 当前登录用户。 */
    private static final Long ME = 1001L;

    /** 他人（文档作者）。 */
    private static final Long OTHER = 1002L;

    /** 命中文档的更新时间。 */
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 18, 15, 30, 0);

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
     * AC-04.1（正常流）：关键词「接口」检索第 1 页（pageSize=10）→ 分页数据 + total + 默认 updated_at 倒序。
     */
    @Test
    @DisplayName("AC-04.1 关键词「接口」检索第1页 pageSize=10：返回 title/summary/authorName/updatedAt 与正确 total，默认按 updated_at 倒序")
    void ac0401_searchByKeywordReturnsPagedRowsOrderedByUpdatedAt() {
        SecurityContext.set(ME, "tok-me");
        DocumentSearchDtoReq req = searchRequest("接口", 1, 10);
        DocumentListRow row = new DocumentListRow(9001L, "接口规范 v2", "统一接口约定", 0L, OTHER,
                DocumentStatus.PUBLISHED, 3, 0, 12, 1,
                LocalDateTime.of(2026, 8, 1, 9, 0, 0), UPDATED_AT, OTHER);
        DocumentSearchRow searchRow = new DocumentSearchRow(row, "…并按 <em>接口</em> 规范返回…", "content");
        when(documentRepository.searchFullText(any(DocumentFullTextQuery.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(searchRow), PageRequest.of(0, 10), 1L));
        when(userService.realNamesOf(any())).thenReturn(Map.of(OTHER, "李四"));

        PageVo<DocumentVo> page = documentService.search(req);

        assertThat(page.total()).as("AC-04.1 total 正确").isEqualTo(1L);
        assertThat(page.pageNum()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.list()).hasSize(1);
        DocumentVo vo = page.list().get(0);
        assertThat(vo.getTitle()).as("AC-04.1 含 title").isEqualTo("接口规范 v2");
        assertThat(vo.getSummary()).as("AC-04.1 含 summary").isEqualTo("统一接口约定");
        assertThat(vo.getAuthorName()).as("AC-04.1 含 authorName（批量补齐）").isEqualTo("李四");
        assertThat(vo.getUpdatedAt()).as("AC-04.1 含 updatedAt").isEqualTo(TimeUtil.format(UPDATED_AT));
        assertThat(vo.getHighlight()).as("AC-04.1 全文检索分支带回命中片段").contains("<em>接口</em>");
        assertThat(vo.getMatchedIn()).isEqualTo("content");
        assertThat(vo.getCategoryName()).isEqualTo("未分类");

        // 交给仓储的条件与排序：第 1 页（0 基）、pageSize=10、默认 updated_at 倒序
        ArgumentCaptor<DocumentFullTextQuery> queryCaptor = ArgumentCaptor.forClass(DocumentFullTextQuery.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).searchFullText(queryCaptor.capture(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        Sort.Order order = pageable.getSort().getOrderFor("updatedAt");
        assertThat(order).as("AC-04.1 默认排序键必须是 updatedAt").isNotNull();
        assertThat(order.getDirection()).as("AC-04.1 默认倒序").isEqualTo(Sort.Direction.DESC);
        assertThat(queryCaptor.getValue().expression())
                .as("AC-04.1 「接口」两字走 ngram 布尔表达式").isEqualTo("+接口*");
    }

    /**
     * AC-04.2（异常流）：无匹配关键词 → 200 + 空列表 + total=0（不是 404/500）。
     */
    @Test
    @DisplayName("AC-04.2 关键词无匹配（zzz-not-exist）：返回空列表 + total=0 且不抛异常（不是 404/500），也不做作者名回表")
    void ac0402_searchWithoutMatchReturnsEmptyPageNotError() {
        SecurityContext.set(ME, "tok-me");
        DocumentSearchDtoReq req = searchRequest("zzz-not-exist", 1, 10);
        when(documentRepository.searchFullText(any(DocumentFullTextQuery.class), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        // 不抛 BusinessException ⇒ 全局异常处理器不会翻成 404/500，响应就是 200 + 空分页
        PageVo<DocumentVo> page = documentService.search(req);

        assertThat(page).isNotNull();
        assertThat(page.list()).as("AC-04.2 空列表").isEmpty();
        assertThat(page.total()).as("AC-04.2 total=0").isZero();
        assertThat(page.pageNum()).as("AC-04.2 分页元信息保留").isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(10);
        verify(userService, never()).realNamesOf(any());
    }

    /**
     * AC-04.3（异常流）：检索结果不含他人的 DRAFT 文档（数据权限过滤在 Service 层完成）。
     */
    @Test
    @DisplayName("AC-04.3 检索不含他人 DRAFT：交给仓储的状态条件被收窄为 [PUBLISHED]，结果里他人的草稿进不来，显式索要 DRAFT 直接 400")
    void ac0403_searchExcludesOthersDraftByServiceLevelScope() {
        SecurityContext.set(ME, "tok-me");
        DocumentSearchDtoReq req = searchRequest("", 1, 10);

        // 仓储应答：按 Service 交下来的状态条件对数据集过滤（数据集里同时有他人的草稿与已发布）
        DocumentListRow othersDraft = new DocumentListRow(9001L, "他人的草稿", "不该被检索到", 0L, OTHER,
                DocumentStatus.DRAFT, 1, 0, 0, 0,
                LocalDateTime.of(2026, 9, 1, 9, 0, 0), LocalDateTime.of(2026, 9, 20, 9, 0, 0), OTHER);
        DocumentListRow othersPublished = new DocumentListRow(9002L, "他人的已发布", "可以检索到", 0L, OTHER,
                DocumentStatus.PUBLISHED, 2, 0, 5, 0,
                LocalDateTime.of(2026, 9, 2, 9, 0, 0), LocalDateTime.of(2026, 9, 19, 9, 0, 0), OTHER);
        List<DocumentListRow> dataset = List.of(othersDraft, othersPublished);
        when(documentRepository.search(any(DocumentListQuery.class), any(Pageable.class))).thenAnswer(invocation -> {
            DocumentListQuery query = invocation.getArgument(0);
            Pageable pageable = invocation.getArgument(1);
            List<DocumentListRow> hit = new ArrayList<>();
            for (DocumentListRow candidate : dataset) {
                if (query.statuses().contains(candidate.status())) {
                    hit.add(candidate);
                }
            }
            return new PageImpl<>(hit, pageable, hit.size());
        });
        when(userService.realNamesOf(any())).thenReturn(Map.of(OTHER, "李四"));

        PageVo<DocumentVo> page = documentService.search(req);

        assertThat(page.list()).extracting(DocumentVo::getId)
                .as("AC-04.3 结果里只有他人的已发布文档，他人的草稿 9001 不出现")
                .containsExactly("9002");
        assertThat(page.list()).extracting(DocumentVo::getStatus)
                .containsExactly(DocumentStatus.PUBLISHED);
        assertThat(page.total()).as("AC-04.3 草稿不计入 total").isEqualTo(1L);

        ArgumentCaptor<DocumentListQuery> queryCaptor = ArgumentCaptor.forClass(DocumentListQuery.class);
        verify(documentRepository).search(queryCaptor.capture(), any(Pageable.class));
        DocumentListQuery handedToRepository = queryCaptor.getValue();
        assertThat(handedToRepository.statuses())
                .as("AC-04.3 Service 强制把状态收窄为 [PUBLISHED]（调用方无法放宽）")
                .containsExactly(DocumentStatus.PUBLISHED);
        assertThat(handedToRepository.authorId())
                .as("AC-04.3 检索是全平台可见范围（不是仅本人），可见性靠状态收窄实现").isNull();

        // 显式请求 status=DRAFT 也不放行：在进仓储之前就 400
        req.setStatus("DRAFT");
        BusinessException failure = UnitTestSupport.businessFailure(() -> documentService.search(req));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(failure.getMessage()).contains("仅支持查询已发布文档");
        verify(documentRepository).search(any(DocumentListQuery.class), any(Pageable.class));
    }

    /**
     * 构造检索入参。
     *
     * @param keyword  关键词（空串 = 走 Criteria 分支）
     * @param pageNum  页码（从 1 起）
     * @param pageSize 每页条数
     * @return 检索入参
     */
    private static DocumentSearchDtoReq searchRequest(String keyword, int pageNum, int pageSize) {
        DocumentSearchDtoReq req = new DocumentSearchDtoReq();
        req.setKeyword(keyword);
        req.setPageNum(pageNum);
        req.setPageSize(pageSize);
        return req;
    }
}
