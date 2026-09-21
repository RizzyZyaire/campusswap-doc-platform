-- =============================================================================
-- 性能压测夹具（课件 3.1 §4：索引与执行计划验证用）
-- 用法:
--   灌数据: mysql ... campusswap_db -e "source .../perf-fixture.sql"      -- 默认 20000 行
--   清数据: mysql ... campusswap_db -e "DELETE FROM doc_document WHERE title LIKE '【压测】%';"
-- 说明: 压测行标题统一以「【压测】」开头，便于一键清理，不影响种子数据与演示。
--       行数可通过 @rows 变量调整（默认 20000）。
-- =============================================================================

USE `campusswap_db`;
SET SESSION cte_max_recursion_depth = 200000;

-- 1) 灌入压测文档（分布：4 个分类 × 3 种状态 × 最多 500 阅读量；作者固定为 2/3 两个用户）
INSERT INTO `doc_document`
    (`category_id`,`title`,`summary`,`content_md`,`status`,`version_num`,`price_cents`,
     `view_count`,`favorite_count`,`created_by`,`created_at`,`updated_at`)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000
)
SELECT
    1 + (n % 4)                                        AS category_id,
    CONCAT('【压测】文档 ', LPAD(n, 6, '0'))            AS title,
    CONCAT('压测摘要 ', n)                              AS summary,
    NULL                                               AS content_md,
    CASE n % 3 WHEN 0 THEN 'PUBLISHED' WHEN 1 THEN 'DRAFT' ELSE 'PUBLISHED' END AS status,
    1                                                  AS version_num,
    0                                                  AS price_cents,
    n % 500                                            AS view_count,
    0                                                  AS favorite_count,
    CASE n % 2 WHEN 0 THEN 2 ELSE 3 END                AS created_by,
    NOW() - INTERVAL (n % 365) DAY                     AS created_at,
    NOW() - INTERVAL (n % 365) DAY                     AS updated_at
FROM seq;

-- 2) 让统计信息反映真实分布（否则优化器可能仍按旧基数选错计划）
ANALYZE TABLE `doc_document`;

-- 3) 灌数据后的规模自检
SELECT '压测文档数' AS item, COUNT(*) AS cnt FROM `doc_document` WHERE `title` LIKE '【压测】%'
UNION ALL SELECT '文档总数', COUNT(*) FROM `doc_document`
UNION ALL SELECT '已发布数', COUNT(*) FROM `doc_document` WHERE `status`='PUBLISHED';
