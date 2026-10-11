package com.campusswap.document.search;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 文档检索索引实体（课件 10.1 §4：主库 doc_document 的可检索字段映射到 ES 索引）。
 *
 * <p><b>分词口径（课件 10.1 §3.2 黄金配置）</b>：需要语义检索的三个文本字段一律
 * {@code analyzer = "ik_max_word"}（入库细粒度穷尽分词，保证召回率）+
 * {@code searchAnalyzer = "ik_smart"}（查询粗粒度切分，保证精准度）；
 * 只用于精确过滤/聚合的字段（状态、分类名、标签、作者）声明为 {@code Keyword}，不走分词器。</p>
 *
 * <p><b>为什么日期字段用 Keyword 存 ISO 字符串</b>：ISO-8601 字符串（{@code 2026-10-11T09:30:00}）
 * 的字典序与时间序一致，可以直接排序，同时避开 ES 日期格式转换在客户端/服务端版本不一致时的坑
 * （本项目 Spring Data ES 6.1.1 + Java 客户端 9.4.5，而服务端是 8.18.0）。</p>
 *
 * <p>本类不使用 {@code @Data}（项目红线：实体类禁 {@code @Data}），只开 Getter/Setter + Builder。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = DocumentSearchIndex.INDEX_NAME)
public class DocumentSearchIndex {

    /** 索引名（课件示例用 dochub_document；本项目按库名与业务命名）。 */
    public static final String INDEX_NAME = "campusswap_document";

    /** 文档 ID：与主库 {@code doc_document.id} 强一致。 */
    @Id
    private Long id;

    /** 标题：精细入库 / 粗粒度查询，检索权重最高（title^3）。 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /** 纯文本摘要：权重次之（summary^2）。 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String summary;

    /** 正文 Markdown 原文：默认权重（content）。 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /** 分类 ID：精确过滤用，不分词。 */
    @Field(type = FieldType.Long)
    private Long categoryId;

    /** 分类名称：侧栏聚合展示用，不分词。 */
    @Field(type = FieldType.Keyword)
    private String categoryName;

    /** 文档状态：只索引 PUBLISHED，但保留字段以便过滤与排障。 */
    @Field(type = FieldType.Keyword)
    private String status;

    /** 标签名列表：精确过滤/聚合用，不分词。 */
    @Field(type = FieldType.Keyword)
    private List<String> tags;

    /** 作者 ID（= doc_document.created_by）。 */
    @Field(type = FieldType.Long)
    private Long authorId;

    /** 作者姓名。 */
    @Field(type = FieldType.Keyword)
    private String authorName;

    /** 作者所属部门名（权限/展示过滤用）。 */
    @Field(type = FieldType.Keyword)
    private String deptName;

    /** 发布时间（ISO-8601 字符串，可排序）。 */
    @Field(type = FieldType.Keyword)
    private String publishAt;

    /** 最后更新时间（ISO-8601 字符串，可排序）。 */
    @Field(type = FieldType.Keyword)
    private String updatedAt;

    /** 浏览量（数值，可用于热度排序）。 */
    @Field(type = FieldType.Integer)
    private Integer viewCount;
}
