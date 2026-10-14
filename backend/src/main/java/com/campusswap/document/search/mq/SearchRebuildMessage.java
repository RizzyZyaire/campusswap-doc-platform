package com.campusswap.document.search.mq;

import java.io.Serializable;

/**
 * 异步索引重建消息体（课件 13.1：生产者只负责"下单"，消费者异步执行）。
 *
 * <p><b>为什么消息里只放 taskId 而不是整份任务对象</b>：消息体越小越好投递、越不容易在
 * broker 里堆积；任务的真实状态以 Redis 里的记录为准（消费者按 taskId 回写进度），
 * 这样消息重投、重复消费都不会让状态错乱（消费端幂等，见课件 §7）。</p>
 *
 * @param taskId     全局唯一任务 ID
 * @param operatorId 触发重建的操作人（审计用）
 * @param requestedAt 提交时间戳（毫秒，便于排障时对时序）
 * @author Zyaire
 */
public record SearchRebuildMessage(String taskId, Long operatorId, long requestedAt) implements Serializable {
}
