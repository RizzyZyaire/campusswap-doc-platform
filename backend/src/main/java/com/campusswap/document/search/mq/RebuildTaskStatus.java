package com.campusswap.document.search.mq;

/**
 * 异步重建任务状态（课件 13.1 验收：接口立即返回 {@code SUBMITTED}）。
 *
 * <p>状态流转：{@code SUBMITTED → RUNNING → SUCCEEDED / FAILED}；重复投递到已成功的任务时
 * 消费者直接 ack 跳过（幂等）。</p>
 *
 * @author Zyaire
 */
public enum RebuildTaskStatus {

    /** 已提交（消息已进队列，等待消费）。 */
    SUBMITTED("已提交"),

    /** 消费中（消费者已拉取，正在重建索引）。 */
    RUNNING("执行中"),

    /** 重建成功。 */
    SUCCEEDED("已完成"),

    /** 重建失败（异常已进死信队列，可人工排查后重投）。 */
    FAILED("失败");

    private final String label;

    RebuildTaskStatus(String label) {
        this.label = label;
    }

    /** 中文显示名（界面直接用）。 */
    public String label() {
        return label;
    }
}
