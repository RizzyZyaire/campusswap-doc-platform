package com.campusswap.document.search;

import com.campusswap.common.api.PageVo;
import com.campusswap.document.dto.DocumentSearchDtoReq;
import com.campusswap.document.dto.DocumentSearchEsDtoReq;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.api.ErrorCode;
import com.campusswap.document.search.mq.SearchRebuildProducer;
import com.campusswap.document.search.mq.SearchRebuildTask;
import com.campusswap.document.search.mq.SearchRebuildTaskStore;
import com.campusswap.document.service.DocumentService;
import com.campusswap.document.vo.DocumentVo;
import com.campusswap.document.vo.SearchEngineStatusVo;
import com.campusswap.document.vo.SearchHitVo;
import com.campusswap.document.vo.SearchRebuildVo;
import com.campusswap.document.vo.SearchResultVo;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

/**
 * 全文检索服务实现（课件 10.1 落地 + 用户追加的"检索范围/关键词高亮"）。
 *
 * <p><b>三件事在这里合流</b>：</p>
 * <ol>
 *   <li><b>ES 引擎</b>：{@link DocumentSearchRepository} 的三条 JSON DSL 查询（范围 TITLE / CONTENT / ALL），
 *       权重 title^3 / summary^2 / content，filter 过滤 status=PUBLISHED；</li>
 *   <li><b>MySQL 降级引擎</b>：ES 不可用（没装 / 没启动 / 查询异常）时调用既有的
 *       {@code DocumentService.search}，返回结构完全一致，只把 {@code engine} 标成 mysql；</li>
 *   <li><b>高亮</b>：两种引擎共用 Java 侧的 {@link SearchTextHighlighter}（先转义再插 mark），
 *       保证"ES 下有高亮、降级后也有高亮"，且不引入 XSS 通道。</li>
 * </ol>
 *
 * <p>可用性探测带 10 秒缓存：ES 没启动时每次检索都去连一遍会拖慢接口（连接超时配置见 application yml）。</p>
 *
 * @author Zyaire
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSearchServiceImpl implements DocumentSearchService {

    /** 重建索引时的批大小（游标分页，避免深分页与一次性大对象）。 */
    private static final int REBUILD_BATCH = 500;

    /** ES 可用性探测缓存时长（毫秒）。 */
    private static final long PROBE_TTL_MS = 10_000L;

    /** 写进 ES 的时间格式（ISO-8601，字典序 = 时间序，可直接排序）。 */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 返回给前端的时间格式（与全站一致）。 */
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * ES 侧仓储（检索 + 索引写入）。
     *
     * <p>它是普通 {@code @Repository} Bean（不是 Spring Data 仓储接口），构造期不连集群，
     * 因此这里可以直接注入——应用在没有 ES 的机器上也能正常启动，检索时再降级。</p>
     */
    private final DocumentSearchRepository searchRepository;

    /** 索引是否已确认存在（首次用到 ES 时建一次，避免每次检索都往返一次 exists）。 */
    private volatile boolean indexReady = false;

    /** 主库侧只读仓储（重建/增量同步的数据来源）。 */
    private final DocumentIndexRepository indexRepository;

    /** 既有 MySQL 检索实现（降级引擎）。 */
    private final DocumentService documentService;

    /** 异步重建任务状态仓库（Redis）。 */
    private final SearchRebuildTaskStore rebuildTaskStore;

    /** 异步重建消息生产者（RabbitMQ）。 */
    private final SearchRebuildProducer rebuildProducer;

    /** ES 地址（回显给状态接口，便于排障）。 */
    @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}")
    private String endpoint;

    /**
     * 取 ES 仓储（延迟实例化；ES 不可用时抛异常，由调用处的 try/catch 接住并降级）。
     *
     * @return ES 仓储
     */
    /**
     * 确保索引存在（带注解里声明的 IK 分词映射）。
     *
     * <p>因为实体上是 {@code createIndex = false}（见 DocumentSearchIndex 的说明），
     * 建索引这件事在这里做：首次用到 ES 时建一次，之后靠 {@link #indexReady} 短路。
     * 本方法只在"已经确认 ES 可用"之后调用，且调用处均有 try/catch。</p>
     */
    private void ensureIndex() {
        if (indexReady) {
            return;
        }
        synchronized (this) {
            if (indexReady) {
                return;
            }
            searchRepository.createIndexIfMissing();
            indexReady = true;
        }
    }

    /** 上次探测时间戳。 */
    private volatile long probedAt = 0L;

    /** 上次探测结果。 */
    private volatile boolean esAvailable = false;

    /** 上次探测到的索引文档数（-1 = 未知）。 */
    private volatile long cachedIndexCount = -1L;

    @Override
    public SearchResultVo search(DocumentSearchEsDtoReq req) {
        String keyword = req.getKeyword() == null ? "" : req.getKeyword().trim();
        SearchScope scope = SearchScope.parse(req.getScope());
        int pageNum = req.getPageNum() == null || req.getPageNum() < 1 ? 1 : req.getPageNum();
        int pageSize = req.getPageSize() == null || req.getPageSize() < 1 ? 10 : req.getPageSize();
        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, sortOf(req.getSort()));

        SearchResultVo vo = new SearchResultVo();
        vo.setKeyword(keyword);
        vo.setScope(scope.name());
        vo.setScopeLabel(scope.label());
        vo.setPageNum(pageNum);
        vo.setPageSize(pageSize);
        vo.setList(List.of());
        vo.setTotal(0);

        if (keyword.isEmpty()) {
            vo.setEngine(esAvailable() ? ENGINE_ES : ENGINE_MYSQL);
            vo.setEngineMessage("请输入关键词后再检索");
            return vo;
        }

        if (esAvailable()) {
            try {
                ensureIndex();
                SearchHits<DocumentSearchIndex> hits = runEsQuery(scope, keyword, req.getCategoryId(), req.getStartTime(),
                req.getEndTime(), req.getTag(), pageable);
                List<SearchHitVo> list = new ArrayList<>();
                for (SearchHit<DocumentSearchIndex> hit : hits.getSearchHits()) {
                    list.add(toHitVo(hit, keyword));
                }
                vo.setEngine(ENGINE_ES);
                vo.setEngineMessage("Elasticsearch 引擎（IK 中文分词，相关度排序）");
                vo.setList(list);
                vo.setTotal(hits.getTotalHits());
                return vo;
            } catch (Exception e) {
                // 索引被删/映射变更/连接抖动都算"ES 这次不可用"：标记并降级，不让用户看到 500
                markEsDown(e);
            }
        }
        return mysqlFallback(req, vo, keyword, pageNum, pageSize);
    }

    @Override
    public SearchRebuildVo rebuildAll() {
        SearchRebuildVo vo = new SearchRebuildVo();
        long start = System.currentTimeMillis();
        if (!esAvailable()) {
            vo.setSuccess(false);
            vo.setMessage("Elasticsearch 未连接（" + endpoint + "）：请先启动 ES（双击 campusswap-start.cmd 会自动拉起）再重建索引；"
                    + "当前检索会自动使用 MySQL 引擎，功能不受影响。");
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        }
        try {
            ensureIndex();
            // 全量重建 = 索引整个重建再灌，保证不残留已下架的历史文档、映射也是最新的
            searchRepository.recreateIndex();
            Map<Long, List<String>> tagMap = loadTagMap();
            long afterId = 0L;
            long indexed = 0L;
            List<DocumentSearchIndex> batch = new ArrayList<>(REBUILD_BATCH);
            while (true) {
                List<DocumentIndexRepository.DocumentIndexRow> rows =
                        indexRepository.findPublishedAfter(afterId, REBUILD_BATCH);
                if (rows.isEmpty()) {
                    break;
                }
                batch.clear();
                for (DocumentIndexRepository.DocumentIndexRow row : rows) {
                    batch.add(toIndex(row, tagMap.getOrDefault(row.getId(), List.of())));
                }
                searchRepository.saveAll(batch);
                indexed += batch.size();
                afterId = rows.get(rows.size() - 1).getId();
            }
            long count = searchRepository.count();
            cachedIndexCount = count;
            vo.setSuccess(true);
            vo.setIndexed(indexed);
            vo.setIndexCount(count);
            vo.setMessage("索引重建完成：从主库同步 " + indexed + " 篇已发布文档，索引内共 " + count + " 篇");
            log.info("全文索引重建完成：indexed={}, indexCount={}", indexed, count);
        } catch (Exception e) {
            markEsDown(e);
            vo.setSuccess(false);
            vo.setMessage("索引重建失败：" + e.getMessage());
        }
        vo.setElapsedMs(System.currentTimeMillis() - start);
        return vo;
    }

    @Override
    public SearchRebuildTask submitRebuildTask(Long operatorId) {
        long start = System.currentTimeMillis();
        SearchRebuildTask task = rebuildTaskStore.create(operatorId, 0L);
        boolean sent = rebuildProducer.send(task);
        long cost = System.currentTimeMillis() - start;
        task.setSubmitCostMs(cost);
        if (sent) {
            task.setMessage("任务已提交，等待后台消费者执行（提交耗时 " + cost + " ms）");
        }
        rebuildTaskStore.save(task);
        return task;
    }

    @Override
    public SearchRebuildTask getRebuildTask(String taskId) {
        SearchRebuildTask task = rebuildTaskStore.get(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "重建任务不存在或已过期（任务记录保留 2 小时）");
        }
        return task;
    }

    @Override
    public void syncOne(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            if (!esAvailable()) {
                return;
            }
            ensureIndex();
            DocumentIndexRepository.DocumentIndexRow row = indexRepository.findPublishedById(documentId);
            if (row == null) {
                // 编辑后回到草稿 / 已归档 / 进回收站：都不该留在检索索引里
                searchRepository.deleteById(documentId);
                return;
            }
            searchRepository.save(toIndex(row, tagsOf(documentId)));
        } catch (Exception e) {
            // 检索索引是派生数据：同步失败只记日志，绝不能影响业务写入
            log.warn("文档 {} 增量同步到检索索引失败：{}", documentId, e.getMessage());
        }
    }

    @Override
    public void removeOne(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            if (!esAvailable()) {
                return;
            }
            searchRepository.deleteById(documentId);
        } catch (Exception e) {
            log.warn("文档 {} 从检索索引删除失败：{}", documentId, e.getMessage());
        }
    }

    @Override
    public SearchEngineStatusVo status() {
        SearchEngineStatusVo vo = new SearchEngineStatusVo();
        vo.setEndpoint(endpoint);
        boolean up = esAvailable();
        vo.setElasticsearchAvailable(up);
        vo.setEngine(up ? ENGINE_ES : ENGINE_MYSQL);
        vo.setIndexCount(up ? cachedIndexCount : -1L);
        vo.setIkAnalyzerReady(up);
        vo.setMessage(up
                ? "Elasticsearch 已连接，使用 IK 中文分词与相关度排序"
                : "Elasticsearch 未连接，已降级为 MySQL 引擎（无相关度评分，高亮基于标题与摘要）");
        return vo;
    }

    /** 引擎名常量。 */
    private static final String ENGINE_ES = "elasticsearch";

    /** 引擎名常量。 */
    private static final String ENGINE_MYSQL = "mysql";

    /**
     * 按范围执行 ES 查询。
     *
     * @param scope      检索范围
     * @param keyword    关键词
     * @param categoryId 分类 ID（可空）
     * @param pageable   分页与排序
     * @return 命中结果
     */
    /**
     * 起始时间规范化：只给日期时补 00:00:00（便于前端日期选择器直接传 yyyy-MM-dd）。
     *
     * @param value 原始值（可空）
     * @return 规范化后的时间字符串
     */
    private String normalizeStart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String full = trimmed.length() == 10 ? trimmed + " 00:00:00" : trimmed;
        // 关键：索引里 publishAt 存的是 ISO（2026-09-15T15:40:00，带 T）。
        // keyword 字段的范围查询是**字典序**比较，查询边界必须与索引里的格式完全一致，
        // 否则 "2026-…T…" 会大于 "2030-… …"（第 4 个字符 6 > 0），过滤形同虚设（实测踩到）。
        return full.replace(' ', 'T');
    }

    /**
     * 结束时间规范化：只给日期时补 23:59:59。
     *
     * @param value 原始值（可空）
     * @return 规范化后的时间字符串
     */
    private String normalizeEnd(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String full = trimmed.length() == 10 ? trimmed + " 23:59:59" : trimmed;
        return full.replace(' ', 'T');
    }
    private SearchHits<DocumentSearchIndex> runEsQuery(SearchScope scope, String keyword, String categoryId, String startTime,
                                                String endTime, String tag,
                                                       Pageable pageable) {
        // 范围决定 must 子句（标题 / 正文 / 联合），分类决定是否追加 filter——都在仓储里拼装
        return searchRepository.search(keyword, scope, parseId(categoryId), normalizeStart(startTime),
                normalizeEnd(endTime), tag, pageable);
    }

    /**
     * ES 命中 → 出参（含 Java 侧高亮与命中字段清单）。
     *
     * @param hit     ES 命中（带分数）
     * @param keyword 关键词
     * @return 命中出参
     */
    private SearchHitVo toHitVo(SearchHit<DocumentSearchIndex> hit, String keyword) {
        DocumentSearchIndex doc = hit.getContent();
        SearchHitVo vo = new SearchHitVo();
        vo.setId(doc.getId() == null ? null : String.valueOf(doc.getId()));
        vo.setTitle(doc.getTitle());
        vo.setTitleHighlight(SearchTextHighlighter.mark(doc.getTitle(), keyword));
        String body = doc.getContent() == null || doc.getContent().isBlank() ? doc.getSummary() : doc.getContent();
        vo.setSnippet(SearchTextHighlighter.snippet(body, keyword));
        vo.setCategoryName(doc.getCategoryName() == null ? "未分类" : doc.getCategoryName());
        vo.setAuthorName(doc.getAuthorName());
        vo.setStatus(doc.getStatus());
        vo.setPublishAt(doc.getPublishAt() == null ? null : doc.getPublishAt().replace('T', ' '));
        vo.setScore(formatScore(hit.getScore()));
        vo.setMatchedFields(matchedFields(doc.getTitle(), doc.getSummary(), doc.getContent(), keyword));
        return vo;
    }

    /**
     * MySQL 降级检索：复用既有实现，保持出参结构一致。
     *
     * @param req      原始请求
     * @param vo       待填充出参
     * @param keyword  关键词
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 填好的出参（engine=mysql）
     */
    private SearchResultVo mysqlFallback(DocumentSearchEsDtoReq req, SearchResultVo vo, String keyword,
                                         int pageNum, int pageSize) {
        DocumentSearchDtoReq mysqlReq = new DocumentSearchDtoReq();
        mysqlReq.setKeyword(keyword);
        mysqlReq.setCategoryId(req.getCategoryId());
        mysqlReq.setStartTime(req.getStartTime());
        mysqlReq.setEndTime(req.getEndTime());
        mysqlReq.setSort(req.getSort());
        mysqlReq.setPageNum(pageNum);
        mysqlReq.setPageSize(pageSize);
        PageVo<DocumentVo> page = documentService.search(mysqlReq);
        List<SearchHitVo> list = new ArrayList<>();
        for (DocumentVo row : page.list()) {
            SearchHitVo hit = new SearchHitVo();
            hit.setId(row.getId());
            hit.setTitle(row.getTitle());
            hit.setTitleHighlight(SearchTextHighlighter.mark(row.getTitle(), keyword));
            hit.setSnippet(SearchTextHighlighter.snippet(row.getSummary(), keyword));
            hit.setCategoryName(row.getCategoryName());
            hit.setAuthorName(row.getAuthorName());
            hit.setStatus(row.getStatus() == null ? null : row.getStatus().name());
            hit.setPublishAt(row.getUpdatedAt());
            hit.setScore(null);
            hit.setMatchedFields(matchedFields(row.getTitle(), row.getSummary(), null, keyword));
            list.add(hit);
        }
        vo.setEngine(ENGINE_MYSQL);
        vo.setEngineMessage("Elasticsearch 未连接，已降级为 MySQL 引擎（无相关度评分；高亮基于标题与摘要；检索范围仅 ES 引擎支持，降级时按标题+摘要匹配）");
        vo.setList(list);
        vo.setTotal(page.total());
        return vo;
    }

    /**
     * 命中字段清单（界面提示"命中在标题/摘要/正文"）。
     *
     * @param title   标题
     * @param summary 摘要
     * @param content 正文
     * @param keyword 关键词
     * @return 命中的字段名列表
     */
    private List<String> matchedFields(String title, String summary, String content, String keyword) {
        List<String> fields = new ArrayList<>(3);
        if (SearchTextHighlighter.contains(title, keyword)) {
            fields.add("title");
        }
        if (SearchTextHighlighter.contains(summary, keyword)) {
            fields.add("summary");
        }
        if (SearchTextHighlighter.contains(content, keyword)) {
            fields.add("content");
        }
        return fields;
    }

    /**
     * 主库行 → 索引文档。
     *
     * @param row  主库投影行
     * @param tags 标签名列表
     * @return 索引文档
     */
    private DocumentSearchIndex toIndex(DocumentIndexRepository.DocumentIndexRow row, List<String> tags) {
        return DocumentSearchIndex.builder()
                .id(row.getId())
                .title(row.getTitle())
                .summary(row.getSummary())
                .content(row.getContentMd())
                .categoryId(row.getCategoryId())
                .categoryName(row.getCategoryName() == null ? "未分类" : row.getCategoryName())
                .status(row.getStatus())
                .tags(tags)
                .authorId(row.getAuthorId())
                .authorName(row.getAuthorName())
                .deptName(row.getDeptName())
                .publishAt(toIso(row.getPublishAt()))
                .updatedAt(toIso(row.getUpdatedAt()))
                .viewCount(row.getViewCount())
                .build();
    }

    /**
     * 相关度分数 → 两位小数字符串（后端禁止出现浮点类型，见 SearchHitVo#score 的说明）。
     *
     * @param raw ES 给出的原始分数
     * @return 形如 "4.83" 的字符串
     */
    private String formatScore(Number raw) {
        return new java.text.DecimalFormat("0.00").format(raw);
    }

    /**
     * 时间戳 → ISO 字符串。
     *
     * @param ts 时间（MySQL DATETIME 由驱动映射为 LocalDateTime），可空
     * @return ISO-8601 字符串，可空
     */
    private String toIso(LocalDateTime ts) {
        return ts == null ? null : ts.format(ISO);
    }

    /**
     * 取全量"文档 → 标签名"映射（重建时一次性加载）。
     *
     * @return 文档 ID → 标签名列表
     */
    private Map<Long, List<String>> loadTagMap() {
        Map<Long, List<String>> map = new HashMap<>();
        for (DocumentIndexRepository.DocumentTagRow row : indexRepository.findAllDocumentTags()) {
            map.computeIfAbsent(row.getDocumentId(), k -> new ArrayList<>()).add(row.getTagName());
        }
        return map;
    }

    /**
     * 取单篇文档的标签名（增量同步用）。
     *
     * @param documentId 文档 ID
     * @return 标签名列表（可空列表）
     */
    private List<String> tagsOf(Long documentId) {
        List<String> tags = new ArrayList<>();
        for (DocumentIndexRepository.DocumentTagRow row : indexRepository.findAllDocumentTags()) {
            if (documentId.equals(row.getDocumentId())) {
                tags.add(row.getTagName());
            }
        }
        return tags;
    }

    /**
     * 解析可选数字 ID。
     *
     * @param raw 字符串形式的 ID，可空
     * @return Long 或 null
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把排序参数映射成 Spring Data 的 Sort（空或 relevance = 不排序，交给 ES 按 _score 排）。
     *
     * @param sort 排序方式
     * @return Sort 对象
     */
    private Sort sortOf(String sort) {
        if (sort == null || sort.isBlank() || "relevance".equals(sort)) {
            return Sort.unsorted();
        }
        return switch (sort) {
            case "publishAt_desc" -> Sort.by(Sort.Direction.DESC, "publishAt");
            case "viewCount_desc" -> Sort.by(Sort.Direction.DESC, "viewCount");
            default -> Sort.by(Sort.Direction.DESC, "updatedAt");
        };
    }

    /**
     * ES 可用性探测（10 秒缓存）：只做一次 {@code count()}，连通即视为可用。
     *
     * @return ES 是否可用
     */
    private boolean esAvailable() {
        long now = System.currentTimeMillis();
        if (now - probedAt < PROBE_TTL_MS) {
            return esAvailable;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - probedAt < PROBE_TTL_MS) {
                return esAvailable;
            }
            try {
                cachedIndexCount = searchRepository.count();
                esAvailable = true;
            } catch (Exception e) {
                cachedIndexCount = -1L;
                if (esAvailable) {
                    log.warn("Elasticsearch 连接中断，检索降级为 MySQL 引擎：{}", e.getMessage());
                }
                esAvailable = false;
            }
            probedAt = System.currentTimeMillis();
            return esAvailable;
        }
    }

    /**
     * 标记 ES 不可用（查询中途出错时立刻降级，不用等缓存过期）。
     *
     * @param cause 原始异常
     */
    private void markEsDown(Exception cause) {
        log.warn("Elasticsearch 查询失败，本次检索降级为 MySQL 引擎：{}", cause.getMessage());
        esAvailable = false;
        cachedIndexCount = -1L;
        indexReady = false;
        probedAt = System.currentTimeMillis();
    }

    /**
     * 时间格式（供状态接口与测试复用）。
     *
     * @return 人类可读的时间格式
     */
    static DateTimeFormatter humanFormatter() {
        return HUMAN;
    }
}
