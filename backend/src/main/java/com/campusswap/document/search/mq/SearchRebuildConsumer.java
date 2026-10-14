package com.campusswap.document.search.mq;

import com.campusswap.common.security.RedisKeys;
import com.campusswap.config.MqConfig;
import com.campusswap.document.search.DocumentSearchService;
import com.campusswap.document.vo.SearchRebuildVo;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 索引重建消息**消费者**（课件 13.1 验收第 2、3、4 条）。
 *
 * <p>四个要点：</p>
 * <ol>
 *   <li><b>手动 ack</b>（{@code ackMode = "MANUAL"}）：业务真正执行成功后才 {@code basicAck}，
 *       中途宕机消息会重新投递，不会"消息没了但索引也没重建"；</li>
 *   <li><b>幂等</b>：任务已是 {@code SUCCEEDED} 时直接 ack 跳过（重复投递不重复干活，课件 §7）；</li>
 *   <li><b>失败进死信队列</b>：异常时 {@code basicNack(requeue=false)} —— 业务队列上的死信配置会把消息
 *       路由到 {@code campusswap.search.rebuild.dlq}，避免原地重投把队列堵死（课件验收第 4 条）；</li>
 *   <li><b>串行锁</b>：同一时刻只允许一个重建真正执行（全量重建会删索引重建，并发跑会互相打架）；
 *       拿不到锁时把消息退回队列稍后再试（有锁 TTL 兜底，不会无限循环）。</li>
 * </ol>
 *
 * @author Zyaire
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchRebuildConsumer {

    /** 重建串行锁的持有时间（超过则自动释放，避免消费者崩溃后死锁）。 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    /** 抢锁失败时的退避时间（毫秒），避免热循环。 */
    private static final long BUSY_BACKOFF_MS = 3000L;

    private final SearchRebuildTaskStore taskStore;

    private final DocumentSearchService documentSearchService;

    private final StringRedisTemplate redis;

    /**
     * 消费重建消息。
     *
     * @param message     消息体（只带 taskId，状态以 Redis 为准）
     * @param channel     AMQP 通道（用于手动 ack/nack）
     * @param deliveryTag 投递标签
     * @throws IOException ack/nack 失败（连接断了，交给容器重连）
     */
    @RabbitListener(queues = MqConfig.REBUILD_QUEUE, ackMode = "MANUAL")
    public void onRebuildMessage(SearchRebuildMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String taskId = message == null ? null : message.taskId();
        SearchRebuildTask task = taskStore.get(taskId);
        if (task == null) {
            log.warn("收到未知的重建任务消息，直接确认: taskId={}", taskId);
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (task.getStatus() == RebuildTaskStatus.SUCCEEDED) {
            log.info("任务已成功过，幂等跳过: taskId={}", taskId);
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (!tryLock(taskId)) {
            log.info("已有重建任务在执行，稍后重试: taskId={}", taskId);
            sleep(BUSY_BACKOFF_MS);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        try {
            taskStore.markRunning(task);
            SearchRebuildVo result = documentSearchService.rebuildAll();
            if (result.isSuccess()) {
                taskStore.markSucceeded(task, result.getIndexed(), result.getIndexCount(), result.getElapsedMs(),
                        result.getMessage());
                channel.basicAck(deliveryTag, false);
                log.info("异步索引重建完成: taskId={}, indexed={}, indexCount={}, elapsedMs={}", taskId,
                        result.getIndexed(), result.getIndexCount(), result.getElapsedMs());
            } else {
                taskStore.markFailed(task, result.getMessage());
                channel.basicNack(deliveryTag, false, false);
                log.warn("异步索引重建失败，消息进入死信队列: taskId={}, reason={}", taskId, result.getMessage());
            }
        } catch (Exception e) {
            taskStore.markFailed(task, "消费异常：" + e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            log.error("异步索引重建异常，消息进入死信队列: taskId={}, err={}", taskId, e.getMessage(), e);
        } finally {
            unlock(taskId);
        }
    }

    /**
     * 尝试获取串行锁。
     *
     * @param taskId 任务 ID
     * @return true = 拿到锁
     */
    private boolean tryLock(String taskId) {
        Boolean ok = redis.opsForValue().setIfAbsent(RedisKeys.SEARCH_REBUILD_LOCK, taskId, LOCK_TTL);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放串行锁（只释放自己持有的那把）。
     *
     * @param taskId 任务 ID
     */
    private void unlock(String taskId) {
        try {
            String owner = redis.opsForValue().get(RedisKeys.SEARCH_REBUILD_LOCK);
            if (taskId != null && taskId.equals(owner)) {
                redis.delete(RedisKeys.SEARCH_REBUILD_LOCK);
            }
        } catch (Exception e) {
            log.warn("释放重建锁失败: taskId={}, {}", taskId, e.getMessage());
        }
    }

    /**
     * 短暂等待（抢锁失败退避）。
     *
     * @param millis 毫秒
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
