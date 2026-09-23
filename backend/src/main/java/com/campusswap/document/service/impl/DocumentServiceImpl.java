package com.campusswap.document.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.RedisKeys;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.document.dto.DocumentCreateDtoReq;
import com.campusswap.document.dto.DocumentDeriveDtoReq;
import com.campusswap.document.dto.DocumentDestroyDtoReq;
import com.campusswap.document.dto.DocumentManageDtoReq;
import com.campusswap.document.dto.DocumentMineDtoReq;
import com.campusswap.document.dto.DocumentSearchDtoReq;
import com.campusswap.document.dto.DocumentSort;
import com.campusswap.document.dto.DocumentTrashDtoReq;
import com.campusswap.document.dto.DocumentUpdateDtoReq;
import com.campusswap.document.dto.FavoritePageDtoReq;
import com.campusswap.document.repository.CategoryRepository;
import com.campusswap.document.repository.DocumentColumns;
import com.campusswap.document.repository.DocumentFullTextQuery;
import com.campusswap.document.repository.DocumentListQuery;
import com.campusswap.document.repository.DocumentListRow;
import com.campusswap.document.repository.DocumentManageQuery;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.repository.DocumentSearchRow;
import com.campusswap.document.repository.DocumentTagRelRepository;
import com.campusswap.document.repository.DocumentVersionRepository;
import com.campusswap.document.repository.FavoriteRepository;
import com.campusswap.document.repository.TagRepository;
import com.campusswap.document.service.DocumentService;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVersionVo;
import com.campusswap.document.vo.DocumentVo;
import com.campusswap.document.vo.FavoriteVo;
import com.campusswap.document.vo.TagVo;
import com.campusswap.common.api.PageDtoReq;
import com.campusswap.entity.Category;
import com.campusswap.entity.Document;
import com.campusswap.entity.DocumentTagRel;
import com.campusswap.entity.DocumentVersion;
import com.campusswap.entity.Favorite;
import com.campusswap.entity.Tag;
import com.campusswap.entity.enums.ChangeType;
import com.campusswap.entity.enums.DocumentStatus;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.UserService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 文档服务实现（US-02 ~ US-06）。
 *
 * <p><b>性能口径（ARCHITECTURE §10.5 + 课件 3.1）</b>：</p>
 * <ul>
 *   <li>列表一律走 DTO 构造器投影（{@link DocumentListRow}），<b>不读 {@code content_md}</b>；</li>
 *   <li>作者名 / 分类名用 {@code IN} 批量补齐 + 内存 Map，禁止循环查库；</li>
 *   <li>分类「含子孙」用一次前缀查询取回 ID 集合，再以 {@code IN} 参与主查询；</li>
 *   <li>SQL 条数为<b>常数</b>，与 {@code pageSize}、数据量无关（实测见 M4-CLOSURE.md）。</li>
 * </ul>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    /** 每篇文档最多标签数（BR-14）。 */
    private static final int MAX_TAGS = 5;

    /** 未分类的占位名称（VO 展示用，不落库）。 */
    private static final String UNCATEGORIZED = "未分类";

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentTagRelRepository documentTagRelRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final FavoriteRepository favoriteRepository;
    private final UserService userService;
    private final PermissionCacheService permissionCacheService;
    private final StringRedisTemplate stringRedisTemplate;

    // ─────────────────────────── 列表 ───────────────────────────

    /**
     * 检索已发布文档。
     *
     * <p><b>关键词分支（API_SPECIFICATION §9.3）</b>：</p>
     * <ul>
     *   <li>关键词非空且去空格后长度 ≥ 2 → 走全文检索分支（{@code MATCH(...) AGAINST(... IN BOOLEAN MODE)}，
     *       原生 SQL + ngram 索引 {@code ft_doc_search}），出参带 {@code highlight} / {@code matchedIn}；</li>
     *   <li>关键词为空（或不足 2 字、剥离后无可用词）→ 保持既有 Criteria 分支<b>完全不变</b>
     *       （SQL 预算 3~5 条不因此变化）。</li>
     * </ul>
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVo> search(DocumentSearchDtoReq req) {
        Long me = SecurityContext.requireUserId();
        if (StringUtils.hasText(req.getStatus()) && !DocumentStatus.PUBLISHED.name().equalsIgnoreCase(req.getStatus().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本接口仅支持查询已发布文档");
        }
        Long categoryId = IdUtil.toLongOrNull(req.getCategoryId(), "分类ID");
        Collection<Long> categoryIds = categoryIdsIncludingDescendants(categoryId);
        List<Long> tagIds = parseIds(req.getTagIds(), "标签ID");
        LocalDateTime start = TimeUtil.parse(req.getStartTime(), "开始时间");
        LocalDateTime end = TimeUtil.parse(req.getEndTime(), "结束时间");
        TimeUtil.assertRange(start, end);
        DocumentSort sort = DocumentSort.from(req.getSort());

        if (DocumentFullTextQuery.supports(req.getKeyword())) {
            DocumentFullTextQuery fullTextQuery = DocumentFullTextQuery.of(req.getKeyword(), categoryIds, tagIds,
                    start, end);
            // 排序由原生 SQL 负责（相关度 / 更新时间 / 发布时间 / 阅读量），Pageable 只承载分页
            Page<DocumentSearchRow> page = documentRepository.searchFullText(fullTextQuery,
                    req.toPageable(sort.sort()));
            return buildSearchPage(page, me, true);
        }
        if (sort.isRelevance()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "排序方式 relevance 仅在关键词检索（2个字及以上）时可用");
        }
        DocumentListQuery query = new DocumentListQuery(req.getKeyword(), List.of(DocumentStatus.PUBLISHED),
                null, categoryIds, tagIds, start, end);
        Page<DocumentListRow> page = documentRepository.search(query, req.toPageable(sort.sort()));
        return toPageVo(page, me);
    }

    /**
     * 治理用全状态列表（权限 {@code doc:manage}，API_SPECIFICATION §9.2）。
     *
     * <p>与检索接口的差别：</p>
     * <ul>
     *   <li>不加「仅已发布」限制；{@code status=TRASH} 与「空 = 全部」都能看见 {@code deleted = 1} 的回收站行
     *       —— 因此整条链路走原生 SQL 绕过 {@code @SQLRestriction("deleted = 0")}；</li>
     *   <li>筛选维度是 状态 + 关键词 + 分类（含子孙）+ <b>拟稿人</b>（{@code created_by}）+ 时间区间 + 排序
     *       （契约原文的 {@code unitId}「发文单位」已由作者更正：本表没有单位列，改为 {@code authorId}）。</li>
     * </ul>
     *
     * <p>关键词沿用检索页同一套口径：≥2 字走 ngram 全文检索（能搜正文，出参带 {@code highlight} /
     * {@code matchedIn}）；不足 2 字或剥离后无可用词时回落 {@code title/summary} 的 LIKE。</p>
     *
     * <p>SQL 预算：1（原生分页）+ 1（作者名批量）+ 1（分类名批量）= 3 条，与 {@code ARCHITECTURE §10.5} 一致；
     * {@code pageSize} 变化不影响条数。</p>
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVo> manage(DocumentManageDtoReq req) {
        Long me = SecurityContext.requireUserId();
        // 空 = 全部状态（含回收站）；非法取值沿用既有 400 文案
        String status = StringUtils.hasText(req.getStatus()) ? parseStatus(req.getStatus()).name()
                : DocumentManageQuery.ALL_STATUSES;
        Long categoryId = IdUtil.toLongOrNull(req.getCategoryId(), "分类ID");
        Collection<Long> categoryIds = categoryIdsIncludingDescendants(categoryId);
        Long authorId = IdUtil.toLongOrNull(req.getAuthorId(), "拟稿人ID");
        LocalDateTime start = TimeUtil.parse(req.getStartTime(), "开始时间");
        LocalDateTime end = TimeUtil.parse(req.getEndTime(), "结束时间");
        TimeUtil.assertRange(start, end);
        DocumentSort sort = DocumentSort.from(req.getSort());

        boolean fullText = DocumentFullTextQuery.supports(req.getKeyword());
        if (sort.isRelevance() && !fullText) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "排序方式 relevance 仅在关键词检索（2个字及以上）时可用");
        }
        String keyword = req.getKeyword() == null ? "" : req.getKeyword().trim();
        DocumentManageQuery query = new DocumentManageQuery(status,
                fullText ? DocumentFullTextQuery.expression(DocumentFullTextQuery.terms(keyword)) : "",
                fullText ? DocumentFullTextQuery.terms(keyword) : List.of(),
                fullText ? "" : keyword, categoryIds, authorId, start, end);
        Page<DocumentSearchRow> page = documentRepository.searchManage(query, req.toPageable(sort.sort()));
        // canEdit 恒为 false：治理列表是全平台视角，管理员治理他人文档走审核/归档接口（§4.7.1），
        // 行内「编辑」入口由「我的文档」页承担（作者更正稿明确要求 canEdit 恒 false）
        return buildSearchPage(page, me, false);
    }

    /**
     * 我的文档（作者条件强制注入，防越权）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVo> mine(DocumentMineDtoReq req) {
        Long me = SecurityContext.requireUserId();
        List<DocumentStatus> statuses = new ArrayList<>();
        if (StringUtils.hasText(req.getStatus())) {
            DocumentStatus status = parseStatus(req.getStatus());
            if (status == DocumentStatus.TRASH) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文档状态取值非法");
            }
            statuses.add(status);
        } else {
            statuses.addAll(List.of(DocumentStatus.DRAFT, DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED));
        }
        DocumentListQuery query = new DocumentListQuery(req.getKeyword(), statuses, me, null, null, null, null);
        Page<DocumentListRow> page = documentRepository.search(query, req.toPageable(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        return toPageVo(page, me);
    }

    /**
     * 回收站列表：普通用户只看自己的，管理员（{@code doc:manage}）看全部。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVo> trash(DocumentTrashDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Long authorFilter = hasPermission(me, "doc:manage") ? 0L : me;
        String keyword = req.getKeyword() == null ? "" : req.getKeyword().trim();
        Page<Object[]> page = documentRepository.findTrashPage(authorFilter, keyword, req.toPageableUnsorted());
        List<DocumentListRow> rows = page.getContent().stream().map(DocumentListRow::of).toList();
        return buildPage(rows, page.getTotalElements(), page.getNumber() + 1, page.getSize(), me, false);
    }

    /**
     * 我的收藏列表（按收藏时间倒序）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVo> favorites(FavoritePageDtoReq req) {
        Long me = SecurityContext.requireUserId();
        String keyword = req.getKeyword() == null ? "" : req.getKeyword().trim();
        Page<Object[]> page = documentRepository.findFavoritePage(me, keyword, req.toPageableUnsorted());
        List<DocumentListRow> rows = page.getContent().stream().map(DocumentListRow::of).toList();
        return buildPage(rows, page.getTotalElements(), page.getNumber() + 1, page.getSize(), me, false);
    }

    // ─────────────────────────── 写操作 ───────────────────────────

    /**
     * 新建草稿。
     *
     * @param req 新建入参
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo create(DocumentCreateDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Long categoryId = resolveCategoryId(req.categoryId(), true);
        List<Long> tagIds = validateTagIds(req.tagIds());

        Document doc = Document.builder()
                .categoryId(categoryId)
                .title(req.title().trim())
                .summary(blankToNull(req.summary()))
                .contentMd(req.contentMd())
                .status(DocumentStatus.DRAFT)
                .versionNum(1)
                .priceCents(req.priceCents() == null ? 0 : req.priceCents())
                .viewCount(0)
                .favoriteCount(0)
                .build();
        documentRepository.saveAndFlush(doc);
        writeVersion(doc, ChangeType.CREATE, "创建文档");
        bindTags(doc.getId(), tagIds, List.of());
        log.info("新建文档: id={}, title={}, author={}", doc.getId(), doc.getTitle(), me);
        return buildDetail(doc, me, false);
    }

    /**
     * 编辑文档：版本 +1、写版本快照、标签差值重绑。
     *
     * @param id  文档 ID
     * @param req 编辑入参
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo update(Long id, DocumentUpdateDtoReq req) {
        Long me = SecurityContext.requireUserId();
        if (!String.valueOf(id).equals(req.id().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求体中的文档ID与路径不一致");
        }
        Document doc = documentRepository.findDetailById(id).orElse(null);
        if (doc == null) {
            if (!documentRepository.findTrashById(id).isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT_STATUS, "文档已在回收站中，请先恢复");
            }
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除");
        }
        assertOwnerOrManage(doc, me, "无权限修改该文档");
        if (doc.getStatus() == DocumentStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "归档文档为只读，请先恢复上架");
        }
        if (doc.getStatus() == DocumentStatus.TRASH) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "文档已在回收站中，请先恢复");
        }

        Long categoryId = resolveCategoryId(req.categoryId(), false);
        List<Long> newTagIds = req.tagIds() == null ? null : validateTagIds(req.tagIds());

        doc.setTitle(req.title().trim());
        doc.setSummary(blankToNull(req.summary()));
        doc.setContentMd(req.contentMd());
        doc.setCategoryId(categoryId);
        doc.setPriceCents(req.priceCents() == null ? doc.getPriceCents() : req.priceCents());
        doc.setVersionNum(doc.getVersionNum() + 1);
        documentRepository.saveAndFlush(doc);

        writeVersion(doc, ChangeType.EDIT, null);
        if (newTagIds != null) {
            List<Long> oldTagIds = documentQueryTagIds(id);
            bindTags(id, newTagIds, oldTagIds);
        }
        return buildDetail(doc, me, false);
    }

    /**
     * 提交发布（DRAFT → PUBLISHED）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo publish(Long id) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentRepository.findDetailById(id).orElse(null);
        if (doc == null) {
            if (!documentRepository.findTrashById(id).isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT_STATUS, "回收站文档需先恢复为草稿");
            }
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除");
        }
        assertOwnerOrManage(doc, me, "无权限发布该文档");
        if (doc.getStatus() != DocumentStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "文档当前状态不允许发布");
        }
        if (!StringUtils.hasText(doc.getContentMd())) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "文档当前状态不允许发布");
        }
        doc.setStatus(DocumentStatus.PUBLISHED);
        doc.setVersionNum(doc.getVersionNum() + 1);
        if (doc.getPublishAt() == null) {
            doc.setPublishAt(LocalDateTime.now());
        }
        doc.setRejectReason(null);
        documentRepository.saveAndFlush(doc);
        writeVersion(doc, ChangeType.PUBLISH, "提交发布");
        log.info("文档发布: id={}, version={}", id, doc.getVersionNum());
        return buildDetail(doc, me, false);
    }

    /**
     * 派生新草稿（源文档不被修改）。
     *
     * @param id  源文档 ID
     * @param req 派生入参
     * @return 新文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo derive(Long id, DocumentDeriveDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Document source = documentRepository.findDetailById(id).orElse(null);
        if (source == null) {
            // 源文档在回收站里：按 US-05 AC-05.3 给 409（而不是含混的 404）
            if (!documentRepository.findTrashById(id).isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT_STATUS, "回收站文档不可派生，请先恢复");
            }
            throw new BusinessException(ErrorCode.NOT_FOUND, "源文档不存在或已被删除");
        }
        assertVisible(source, me);
        if (source.getStatus() == DocumentStatus.TRASH) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "回收站文档不可派生，请先恢复");
        }
        String title = req == null || !StringUtils.hasText(req.title())
                ? source.getTitle() + "（副本）" : req.title().trim();
        if (title.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档标题不能为空且不超过128字");
        }

        Document doc = Document.builder()
                .categoryId(source.getCategoryId())
                .title(title)
                .summary(source.getSummary())
                .contentMd(source.getContentMd())
                .status(DocumentStatus.DRAFT)
                .versionNum(1)
                .priceCents(source.getPriceCents())
                .viewCount(0)
                .favoriteCount(0)
                .derivedFromId(source.getId())
                .build();
        documentRepository.saveAndFlush(doc);
        writeVersion(doc, ChangeType.CREATE, "由文档 " + source.getId() + " 派生");
        log.info("派生文档: 新 id={}, 源 id={}, 操作人={}", doc.getId(), source.getId(), me);
        return buildDetail(doc, me, false);
    }

    /**
     * 删除文档（进回收站）。
     *
     * @param id 文档 ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentRepository.findDetailById(id).orElse(null);
        if (doc == null) {
            // 看不见有两种可能：不存在，或已经在回收站（回收站行对实体查询不可见）
            if (!documentRepository.findTrashById(id).isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT_STATUS, "文档已在回收站中");
            }
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除");
        }
        assertOwnerOrManage(doc, me, "无权限删除该文档");
        if (doc.getStatus() == DocumentStatus.TRASH) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "文档已在回收站中");
        }
        // 版本号在同一事务里 +1（与删除快照的版本号保持一致，见 DocumentRepository#moveToTrash）
        int version = doc.getVersionNum() + 1;
        documentRepository.moveToTrash(id, me);
        DocumentVersion snapshot = DocumentVersion.builder()
                .documentId(id)
                .versionNum(version)
                .title(doc.getTitle())
                .contentMd(doc.getContentMd())
                .changeType(ChangeType.DELETE)
                .build();
        documentVersionRepository.saveAndFlush(snapshot);
        log.info("文档进回收站: id={}, 版本={}, 操作人={}", id, version, me);
    }

    /**
     * 从回收站恢复（TRASH → DRAFT）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @Override
    @Transactional
    public DocumentDetailVo restore(Long id) {
        Long me = SecurityContext.requireUserId();
        Object[] row = requireTrashRow(id, "仅回收站中的文档可以恢复");
        Long authorId = DocumentColumns.longVal(row, DocumentColumns.CREATED_BY);
        if (!java.util.Objects.equals(authorId, me) && !hasPermission(me, "doc:manage")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "无权限恢复该文档");
        }
        documentRepository.restoreFromTrash(id, me);
        Document doc = getDocumentOrThrow(id);
        writeVersion(doc, ChangeType.RESTORE, "从回收站恢复");
        log.info("文档恢复: id={}, 版本={}, 操作人={}", id, doc.getVersionNum(), me);
        return buildDetail(doc, me, false);
    }

    /**
     * 彻底删除（物理删除主表 + 版本 + 收藏 + 标签关系，并回收标签计数）。
     *
     * @param id  文档 ID
     * @param req 二次确认入参
     */
    @Override
    @Transactional
    public void destroy(Long id, DocumentDestroyDtoReq req) {
        Long me = SecurityContext.requireUserId();
        Object[] row = requireTrashRow(id, "仅回收站中的文档可以彻底删除");
        Long authorId = DocumentColumns.longVal(row, DocumentColumns.CREATED_BY);
        if (!java.util.Objects.equals(authorId, me) && !hasPermission(me, "doc:manage")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "无权限彻底删除该文档");
        }
        List<Long> tagIds = documentQueryTagIds(id);
        documentTagRelRepository.deleteByDocumentId(id);
        if (!tagIds.isEmpty()) {
            tagRepository.decreaseUseCount(tagIds);
        }
        documentVersionRepository.deleteByDocumentIdPhysically(id);
        favoriteRepository.deleteByDocumentIdPhysically(id);
        documentRepository.destroyPhysically(id);
        log.warn("文档彻底删除: id={}, 操作人={}, 清理标签={}", id, me, tagIds.size());
    }

    // ─────────────────────────── 详情与版本 ───────────────────────────

    /**
     * 文档详情：可见性校验 + 已发布文档阅读量去重累加。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @Override
    public DocumentDetailVo detail(Long id) {
        Long me = SecurityContext.requireUserId();
        Document doc = documentRepository.findDetailById(id).orElse(null);
        if (doc == null) {
            // 回收站文档对实体查询不可见（deleted = 1），但可见性规则里 TRASH 对作者/管理员依然可见（§4.6.5），
            // 因此这里回退到原生行；既不是"不存在"、也不是"无权看"，才给 404。
            List<Object[]> trash = documentRepository.findTrashById(id);
            if (trash.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除");
            }
            Object[] row = trash.get(0);
            Long authorId = DocumentColumns.longVal(row, DocumentColumns.CREATED_BY);
            if (!java.util.Objects.equals(authorId, me) && !hasPermission(me, "doc:manage")) {
                throw new BusinessException(ErrorCode.NO_PERMISSION, "无权限查看该文档");
            }
            return buildTrashDetail(row, me);
        }
        assertVisible(doc, me);
        return buildDetail(doc, me, true);
    }

    /**
     * 版本历史。
     *
     * @param id   文档 ID
     * @param page 分页
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<DocumentVersionVo> versions(Long id, PageDtoReq page) {
        Long me = SecurityContext.requireUserId();
        Document doc = getDocumentOrThrow(id);
        assertOwnerOrManage(doc, me, "无权限查看该文档版本");
        Page<DocumentVersion> versions = documentVersionRepository
                .findByDocumentIdOrderByVersionNumDesc(id, page.toPageableUnsorted());
        Set<Long> operatorIds = new LinkedHashSet<>();
        for (DocumentVersion version : versions.getContent()) {
            if (version.getCreatedBy() != null) {
                operatorIds.add(version.getCreatedBy());
            }
        }
        Map<Long, String> operatorNames = userService.realNamesOf(operatorIds);
        List<DocumentVersionVo> list = versions.getContent().stream()
                .map(version -> DocumentVersionVo.of(version, operatorNames.get(version.getCreatedBy())))
                .toList();
        return PageVo.of(list, versions.getTotalElements(), versions.getNumber() + 1, versions.getSize());
    }

    // ─────────────────────────── 收藏 ───────────────────────────

    /**
     * 收藏（幂等）。
     *
     * @param id 文档 ID
     * @return 收藏结果
     */
    @Override
    @Transactional
    public FavoriteVo favorite(Long id) {
        Long me = SecurityContext.requireUserId();
        Document doc = getDocumentOrThrow(id);
        assertVisible(doc, me);
        boolean exists = favoriteRepository.existsByUserIdAndDocumentId(me, id);
        if (exists) {
            return new FavoriteVo(IdUtil.toStr(id), true, doc.getFavoriteCount());
        }
        favoriteRepository.saveAndFlush(new Favorite(me, id));
        documentRepository.updateFavoriteCount(id, 1);
        return new FavoriteVo(IdUtil.toStr(id), true, doc.getFavoriteCount() + 1);
    }

    /**
     * 取消收藏（幂等）。
     *
     * @param id 文档 ID
     * @return 收藏结果
     */
    @Override
    @Transactional
    public FavoriteVo unfavorite(Long id) {
        Long me = SecurityContext.requireUserId();
        Document doc = getDocumentOrThrow(id);
        int removed = favoriteRepository.deleteByUserIdAndDocumentId(me, id);
        if (removed > 0) {
            documentRepository.updateFavoriteCount(id, -1);
        }
        int count = Math.max(0, doc.getFavoriteCount() - (removed > 0 ? 1 : 0));
        return new FavoriteVo(IdUtil.toStr(id), false, count);
    }

    // ─────────────────────────── 共用能力 ───────────────────────────

    /**
     * 取文档实体，不存在则 404。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    @Override
    @Transactional(readOnly = true)
    public Document getDocumentOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除");
        }
        return documentRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除"));
    }

    /**
     * 取回收站文档行，不存在则 404。
     *
     * @param id 文档 ID
     * @return 16 列结果行
     */
    @Override
    @Transactional(readOnly = true)
    public Object[] getTrashRowOrThrow(Long id) {
        return requireTrashRow(id, null);
    }

    /**
     * 取回收站文档行，并按需区分 404 / 409：
     * 文档存在但不在回收站 → 409（状态冲突）；文档彻底不存在 → 404。
     *
     * @param id          文档 ID
     * @param conflictMsg 409 的中文提示（null = 只做 404 判定）
     * @return 16 列结果行
     */
    private Object[] requireTrashRow(Long id, String conflictMsg) {
        List<Object[]> rows = documentRepository.findTrashById(id);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        if (conflictMsg != null && id != null && documentRepository.findDetailById(id).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, conflictMsg);
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在或已被删除");
    }

    /**
     * 可见性校验（防 IDOR，BR-21）。
     *
     * @param doc      文档实体
     * @param viewerId 访问者 ID
     */
    @Override
    public void assertVisible(Document doc, Long viewerId) {
        if (doc.getStatus() == DocumentStatus.PUBLISHED) {
            return;
        }
        if (doc.getCreatedBy() != null && doc.getCreatedBy().equals(viewerId)) {
            return;
        }
        if (hasPermission(viewerId, "doc:manage")) {
            return;
        }
        throw new BusinessException(ErrorCode.NO_PERMISSION, "无权限查看该文档");
    }

    /**
     * 归属校验：非作者且无 {@code doc:manage} → 403。
     *
     * @param doc      文档实体
     * @param operator 操作人 ID
     * @param message  中文提示
     */
    @Override
    public void assertOwnerOrManage(Document doc, Long operator, String message) {
        if (doc.getCreatedBy() != null && doc.getCreatedBy().equals(operator)) {
            return;
        }
        if (hasPermission(operator, "doc:manage")) {
            return;
        }
        throw new BusinessException(ErrorCode.NO_PERMISSION, message);
    }

    /**
     * 组装文档详情（分类名 / 作者名 / 标签 / 收藏态，必要时累计阅读量）。
     *
     * @param doc       文档实体
     * @param viewerId  访问者 ID
     * @param countView 是否累计阅读量
     * @return 文档详情
     */
    @Override
    public DocumentDetailVo buildDetail(Document doc, Long viewerId, boolean countView) {
        if (countView && doc.getStatus() == DocumentStatus.PUBLISHED) {
            if (touchViewOnce(doc.getId(), viewerId)) {
                documentRepository.increaseViewCount(doc.getId());
                doc.setViewCount(doc.getViewCount() + 1);
            }
        }
        String categoryName = categoryNameOf(doc.getCategoryId());
        String authorName = userService.realNameOf(doc.getCreatedBy());
        List<TagVo> tags = tagRepository.findTagsByDocumentId(doc.getId()).stream().map(TagVo::of).toList();
        boolean favorited = favoriteRepository.existsByUserIdAndDocumentId(viewerId, doc.getId());
        return DocumentDetailVo.of(doc, categoryName, authorName, canEdit(doc, viewerId), favorited, tags);
    }

    /**
     * 组装回收站文档详情。
     *
     * @param row      回收站结果行
     * @param viewerId 访问者 ID
     * @return 文档详情
     */
    @Override
    @Transactional(readOnly = true)
    public DocumentDetailVo buildTrashDetail(Object[] row, Long viewerId) {
        Long id = DocumentColumns.longVal(row, DocumentColumns.ID);
        String categoryName = categoryNameOf(DocumentColumns.longVal(row, DocumentColumns.CATEGORY_ID));
        String authorName = userService.realNameOf(DocumentColumns.longVal(row, DocumentColumns.CREATED_BY));
        List<TagVo> tags = tagRepository.findTagsByDocumentId(id).stream().map(TagVo::of).toList();
        return DocumentDetailVo.ofTrash(row, categoryName, authorName, tags);
    }

    /**
     * 写版本留痕。
     *
     * @param doc        文档实体
     * @param changeType 变更类型
     * @param remark     备注
     */
    @Override
    public void writeVersion(Document doc, ChangeType changeType, String remark) {
        DocumentVersion version = DocumentVersion.builder()
                .documentId(doc.getId())
                .versionNum(doc.getVersionNum())
                .title(doc.getTitle())
                .contentMd(doc.getContentMd())
                .changeType(changeType)
                .changeRemark(remark)
                .build();
        documentVersionRepository.saveAndFlush(version);
    }

    /**
     * 当前用户对文档是否可编辑。
     *
     * @param doc    文档
     * @param userId 用户 ID
     * @return true = 可编辑
     */
    @Override
    public boolean canEdit(Document doc, Long userId) {
        if (doc.getCreatedBy() == null || !doc.getCreatedBy().equals(userId)) {
            return false;
        }
        return doc.getStatus() == DocumentStatus.DRAFT || doc.getStatus() == DocumentStatus.PUBLISHED;
    }

    /**
     * 是否拥有某权限码。
     *
     * @param userId 用户 ID
     * @param code   权限码
     * @return true = 拥有
     */
    @Override
    public boolean hasPermission(Long userId, String code) {
        return permissionCacheService.hasPermission(userId, code);
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    /**
     * 列表 → PageVo（批量补作者名 / 分类名）。
     *
     * @param page 投影分页
     * @param me   当前用户
     * @return 分页结果
     */
    private PageVo<DocumentVo> toPageVo(Page<DocumentListRow> page, Long me) {
        return buildPage(page.getContent(), page.getTotalElements(), page.getNumber() + 1, page.getSize(), me, true);
    }

    /**
     * 全文检索 / 治理列表分页 → PageVo。
     *
     * <p>在 {@link #buildPage} 的结果上按行序回填 {@code highlight} / {@code matchedIn}
     * （{@link #buildPage} 是逐行 1:1 组装的，因此下标对齐可靠）；这样批量补名（作者/分类）只需一份实现。</p>
     *
     * @param page               原生分页（投影行 + 高亮 + 命中字段）
     * @param me                 当前用户
     * @param allowOwnershipEdit 是否允许按归属计算可编辑
     * @return 分页结果
     */
    private PageVo<DocumentVo> buildSearchPage(Page<DocumentSearchRow> page, Long me, boolean allowOwnershipEdit) {
        List<DocumentSearchRow> searchRows = page.getContent();
        List<DocumentListRow> rows = searchRows.stream().map(DocumentSearchRow::row).toList();
        PageVo<DocumentVo> result = buildPage(rows, page.getTotalElements(), page.getNumber() + 1,
                page.getSize(), me, allowOwnershipEdit);
        List<DocumentVo> list = result.list();
        for (int i = 0; i < list.size(); i++) {
            DocumentVo vo = list.get(i);
            vo.setHighlight(searchRows.get(i).highlight());
            vo.setMatchedIn(searchRows.get(i).matchedIn());
        }
        return result;
    }

    /**
     * 行列表 → PageVo（详见接口说明）。
     *
     * @param rows               投影行
     * @param total              总条数
     * @param pageNum            页码
     * @param pageSize           每页条数
     * @param me                 当前用户
     * @param allowOwnershipEdit 是否允许按归属计算可编辑
     * @return 分页结果
     */
    @Override
    public PageVo<DocumentVo> buildPage(List<DocumentListRow> rows, long total, int pageNum, int pageSize,
                                        Long me, boolean allowOwnershipEdit) {
        if (rows.isEmpty()) {
            return PageVo.of(List.of(), total, pageNum, pageSize);
        }
        Set<Long> authorIds = new LinkedHashSet<>();
        Set<Long> categoryIds = new LinkedHashSet<>();
        for (DocumentListRow row : rows) {
            if (row.authorId() != null) {
                authorIds.add(row.authorId());
            }
            if (row.categoryId() != null && row.categoryId() != 0L) {
                categoryIds.add(row.categoryId());
            }
        }
        Map<Long, String> authorNames = userService.realNamesOf(authorIds);
        Map<Long, String> categoryNames = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Category category : categoryRepository.findAllById(categoryIds)) {
                categoryNames.put(category.getId(), category.getName());
            }
        }
        List<DocumentVo> list = new ArrayList<>(rows.size());
        for (DocumentListRow row : rows) {
            String categoryName = row.categoryId() == null || row.categoryId() == 0L
                    ? UNCATEGORIZED : categoryNames.getOrDefault(row.categoryId(), UNCATEGORIZED);
            boolean editable = row.status() == DocumentStatus.DRAFT || row.status() == DocumentStatus.PUBLISHED;
            boolean mine = row.authorId() != null && row.authorId().equals(me);
            list.add(DocumentVo.of(row, categoryName, authorNames.get(row.authorId()),
                    allowOwnershipEdit && editable && mine));
        }
        return PageVo.of(list, total, pageNum, pageSize);
    }

    /**
     * 分类过滤集合：给定分类及其全部子孙（一次前缀查询）。
     *
     * @param categoryId 分类 ID（null / 0 = 不限）
     * @return 分类 ID 集合；不限时返回 null
     */
    private Collection<Long> categoryIdsIncludingDescendants(Long categoryId) {
        if (categoryId == null || categoryId == 0L) {
            return null;
        }
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "分类不存在，请重新选择"));
        Set<Long> ids = new HashSet<>();
        ids.add(category.getId());
        for (Category child : categoryRepository.findByAncestorsPath(category.getAncestors() + "," + category.getId())) {
            ids.add(child.getId());
        }
        return ids;
    }

    /**
     * 解析并校验分类 ID（不传 / "0" = 未分类）。
     *
     * @param categoryId 入参分类 ID
     * @param allowEmpty 是否允许为空（新建允许，编辑也允许，保留参数以便将来区分）
     * @return 分类 ID（0 = 未分类）
     */
    private Long resolveCategoryId(String categoryId, boolean allowEmpty) {
        Long id = IdUtil.toLongOrNull(categoryId, "分类ID");
        if (id == null || id == 0L) {
            return 0L;
        }
        if (!categoryRepository.existsById(id)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类不存在，请重新选择");
        }
        return id;
    }

    /**
     * 解析并校验标签 ID 列表（最多 5 个，且必须都存在）。
     *
     * @param rawTagIds 入参标签 ID
     * @return 去重后的标签 ID 列表
     */
    private List<Long> validateTagIds(List<String> rawTagIds) {
        List<Long> tagIds = parseIds(rawTagIds, "标签ID");
        if (tagIds.size() > MAX_TAGS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "每篇文档最多只能选择5个标签");
        }
        if (!tagIds.isEmpty() && tagRepository.findAllByIdIn(tagIds).size() != tagIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "标签不存在，请重新选择");
        }
        return tagIds;
    }

    /**
     * 标签重绑：先删后插 + 计数差值更新（一个事务）。
     *
     * @param documentId 文档 ID
     * @param newTagIds  新标签集合
     * @param oldTagIds  旧标签集合
     */
    private void bindTags(Long documentId, List<Long> newTagIds, List<Long> oldTagIds) {
        documentTagRelRepository.deleteByDocumentId(documentId);
        if (!newTagIds.isEmpty()) {
            List<DocumentTagRel> relations = newTagIds.stream().map(tagId -> new DocumentTagRel(documentId, tagId)).toList();
            documentTagRelRepository.saveAll(relations);
        }
        Set<Long> added = new LinkedHashSet<>(newTagIds);
        added.removeAll(oldTagIds);
        Set<Long> removed = new LinkedHashSet<>(oldTagIds);
        removed.removeAll(newTagIds);
        if (!added.isEmpty()) {
            tagRepository.increaseUseCount(added);
        }
        if (!removed.isEmpty()) {
            tagRepository.decreaseUseCount(removed);
        }
    }

    /**
     * 取文档当前标签 ID（走定制查询片段，一条 SQL）。
     *
     * @param documentId 文档 ID
     * @return 标签 ID 列表
     */
    private List<Long> documentQueryTagIds(Long documentId) {
        return documentRepository.findTagIds(documentId);
    }

    /**
     * 阅读量去重：Redis SETNX，30 分钟内同一用户只计一次（BR-09）。
     *
     * <p>Redis 不可用时返回 false（宁可少计，也不因为缓存故障把阅读量刷爆）。</p>
     *
     * @param docId  文档 ID
     * @param userId 用户 ID
     * @return true = 本次需要累加
     */
    private boolean touchViewOnce(Long docId, Long userId) {
        try {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(RedisKeys.viewDedup(docId, userId), "1", RedisKeys.VIEW_TTL);
            return Boolean.TRUE.equals(first);
        } catch (Exception ex) {
            log.warn("阅读量去重失败（降级为不累加）: docId={}, cause={}", docId, ex.getMessage());
            return false;
        }
    }

    /**
     * 分类名（0 = 未分类）。
     *
     * @param categoryId 分类 ID
     * @return 分类名
     */
    private String categoryNameOf(Long categoryId) {
        if (categoryId == null || categoryId == 0L) {
            return UNCATEGORIZED;
        }
        return categoryRepository.findById(categoryId).map(Category::getName).orElse(UNCATEGORIZED);
    }

    /**
     * 解析 ID 字符串数组。
     *
     * @param values 入参
     * @param field  字段中文名
     * @return ID 列表（去重、保序）
     */
    private List<Long> parseIds(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            // 兼容 tagIds=1,2,3 这种逗号分隔写法
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    ids.add(IdUtil.toLong(part.trim(), field));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 解析文档状态（非法值返回约定文案）。
     *
     * @param text 状态串
     * @return 状态枚举
     */
    private DocumentStatus parseStatus(String text) {
        try {
            return DocumentStatus.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档状态取值非法");
        }
    }

    /**
     * 空串归一成 null。
     *
     * @param value 原值
     * @return 归一值
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
