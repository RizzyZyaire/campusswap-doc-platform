package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusswap.document.repository.DocumentRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 高并发计数验证（课件 4.1 DoD④：100 线程并发递增，最终结果必须精准等于 100）。
 *
 * <p>验证对象：{@code DocumentRepository#increaseViewCount} —— 单条
 * {@code UPDATE ... SET view_count = view_count + 1} 原子累加，取代「先查后改」的读改写路径。</p>
 *
 * <p>数据卫生：只插一行临时文档（{@code doc_document} 除主键外仅 {@code title} 是必填列），
 * 在 {@link AfterEach} 里物理删除，绝不触碰种子数据。连接池由 {@code application-test.yml}
 * 放大到 120，保证 100 个线程是**真并发**而不是在池子上排队。</p>
 *
 * @author Zyaire
 */
@SpringBootTest
@ActiveProfiles("test")
class ViewCountConcurrencyTest {

    /** 并发线程数（与课件口径一致）。 */
    private static final int THREADS = 100;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试用的临时文档 ID。 */
    private Long docId;

    /** 自增前的阅读量。 */
    private int initial;

    /**
     * 插入临时文档行（走原生 INSERT，避免依赖 JPA 审计上下文）。
     */
    @BeforeEach
    void setUp() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO doc_document (title, status, version_num, view_count, favorite_count,"
                            + " price_cents, created_at, updated_at, deleted)"
                            + " VALUES (?, 'DRAFT', 1, 0, 0, 0, NOW(), NOW(), 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "[test] 并发阅读量探针");
            return ps;
        }, keyHolder);
        docId = keyHolder.getKey().longValue();
        initial = viewCount();
    }

    /**
     * 物理删除临时行（不依赖断言结果，保证失败时也不留垃圾）。
     */
    @AfterEach
    void tearDown() {
        if (docId != null) {
            jdbcTemplate.update("DELETE FROM doc_document WHERE id = ?", docId);
        }
    }

    /**
     * 100 线程同时累加阅读量，一次都不能丢。
     *
     * @throws Exception 线程等待异常
     */
    @Test
    @DisplayName("100 线程并发自增阅读量 → 恰好 +100，无丢失更新")
    void oneHundredConcurrentIncrementsLoseNothing() throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREADS);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        // 所有线程在闸门放开的那一刻同时发起请求，最大化竞争窗口
                        startGate.await();
                        documentRepository.increaseViewCount(docId);
                    } catch (Exception ex) {
                        failures.incrementAndGet();
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finishGate.await(120, TimeUnit.SECONDS))
                    .as("100 个并发任务应在 120 秒内全部完成").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).as("并发自增不应抛异常").isZero();
        assertThat(viewCount())
                .as("原子 SQL 累加必须一次不丢：初始 %d + %d 线程", initial, THREADS)
                .isEqualTo(initial + THREADS);
    }

    /**
     * 读数据库里的当前阅读量（绕开一级缓存）。
     *
     * @return 阅读量
     */
    private int viewCount() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT view_count FROM doc_document WHERE id = ?", Integer.class, docId);
        return value == null ? -1 : value;
    }
}
