package com.campusswap.document.vo;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 全文检索出参（新增接口 {@code GET /api/search/documents} 的 data）。
 *
 * <p>结构与全站分页口径一致（{@code list/total/pageNum/pageSize}），额外带三样东西：</p>
 * <ul>
 *   <li>{@code engine}：本次实际使用的检索引擎（{@code elasticsearch} / {@code mysql}）。
 *       这是"双引擎降级"的对外契约：ES 没启动时检索照常可用，只是分数为 null；</li>
 *   <li>{@code engineMessage}：给界面直接显示的中文说明（如"Elasticsearch 未连接，已降级为
 *       MySQL 引擎（无相关度排序）"）；</li>
 *   <li>{@code scope}：本次生效的检索范围与其中文名，让用户明确"我搜的是标题还是正文"。</li>
 * </ul>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class SearchResultVo {

    /** 实际使用的引擎：elasticsearch / mysql。 */
    private String engine;

    /** 引擎说明（中文，界面直接显示）。 */
    private String engineMessage;

    /** 生效的检索范围（TITLE / CONTENT / ALL）。 */
    private String scope;

    /** 检索范围中文名。 */
    private String scopeLabel;

    /** 本次检索的关键词（原样回显）。 */
    private String keyword;

    /** 命中列表（已高亮）。 */
    private List<SearchHitVo> list;

    /** 命中总数。 */
    private long total;

    /** 当前页码（从 1 开始）。 */
    private int pageNum;

    /** 每页条数。 */
    private int pageSize;
}
