package com.campusswap.document.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 索引重建结果出参（{@code POST /api/search/rebuild}）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class SearchRebuildVo {

    /** 是否成功。 */
    private boolean success;

    /** 从主库读出并推送的文档数。 */
    private long indexed;

    /** 重建后 ES 索引里的文档数（用于自检：应与主库 PUBLISHED 数一致）。 */
    private long indexCount;

    /** 耗时（毫秒）。 */
    private long elapsedMs;

    /** 中文说明（成功条数或失败原因）。 */
    private String message;
}
