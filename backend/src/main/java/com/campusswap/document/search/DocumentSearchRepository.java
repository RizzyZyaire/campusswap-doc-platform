package com.campusswap.document.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 文档检索仓储（课件 10.1 §5.1：Spring Data Elasticsearch 派生查询 + JSON DSL）。
 *
 * <p>三条查询对应三种检索范围（用户要求"搜索前能选择标题 / 内容 / 标题+内容"），
 * 权重与过滤口径完全按课件：{@code title^3 / summary^2 / content}，状态过滤放进
 * {@code filter} 上下文（不参与打分、可利用 Bitset 缓存）。</p>
 *
 * <p>返回类型用 {@link SearchHits}（而不是 {@code Page}）：既拿到文档，又拿到
 * {@code _score} 相关度分数——界面要按相关度展示排序依据。</p>
 *
 * <p>增删改查直接用 {@link ElasticsearchRepository} 继承来的 {@code save / saveAll /
 * deleteAllById / count / deleteAll}（全量重建与增量同步都走它），不重复声明。</p>
 *
 * <p>ES 不可用时本接口的任何调用都会抛异常，由 {@code DocumentSearchServiceImpl}
 * 捕获后降级到 MySQL 引擎。</p>
 *
 * @author Zyaire
 */
public interface DocumentSearchRepository extends ElasticsearchRepository<DocumentSearchIndex, Long> {

    /**
     * 范围 = 标题 + 摘要 + 正文（默认）：标题权重 3、摘要 2、正文 1。
     *
     * @param keyword  关键词
     * @param pageable 分页（排序为空时按相关度 _score 倒序）
     * @return 命中结果（含分数与总数）
     */
    @Query("""
            {
              "bool": {
                "must": [
                  {
                    "multi_match": {
                      "query": "?0",
                      "fields": ["title^3", "summary^2", "content"],
                      "type": "best_fields"
                    }
                  }
                ],
                "filter": [
                  { "term": { "status": "PUBLISHED" } }
                ]
              }
            }
            """)
    SearchHits<DocumentSearchIndex> searchAllFields(String keyword, Pageable pageable);

    /**
     * 范围 = 仅标题。
     *
     * @param keyword  关键词
     * @param pageable 分页
     * @return 命中结果
     */
    @Query("""
            {
              "bool": {
                "must": [
                  { "match": { "title": { "query": "?0" } } }
                ],
                "filter": [
                  { "term": { "status": "PUBLISHED" } }
                ]
              }
            }
            """)
    SearchHits<DocumentSearchIndex> searchTitleOnly(String keyword, Pageable pageable);

    /**
     * 范围 = 仅正文。
     *
     * @param keyword  关键词
     * @param pageable 分页
     * @return 命中结果
     */
    @Query("""
            {
              "bool": {
                "must": [
                  { "match": { "content": { "query": "?0" } } }
                ],
                "filter": [
                  { "term": { "status": "PUBLISHED" } }
                ]
              }
            }
            """)
    SearchHits<DocumentSearchIndex> searchContentOnly(String keyword, Pageable pageable);

    /**
     * 带分类过滤的联合检索（分类树上选了某个分类时用；分类 ID 是数值，模板里不加引号）。
     *
     * @param keyword    关键词
     * @param categoryId 分类 ID
     * @param pageable   分页
     * @return 命中结果
     */
    @Query("""
            {
              "bool": {
                "must": [
                  {
                    "multi_match": {
                      "query": "?0",
                      "fields": ["title^3", "summary^2", "content"],
                      "type": "best_fields"
                    }
                  }
                ],
                "filter": [
                  { "term": { "status": "PUBLISHED" } },
                  { "term": { "categoryId": ?1 } }
                ]
              }
            }
            """)
    SearchHits<DocumentSearchIndex> searchAllFieldsInCategory(String keyword, Long categoryId, Pageable pageable);
}
