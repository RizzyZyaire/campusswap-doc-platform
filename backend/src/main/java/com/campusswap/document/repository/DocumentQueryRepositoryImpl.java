package com.campusswap.document.repository;

import com.campusswap.entity.Document;
import com.campusswap.entity.DocumentTagRel;
import com.campusswap.entity.enums.DocumentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DocumentQueryRepository} 的 Criteria 实现。
 *
 * <p>注意：{@code @SQLRestriction("deleted = 0")} 对 Criteria 查询<b>自动生效</b>，
 * 所以这里看不到回收站数据属正常（回收站走 {@code DocumentRepository} 的原生 SQL）。</p>
 *
 * @author Zyaire
 */
public class DocumentQueryRepositoryImpl implements DocumentQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 动态条件分页查询（一条 SQL 取数据 + 一条 SQL 取总数）。
     *
     * @param query    动态条件
     * @param pageable 分页与排序
     * @return 分页结果
     */
    @Override
    public Page<DocumentListRow> search(DocumentListQuery query, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<DocumentListRow> dataQuery = cb.createQuery(DocumentListRow.class);
        Root<Document> root = dataQuery.from(Document.class);
        dataQuery.select(cb.construct(DocumentListRow.class,
                root.get("id"), root.get("title"), root.get("summary"), root.get("categoryId"),
                root.get("createdBy"), root.get("status"), root.get("versionNum"), root.get("priceCents"),
                root.get("viewCount"), root.get("favoriteCount"),
                root.get("createdAt"), root.get("updatedAt"), root.get("updatedBy")));
        dataQuery.where(predicates(cb, root, dataQuery, query));
        dataQuery.orderBy(orders(cb, root, pageable.getSort()));

        TypedQuery<DocumentListRow> typedQuery = entityManager.createQuery(dataQuery);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<DocumentListRow> content = typedQuery.getResultList();

        // 与 Spring Data 的派生分页保持一致：末页（返回条数 < pageSize 且 offset = 0）不跑 count 查询。
        // 这样本片段与 JpaRepository 的分页行为、SQL 预算口径完全对齐。
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            CriteriaBuilder countBuilder = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> countQuery = countBuilder.createQuery(Long.class);
            Root<Document> countRoot = countQuery.from(Document.class);
            countQuery.select(countBuilder.count(countRoot));
            countQuery.where(predicates(countBuilder, countRoot, countQuery, query));
            return entityManager.createQuery(countQuery).getSingleResult();
        });
    }

    /**
     * 取文档的标签 ID 列表（一条 SQL，顺序与打标签顺序一致）。
     *
     * @param documentId 文档 ID
     * @return 标签 ID 列表
     */
    @Override
    public List<Long> findTagIds(Long documentId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<DocumentTagRel> root = query.from(DocumentTagRel.class);
        query.select(root.get("tagId"))
                .where(cb.equal(root.get("documentId"), documentId))
                .orderBy(cb.asc(root.get("createdAt")));
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * 组装动态谓词。
     *
     * @param cb    条件构造器
     * @param root  文档根
     * @param query 承载子查询的查询对象
     * @param spec  查询条件
     * @return 谓词数组
     */
    private Predicate[] predicates(CriteriaBuilder cb, Root<Document> root,
                                   CriteriaQuery<?> query, DocumentListQuery spec) {
        List<Predicate> predicates = new ArrayList<>();
        if (StringUtils.hasText(spec.keyword())) {
            String like = "%" + spec.keyword().trim() + "%";
            predicates.add(cb.or(cb.like(root.get("title"), like), cb.like(root.get("summary"), like)));
        }
        if (spec.statuses() != null && !spec.statuses().isEmpty()) {
            predicates.add(root.get("status").in(spec.statuses()));
        }
        if (spec.authorId() != null) {
            predicates.add(cb.equal(root.get("createdBy"), spec.authorId()));
        }
        if (spec.categoryIds() != null && !spec.categoryIds().isEmpty()) {
            predicates.add(root.get("categoryId").in(spec.categoryIds()));
        }
        if (spec.tagIds() != null && !spec.tagIds().isEmpty()) {
            // AND 命中：每个标签一个 IN 子查询，合起来仍是同一条 SQL
            for (Long tagId : spec.tagIds()) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<DocumentTagRel> relRoot = subquery.from(DocumentTagRel.class);
                subquery.select(relRoot.get("documentId")).where(cb.equal(relRoot.get("tagId"), tagId));
                predicates.add(root.get("id").in(subquery));
            }
        }
        if (spec.startTime() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), spec.startTime()));
        }
        if (spec.endTime() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), spec.endTime()));
        }
        return predicates.toArray(new Predicate[0]);
    }

    /**
     * 组装排序（属性名来自 Service 侧已经白名单化的 Sort）。
     *
     * @param cb   条件构造器
     * @param root 文档根
     * @param sort 排序规则
     * @return 排序数组
     */
    private List<Order> orders(CriteriaBuilder cb, Root<Document> root, Sort sort) {
        List<Order> orders = new ArrayList<>();
        if (sort == null || sort.isUnsorted()) {
            orders.add(cb.desc(root.get("updatedAt")));
            orders.add(cb.desc(root.get("id")));
            return orders;
        }
        for (Sort.Order order : sort) {
            orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty())) : cb.desc(root.get(order.getProperty())));
        }
        return orders;
    }

    /**
     * 便于其它查询复用的状态集合：非回收站（回收站在原生链路里单独处理）。
     *
     * @return 非回收站状态
     */
    public static List<DocumentStatus> visibleStatuses() {
        return List.of(DocumentStatus.DRAFT, DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED);
    }
}
