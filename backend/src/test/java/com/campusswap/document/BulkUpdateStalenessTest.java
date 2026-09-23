package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.entity.Document;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一级缓存脏读验证（课件 4.1 DoD③：{@code @Modifying(clearAutomatically = true)} 杜绝一级缓存脏读）。
 *
 * <p>两个测试构成一组<b>对照实验</b>：同一个事务里「先读 → 批量自增 → 再读」。</p>
 * <ul>
 *   <li>{@link #withClearAutomatically()}：走我们的仓储方法（已带 {@code clearAutomatically}），
 *       第二次读到的是<b>新值</b> —— 这是修复后的正确行为；</li>
 *   <li>{@link #withoutClearAutomatically()}：走原生 {@code EntityManager.executeUpdate}
 *       （等价于「没加这个参数的 {@code @Modifying}」），第二次读到的仍是<b>旧值</b>
 *       —— 复现课件所说的脏读，用来证明这个参数不是可有可无的装饰。</li>
 * </ul>
 *
 * @author Zyaire
 */
@SpringBootTest
@ActiveProfiles("test")
class BulkUpdateStalenessTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** 临时文档 ID。 */
    private Long docId;

    /** 自增前阅读量。 */
    private int initial;

    /**
     * 插入临时文档行。
     */
    @BeforeEach
    void setUp() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO doc_document (title, status, version_num, view_count, favorite_count,"
                            + " price_cents, created_at, updated_at, deleted)"
                            + " VALUES (?, 'DRAFT', 1, 7, 0, 0, NOW(), NOW(), 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "[test] 一级缓存脏读探针");
            return ps;
        }, keyHolder);
        docId = keyHolder.getKey().longValue();
        initial = dbViewCount();
    }

    /**
     * 物理删除临时行。
     */
    @AfterEach
    void tearDown() {
        if (docId != null) {
            jdbcTemplate.update("DELETE FROM doc_document WHERE id = ?", docId);
        }
    }

    /**
     * 对照组：原生 executeUpdate 不清一级缓存 → 同一事务内读到旧值（复现课件所说脏读）。
     */
    @Test
    @DisplayName("对照组：不加 clearAutomatically 时，同事务内读到的是旧值（脏读复现）")
    void withoutClearAutomatically() {
        Integer secondRead = new TransactionTemplate(txManager).execute(status -> {
            Document first = entityManager.find(Document.class, docId);
            assertThat(first.getViewCount()).isEqualTo(initial);

            int updated = entityManager
                    .createQuery("update Document d set d.viewCount = d.viewCount + 1 where d.id = :id")
                    .setParameter("id", docId)
                    .executeUpdate();
            assertThat(updated).isEqualTo(1);

            Document again = entityManager.find(Document.class, docId);
            return again.getViewCount();
        });

        assertThat(secondRead)
                .as("一级缓存里还是旧对象 → 第二次读到的仍是 %d", initial)
                .isEqualTo(initial);
        assertThat(dbViewCount())
                .as("但数据库其实已经 +1：这正是「缓存与库不一致」的脏读")
                .isEqualTo(initial + 1);
    }

    /**
     * 实验组：我们的仓储方法带 {@code clearAutomatically = true} → 同一事务内读到新值。
     */
    @Test
    @DisplayName("实验组：加了 clearAutomatically 后，同事务内读到新值")
    void withClearAutomatically() {
        Integer secondRead = new TransactionTemplate(txManager).execute(status -> {
            Document first = documentRepository.findById(docId).orElseThrow();
            assertThat(first.getViewCount()).isEqualTo(initial);

            documentRepository.increaseViewCount(docId);

            Document again = documentRepository.findById(docId).orElseThrow();
            return again.getViewCount();
        });

        assertThat(secondRead)
                .as("持久化上下文被清空 → 第二次读必须回源数据库拿到 %d", initial + 1)
                .isEqualTo(initial + 1);
        assertThat(dbViewCount()).isEqualTo(initial + 1);
    }

    /**
     * 绕开一级缓存直读数据库。
     *
     * @return 阅读量
     */
    private int dbViewCount() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT view_count FROM doc_document WHERE id = ?", Integer.class, docId);
        return value == null ? -1 : value;
    }
}
