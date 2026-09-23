package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.dto.DocumentCreateDtoReq;
import com.campusswap.document.service.DocumentService;
import com.campusswap.document.vo.DocumentDetailVo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 标签绑定一致性验证 —— 锁死 A1 加固时被机检抓到的那个真实缺陷。
 *
 * <p><b>缺陷现象（2026-09-23 实测）</b>：`doc_document_tag_rel` 用 `@EmbeddedId` 复合主键，
 * `saveAll` 的新行不会立即 INSERT，而是挂在持久化上下文里等 flush。当时 19 处 `@Modifying`
 * 只加了 `clearAutomatically`，其后紧跟的标签计数原子更新查的是 `doc_tag`（查询空间不含关联表），
 * Hibernate 的自动 flush 因此**没有**把待插入的关联行刷下去，紧接着的 `clear()` 把这批行直接丢掉：
 * 表现为"文档建出来了、标签计数 +1 了，关联表却是 0 行、详情里 tags 为空"。
 *
 * <p>修法：19 处统一写成 `clearAutomatically = true, flushAutomatically = true`（先 flush 再执行、
 * 执行后清缓存）。本测试从 Service 层真实走一遍「建文档 + 打 2 个标签」，断言四件事同时成立：
 * 响应 tags = 2、关联表 2 行、两个标签计数各 +1、版本留痕 1 条。</p>
 *
 * <p>数据卫生：只动自己创建的那一行文档与它带来的计数增量，`@AfterEach` 物理清理并回滚计数。</p>
 *
 * @author Zyaire
 */
@SpringBootTest
@ActiveProfiles("test")
class TagBindingConsistencyTest {

    /** 库中 admin 的用户 ID（作为测试中的操作人）。 */
    private static final long OPERATOR_ID = 1L;

    /** 种子标签 ID（data.sql：1=国家自然科学基金，2=实习支教）。 */
    private static final List<Long> TAG_IDS = List.of(1L, 2L);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次创建的临时文档 ID。 */
    private Long docId;

    /**
     * 写入登录上下文（Service 层从 {@code SecurityContext} 取当前用户）。
     */
    @BeforeEach
    void setUp() {
        SecurityContext.set(OPERATOR_ID, "test-token");
    }

    /**
     * 物理清理临时文档及其留痕，并把标签计数回滚到测试前的值。
     */
    @AfterEach
    void tearDown() {
        if (docId != null) {
            jdbcTemplate.update("DELETE FROM doc_document_tag_rel WHERE document_id = ?", docId);
            jdbcTemplate.update("DELETE FROM doc_version WHERE document_id = ?", docId);
            jdbcTemplate.update("DELETE FROM doc_favorite WHERE document_id = ?", docId);
            jdbcTemplate.update("DELETE FROM doc_document WHERE id = ?", docId);
            for (Long tagId : TAG_IDS) {
                jdbcTemplate.update("UPDATE doc_tag SET use_count = use_count - 1 WHERE id = ? AND use_count > 0", tagId);
            }
        }
        SecurityContext.clear();
    }

    /**
     * 建文档打标签：关联行必须真的落库，且计数与关联行数一致。
     */
    @Test
    @DisplayName("建文档打 2 个标签：响应 tags=2、关联表 2 行、计数各 +1（flush 前置的回归锁）")
    void tagRelationsSurviveBulkCounterUpdate() {
        int before1 = useCount(1L);
        int before2 = useCount(2L);

        DocumentDetailVo vo = documentService.create(new DocumentCreateDtoReq(
                "[test] 标签绑定一致性探针", null, "probe body", "1",
                List.of("1", "2"), 0));

        docId = Long.valueOf(vo.getId());

        assertThat(vo.getTags())
                .as("详情响应里的标签条数").hasSize(2);
        assertThat(relationCount(docId))
                .as("关联表必须真的有 2 行（缺陷时这里是 0）").isEqualTo(2);
        assertThat(useCount(1L)).as("标签 1 计数应 +1").isEqualTo(before1 + 1);
        assertThat(useCount(2L)).as("标签 2 计数应 +1").isEqualTo(before2 + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM doc_version WHERE document_id = ?", Integer.class, docId))
                .as("建文档应留 1 条 CREATE 版本").isEqualTo(1);
    }

    /**
     * 关联表的真实行数。
     *
     * @param documentId 文档 ID
     * @return 行数
     */
    private int relationCount(Long documentId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM doc_document_tag_rel WHERE document_id = ?", Integer.class, documentId);
        return value == null ? -1 : value;
    }

    /**
     * 标签的冗余计数。
     *
     * @param tagId 标签 ID
     * @return use_count
     */
    private int useCount(Long tagId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT use_count FROM doc_tag WHERE id = ?", Integer.class, tagId);
        return value == null ? -1 : value;
    }
}
