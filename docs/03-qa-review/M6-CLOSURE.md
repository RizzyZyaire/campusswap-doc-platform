# M6 收口记录 —— 测试与代码审查

<!-- M6-CLOSURE -->

> **里程碑**：M6 测试与代码审查（`MASTER-PLAN.md` §7）　｜　**日期**：2026-09-24
> **一句话结论**：7 个任务（T6.1~T6.7）全部完成 —— **24 条 BDD 断言的纯单元测试 + 全量 `mvnw clean test` 30/30 绿 + 44 项例外路径回归 + 33 项 §2 硬约束逐条自查 + 20 045 篇数据量下的索引回归 + 18 个读接口逐一点数（零 N+1）**；审查过程中**发现并修掉 3 个真实缺陷**（其中 1 处是"违反即扣分"的分层越界，1 处是已冻结 BDD 断言的文案在实践中不可达）。
> **M6 收口提交**：`1e01c8c`（24 文件 / +3710 −38；本记录与手册标题行登记的就是这一笔）。

---

## 1. 逐任务验收

| 任务 | 交付物 | 实测证据 | 结论 |
|---|---|---|---|
| **T6.1** Service 层单测 1:1 对应 BDD | `backend/src/test/java/com/campusswap/{support,system,document}/` 下新增 **9 个文件 / 24 个 `@Test`**（`Ac01LoginTest` … `Ac08PermissionAdminTest` + 支撑类 `UnitTestSupport`） | 24 条 `@DisplayName` 以 `AC-01.1`…`AC-08.3` 开头、**一条不重不漏**（`verify-m6.ps1` A1/A2 机检）；全部 `@ExtendWith(MockitoExtension.class)`，**0 个 `@SpringBootTest`**（A4/A5） | ✅ |
| **T6.2** `./mvnw clean test` 全绿 | 本次两次全量运行（修复前后各一次） | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`，**Maven 退出码 0**（日志 `D:\DevEnv\logs\m6-mvn-test.log`）；30 = 新增 24 + 既有集成测试 6 | ✅ |
| **T6.3** 异常路径回归留档 | `docs/03-qa-review/verify-m6-http.ps1`（**44 项**）+ `TEST_CHECKLIST.md` 的 `M6-EXCEPTION-PATHS` 表 | **PASS=44 FAIL=0**（越权 403 且行内容逐字不变 / 状态冲突 409 / 非法参数 400 / 重复收藏幂等 / 401 / 404 / 权限树成环 400 + 文案） | ✅ |
| **T6.4** 清单与审查记录 | `docs/03-qa-review/CODE_REVIEW.md`（新增，对照 §2 逐条）+ 本文件 + `TEST_CHECKLIST.md` 增补两节 | CODE_REVIEW 覆盖 `R1~R6`、`DDL1~DDL6`、`L1~L5`、`F1~F4`、`E1~E7`、`P1~P6` 共 **33 项结论**，每项带判据命令与实测值（`verify-m6.ps1` D6 机检 21 个编号齐全） | ✅ |
| **T6.5** 看板全部勾选 + 变动说明 | `MASTER-PLAN.md` M6 七项全部 `- [x]`，逐项附 3 句以内变动说明；M5 标题补登记完成 | `verify-m6.ps1` E1/E2 机检（`- [ ] **T6.` 计数 = 0；7 项全部勾选）；**手册即唯一看板**（手册 §7 已声明"不再另建 `tasks.md`"，故本里程碑不新建该文件） | ✅ |
| **T6.6** 性能回归 | `EXPLAIN-NOTES.md` §5（M6 索引回归）+ 收工重灌 | 20 045 篇实测：`idx_doc_cat_status_updated` 0.342 ms、`idx_doc_status_updated` 0.261 ms、`idx_doc_created_by_updated` 0.123 ms、Q3b 0.203 ms，**均无 Sort / 无 Table scan**；对照组 `IGNORE INDEX` 20.9 ms（**约 80×**）；`FetchType.EAGER` 计数 **0**；列表 SQL 条数常数；`pageNum > 100` → **400** | ✅ |
| **T6.7** 逐接口 SQL 条数 | `docs/03-qa-review/probe-sql-counts.mjs`（新增点数器）+ `TEST_CHECKLIST.md` 的 `M6-SQL-COUNTS` 表 | 18 个读接口 × 2 种数据量（45 篇 / 20 045 篇）**36 次点数全部在 §10.5 预算内**，且 `--compare` 判定"条数与数据量无关" | ✅ |

---

## 2. 本里程碑的代码与脚本变更清单

**主代码（3 处修复，见 `CODE_REVIEW.md` §6）**

| # | 文件 | 变更 | 对应缺陷 |
|---|---|---|---|
| 1 | `document/service/StatService.java`（新增）、`document/service/impl/StatServiceImpl.java`（新增）、`document/controller/StatController.java`（改写） | 把原生聚合查询与 VO 组装从 Controller 下沉到 Service，事务注解随之归位 | M6-D1 分层越界 |
| 2 | `system/service/impl/PermissionServiceImpl.java` | 环检测移到层级/路径校验之前（**只改提示顺序，不放宽任何校验**） | M6-D2 AC-08.3 文案不可达 |
| 3 | `document/service/impl/DocumentServiceImpl.java`（`buildDetail`） | 分类名优先取 `join fetch` 已抓取的关联，未初始化时才回查 | M6-D3 详情接口多 1 条 SQL |

**测试（新增 9 个文件 / 24 用例）**：`support/UnitTestSupport.java`、`system/Ac01LoginTest.java`、`system/Ac08PermissionAdminTest.java`、`document/Ac02DraftCreateTest.java`、`Ac03PublishTest.java`、`Ac04SearchTest.java`、`Ac05DeriveTest.java`、`Ac06EditOwnershipTest.java`、`Ac07ReviewTest.java`
（**主代码之外的测试与脚本**：`src/test` 内新增、`pom.xml` 未改；既有 4 个集成测试类一字未动。）

**机检与工具（新增 4 个脚本）**：`docs/03-qa-review/verify-m6.ps1`（静态 50 项）、`verify-m6-http.ps1`（HTTP 44 项）、`probe-sql-counts.mjs`（逐接口点数 + 数据量对照）、`reload-db.ps1`（一条命令回种子库）。

---

## 3. M6 全量回归矩阵（收口时点，全部可复跑）

| 检查器 | 项数 | 结果 | 备注 |
|---|---|---|---|
| `verify-m0.ps1` | 13 | **13/0** | 需求冻结 |
| `verify-m1.ps1` | 15 | **15/0** | 设计三件套 |
| `verify-api-spec.ps1` | 24 | **exit 0** | 字段/别名口径 |
| `verify-m2.ps1` | 17 | **17/0** | 库结构 + 种子计数 |
| `verify-db-deep.ps1` | 9 | **9/0** | DDL↔实库、逻辑外键孤儿、树链、审计列 |
| `verify-m3.ps1` | 36 | **36/0** | RBAC + 分层 + 注释 |
| `verify-m3-http.ps1` | 142 | **142/0** | 系统域接口 |
| `verify-m4.ps1` | 30 | **30/0** | 文档域静态 |
| `verify-m4-http.ps1` | 222 | **222/0** | 文档域接口 + 17 项 SQL 预算 |
| `verify-m5.ps1` | 62 | **62/0** | 前端 15 路由 / 6 主题 / 批量导入 |
| **`verify-m6.ps1`**（新） | 50 | **50/0** | 单测覆盖、红线、文档锚点、ASCII、SQL 证据 JSON |
| **`verify-m6-http.ps1`**（新） | 44 | **44/0** | 例外路径 |
| `mvnw clean test` | 30 用例 | **30/0/0/0** | 24 单测 + 6 集成 |
| `probe-sql-counts.mjs` | 18 接口 ×2 数据量 | **全 PASS** | 零 N+1 |
| 前端 `typecheck` / `lint` / `build` / `check-classes` | 4 条命令 | **exit 0** | M5 收口后未再改前端 |

> **跑批顺序纪律**（`TEST_CHECKLIST.md` 顶部已写明）：重灌库 → 静态与 DB 检查器 → HTTP 检查器（会改数据）→ **再重灌库**。
> M6 期间因顺序错乱吃过一次亏（`m2` 16/17、`db-deep` 7/9），重灌后复绿；这也是把 `reload-db.ps1` 落成脚本的原因。

---

## 4. 已知缺口 / 顺延（诚实登记）

| # | 事项 | 影响 | 何时处理 |
|---|---|---|---|
| 1 | `DocumentSort` 的 `publishAt_desc`、`viewCount_desc` 两档排序未做 `EXPLAIN ANALYZE` | 若被用作默认排序可能 filesort；当前前端默认 `updatedAt_desc`（已测） | `EXPLAIN-NOTES.md` §4 保留未勾选项，M7 前若仍未做则登记为已知缺口 |
| 2 | 压测只做到 2 万篇（`perf-fixture.sql` 默认值），未做 10 万篇 | 20 045 篇下索引与条数均已稳定，量级结论不受影响 | 需要时改 `perf-fixture.sql` 的行数即可（脚本已参数化说明） |
| 3 | N1/N2 防御纵深缺口（标题校验、`trim()` 前置条件只在边界层） | 仅影响非 HTTP 入口的内部调用 | 见 `CODE_REVIEW.md` §7，留给"入口收敛"重构 |
| 4 | 只读 `@ManyToMany`（`Document.tags`）与课件 2.1 字面要求相反 | 已按项目冻结口径执行并有 4 项防护 | `CODE_REVIEW.md` §7 N3，如需改口径须走 ADR |
| 5 | T1.4 Apifox 手工导入、防火墙 3306/6379 入站规则收窄 | 不影响评分（OpenAPI 文件已交付；规则由用户决定） | 用户手动执行 |

---

## 5. 数据卫生

- 压测数据（标题前缀「【压测】」，20 000 篇）用后即清，最终库状态 = `reload-db.ps1` 重灌结果：**14 表 / 27 索引 / 45 篇文档（28 已发布 / 9 草稿 / 4 归档 / 4 回收站）/ 99 版本 / 109 标签关联 / 39 收藏 / 39 权限点 / 3 角色 / 3 部门 / 3 账号**，`data.sql` 的三项一致性自检全部为 0。
  （索引 20 → 27：M6 收尾时按课件 3.1 的"排序选项"实践任务补了 7 条排序索引，逐条实测与取舍见 `EXPLAIN-NOTES.md` §6 与 `ARCHITECTURE §10.4`。）
- `verify-m4-http.ps1`、`verify-m6-http.ps1` 都会改数据（前者会重置 staff 密码、切换用户状态），因此**跑完必须重灌**；截图/演示前也要重灌（否则 `staff` 的密码会变成检查器里那个）。
- 阅读量去重键 `view:{userId}:{docId}` 写在 Redis（TTL 见 `RedisKeys.VIEW_TTL`），重灌库**不会**清 Redis，所以「详情接口 4 条 vs 5 条」取决于该用户此前是否看过这篇 —— 两种取值都在预算内（§10.5 的 5 条含首次访问自增）。

---

## 6. 与冻结文档的偏差

**无契约变更**：M6 的三处修复都在实现层，端点、请求/响应字段、状态码、中文文案逐字不变，因此不改 `GLOSSARY` / `PRD` / `API_SPECIFICATION` / `UI_UX_SPECIFICATION` / `ARCHITECTURE` 的正文，也没有新增 ADR。

**文档增量**（只增不改既有结论）：

| 文档 | 增量 |
|---|---|
| `docs/03-qa-review/CODE_REVIEW.md` | 新增（§2 硬约束逐条自查 33 项 + 3 个已修缺陷 + 4 项已知取舍） |
| `docs/03-qa-review/TEST_CHECKLIST.md` | 新增 `M6-EXCEPTION-PATHS`（T6.3）与 `M6-SQL-COUNTS`（T6.7）两节 |
| `docs/03-qa-review/EXPLAIN-NOTES.md` | §4 回归清单勾选、新增 §5「M6 索引回归 —— 20 045 篇实测」 |
| `docs/MASTER-PLAN.md` | M5 标题补登记完成；M6 七项打勾 + 每项变动说明 |
| `docs/03-qa-review/M6-CLOSURE.md` | 本文件 |
