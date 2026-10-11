package com.campusswap.document.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 检索引擎状态出参（{@code GET /api/search/status}）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class SearchEngineStatusVo {

    /** ES 是否可用（true = 走 ES 引擎）。 */
    private boolean elasticsearchAvailable;

    /** 当前实际引擎：elasticsearch / mysql。 */
    private String engine;

    /** 中文说明（界面直接显示）。 */
    private String message;

    /** 索引内文档数（ES 不可用时为 -1）。 */
    private long indexCount;

    /** ES 地址（回显，便于排障）。 */
    private String endpoint;

    /** IK 分词器是否就位（ES 可用时探测；不可用时为 false）。 */
    private boolean ikAnalyzerReady;
}
