# EXPLAIN-NOTES.md — 执行计划实测记录（课件 3.1 §4）

| 项 | 值 |
|---|---|
| 文件 | `docs/03-qa-review/EXPLAIN-NOTES.md` |
| 版本 | v1.0（M2 首测，2026-09-21） |
| 数据库 | `campusswap_db`（MySQL 8.0.46，InnoDB / utf8mb4_unicode_ci） |
| 数据规模 | 种子 5 篇 + **压测夹具 20 000 篇**（`backend/sql/perf-fixture.sql`），测试后已清理（当前 5 篇） |
| 上游依据 | 老师课件《3.1 JPA 性能调优与查询进阶》§4（B+Tree / 最左前缀 / EXPLAIN ANALYZE） |
| 复现方式 | `mysql ... campusswap_db -e "source backend/sql/perf-fixture.sql"` → 跑下方 SQL → `DELETE FROM doc_document WHERE title LIKE '【压测】%'` |

> 工具：MySQL 8.0.46 的 `EXPLAIN ANALYZE`（真实执行 + 实测耗时），非 `EXPLAIN` 估算。

---

## 1. 被测的五条高频 SQL

| # | 场景 | SQL 要点 |
|---|---|---|
| Q1 | 文档检索主路径 | `WHERE deleted=0 AND category_id=3 AND status='PUBLISHED' ORDER BY updated_at DESC LIMIT 10` |
| Q2 | 审核队列 / 全站已发布列表 | `WHERE deleted=0 AND status='PUBLISHED' ORDER BY updated_at DESC LIMIT 10` |
| Q3 | 我的文档（作者维度） | `WHERE deleted=0 AND created_by=2 ORDER BY updated_at DESC LIMIT 10` |
| Q3b | 我的文档 + 状态筛选 | `WHERE deleted=0 AND created_by=2 AND status='DRAFT' ORDER BY updated_at DESC LIMIT 10` |
| Q4 | **对照组**：强制忽略相关索引，验证"没索引会怎样" | Q2 加 `IGNORE INDEX (idx_doc_cat_status_updated, idx_doc_status_updated, idx_doc_created_by_updated)` |

---

## 2. 实测输出（节选关键节点）

### Q1 检索主路径 —— 命中 `idx_doc_cat_status_updated` ✅
```
-> Limit: 10 row(s)  (cost=229 rows=10) (actual time=0.146..0.149 rows=10 loops=1)
    -> Filter: (doc_document.deleted = 0)
        -> Index lookup on doc_document using idx_doc_cat_status_updated
           (category_id=3, status='PUBLISHED') (reverse)  (actual time=0.144..0.147 rows=10 loops=1)
```
**实测 0.149 ms**：索引反向扫描直接给出 `updated_at DESC` 顺序，**无 Sort、无 filesort**、只读 10 行。

### Q2 审核队列（仅按状态）—— 命中 `idx_doc_status_updated` ✅
```
-> Limit: 10 row(s)  (cost=266 rows=10) (actual time=0.218..0.221 rows=10 loops=1)
    -> Filter: (doc_document.deleted = 0)
        -> Index lookup on doc_document using idx_doc_status_updated
           (status='PUBLISHED') (reverse)  (actual time=0.213..0.216 rows=10 loops=1)
```
**实测 0.221 ms**。注意：这条查询**走不了** `idx_doc_cat_status_updated`（最左列是 `category_id`，被跳过）——这正是必须**单独建** `idx_doc_status_updated` 的原因。

### Q3 我的文档 —— **首测发现索引缺陷，已修复**
首测（旧索引 `idx_doc_created_by(created_by, deleted)`）❌：
```
-> Limit: 10 row(s)  (cost=1138 rows=10) (actual time=28.4..28.4 rows=10 loops=1)
    -> Sort: doc_document.updated_at DESC, limit input to 10 row(s) per chunk  (actual time=28.4..28.4)
        -> Index lookup on doc_document using idx_doc_created_by (created_by=2, deleted=0)
           (actual time=0.135..26.5 rows=10003 loops=1)      ← 读了 10003 行再内存排序
```
**问题**：`updated_at` 不在索引里 → 索引只能过滤作者，排序必须回表后内存排序（`Sort` 节点）。

修复：索引改为 **`idx_doc_created_by_updated(created_by, updated_at, deleted)`**（排序键紧跟等值列之后）。复测 ✅：
```
-> Limit: 10 row(s)  (cost=268 rows=10) (actual time=0.132..0.135 rows=10 loops=1)
    -> Filter: (doc_document.deleted = 0)
        -> Index lookup on doc_document using idx_doc_created_by_updated (created_by=2) (reverse)
           (actual time=0.129..0.131 rows=10 loops=1)
```
**实测 28.4 ms → 0.135 ms（约 210 倍）**，`Sort` 节点消失，只读 10 行。

### Q3b 我的文档 + 状态筛选 —— 优化器自选 `idx_doc_status_updated` ✅
```
-> Filter: ((doc_document.created_by = 2) and (doc_document.deleted = 0))  (actual time=0.357..0.361 rows=10)
    -> Index lookup on doc_document using idx_doc_status_updated (status='DRAFT') (reverse)
       (actual time=0.35..0.355 rows=21 loops=1)
```
**实测 0.363 ms**。优化器在两条可用索引里选了选择性更好的那条（DRAFT 仅约 1/3），`created_by` 作为索引外的过滤条件——行数极少，代价可忽略。

### Q4 对照组（忽略索引）❌ —— 课件里的"场景 A"复现
```
-> Limit: 10 row(s)  (cost=1995 rows=10) (actual time=18.2..18.2 rows=10 loops=1)
    -> Sort: doc_document.updated_at DESC, limit input to 10 row(s) per chunk  (actual time=18.2..18.2)
        -> Filter: ((doc_document.deleted = 0) and (doc_document.status = 'PUBLISHED'))
           (actual time=0.0483..15.7 rows=13335 loops=1)
            -> Table scan on doc_document  (actual time=0.0451..11.4 rows=20005 loops=1)
```
**实测 18.2 ms**：全表扫描 20 005 行 → 过滤出 13 335 行 → 内存排序取前 10。
与 Q2 的 0.221 ms 对比 ≈ **82 倍差距**，与课件"场景 A（Seq Scan + filesort）vs 场景 B（Index Scan）"的结论完全一致。

---

## 3. 结论与固化动作

| # | 结论 | 固化动作 |
|---|---|---|
| C1 | 分类+状态+时间倒序必须走 `idx_doc_cat_status_updated`，且索引末位带 `deleted` 才能避免回表过滤 | 已写入 `backend/sql/schema.sql` |
| C2 | **仅按状态查询走不了上面的索引（最左前缀）**，必须单独建 `idx_doc_status_updated` | 已建，`verify-m2.ps1` C13 断言两者列序 |
| C3 | **排序键必须进索引**：`(created_by, deleted)` 会让 `ORDER BY updated_at` 退化为 filesort（28.4 ms） | 已改为 `idx_doc_created_by_updated(created_by, updated_at, deleted)`；M6 回归时复核 |
| C4 | 无索引时 20 000 行即产生 18.2 ms + 全表扫描；数据量再涨 10 倍将线性恶化 | Q4 仅作对照，不进入任何生产查询路径 |
| C5 | 所有列表接口的排序字段（`updatedAt` / `publishAt` / `viewCount`）必须与索引列对应 | `DocumentSort` 三档与索引列对照表见 `ARCHITECTURE.md` §10.4 |

---

## 4. M6 回归清单（待办）

- [ ] 数据量提升到 10 万行后重跑 Q1~Q3b，确认仍为 `Index lookup`（无 `Table scan` / 无 `Sort`）
- [ ] 按 `DocumentSort` 的三档排序各跑一次 `EXPLAIN ANALYZE`：`updatedAt_desc`（已测）、`publishAt_desc`、`viewCount_desc` → 后两档若无索引支撑需补索引或降级排序选项
- [ ] 核对列表接口的 Hibernate 生成 SQL 与本文件 Q1~Q3b 一致（避免 JPA 生成额外的 `COUNT` 全表查询）
- [ ] 把本文件与 `TEST_CHECKLIST.md` 的 N+1 记录交叉引用，形成"查询性能"完整证据链
