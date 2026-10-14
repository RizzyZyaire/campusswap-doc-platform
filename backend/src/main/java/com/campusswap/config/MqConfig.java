package com.campusswap.config;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑与序列化配置（课件 13.1：交换机 / 队列 / 死信队列 / JSON 消息）。
 *
 * <p><b>拓扑设计</b>：</p>
 * <pre>
 *   producer ──▶ campusswap.search.exchange (direct)
 *                     │ routingKey = search.rebuild
 *                     ▼
 *              campusswap.search.rebuild  ──(消费失败, requeue=false)──▶ campusswap.search.dlx
 *              （业务队列，手动 ack）                                        │ routingKey = search.rebuild.dlq
 *                                                                            ▼
 *                                                              campusswap.search.rebuild.dlq（死信队列，人工排查/重投）
 * </pre>
 *
 * <p><b>为什么用死信队列而不是无限重投</b>（课件 §6/§7 的可靠性三道防线）：索引重建失败通常是
 * "ES 挂了/映射冲突"这类需要人工介入的问题，原地 requeue 只会把队列堵死并刷爆日志；
 * 让失败消息进 DLQ 保留现场，是工业界标准做法（也是本章验收标准第 4 条）。</p>
 *
 * <p><b>序列化用 JSON 而不是 Java 序列化</b>：消息在管理控制台里可读、跨语言、类结构演进不炸旧消息
 * ——与项目 Redis 口径一致（见 RedisConfig 的说明）。</p>
 *
 * @author Zyaire
 */
@Configuration
public class MqConfig {

    /** 业务交换机（direct）。 */
    public static final String SEARCH_EXCHANGE = "campusswap.search.exchange";

    /** 索引重建业务队列。 */
    public static final String REBUILD_QUEUE = "campusswap.search.rebuild";

    /** 重建消息路由键。 */
    public static final String REBUILD_ROUTING_KEY = "search.rebuild";

    /** 死信交换机。 */
    public static final String SEARCH_DLX = "campusswap.search.dlx";

    /** 索引重建死信队列（课件验收要求的 {@code dochub.document.dlq} 在本项目里的对应物）。 */
    public static final String REBUILD_DLQ = "campusswap.search.rebuild.dlq";

    /** 死信路由键。 */
    public static final String REBUILD_DLQ_ROUTING_KEY = "search.rebuild.dlq";

    /**
     * 业务交换机。
     *
     * @return 直连交换机
     */
    @Bean
    public DirectExchange searchExchange() {
        return new DirectExchange(SEARCH_EXCHANGE, true, false);
    }

    /**
     * 索引重建业务队列：绑定死信交换机，消费失败（nack 且 requeue=false）时消息自动进 DLQ。
     *
     * @return 队列
     */
    @Bean
    public Queue searchRebuildQueue() {
        return QueueBuilder.durable(REBUILD_QUEUE)
                .deadLetterExchange(SEARCH_DLX)
                .deadLetterRoutingKey(REBUILD_DLQ_ROUTING_KEY)
                .build();
    }

    /**
     * 业务队列绑定。
     *
     * @param searchRebuildQueue 业务队列
     * @param searchExchange     业务交换机
     * @return 绑定关系
     */
    @Bean
    public Binding searchRebuildBinding(Queue searchRebuildQueue, DirectExchange searchExchange) {
        return BindingBuilder.bind(searchRebuildQueue).to(searchExchange).with(REBUILD_ROUTING_KEY);
    }

    /**
     * 死信交换机。
     *
     * @return 直连交换机
     */
    @Bean
    public DirectExchange searchDlx() {
        return new DirectExchange(SEARCH_DLX, true, false);
    }

    /**
     * 死信队列（不设 TTL，消息保留到人工处理）。
     *
     * @return 队列
     */
    @Bean
    public Queue searchRebuildDlq() {
        return QueueBuilder.durable(REBUILD_DLQ).build();
    }

    /**
     * 死信队列绑定。
     *
     * @param searchRebuildDlq 死信队列
     * @param searchDlx        死信交换机
     * @return 绑定关系
     */
    @Bean
    public Binding searchRebuildDlqBinding(Queue searchRebuildDlq, DirectExchange searchDlx) {
        return BindingBuilder.bind(searchRebuildDlq).to(searchDlx).with(REBUILD_DLQ_ROUTING_KEY);
    }

    /**
     * JSON 消息转换器（复用 Spring Boot 的 ObjectMapper，时间/中文处理口径与接口一致）。
     *
     * <p>注意包名：Spring Boot 4 / Spring AMQP 4 用的是 <b>Jackson 3</b>（{@code tools.jackson}），
     * 对应的转换器是 {@link JacksonJsonMessageConverter}（Jackson 2 时代的
     * {@code Jackson2JsonMessageConverter} 只能吃 {@code com.fasterxml} 的 ObjectMapper，会编译不过）。</p>
     *
     * @param jsonMapper Boot 提供的 Jackson 3 JsonMapper
     * @return 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

    /**
     * 显式声明 RabbitTemplate 并挂上 JSON 转换器（覆盖自动配置的同名 Bean，行为一致但确定）。
     *
     * @param connectionFactory  连接工厂
     * @param jsonMessageConverter JSON 转换器
     * @return 模板
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
