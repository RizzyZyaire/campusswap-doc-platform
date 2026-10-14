package com.campusswap.document.search.mq;

import com.campusswap.config.MqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 索引重建消息**生产者**（课件 13.1 §1：把耗时任务从请求链路里摘出去）。
 *
 * <p>同步重建 2 万篇文档要几十秒，用户只能干等且会占满 Tomcat 线程；改成"提交任务→立即返回"
 * 后，接口只做三件事：建任务记录（Redis）、发一条消息、返回 taskId —— 验收要求
 * <b>20ms 内返回 SUBMITTED</b>（实测见 M13-CLOSURE）。</p>
 *
 * @author Zyaire
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchRebuildProducer {

    private final RabbitTemplate rabbitTemplate;

    private final SearchRebuildTaskStore taskStore;

    /**
     * 投递重建消息。
     *
     * @param task 已创建的任务（状态 SUBMITTED）
     * @return true = 投递成功；false = broker 不可用（任务已标记 FAILED）
     */
    public boolean send(SearchRebuildTask task) {
        SearchRebuildMessage message = new SearchRebuildMessage(task.getTaskId(), task.getOperatorId(),
                System.currentTimeMillis());
        try {
            rabbitTemplate.convertAndSend(MqConfig.SEARCH_EXCHANGE, MqConfig.REBUILD_ROUTING_KEY, message);
            log.info("索引重建任务已投递: taskId={}, operator={}", task.getTaskId(), task.getOperatorId());
            return true;
        } catch (AmqpException e) {
            // 队列不可用时不吞异常：任务标记失败并把原因回给前端（录屏时一眼能看出 MQ 没起）
            log.warn("索引重建任务投递失败: taskId={}, {}", task.getTaskId(), e.getMessage());
            taskStore.markFailed(task, "消息队列不可用（请确认 RabbitMQ 已启动，5672 端口可连）：" + e.getMessage());
            return false;
        }
    }
}
