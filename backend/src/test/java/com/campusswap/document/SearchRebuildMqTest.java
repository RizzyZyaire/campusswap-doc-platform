package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusswap.common.security.RedisKeys;
import com.campusswap.document.search.DocumentSearchService;
import com.campusswap.document.search.mq.RebuildTaskStatus;
import com.campusswap.document.search.mq.SearchRebuildConsumer;
import com.campusswap.document.search.mq.SearchRebuildMessage;
import com.campusswap.document.search.mq.SearchRebuildTask;
import com.campusswap.document.search.mq.SearchRebuildTaskStore;
import com.campusswap.document.vo.SearchRebuildVo;
import com.rabbitmq.client.Channel;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * M13 异步索引重建消费者单测（课件 13.1 验收第 2、3、4 条的代码级验证）。
 *
 * <p>不连真实 broker：用 mock 的 {@link Channel} 断言 ack/nack 的**具体参数**——
 * 成功必须 {@code basicAck}；失败必须 {@code basicNack(requeue=false)}（requeue=true 会把队列堵死，
 * 也是本单测最想守住的一条线）；重复投递到已成功的任务必须幂等跳过。</p>
 *
 * @author Zyaire
 */
@DisplayName("M13 异步索引重建消费者")
class SearchRebuildMqTest {

    private static final long DELIVERY_TAG = 7L;

    private SearchRebuildTaskStore taskStore;

    private DocumentSearchService documentSearchService;

    private StringRedisTemplate redis;

    private ValueOperations<String, String> valueOps;

    private SearchRebuildConsumer consumer;

    private SearchRebuildTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskStore = mock(SearchRebuildTaskStore.class);
        documentSearchService = mock(DocumentSearchService.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 抢锁成功
        when(valueOps.setIfAbsent(eq(RedisKeys.SEARCH_REBUILD_LOCK), any(), any(Duration.class))).thenReturn(true);
        when(valueOps.get(RedisKeys.SEARCH_REBUILD_LOCK)).thenReturn("task-1");
        consumer = new SearchRebuildConsumer(taskStore, documentSearchService, redis);

        task = new SearchRebuildTask();
        task.setTaskId("task-1");
        task.setStatus(RebuildTaskStatus.SUBMITTED);
        when(taskStore.get("task-1")).thenReturn(task);
    }

    @Test
    @DisplayName("消费成功：调用重建服务后手动 ack，任务置为 SUCCEEDED")
    void ackOnSuccess() throws Exception {
        SearchRebuildVo vo = new SearchRebuildVo();
        vo.setSuccess(true);
        vo.setIndexed(28);
        vo.setIndexCount(28);
        vo.setElapsedMs(550);
        vo.setMessage("索引重建完成");
        when(documentSearchService.rebuildAll()).thenReturn(vo);

        Channel channel = mock(Channel.class);
        consumer.onRebuildMessage(new SearchRebuildMessage("task-1", 1L, System.currentTimeMillis()), channel,
                DELIVERY_TAG);

        verify(taskStore).markRunning(task);
        verify(taskStore).markSucceeded(task, 28, 28, 550, "索引重建完成");
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("重建失败：nack 且 requeue=false，消息进死信队列而不是原地重投")
    void nackToDlqOnFailure() throws Exception {
        SearchRebuildVo vo = new SearchRebuildVo();
        vo.setSuccess(false);
        vo.setMessage("Elasticsearch 未连接（http://127.0.0.1:9200）");
        when(documentSearchService.rebuildAll()).thenReturn(vo);

        Channel channel = mock(Channel.class);
        consumer.onRebuildMessage(new SearchRebuildMessage("task-1", 1L, System.currentTimeMillis()), channel,
                DELIVERY_TAG);

        verify(taskStore).markFailed(task, "Elasticsearch 未连接（http://127.0.0.1:9200）");
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("消费抛异常：同样 nack 进死信队列，任务标记失败")
    void nackToDlqOnException() throws Exception {
        when(documentSearchService.rebuildAll()).thenThrow(new IllegalStateException("ES 连接抖动"));

        Channel channel = mock(Channel.class);
        consumer.onRebuildMessage(new SearchRebuildMessage("task-1", 1L, System.currentTimeMillis()), channel,
                DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(taskStore).markFailed(any(), org.mockito.ArgumentMatchers.contains("ES 连接抖动"));
    }

    @Test
    @DisplayName("幂等：任务已成功过，重复投递只 ack 不重复重建")
    void idempotentWhenAlreadySucceeded() throws Exception {
        task.setStatus(RebuildTaskStatus.SUCCEEDED);

        Channel channel = mock(Channel.class);
        consumer.onRebuildMessage(new SearchRebuildMessage("task-1", 1L, System.currentTimeMillis()), channel,
                DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(documentSearchService, never()).rebuildAll();
    }

    @Test
    @DisplayName("未知任务：直接 ack 丢弃，不触发重建")
    void ackUnknownTask() throws Exception {
        when(taskStore.get("task-x")).thenReturn(null);

        Channel channel = mock(Channel.class);
        consumer.onRebuildMessage(new SearchRebuildMessage("task-x", 1L, System.currentTimeMillis()), channel,
                DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(documentSearchService, never()).rebuildAll();
    }

    @Test
    @DisplayName("已有重建在跑：把消息退回队列稍后重试（requeue=true），不并发重建")
    void requeueWhenLocked() throws Exception {
        when(valueOps.setIfAbsent(eq(RedisKeys.SEARCH_REBUILD_LOCK), any(), any(Duration.class))).thenReturn(false);

        Channel channel = mock(Channel.class);
        consumer.onRebuildMessage(new SearchRebuildMessage("task-1", 1L, System.currentTimeMillis()), channel,
                DELIVERY_TAG);

        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, true);
        verify(documentSearchService, never()).rebuildAll();
        assertThat(task.getStatus()).isEqualTo(RebuildTaskStatus.SUBMITTED);
    }
}
