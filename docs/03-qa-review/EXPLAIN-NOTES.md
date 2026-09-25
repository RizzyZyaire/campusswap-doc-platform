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
| **C6** | **全文检索确实走 `ft_doc_search`**（`type=fulltext`），且同一关键词在旧 `LIKE title/summary` 分支命中 **0** 行、`MATCH ... IN BOOLEAN MODE` 命中 **1** 行 —— 这就是 M5 前置加全文索引的实证依据 | 见 §2 的 **Q5**；`verify-m2.ps1` C12/C13b 断言索引存在且列序 = `title,summary,content_md` + ngram 解析器 |

---

## 2b. Q5 全文检索（M5 前置，2026-09-23 实测）

**被测 SQL**（`DocumentQueryRepositoryImpl#searchFullText` 的原生分页片段，此处去掉高亮窗口只留过滤条件）：

```sql
SELECT d.id FROM doc_document d
 WHERE d.deleted = 0 AND d.status = 'PUBLISHED'
   AND MATCH(d.title, d.summary, d.content_md) AGAINST('+复制比*' IN BOOLEAN MODE);
```

**实测输出**（重灌校园口径种子 + `ANALYZE TABLE doc_document` 之后，MySQL 8.0.46）：

```
           id: 1
  select_type: SIMPLE
        table: d
         type: fulltext                    <-- 走全文索引，不是 ref/ALL
possible_keys: ft_doc_search
          key: ft_doc_search               <-- 命中的就是它
      key_len: 0
          ref: const
         rows: 1
     filtered: 20.00
        Extra: Using where; Ft_hints: no_ranking
```

**同一关键词的三路对照**（`backend/sql/data.sql` 的校园种子，关键词「复制比」只出现在正文里）：

| 查询 | 命中行数 |
|---|---|
| `WHERE title LIKE '%复制比%' OR summary LIKE '%复制比%'`（**旧口径**） | **0** |
| `WHERE content_md LIKE '%复制比%'`（正文确实有这个词） | **1** |
| `WHERE MATCH(title,summary,content_md) AGAINST('+复制比*' IN BOOLEAN MODE)`（**新口径**） | **1** |
| 新口径 + `deleted = 0 AND status = 'PUBLISHED'`（检索接口的真实口径） | **1** |

**「简报」同理 0 → 1**；反向用例「危化品」只在**草稿**（doc3）正文里：全状态 `MATCH` = 1，加上 `status='PUBLISHED'` 后 = **0** —— 证明全文分支没有把草稿泄漏进检索页。

**三条硬约束（实测踩到，已写进 `ARCHITECTURE.md` §10.4）**：

1. **列组合必须与索引完全一致**：`MATCH(title, summary)` 这类子集写法直接 `ERROR 1191 (Can't find FULLTEXT index matching the column list)` —— 所以「命中标题还是摘要」用 `LOCATE` 判定。
2. **1 字词会让整个布尔 AND 归零**（`ngram_token_size = 2`）：`+M4* +A* +123456*` = 0 行，去掉 1 字的 `A` 后 = 1 行 → 表达式生成时丢弃长度 < 2 的词。
3. **批量造数后必须 `ANALYZE TABLE doc_document;`**（跑 `perf-fixture.sql` 之后），否则全文索引统计不新鲜。

**复现命令**（可直接粘）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\logs\oapi\reseed-campus.ps1   # 重灌种子 + ANALYZE + 探针
```

```sql
USE campusswap_db;
ANALYZE TABLE doc_document;
SELECT COUNT(*) FROM doc_document WHERE title LIKE '%复制比%' OR summary LIKE '%复制比%';                       -- 期望 0
SELECT COUNT(*) FROM doc_document WHERE MATCH(title,summary,content_md) AGAINST('+复制比*' IN BOOLEAN MODE);  -- 期望 1
EXPLAIN SELECT d.id FROM doc_document d WHERE d.deleted=0 AND d.status='PUBLISHED'
  AND MATCH(d.title,d.summary,d.content_md) AGAINST('+复制比*' IN BOOLEAN MODE)\G                                -- 期望 type=fulltext
```

---

## 4. M6 回归清单

- [x] 数据量提升到 **20 045 行**（注入 20 000 篇压测文档）后重跑 Q1~Q3b → 全部仍为 `Index lookup`，**无 `Table scan`、无 `Sort`**（见 §5，2026-09-24 M6-T6.6）
- [x] 按 `DocumentSort` 的三档排序各跑一次 `EXPLAIN ANALYZE`：`updatedAt_desc`（已测）、`publishAt_desc`、`viewCount_desc` → **实测后两档原本是 filesort（45.9 / 47.0 ms），已补 7 条排序索引，复测三档 × 两种筛选形态全部 ≤0.3 ms**（见 §6，2026-09-24 收尾）
- [x] 核对列表接口的 Hibernate 生成 SQL 与本文件 Q1~Q3b 一致（`probe-sql-counts.mjs` 抓到的 SQL 原文已逐条登记在 `TEST_CHECKLIST.md` 的 M6 表）
- [x] 把本文件与 `TEST_CHECKLIST.md` 的 N+1 记录交叉引用，形成"查询性能"完整证据链（§5 ↔ `TEST_CHECKLIST.md` M6-SQL-COUNTS 段）
- [x] ~~全文检索分支的 `EXPLAIN`~~ → 已在 §2b Q5 完成（2026-09-23，M5 前置）

---

## 5. M6 索引回归 —— 20 045 篇数据量实测（T6.6）

<!-- M6-INDEX-REGRESSION -->

> **做法**：先 `reload-db.ps1` 回 45 篇种子库，再 `source backend/sql/perf-fixture.sql` 注入 20 000 篇（标题统一以「【压测】」开头，便于一键清理），
> `ANALYZE TABLE doc_document` 让统计信息反映真实分布，然后重跑 §1 的五条高频 SQL。
> **原始输出**：`D:\DevEnv\logs\m6-explain-perf.txt`（`mysql -N -B -e "EXPLAIN ANALYZE …"` 逐条留档）。

| 查询 | 45 篇（M2 首测） | **20 045 篇（M6 复测）** | 命中的索引 | 是否仍达标 |
|---|---|---|---|---|
| Q1 检索主路径（`category_id=3` + `PUBLISHED`，按 `updated_at DESC`） | 0.149 ms | **0.342 ms** | `Index lookup on doc_document using idx_doc_cat_status_updated (category_id=3, status='PUBLISHED') (reverse)` | ✅ 无 Sort、无回表过滤 |
| Q2 审核队列 / 全站已发布（仅 `status`） | 0.221 ms | **0.261 ms** | `Index lookup on doc_document using idx_doc_status_updated (status='PUBLISHED') (reverse)` | ✅ 最左前缀成立，必须单独建这条索引 |
| Q3 我的文档（`created_by=2`） | 0.135 ms | **0.123 ms** | `Index lookup on doc_document using idx_doc_created_by_updated (created_by=2) (reverse)` | ✅ 排序键在索引里，无 filesort |
| Q3b 我的文档 + 状态（`created_by=2` + `DRAFT`） | — | **0.203 ms** | 优化器自选 `idx_doc_status_updated (status='DRAFT') (reverse)` + 过滤 `created_by` | ✅ 仍是索引扫描，无 Sort |
| Q4 **对照组**（`IGNORE INDEX` 三条业务索引） | 18.2 ms（全表扫描） | **20.9 ms**（`Table scan` + `Sort … limit input to 10 row(s)`） | 无 | ❌ 故意退化 —— 对照组证明上面四条的加速来自索引 |

**结论（M6 的"高频查询命中复合索引"判据）**：

1. `idx_doc_cat_status_updated` 与 `idx_doc_status_updated` **在 20 045 篇数据量下仍然命中**，且都是 `(reverse)` 反向扫描直接给出 `updated_at DESC` 顺序 —— **没有出现 `Sort` / filesort**；
2. 索引扫描只读 10 行（`rows=10`），成本估算 `cost=230/271`，与数据量（20 434 行）无关；
3. 对照组（20.9 ms）与命中索引（0.26 ms）相差约 **80 倍**，说明这四条查询的耗时确实由索引决定，而不是"数据量小所以看起来快"；
4. `deleted = 0` 仍以 `Filter` 形式出现（索引末位带 `deleted` 列），实测只影响 10 行的过滤，未退化为回表全扫。

**收工动作**：压测数据用完即清（`DELETE FROM doc_document WHERE title LIKE '【压测】%'`），M6 最终状态是**重灌后的 45 篇种子库**（`reload-db.ps1` 自检：45/28/9/4/4/99/109/39，三项一致性计数 0）。

---

## 6. 排序选项索引修复 —— `DocumentSort` 三档排序实测（收尾项，2026-09-24）

<!-- M7-SORT-INDEX-FIX -->

> **背景**：§4 的最后一条未做项。前端把 `DocumentSort` 的三档排序都开给了用户
> （检索页 `document.ts` 的 `sort?`、治理页 `GovernanceView.vue` 的「最近更新 / 发布时间 / 阅读量」下拉），
> 但 `doc_document` 上只有 `updated_at` 进了索引，`publish_at` / `view_count` 没有。
> **方法**：注入 20 000 篇（库内 20 045 篇）+ `ANALYZE TABLE` → 对「三档排序 × {有状态筛选, 不过滤状态} × {全站, 带分类}」共 8 种形态跑 `EXPLAIN ANALYZE` → 按结果补索引 → 同样 8 条复测。
> **原始输出**：修复前 `D:\DevEnv\logs\m6b-sort-explain-before.txt`；修复后 `D:\DevEnv\logs\m7-sort-explain-final.txt`。

### 6.1 修复前（20 045 篇，真实执行）

| 形态 | 计划 | 耗时 | 问题 |
|---|---|---|---|
| `status='PUBLISHED'` + `ORDER BY updated_at DESC` | `Index lookup idx_doc_status_updated (reverse)` | 0.75 ms | 正常（对照组） |
| `status='PUBLISHED'` + `ORDER BY publish_at DESC` | `Index lookup idx_doc_status_updated` → **`Sort`** | **45.9 ms** | ❌ 排序键不在索引里，读了 13 361 行再内存排序 |
| `status='PUBLISHED'` + `ORDER BY view_count DESC` | 同上 → **`Sort`** | **47.0 ms** | ❌ 同上 |
| `category_id=3 AND status` + `ORDER BY publish_at DESC` | `Index lookup idx_doc_cat_status_updated` → **`Sort`** | **13.2 ms** | ❌ 读了 3 341 行再排序 |
| `category_id=3 AND status` + `ORDER BY view_count DESC` | 同上 → **`Sort`** | **11.5 ms** | ❌ 同上 |
| 不过滤状态 + `ORDER BY updated_at DESC`（治理页默认视图） | **`Table scan`** → `Sort` | **19.3 ms** | ❌ 状态列无等值谓词，两条状态索引都用不上 |
| 不过滤状态 + `ORDER BY publish_at DESC` | `Table scan` → `Sort` | **18.8 ms** | ❌ 同上 |
| 不过滤状态 + `ORDER BY view_count DESC` | `Table scan` → `Sort` | **18.8 ms** | ❌ 同上 |

### 6.2 补的 7 条索引（`backend/sql/schema.sql`，`doc_document`）

| 索引 | 列 | 服务的形态 |
|---|---|---|
| `idx_doc_status_publish` | `(status, publish_at, deleted)` | 已发布/审核队列 + 发布时间排序 |
| `idx_doc_status_view` | `(status, view_count, deleted)` | 同上 + 阅读量排序 |
| `idx_doc_cat_status_publish` | `(category_id, status, publish_at, deleted)` | 分类筛选 + 发布时间排序 |
| `idx_doc_cat_status_view` | `(category_id, status, view_count, deleted)` | 分类筛选 + 阅读量排序 |
| `idx_doc_deleted_updated` | `(deleted, updated_at)` | 治理页「全部状态」+ 默认排序 |
| `idx_doc_deleted_publish` | `(deleted, publish_at)` | 治理页「全部状态」+ 发布时间排序 |
| `idx_doc_deleted_view` | `(deleted, view_count)` | 治理页「全部状态」+ 阅读量排序 |

**索引总数**：`schema.sql` 的二级索引 20 → **27**（`doc_document` 一张表 12 个，含 1 个 ngram 全文索引）。
**为什么"不过滤状态"要单列三条**：`deleted` 只有 0/1 两个取值，`(deleted, 排序键)` 这种"低基数等值列 + 排序键"的组合正好能让 MySQL 反向扫索引取前 10 行；
一开始只补了 `(deleted, updated_at)`，结果另外两档的优化器改走索引扫描再排序（60.7 / 47.5 ms，**比原来的全表扫描更慢**）—— 这三条要么一起补，要么一条都别补，这个中间态是靠复测发现的。

### 6.3 修复后（同样 20 045 篇，8 种形态复测）

| 形态 | 计划 | 耗时 | 提升 |
|---|---|---|---|
| `status` + `updated_at DESC` | `Index lookup idx_doc_status_updated (reverse)` | 0.139 ms | — |
| `status` + `publish_at DESC` | `Index lookup idx_doc_status_publish (reverse)` | **0.254 ms** | **181×** |
| `status` + `view_count DESC` | `Index lookup idx_doc_status_view (reverse)` | **0.285 ms** | **165×** |
| `category+status` + `publish_at DESC` | `Index lookup idx_doc_cat_status_publish (reverse)` | **0.122 ms** | **108×** |
| `category+status` + `view_count DESC` | `Index lookup idx_doc_cat_status_view (reverse)` | **0.139 ms** | **83×** |
| 不过滤状态 + `updated_at DESC` | `Index lookup idx_doc_deleted_updated (reverse)` | **0.191 ms** | **101×** |
| 不过滤状态 + `publish_at DESC` | `Index lookup idx_doc_deleted_publish (reverse)` | **0.218 ms** | **86×** |
| 不过滤状态 + `view_count DESC` | `Index lookup idx_doc_deleted_view (reverse)` | **0.187 ms** | **101×** |

**结论**：8 种形态**全部为 `Index lookup ... (reverse)`，8 条计划里 0 个 `Sort`、0 个 `Table scan`**，
每一档都只读 10 行（`rows=10`）；实测耗时从 11.5~47.0 ms 降到 0.122~0.285 ms。
`verify-m2.ps1` 的 **C13c** 断言这 7 条索引的列序（含 `deleted` 必须落在末尾），改错列序会直接机检失败。
唯一无法进索引的排序是 `relevance`（全文相关度）：它按 `MATCH(...) AGAINST(...)` 的计算分排序，任何 B-tree 都服务不了 —— 属算法固有，已在 `ARCHITECTURE §10.4` 说明。

**代价与取舍（如实登记）**：`doc_document` 现在 12 个索引，写入时每个索引都要维护。本项目读多写少
（文档写入是低频人工操作，20 000 行压测数据本身也是离线灌的），换来的是三档排序在线性增长的数据量下依旧平稳；
如果将来写变成高频，应先合并 `(status, X, deleted)` 与 `(deleted, X)` 两组里的重叠形态，而不是继续加索引。
