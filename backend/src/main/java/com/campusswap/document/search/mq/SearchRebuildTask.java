package com.campusswap.document.search.mq;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

/**
 * 异步重建任务（Redis 里的一条记录，JSON 字符串存储）。
 *
 * <p>字段刻意与前端展示一一对应：状态徽标、提交/开始/结束时间、同步条数、耗时、失败原因。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class SearchRebuildTask {

    /** 全局唯一任务 ID（UUID 去横线）。 */
    private String taskId;

    /** 当前状态。 */
    private RebuildTaskStatus status;

    /** 状态中文名（回显，省得前端再映射）。 */
    private String statusLabel;

    /** 触发人 ID（按项目铁律序列化为字符串，前端 TS 一律 string）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    /** 提交时间（ISO-8601）。 */
    private String submittedAt;

    /** 开始执行时间（ISO-8601）。 */
    private String startedAt;

    /** 结束时间（ISO-8601）。 */
    private String finishedAt;

    /** 本次从主库同步进索引的文档数。 */
    private Long indexed;

    /** 重建后索引内文档总数。 */
    private Long indexCount;

    /** 重建耗时（毫秒，消费者侧统计）。 */
    private Long elapsedMs;

    /** 中文结果说明或失败原因。 */
    private String message;

    /** 投递尝试次数（消费者每次拉取 +1，便于排查重复投递）。 */
    private Integer attempts;

    /** 提交接口耗时（毫秒）——验收要求"20ms 内返回 SUBMITTED"。 */
    private Long submitCostMs;

    /**
     * 状态变更时同步刷新中文名。
     *
     * @param status 新状态
     */
    public void setStatus(RebuildTaskStatus status) {
        this.status = status;
        this.statusLabel = status == null ? null : status.label();
    }
}
