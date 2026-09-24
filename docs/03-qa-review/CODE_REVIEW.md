# CampusSwap 代码审查记录（M6 · T6.4）

<!-- M6-CODE-REVIEW -->

> **一句话结论**：对照 `MASTER-PLAN.md` §2 硬约束逐条自查 **33 项全部通过**（§2.1 六红线 / §2.2 DDL 六条 / §2.3 分层五条 + 注释规范 / §2.4 前端四条 / §5.1 实体七戒律 / §5.1.1 查询性能六条），
> 审查过程中**发现并修掉 3 个真实缺陷**（1 处分层越界、1 处验收文案不可达、1 处详情接口多 1 条 SQL），另有 4 项**记录在案的已知取舍**（含理由与风险）。
> 每条结论都给出**可复跑的判据命令**与**实测值**，不写"应该没问题"。

| 项目 | 值 |
|---|---|
| 审查对象 | `backend/` 158 个 Java 文件 / 12 085 行；`frontend/src` 46 个文件 / 8 554 行；`backend/sql` 3 个脚本 |
| 判据来源 | `MASTER-PLAN.md` §2.1~§2.4、§5.1、§5.1.1；老师《AGENTS.md》六条编码红线；课件 2.1（实体七戒律）、3.1（查询性能五红线） |
| 证据来源 | ① 机检脚本（可复跑，见 §7）② 纯单元测试 24 条（T6.1）③ HTTP 检查器 44 条（T6.3）④ 逐接口 SQL 点数 18 个接口（T6.7）⑤ `EXPLAIN ANALYZE` 20 045 篇数据量实测（T6.6） |
| 审查方式 | 先机检（把口径固化成脚本）→ 再人工通读（分层、命名、注释、异常语义）→ 最后用 HTTP/单测复现每条怀疑 |

---

## 1. §2.1 编码六红线

| # | 红线 | 判据命令（可复跑） | 实测 | 结论 |
|---|---|---|---|---|
| R1 | 字段不得脑补：Entity / DTO / VO / TS Interface / DB 列名五处一致 | `verify-api-spec.ps1`（24 项）+ `verify-db-deep.ps1`（DDL 文件↔实库逐列）+ `verify-m1.ps1` | DDL↔实库逐列 diff = 0；实库 14 表 / **116 列**；`GLOSSARY.md` 字段名对账 0 处未收录；接口字段名 57 端点全覆盖 | ✅ |
| R2 | 金额禁止浮点：全链路 `priceCents` / `price_cents`（整数分） | 全仓 grep `double\|float\|Double\|Float\|BigDecimal`（后端 158 文件 + 前端 46 文件） | **命中 0**；金额列 `doc_document.price_cents INT UNSIGNED`（1 处，见 DDL2）；前端唯一换算 `format.ts:43 \`¥${(cents / 100).toFixed(2)}\`` | ✅ |
| R3 | Controller 入参 `@Valid` + Jakarta 校验 + **中文提示** | `verify-m6.ps1` B3/B4；`verify-m4-http.ps1` 的 400 断言 | 真实 `@RequestBody` **25 处全部带 `@Valid`**（第 26 处命中是 `GlobalExceptionHandler` javadoc 里的文字说明）；400 文案逐字比对通过：`E1/E2` 标题、`E6/E7` 页码、`E9` 每页条数、`E10` 枚举取值 | ✅ |
| R4 | 禁止越权（IDOR）：更新/下架/删除/派生在 Service 层校验属主 | `verify-m6-http.ps1` C1~C4；`verify-m4-http.ps1` | Service 层校验调用点：`assertOwnerOrManage` **6** 处、`assertVisible` **5** 处、`SecurityContext.requireUserId` **25** 处；staff 改他人文档 → **403**「无权限修改该文档」，且**该行 18 个字段逐字未变**（C4） | ✅ |
| R5 | 禁止 Entity 穿透前端：出参必须是 VO | `verify-m6.ps1`（Controller 出参类型清点）；`Ac01LoginTest` | Controller 出参 **22 种类型全为 `VO` / `PageVo<VO>` / `List<VO>` / `Void`，0 处 Entity**；`LoginVo` 不含 `password_hash`（单测断言 VO 继承链无该字段与 getter，并以实体做非空对照）；`BaseEntity.id` 带 `@JsonSerialize(using = ToStringSerializer.class)` 防 JS 精度丢失 | ✅ |
| R6 | 前端禁内联 `style="..."`、TS 禁 `any` | `verify-m6.ps1` B10/B11 + `pnpm run lint`（`--max-warnings 0`） | 46 个前端文件中 `: any` / `as any` / `<any>` **0** 处、`style="` **0** 处；`typecheck` / `lint` / `build` / `check-classes` 四条命令 exit 0 | ✅ |

---

## 2. §2.2 DDL 六条（`backend/sql/schema.sql` ↔ 实库）

| # | 要求 | 实测 | 结论 |
|---|---|---|---|
| DDL1 | 每表显式 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` | `CREATE TABLE` **14** 处、`ENGINE=InnoDB` **14** 处、`utf8mb4_unicode_ci` **16** 处（含 `CREATE DATABASE`） | ✅ |
| DDL2 | 金额列 `INT UNSIGNED`（分） | `price_cents INT UNSIGNED NOT NULL DEFAULT 0`（`doc_document`，带 COMMENT）；其余计数列同样无符号 | ✅ |
| DDL3 | 主键 `BIGINT NOT NULL AUTO_INCREMENT`；6 张纯关联中间表用复合主键、无 `id` | `PRIMARY KEY` **14** 处：**8** 张业务表单列自增 + **6** 张中间表复合主键（`sys_user_role`/`sys_role_permission`/`sys_user_permission`/`sys_dept_role`/`doc_document_tag_rel`/`doc_favorite`） | ✅ |
| DDL4 | 不建任何外键约束 | `FOREIGN KEY` / `REFERENCES` **0** 处；逻辑外键由 `verify-db-deep.ps1` 的 **14 组孤儿行检查** 兜底（实测孤儿 0 行） | ✅ |
| DDL5 | 高频查询列建索引/复合索引、注意最左前缀 | `information_schema` 实测 **20 个二级索引**（含 3 个业务复合索引 + 1 个 `FULLTEXT ... WITH PARSER ngram`）；T6.6 在 20 045 篇数据量下复测：Q1 命中 `idx_doc_cat_status_updated`、Q2 命中 `idx_doc_status_updated`、Q3 命中 `idx_doc_created_by_updated`（详见 `EXPLAIN-NOTES.md` §5） | ✅ |
| DDL6 | 每张表、每个字段都要有 `COMMENT` | 表级 `COMMENT='...'` **14** 处；`information_schema.columns` 116 列全部带注释（0 列缺注释） | ✅ |

---

## 3. §2.3 分层五条 + 注释规范

| # | 要求 | 实测 | 结论 |
|---|---|---|---|
| L1 | Controller 只做路由 / `@Valid` 转发 / 调 Service / 包装响应 | **M6 修复**：原 `StatController` 直接注入 `DocumentRepository` 并组装 VO（越界）。现 Controller 目录 **0 处 repository import**（机检 `verify-m6.ps1` B2）。全仓 48 个映射注解对应 57 个端点，全部只有「取 `SecurityContext` + 调 Service + `ResponseResult.ok`」三件事 | ✅（修复后） |
| L2 | Service 承载业务规则 / 事务 / 属主校验 / DTO↔Entity↔VO | Service 实现里 `nativeQuery` **0** 处（原生 SQL 全在 repository：`DocumentQueryRepositoryImpl` + 各 `@Query(nativeQuery = true)`）；`@Transactional` 边界全在 Service；属主校验 11 个调用点（R4） | ✅ |
| L3 | Service 接口 / 实现分离（`ServiceImpl`） | **12 个 Service 接口 / 13 个实现类**（`FileStorageService` 是唯一无接口的存储工具类，属有意为之：不含业务规则）；`StatService` 为 M6 新增，补齐第 12 个接口 | ✅ |
| L4 | Repository 只做数据访问：Spring Data 接口 / `@Query` / 投影 | 14 个仓储；`@Modifying` **19 处全部成对**写 `clearAutomatically + flushAutomatically`（机检 B5/B6；另有 2 处命中是 javadoc 说明文字）；分页统一 `PageableExecutionUtils` 语义，无业务判断 | ✅ |
| L5 | Entity / DTO / VO 分离，VO 不带敏感字段 | 21 个 Entity 文件（14 实体 + 6 复合主键类 + BaseEntity）、**33** 个 DTO、**18** 个 VO；VO 全部是 record/显式字段，无 `passwordHash`（`UserVo` 仅 `id/username/realName/...`） | ✅ |
| 注释 | 每个类 `@author`；每个 public 方法 `@param`/`@return` | 158 个类 **100% 带 `@author`**；240 个 public/protected 方法中 **236 个有完整 javadoc**，剩余 4 处是扫描器把「带初始化的常量字段」误判为方法（`RedisKeys.TOKEN_TTL` 等），人工复核为字段注释齐全 → **方法覆盖率 236/236 = 100%** | ✅ |

---

## 4. §2.4 前端四条

| # | 要求 | 实测 | 结论 |
|---|---|---|---|
| F1 | 目录固定 `src/{api,types,components,views,stores,router,utils}` | 7 个必需目录全部存在；另有 `layouts`（AppShell/登录壳）、`assets`（13 个由预览生成的资源）、`styles`（`theme.css`/`components.css`/`main.css`）三个补充目录，`UI_UX_SPECIFICATION` §2 已登记 | ✅ |
| F2 | 类型对齐 `GLOSSARY.md`；**雪花 ID 一律 `string`** | `src/types` 4 个文件；`id: string` 10 处、`id: number` **0** 处、`any` **0** 处 | ✅ |
| F3 | Axios 统一封装：请求带 Token；响应统一处理 401/403/业务码 | `src/api/request.ts`：请求拦截注入 `Authorization: Bearer`（`TOKEN_KEY = 'campusswap.token'` 单点定义）、响应拦截按 `code === 401` 清 token + 广播跳登录、`code === 403` 提示无权限、其余业务码取服务端中文 `message` | ✅ |
| F4 | 每页必须有空 / 加载中 / 错误 / 无权限四态 | `verify-m5.ps1` D1/D5 逐页断言 8 个页面四态与中文文案；M5 收口 62/62 全绿（M6 复跑仍 62/62） | ✅ |

---

## 5. §5.1 实体七戒律 + §5.1.1 查询性能六条

| # | 戒律 / 要求 | 实测 | 结论 |
|---|---|---|---|
| E1 | 禁 `@Data` | 全仓命中 **0**（唯一命中在 `BaseEntity` javadoc 的说明文字里） | ✅ |
| E2 | 必须有 `@NoArgsConstructor` | 实体目录 `@NoArgsConstructor` 20 处（14 实体 + 6 复合主键类；`BaseEntity` 为抽象父类，由子类注解承担） | ✅ |
| E3 | 主键用包装类 `Long` | `private Long id;` 1 处（`BaseEntity`）、`private long id;` **0** 处 | ✅ |
| E4 | 禁 `ddl-auto=update` | `application-dev.yml`：`ddl-auto: none`（表结构只来自 `schema.sql`）；启动时用 `-Dspring.jpa.hibernate.ddl-auto=validate` 验证过实体↔实库逐列对齐 | ✅ |
| E5 | 禁 `@ManyToMany` **参与写入**（查询导航可用只读） | 1 处只读关联：`Document.tags`（`insertable=false, updatable=false` + 无 `cascade` + 无 `orphanRemoval`）；`getTags().add/remove` 调用 **0** 处（机检 B7）；标签增删一律走 `DocumentTagRelRepository` | ✅ |
| E6 | 禁自关联对象，树形用 `parentId` + `ancestors` | 实体里 `@JoinColumn(name = "parent_id")` 自关联 **0** 处；`sys_permission`/`sys_dept`/`doc_category` 三棵树都是 `parent_id` + `ancestors` 字段，一次查全 + 内存组树（SQL 1 条） | ✅ |
| E7 | 所有 ID 序列化为 `String` | `BaseEntity.id` 带 `@JsonSerialize(using = ToStringSerializer.class)`；9 个 VO 显式声明 `private String id`；前端 TS 侧 ID 全 `string`（F2） | ✅ |
| P1 | 关联全部 `FetchType.LAZY`，无裸露 `EAGER` | 全仓 `FetchType.EAGER` **0**（机检 `verify-m6.ps1` B1）；`@ManyToOne` 1 处显式 LAZY；`@ManyToMany` 1 处显式 LAZY | ✅ |
| P2 | 列表接口零 N+1：SQL 条数为常数 | 18 个读接口在 45 篇与 20 045 篇两种数据量下逐一点数，**条数完全一致**（`TEST_CHECKLIST.md` M6 表 + `sql-counts-seed/perf.json`）；`pageSize=1` 与 `pageSize=100` 之差只可能是 1 条分页 count | ✅ |
| P3 | 列表禁查大文本：`DocumentVo` 不含 `contentMd` | `DocumentVo` 无 `contentMd` 字段（唯一命中是 javadoc 的说明文字）；列表 SQL 实测不含 `content_md` 列（`verify-m4-http.ps1` + 逐接口点数器抓到的 SQL 原文） | ✅ |
| P4 | 动态多条件用 `JpaSpecificationExecutor` + Criteria，禁手写拼接 | 10 个仓储继承 `JpaSpecificationExecutor`，3 个 `*Specifications` 条件构造类；全文检索用**参数绑定**的原生 SQL（`MATCH ... AGAINST(:expr)`），无字符串拼接（检查器断言 `:expr` 绑定 + 关键词不进 SQL 字面量） | ✅ |
| P5 | 高频查询命中复合索引 | 20 045 篇实测：Q1 `idx_doc_cat_status_updated` 0.342 ms、Q2 `idx_doc_status_updated` 0.261 ms、Q3 `idx_doc_created_by_updated` 0.123 ms、Q3b 优化器自选 `idx_doc_status_updated` 0.203 ms；**对照组**（`IGNORE INDEX`）退化为全表扫描 + Sort **20.9 ms**（约 80×） | ✅ |
| P6 | 五大避坑红线 | ① `@Data` 0；② 循环查库改批量：`findAllById` 4 处、`@EntityGraph` 1 处、`JOIN FETCH` 3 处；③ 列表不含大文本（P3）；④ 参数类型与列一致（`Long`↔`BIGINT`、金额 `Integer`↔`INT UNSIGNED`，M2/M4 已留证）；⑤ 深分页：`pageNum > 100` → **400**「页码不能超过100，请缩小筛选范围后再试」（`verify-m6-http.ps1` E6/E7） | ✅ |

---

## 6. 本次审查发现并修复的缺陷（3 条）

### M6-D1　`StatController` 直连 Repository，违反 §2.3 分层五条

- **发现方式**：机检「Controller 目录是否 import repository」时命中 `StatController.java:6-7,27,39`。
- **影响**：Controller 里出现了 VO 组装与聚合查询调用，事务注解也挂在 Controller 上 —— 违反「Controller 绝不做业务逻辑、直接调 Repository」（违反即扣分项）。
- **修复**：新增 `document/service/StatService.java` + `document/service/impl/StatServiceImpl.java`（接口/实现分离，`@Transactional(readOnly = true)` 归位到 Service），`StatController` 只保留路由 + `@RequiresPermission` + `ResponseResult.ok`。
- **复验**：`verify-m6.ps1` B2（Controller 0 处 repository import）✅；`verify-m4-http.ps1` 的 `sql.stats = 1` 仍通过（SQL 条数未变）✅；stat 接口实测返回 `{myDocumentCount, myFavoriteCount, publishedCount}` 正常 ✅。

### M6-D2　AC-08.3「环」提示不可达，M3 机检属"覆盖假绿"

- **发现方式**：T6.1 单测在构造 AC-08.3 用例时发现：把节点移到自己的子孙下时，`PermissionServiceImpl.update` 先跑层级校验（`assertHierarchy`），提示变成「权限层级不合法…」，**US-08 约定的文案「不能将节点移动到其子节点下」在实践中几乎打不到**；而 `verify-m3-http.ps1` 的两条环用例只断言状态码 400，不看文案 → 脚本绿 ≠ 该分支被覆盖。
- **影响**：行为本身安全（仍 400、库中一行未动），属**验收文案与覆盖率**问题：一条已冻结的 BDD 断言没有真实证据。
- **修复**：把环检测移到层级/路径校验之前（`PermissionServiceImpl.update`），并在注释里写明"只是把提示顺序前移，原来会拒绝的形状仍然拒绝"。同时在 **M6 新增**的 `verify-m6-http.ps1` G1~G5 里做三件事：断言 400、断言文案逐字等于约定文案、断言节点 `parentId` 与子节点数不变（该检查对修复前的代码会失败，是一条真正的回归锁）。M3 的脚本保持原样不动（历史记录不改写）。
- **复验**：`verify-m6-http.ps1` 44/44 ✅；`Ac08PermissionAdminTest` 3 条单测 ✅。

### M6-D3　详情接口 6 条 SQL，超出 `ARCHITECTURE §10.5` 预算 5

- **发现方式**：T6.7 逐接口点数（20 045 篇数据量、首次访问）时 `GET /api/documents/{id}` 实测 **6** 条，抓到的 SQL 原文显示第 1 条已 `join fetch` 带出分类列，第 3 条又按 `category_id` 回查了一次 `doc_category`。
- **修复**：`buildDetail` 改为优先使用已抓取的关联（`doc.getCategory()`），仅当关联为空/未初始化时才回退 `categoryNameOf(...)`——语义完全一致，省掉 1 条重复查询。
- **复验**：修复后实测详情 = **5 条（含首次访问阅读量自增）/ 4 条（非首次）**，与预算一致；`verify-m4-http.ps1` 222/222 ✅（详情页分类名、标签、收藏态断言全绿）。

---

## 7. 记录在案、**本次不改**的已知取舍（4 条）

| # | 事项 | 为什么发现 | 为什么不改 | 风险与兜底 |
|---|---|---|---|---|
| N1 | 标题「非空 + ≤128 字」只有 Controller 一层防线（`DocumentServiceImpl.create/update` 直接 `req.title().trim()`） | 单测 AC-02.2 只能覆盖到校验层；人工通读发现 Service 无二次校验 | 边界校验的唯一口径放在 Controller（`@Valid`）是本项目冻结的分层约定；在 Service 重复一遍会产生"两处真相"，后续改一处漏一处 | 绕过 HTTP 入口（如内部调用）时：空标题会写入空串（`NOT NULL` 拦不住空串），超长在严格模式下抛 `DataIntegrityViolation` 被兜成 409。**当前所有入口都经 `@Valid`**（HTTP 实测通过）；若后续新增内部调用方，应先补 `assertTitle` |
| N2 | `ReviewServiceImpl` 对校验层已保证非空的字段直接 `.trim()`（audit/reject/archive） | 人工通读时对照 `DocumentRejectDtoReq` 的 `@NotBlank` | 同上：这些字段的契约由 DTO 承担；加判空等于重复校验 | 绕过校验层时 `null.trim()` → NPE → 500（不是数据损坏）。与 N1 同族，一并留待"入口收敛"重构时处理 |
| N3 | `Document.tags` 使用只读 `@ManyToMany` | 机检 `@ManyToMany` 命中 1 处 | 老师两份材料自相矛盾（6.1 用、2.1 禁），本项目按 `MASTER-PLAN` §5 戒律 5 的折中口径执行：查询导航可用只读关联，**禁止参与写入**；`GLOSSARY.md` §2.3/§3.7、`ARCHITECTURE.md` §4.4、`DIFF-VS-COURSEWARE-4-7.md` §4.4 均已登记该决策 | 4 项防护：join 列 `insertable=false, updatable=false`、无 `cascade`、无 `orphanRemoval`、`add/remove` 调用 0 处；实际读取走 `tagRepository.findTagsByDocumentId`（详情接口 1 条 SQL） |
| N4 | `verify-m3-http.ps1` 的两条环用例只断言 400 | M6-D2 排查时发现 | M3 检查器是那一轮的验收留痕，**不回改历史证据**（改了就无法追溯当时的结论） | 已由 `verify-m6-http.ps1` G1~G5 补齐（文案 + 数据不变），并在本文件与 `M6-CLOSURE.md` 双处登记 |

---

## 8. 复跑方式（审查结论可自证）

```powershell
# ① 静态与 DB 检查器（只读，先跑）
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m0.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m1.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-api-spec.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m2.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-db-deep.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m3.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m4.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m5.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m6.ps1     # 本次新增：把上面 33 项判据固化

# ② 单测（纯 Mockito，无需数据库）
$env:JAVA_HOME='D:\DevEnv\02_JDK\jdk-17.0.5'; cd backend; .\mvnw.cmd clean test

# ③ HTTP 层（会改数据，跑完请重灌库）
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m3-http.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m4-http.ps1 -AppLog D:\DevEnv\logs\campusswap-app.log
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m6-http.ps1

# ④ 查询性能证据链（逐接口 SQL 点数 + 索引回归）
F:\node\node.exe docs/03-qa-review/probe-sql-counts.mjs
```

> 运行顺序与"跑完必须重灌库"的原因写在 `TEST_CHECKLIST.md` 顶部；重灌一条命令：`reload-db.ps1`。

---

## 9. 与冻结文档的偏差登记

本次审查**没有**改动任何冻结口径（`GLOSSARY` / `PRD` / `API_SPECIFICATION` / `UI_UX_SPECIFICATION` / `ARCHITECTURE` 的字段与契约）。
三处修复都属**实现层修正**：`StatController` 拆分、环检测顺序、详情分类名取值来源 —— 对外契约（端点、字段、状态码、中文文案）逐字不变，故无需变更 ADR；已在 `M6-CLOSURE.md` 与 `docs/MASTER-PLAN.md` 的 M6 变动说明里记录。
