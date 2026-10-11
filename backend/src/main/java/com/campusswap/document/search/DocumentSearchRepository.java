package com.campusswap.document.search;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.stereotype.Repository;

/**
 * 文档检索仓储（课件 10.1 §5.1：索引实体的增删查 + 多字段检索）。
 *
 * <p><b>为什么是普通 {@code @Repository} 类而不是 Spring Data 的
 * {@code ElasticsearchRepository} 接口</b>（本项目唯一一处刻意偏离课件的写法，原因有实测支撑）：</p>
 * <ol>
 *   <li>Spring Data ES 的仓储<b>在应用启动时就被实例化</b>，而
 *       {@code SimpleElasticsearchRepository} 的构造器会立刻连集群检查/创建索引 ——
 *       结果是"这台机器没装 ES"会让<b>整个应用启动失败</b>
 *       （实测日志：{@code Failed to instantiate [SimpleElasticsearchRepository] ...
 *       Connect to http://127.0.0.1:9200 failed: Connection refused}），
 *       双引擎降级就形同虚设；</li>
 *   <li>{@code @EnableElasticsearchRepositories} 在 Spring Data ES 6.1.1 里<b>没有</b>
 *       {@code bootstrapMode} 属性，无法声明惰性引导；</li>
 *   <li>注解式 {@code @Query} 是静态 DSL，而"检索范围（标题/正文/联合）+ 可选分类过滤"
 *       需要动态拼装条件。</li>
 * </ol>
 * <p>改成普通 Bean + {@link ElasticsearchOperations} 后：构造期零网络请求，
 * 索引由 {@link #createIndexIfMissing()} 显式创建（映射仍来自
 * {@link DocumentSearchIndex} 上的 {@code @Field} 注解，含 IK 分词器），
 * 查询条件在 Java 侧按需拼装。<b>检索语义与课件完全一致</b>：
 * 权重 {@code title^3 / summary^2 / content}、状态过滤进 {@code filter} 上下文。</p>
 *
 * @author Zyaire
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DocumentSearchRepository {

    /** ES 模板（构造期不连接集群，取 Bean 是安全的）。 */
    private final ElasticsearchOperations operations;

    /**
     * 确保索引存在（带 {@link DocumentSearchIndex} 注解里声明的 IK 分词映射）。
     *
     * @return true = 索引已存在或本次创建成功
     */
    public boolean createIndexIfMissing() {
        IndexOperations ops = operations.indexOps(DocumentSearchIndex.class);
        if (ops.exists()) {
            return true;
        }
        boolean created = ops.createWithMapping();
        if (created) {
            log.info("已创建检索索引 {}（映射含 ik_max_word / ik_smart）", DocumentSearchIndex.INDEX_NAME);
        }
        return created || ops.exists();
    }

    /**
     * 索引是否已存在。
     *
     * @return true = 存在
     */
    public boolean indexExists() {
        return operations.indexOps(DocumentSearchIndex.class).exists();
    }

    /**
     * 保存（或覆盖）一篇索引文档。
     *
     * @param document 索引文档
     */
    public void save(DocumentSearchIndex document) {
        operations.save(document);
        refresh();
    }

    /**
     * 批量保存索引文档（全量重建用）。
     *
     * @param documents 索引文档集合
     */
    public void saveAll(List<DocumentSearchIndex> documents) {
        if (documents != null && !documents.isEmpty()) {
            operations.save(documents);
            refresh();
        }
    }

    /**
     * 按文档 ID 删除索引文档。
     *
     * @param documentId 文档 ID
     */
    public void deleteById(Long documentId) {
        operations.delete(String.valueOf(documentId), IndexCoordinates.of(DocumentSearchIndex.INDEX_NAME));
    }

    /**
     * 重建索引本体（全量重建前调用）：删掉旧索引再按注解映射建新的。
     *
     * <p>比"清空文档"更彻底也更省事：连映射一起刷新，且不依赖 delete-by-query 的
     * 版本兼容性（{@code ElasticsearchOperations} 的 delete-by-query 需要
     * {@code DeleteQuery}，而"清空重建"在本场景语义更直白）。</p>
     */
    public void recreateIndex() {
        IndexOperations ops = operations.indexOps(DocumentSearchIndex.class);
        if (ops.exists()) {
            ops.delete();
        }
        ops.createWithMapping();
    }

    /**
     * 索引内文档数（可用性探测与重建自检都用它）。
     *
     * @return 文档数
     */
    public long count() {
        return operations.count(NativeQuery.builder().withQuery(matchAll()).build(), DocumentSearchIndex.class);
    }

    /**
     * 多字段检索（课件 §5.1 的 DSL 在这里按范围动态拼装）。
     *
     * @param keyword    关键词
     * @param scope      检索范围：TITLE / CONTENT / ALL
     * @param categoryId 分类 ID（可空）
     * @param pageable   分页（排序为空时按相关度 _score 倒序）
     * @return 命中结果（含分数与总数）
     */
    public SearchHits<DocumentSearchIndex> search(String keyword, SearchScope scope, Long categoryId,
                                                 Pageable pageable) {
        BoolQuery.Builder bool = new BoolQuery.Builder().must(mustClause(keyword, scope));
        // 过滤放 filter 上下文：不参与打分、可利用 Bitset 缓存；索引里只有 PUBLISHED，这里是双保险
        bool.filter(TermQuery.of(t -> t.field("status").value("PUBLISHED"))._toQuery());
        if (categoryId != null) {
            bool.filter(TermQuery.of(t -> t.field("categoryId").value(categoryId))._toQuery());
        }
        NativeQuery query = NativeQuery.builder()
                .withQuery(bool.build()._toQuery())
                .withPageable(pageable)
                .build();
        return operations.search(query, DocumentSearchIndex.class);
    }

    /**
     * 显式刷新索引，保证"写进去马上就能搜到"。
     *
     * <p><b>为什么必须显式刷新</b>（实测教训）：ES 默认每 1 秒才把内存缓冲刷成可搜索段，
     * 而 {@code ElasticsearchOperations#save} 不带刷新策略 —— 于是"发布文档 → 立刻检索"
     * 或"重建索引 → 立刻自检文档数"会读到 0，看起来像没写进去（实测踩到：
     * {@code indexed=28} 但 {@code _count=0}）。本项目的量级（几十~几万篇）刷新一次开销可忽略，
     * 换来确定的读写一致性。</p>
     */
    private void refresh() {
        operations.indexOps(DocumentSearchIndex.class).refresh();
    }

    /**
     * 按范围生成 must 子句。
     *
     * @param keyword 关键词
     * @param scope   检索范围
     * @return Query
     */
    private Query mustClause(String keyword, SearchScope scope) {
        return switch (scope) {
            case TITLE -> MatchQuery.of(m -> m.field("title").query(keyword))._toQuery();
            case CONTENT -> MatchQuery.of(m -> m.field("content").query(keyword))._toQuery();
            case ALL -> MultiMatchQuery.of(m -> m
                    .query(keyword)
                    .fields("title^3", "summary^2", "content")
                    .type(TextQueryType.BestFields))._toQuery();
        };
    }

    /**
     * match_all 查询（计数用）。
     *
     * @return Query
     */
    private Query matchAll() {
        return Query.of(q -> q.matchAll(m -> m));
    }
}
