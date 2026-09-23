# COURSEWARE-CLOSURE — 课件 4.1 / 5.1 对齐收口记录

> 时间：2026-09-23 晚 ｜ 触发：老师新讲了 4.1 乐观锁与 SQL 原子操作、5.1 Spring Security+JWT+Redis、
> 6.1 RBAC、7.1~7.3 Redis 缓存三课；用户要求"除不用的 JWT 架构外，看有哪些值得改/升级，麻烦程度如何"。
> **对账底稿**：`DIFF-VS-COURSEWARE-4-7.md`（DoD 逐条打勾 + 难度 + 决策表）。
> **本次范围**：A1 `@Modifying` 参数、A2 密码哈希抽象、A3 JUnit 白盒通道、B1① 陈旧表单防覆盖（409）。
> **明确不做**：D5 字典缓存（用户指示"降级为只写文档说明"）、ZSet 热榜（D6 不做）、JetCache 双级缓存等（理由见 §6）。
> 口径：本文件所有数字与输出均为本轮实测，命令写在 §8，可复跑。

---

## 1 一句话结论

四项全部落地并各有可复跑证据：**产品机检 504 → 507 项、JUnit 白盒 0 → 6 用例、预览稿自检 200 → 211 项，全部 0 失败**。
过程中**被机检抓到一个真实缺陷**（`@Modifying` 只加 `clearAutomatically` 会丢掉未 flush 的挂起写入，导致"标签计数 +1、关联表 0 行"），
已修复、复验并把这次教训固化成规则（`ARCHITECTURE §10.6`）与回归测试（`TagBindingConsistencyTest`）。

| 项 | 课件依据 | 改动 | 证据 |
|---|---|---|---|
| **A1** `@Modifying` 参数 | 4.1 DoD③"必须带 `clearAutomatically = true`，杜绝一级缓存脏读" | 8 个仓储 **19 处**统一改为 `@Modifying(clearAutomatically = true, flushAutomatically = true)` | `BulkUpdateStalenessTest`（对照实验）+ 全量机检 507 全绿 |
| **A2** 密码哈希抽象 | 5.1 DoD①"必须使用 `BCryptPasswordEncoder`" | `pom.xml` +`spring-security-crypto`；新增 `config/PasswordEncoderConfig`；`AuthServiceImpl`/`UserServiceImpl` 4 处调用点改走 `PasswordEncoder` | 依赖树与打包 classpath 实测；`PasswordHashCompatTest`；三账号登录 HTTP 200 |
| **A3** 白盒测试通道 | 6 份课件里 4 份 DoD 点名"单元与集成测试绿灯" | 新增 4 个测试类 / 6 用例 + `application-test.yml`（连接池 120） | `mvnw test` → `Tests run: 6, Failures: 0, Errors: 0` |
| **B1①** 陈旧表单防覆盖 | 4.1 DoD①②"DTO 带 version + 冲突返回 409 结构化 JSON" | `DocumentUpdateDtoReq` 新增**必填** `versionNum`；Service 比对 → 409；机检 6 处调用补字段 + 3 条新断言 | `verify-m4-http.ps1` 218 → **221** 全绿；`m3-http` 142 全绿 |
| — | — | **端点仍是 57 个、表结构不变、权限码不变** | `verify-api-spec.ps1` 24 项全绿 |

---

## 2 B1① 的决策变更：为什么复用 `version_num` 而**不**引入 JPA `@Version`

`ARCHITECTURE §17 ADR-07` 原文是"**不做乐观锁**（`@Version`），以 `version_num` 单调递增 + 后写覆盖"。
课件 4.1 把"Web 全链路防覆盖"列为 DoD，因此**改写了这条冻结决策**（原文已保留为"备选 ③"并标注作废原因，不是悄悄删掉）：

| 方案 | 做法 | 取舍 |
|---|---|---|
| ① `@Version` 乐观锁 | 新增 `lock_version` 列 + 实体注解，Hibernate 自动拼 `WHERE id=? AND version=?` | **不选**：`version_num` 已是业务版本号（每次正文写入或状态流转 +1，BR-06）。新增乐观锁列会让"状态流转"（提交/审核/归档/回收都要 +1）与"并发冲突检测"两套语义互相污染；还要给 14 条保存路径逐一评估 `ObjectOptimisticLockingFailureException` 的重试语义。收益仅是"日志里能看到一条 `WHERE id=? AND version=?`" |
| ② **应用层版本比对（本次采用）** | `PUT /api/documents/{id}` 必填 `versionNum`（前端带"打开编辑页时读到的那一版"），与库中当前值不一致 → **409** | 拿到课件 DoD①② 的全部语义；**不改表、不加依赖**；前端 M5 未开工，现在定契约最便宜 |
| ③ 后写覆盖（原决策） | 不校验，最后保存者生效 | 作废：两人同时编辑会**静默丢掉**先保存者的改动 |

**校验顺序（写死，避免"同时踩两个错误时前端看到哪一条"含糊）**：
`id` 与路径一致（400）→ 文档存在（404）→ 归属（403）→ 归档/回收站只读（409）→ **`versionNum` 一致（409）** → 写入。

**前端影响**（M5 未开工，已写进计划）：编辑页保存回传 `versionNum`；收到 409 走"版本冲突横幅 + 刷新内容按钮"，
不切只读、不静默覆盖。已同步 `UI_UX_SPECIFICATION §8.5`、`MASTER-PLAN T5.6`、`GLOSSARY`、`API_SPECIFICATION §4.6.6/§9.5`、预览稿 v8.2。

---

## 3 先说失败：本轮被机检抓到的真实缺陷（**先红后绿**）

### 3.1 现象

A1 第一版只加了 `clearAutomatically = true`（19 处），随后跑 `verify-m4-http.ps1`：

```
########## m4-http  exit=2  41.6s ##########
 RESULT: PASS=216  FAIL=2
 Failed checks:
   - US02.create.tags.count      (期望 2，实际 0)
   - US06.edit.tags.count        (期望 1，实际 0)
```

### 3.2 复现（HTTP + SQL，两条独立证据）

新建文档带 2 个标签（tag 1、2）：

| 观察点 | 坏版本（只 clear） | 修好后 |
|---|---|---|
| 响应里的 `tags` | `[]`（0 条） | 2 条（国家自然科学基金、实习支教） |
| `doc_document_tag_rel` 行数 | **0** | **2** |
| `doc_tag.use_count` | 1 → **2**、1 → **2**（照加不误） | 1 → 2、1 → 2（与关联行数一致） |
| `doc_version` 行数 | 1 | 1 |

即：**计数被改了，关联行却消失了** —— 数据被写坏，不是读脏。

### 3.3 根因（Hibernate 语义，不是猜的）

1. `doc_document_tag_rel` 用 **`@EmbeddedId` 复合主键**，`saveAll` 的新行**不会立即 INSERT**，而是挂在持久化上下文里等 flush；
2. 紧跟其后的标签计数批量更新（`update doc_tag set use_count = ...`）的**查询空间只有 `doc_tag`**，
   Hibernate 的自动 flush 按查询空间判断"与待插入行无关"→ **不 flush**；
3. 而 `clearAutomatically` 在语句执行后 `clear()`，**`clear()` 不 flush**，于是把这批待插入的关联行**直接丢掉**。

一句话：**`clearAutomatically` 必须与 `flushAutomatically` 成对使用**；只加前者会把"防止读脏"变成"写入丢失"。

### 3.4 修法与复验

- 19 处统一改为 `@Modifying(clearAutomatically = true, flushAutomatically = true)`；
- `verify-m4-http.ps1` → **221/221**（218 原有 + 3 条新断言），`m3-http` **142/142**，其余 7 个脚本全绿；
- 新增回归锁 **`TagBindingConsistencyTest`**：从 Service 层真实走一遍"建文档 + 打 2 个标签"，
  同时断言「响应 tags=2 + 关联表 2 行 + 两个标签计数各 +1 + 版本留痕 1 条」——
  这个用例在坏版本上必红，在修好后必绿；
- 规则与现场记录写进 `ARCHITECTURE §10.6`（含"19 处逐个复核结论：执行点之前都已 `saveAndFlush`，
  故补 `flushAutomatically` 不改变任何 SQL 条数预算，17 项预算断言实测不变"）。

### 3.5 顺带修的数据库卫生

坏版本那次 m4-http 中途失败退出，留下了 4 篇临时文档（`M4-empty-*` / `M4-DRAFT-*` / `M4-A-*`）与被我探针创建的 1 篇，
标签计数也被多加了 3 次。处置：**用 `backend/sql/schema.sql` + `data.sql` 重建到干净口径**（两脚本可重复执行，本轮又验证了一次），
重建后逐项核对：权限 39 / 角色 3 / 部门 3 / 用户 3 / 分类 4 / 标签 5 / 文档 5 / 版本 9 / 收藏 3 / 标签关联 5 /
标签计数 `1,1,1,2,0`（与关联行数自洽）/ 索引 21 个（19 个具名 + PRIMARY）。

### 3.6 收口回归又抓到一个真实缺陷：`DB_PASSWORD` 环境变量撞名（**已复现、已修**）

**现象**：最后一轮全量回归里，9 个机检脚本全绿（m4-http 221/221），但紧接着的 `mvnw test`
**6 个用例全部 `Errors`**（不是断言失败，而是 Spring 上下文起不来）：

```
HHH000247: ErrorCode: 1045, SQLState: 28000
Access denied for user 'campusswap_dev'@'localhost' (using password: YES)
Failed to initialize JPA EntityManagerFactory ... Unable to determine Dialect without JDBC metadata
```

**为什么极难发现**：dev 应用**靠已建好的 Hikari 连接池照常工作**（连接是重建库之前认证过的），
所以三个 HTTP 机检依旧全绿；只有**新起的测试 JVM** 需要重新认证，才会暴露。第一反应容易误判成"数据库/Redis 抖动"。

**根因（不是猜的，是复现出来的）**：机检脚本 `verify-m2.ps1` / `verify-db-deep.ps1` 约定
`$env:DB_PASSWORD` = **root 的口令**；而 `application-test.yml` 写的是 `${DB_PASSWORD:CampusSwap@2026}`，
Spring 会把**同名环境变量**插值进来 → 测试 JVM 拿 root 口令去连 `campusswap_dev` → 1045。
验证方式：同一顺序里只要导出 `DB_PASSWORD=123456` 就必失败（两次），不导出就必通过（两次）——
**4 次运行 100% 复现**。

**修法（两边同时解耦，避免"这次改了下次又撞"）**：

| 位置 | 改动 |
|---|---|
| `backend/src/test/resources/application-test.yml` | 改用专属变量名 `${CAMPUSSWAP_TEST_DB_USER:campusswap_dev}` / `${CAMPUSSWAP_TEST_DB_PASSWORD:CampusSwap@2026}`，并在文件里写明这次踩坑 |
| `verify-m2.ps1` / `verify-db-deep.ps1` | 口令优先取 `$env:MYSQL_ROOT_PASSWORD`，**兼容**旧名 `$env:DB_PASSWORD`（两个脚本用法注释同步更新，仍保持纯 ASCII） |

**复验**：清空 `DB_PASSWORD`、只设 `MYSQL_ROOT_PASSWORD` 后按原顺序全量重跑 —— 见 §4.5。

**顺带记下的同源陷阱**（留给以后，不擅自改冻结合同）：`application-dev.yml` / `application-prod.yml`
同样把 `DB_PASSWORD` 当 **campusswap_dev** 的口令，而机检脚本把同名变量当 **root** 的口令。
即"照着机检文档设了 `DB_PASSWORD=123456`，再在同一个终端启动应用"，dev 会连不上库。
现在测试侧已解耦；dev/prod 侧的命名属 `PRD NFR-S5` 与 `ARCHITECTURE §7` 的冻结口径，改动需单独决策。

---

## 4 逐项实测证据

### 4.1 A1：`@Modifying` 参数

| 证据 | 内容 |
|---|---|
| 静态计数 | `Get-ChildItem backend\src -Recurse -Filter *.java \| Select-String '@Modifying'` → 真实注解 **19 处**（另有 4 行 javadoc 提到该词，故 grep 计数为 23）；19 处**全部**带 `clearAutomatically = true, flushAutomatically = true` |
| 调用序列复核 | 8 个仓储 19 处逐个看调用点：批量语句执行前都已 `saveAndFlush` 或本来无挂起改动 → `flushAutomatically` 不改变 SQL 条数（17 项 SQL 预算断言实测不变）；其中 4 处为预留方法、当前无调用方，统一加参数只为规则一致 |
| **对照组（证明 clear 不是装饰）** | `BulkUpdateStalenessTest#withoutClearAutomatically`：同一事务内 `em.find` → 原生 `executeUpdate()`（等价于没加参数的 `@Modifying`）→ 再 `em.find`，**读到旧值**，而直读数据库已是新值（脏读复现，绿） |
| **实验组（证明我们的仓储已修好）** | `BulkUpdateStalenessTest#withClearAutomatically`：同一事务内 `findById` → `increaseViewCount`（带参数）→ 再 `findById`，**读到新值**（`initial + 1`） |
| 端到端 | 全量机检 507 项 0 失败；`mvnw test` 6/6 绿 |

### 4.2 A2：官方 `PasswordEncoder`（**先证兼容、再改实现**）

A2 没有"红"这一步是**故意的**：改错了会让三个种子账号全部登不进来。所以先做**只读探针**（`docs/03-qa-review/probes/PwProbe.java`）：

```
hash.prefix      = $2b$10$          ← 库里既有的 Hutool 生成哈希
matches(existing)= true             ← Spring Security 7.1.1 能校验它
spring.new.prefix= $2a$10$
matches(new)     = true
upgradeEncoding  = false            ← 不需要强制重算哈希
```

改完后的实测：

| 证据 | 内容 |
|---|---|
| 依赖树 | 编译期 classpath 里 `org.springframework.security` **只有 `spring-security-crypto:7.1.1`** 一条（无 `-core` / `-web` / `-config`）；`commons-logging:1.3.6` 是 `spring-core` 本来就依赖的 |
| 依赖版本来源 | Boot 4.1.1 BOM 的 `<spring-security.version>7.1.1</spring-security.version>`，与本地仓库已缓存的版本一致 → **加依赖不需要联网** |
| jar 内容 | `spring-security-crypto-7.1.1.jar` 共 89 个条目，顶层包**只有** `org/springframework/security/crypto` → 不含过滤器链相关类 |
| 启动日志 | 全程无 Spring Security 自动配置痕迹（无 `Will secure any request`、无 `springSecurityFilterChain`） |
| 接口行为 | 无 token 访问 `/api/documents/manage` → **我方 JSON 401** `{"code":401,"message":"登录状态已失效，请重新登录","data":null}`（不是框架默认 401/跳转） |
| 三账号登录 | `admin` / `docadmin` / `staff` 全部 `HTTP 200 code=200`，`GET /api/auth/me` HTTP 200 且 admin 拿到 39 个权限码 |
| 白盒 | `PasswordHashCompatTest`：`$2b$` 存量哈希可校验、错误口令被拒、`upgradeEncoding=false`；新哈希为 `$2a$10$`、自校验通过、**Hutool 也能反向校验**（换回去也不会锁死账号） |
| 自助改密 | `verify-m3-http.ps1` §8b（142 项里的一部分）：旧密码错 400、强度四档 400、改密后旧 token 全 401、新密码登录 200 |

### 4.3 A3：JUnit 白盒通道（6/6 绿）

```
[INFO] Running com.campusswap.document.BulkUpdateStalenessTest      Tests run: 2, Failures: 0, Errors: 0
[INFO] Running com.campusswap.document.TagBindingConsistencyTest    Tests run: 1, Failures: 0, Errors: 0
[INFO] Running com.campusswap.document.ViewCountConcurrencyTest     Tests run: 1, Failures: 0, Errors: 0
[INFO] Running com.campusswap.system.PasswordHashCompatTest         Tests run: 2, Failures: 0, Errors: 0
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- **`ViewCountConcurrencyTest`**：100 线程经 `CountDownLatch` 同刻释放并发调用 `increaseViewCount` → 断言最终 `view_count = 初始 + 100`（课件 4.1 DoD④）；数据卫生用"插入一行临时文档 + `@AfterEach` 物理删除"，不碰种子数据。
- **`application-test.yml` 为什么必要**：dev 的 Hikari `maximum-pool-size = 10`，100 个线程会**全部堵在池子上排队**——断言照样通过，但那是"假并发"（等于串行）。测试 profile 把池子放大到 **120**，100 个线程才真的同时打到数据库。这是"真测"与"演一下"的分界。
- **`TagBindingConsistencyTest`**：见 §3.4，锁死本轮缺陷。
- 4 个测试类共用同一个 Spring 上下文；`src/test` 此前是**空包**（0 个测试类），本轮是项目第一次有自动化测试。

### 4.4 契约与接口（B1①）

| 项 | 结果 |
|---|---|
| 过期版本 | `PUT` 带 `versionNum = 2`（库中已是 3）→ **409**「该文档已被他人修改（当前版本 v3），请刷新后重试」 |
| 冲突后内容不动 | 该次请求后 `GET` 详情，标题仍是上一次成功保存的 `... v2`（**分毫未改**） |
| 缺字段 | 不带 `versionNum` → **400**「缺少文档版本号，请刷新后重试」 |
| 正常保存 | 带当前版本 → 200 且 `versionNum` 3 → 4（断言 `US06.edit.versionNum = 3` 覆盖前一次） |
| 端点总数 | **仍 57 个**（`verify-api-spec.ps1` 24 项全绿，含端点表与响应体契约检查） |

### 4.5 收口回归的最终结果（一轮跑完，全部 0 失败）

顺序 = **重建库 → 6 个静态/DB → 3 个 HTTP → JUnit → 预览稿自检**
（环境里清空 `DB_PASSWORD`、只设 `MYSQL_ROOT_PASSWORD` —— 即 §3.6 修好后的正确跑法）：

| 通道 | 结果 |
|---|---|
| `verify-m0` / `m1` / `api-spec` | 13 / 15 / 24 全绿 |
| `verify-m2` / `db-deep` | 17 / 9 全绿 |
| `verify-m3` / `m3-http` | 36 / **142** 全绿 |
| `verify-m4` / **`m4-http`** | 30 / **221** 全绿（含 3 条陈旧表单新断言 + 17 项 SQL 预算） |
| **JUnit** | **6/6 绿**（上一轮同一顺序里 6 个用例全报 1045，修 §3.6 后复绿） |
| 预览稿自检 | **211/211 绿** |
| **合计** | **产品机检 507 + JUnit 6 用例 + 预览稿 211 = 724 项，0 失败** |

原始输出留档（以后出问题先看这三个）：

| 文件 | 内容 |
|---|---|
| `D:\DevEnv\logs\mvnw-test.log` | `mvnw test` 全量输出（含 surefire 报告与堆栈；上一轮那次 1045 的堆栈就是从这类日志里抓到的） |
| `D:\DevEnv\logs\campusswap-app.log` | dev 应用日志（m4-http 的 `Count-Sql` 预算断言读它） |
| `D:\DevEnv\logs\shots\` | 预览截图 5 张（kit / home / edit / login / search）+ 2 张 2× 放大裁剪（改动的两处） |
| `D:\DevEnv\logs\campusswap-app-run1-noclear.log` | **坏版本**（只加 `clearAutomatically`）那一轮的日志，留档对照 |

---

## 5 与冻结文档的偏差清单（改了什么口径、为什么）

| 文档 | 改了什么 | 为什么 |
|---|---|---|
| `ARCHITECTURE.md §17 ADR-07` | 由"不做乐观锁、后写覆盖"**改为**"应用层版本比对"，原决策保留为备选 ③ 并标注作废原因 | 对齐课件 4.1 DoD①②；决策变更留痕（谁、何时、为什么） |
| `ARCHITECTURE.md §17 ADR-11` | **新增**：验收通道 = 外部机检 + JUnit 白盒 | 课件 4 份 DoD 点名单测；黑盒压不了并发 |
| `ARCHITECTURE.md §10.6` | **新增**：`@Modifying` 两参数必须成对 + 本轮踩坑现场记录 + 19 处复核结论 | 把"被机检抓到的缺陷"变成规则，防止下次只加一个参数 |
| `ARCHITECTURE.md §12` | 并发编辑一行改写；新增"高频计数"一行 | 与 ADR-07 / §10.6 对齐 |
| `ARCHITECTURE.md §19` | 冻结自检表 +1 行（并发与陈旧表单有可复跑证据） | 自检项要能指到证据 |
| `API_SPECIFICATION.md §4.6.6` | 入参表 +`versionNum`；错误码表 409 分两种；新增"校验顺序"与"实现口径"两条 | 契约唯一真源 |
| `API_SPECIFICATION.md §9.5` | **新增**变更记录（触发/改动/为什么不引 `@Version`/影响面） | 与 §9.1~§9.4 同格式 |
| `GLOSSARY.md` | `DocumentUpdateDtoReq` 增 `versionNum` 必填说明 | 字段字典唯一真源 |
| `UI_UX_SPECIFICATION.md §8.5` | 错误四态里 409 **拆成两种**（状态冲突切只读 / **版本冲突不切只读**，给"刷新内容"按钮）；关键交互补"保存必须回传 `versionNum`" | M5 照此实现 |
| `TEST_CHECKLIST.md` | 汇总表项数更新（m4-http 218→221、预览 200→211、新增 JUnit 行）+ 新增覆盖说明 | 全量口径 |
| `MASTER-PLAN.md` | T5.6 补"编辑器保存必须回传 `versionNum`"；新增 T5.9（`mvnw test` 可复跑）；附录 D 追加 v2.6 行 | 计划与现状一致 |
| `UI-PREVIEW.html` + `ui-preview.smoke.mjs` | 预览稿 **v8.1 → v8.2**：设计系统页补 409 版本冲突提示与 toast；「与前端的落地接口对照」登记该契约（三项变更 → 四项）；自检 200 → 211 | 预览稿严格照正式版（用户长期要求） |
| `backend/pom.xml` | +`spring-security-crypto`（版本由 Boot BOM 给）；Hutool 注释改为 `IdUtil、StrUtil` | 课件 5.1 |
| `backend/sql/*` | **未改**（本次不涉及表结构） | 表结构仍来自 `schema.sql` |

---

## 6 明确不做的事（连同理由一起留档）

| 项 | 课件依据 | 为什么不做 |
|---|---|---|
| **D5 缓存分类树**（Cache-Aside 示范） | 7.2 | **用户明确指示降级为"只写文档说明"**。补记技术理由：分类树本身只有 1 条 SQL（实测 0.149ms 级），本机 Redis 往返与之同量级，收益测不出来；且机检有 `Count-Sql 'category-tree' = 1` 的精确断言，缓存命中会变成 0，需要改口径。**权限缓存本身就是标准 Cache-Aside 落地**（读未命中→查库→回填 30min TTL；写→事务提交后删缓存），这条已经在了 |
| ZSet 热门文档 Top 10 | 7.2 | 与现有 `sort=viewCount_desc` 功能重叠，且属 PRD 之外的新需求（用户已明确不做） |
| 分类树 / 标签列表缓存整对象 | 7.2 | 标签列表带 `use_count`，它会随"文档打标签"变化（不在标签自己的写接口上）→ 缓存必然脏读 |
| JetCache 双级缓存（Caffeine + Redis） | 7.3 | 前提是"多节点集群 + L1 省网络往返"。我们是单实例、MySQL/Redis 都在本机；且 L1 本地缓存与 AC-08.1"授权变更即时生效"直接冲突（会引入最长 `localExpire` 的脏读窗口）。另外 `caffeine` / `jetcache` 不在本地 Maven 仓库，要用得联网下载 |
| 权限码全量改三段式 | 6.1 | 要动 39 个码 + 3 张关联表 + **53 处注解** + 8 份文档 + 4 个机检脚本，纯机械但漏一处就是 403。我们的规范是"两段 = 页面/入口（如 `doc:center`）、三段 = 按钮级（如 `sys:user:disable`）"，已写进 GLOSSARY |
| 角色码加 `ROLE_` 前缀 | 6.1 | 该前缀只在 Spring Security 的 `hasRole()` 语义下有意义，我们不用该框架 |
| `@ManyToMany` 建模 | 6.1 | 老师两份材料自相矛盾：`6.1` 用 `@ManyToMany`，`2.1 专题指南` 明令禁止（中间表要能挂扩展字段、要能审计）。我们按 `2.1` 做 |
| `maxmemory-policy = allkeys-lru` | 7.1 | 课件前提是"Redis 当纯缓存"；我们的键是**会话与授权数据**，被 LRU 淘汰 = 用户莫名掉线/权限错判（静默故障）。我们的防线是"**4 类键全部带 TTL**"（实测 `dbsize` 与 `expires` 相等）+ `noeviction`（写满宁可报错也不静默丢会话）。本机 `maxmemory 0` 如实记录，生产建议设上限并保持 `noeviction` |
| `@PreAuthorize` 迁移 | 6.1 | `DIFF-VS-TEACHER §9` 已结案（老师答复"底层原理一样，那套只是更规范"） |

---

## 7 数据卫生与回滚

### 7.1 备份（改动前，三件套）

| 备份 | 位置 | 校验 |
|---|---|---|
| git tag | `pre-courseware-align`（指向 `fd6b984`，已推送远端） | `git show pre-courseware-align` |
| 源码快照 | `D:\DevEnv\backup\campusswap-src-20260923-210228.zip`（`git archive HEAD`，仅受版本控制文件） | SHA256 `93DC9A35E5B1138CD09C13F8DCF6ADE0FACC6E2AAF592845AC793163B4D0F392`，1 909.6 KB |
| 数据库 | `D:\DevEnv\backup\campusswap_db-20260923-210228.sql`（含 routines/events，single-transaction） | SHA256 `7CCC953A9BCC2091CEB7AAEE01EC32CCE80E09CE961FF4F879BD12B989C72996`，14 张表 + 13 条 INSERT |

Redis 无需备份：`dbsize = 0`（全部键都是带 TTL 的会话/权限/去重标记，丢了重新登录即可）。

### 7.2 回滚命令

```powershell
# 代码回滚到加固前
git -C D:\DevEnv\projects\campusswap reset --hard pre-courseware-align
# 数据库回滚
cmd /c "D:\DevEnv\03_MySQL\bin\mysql.exe -uroot -p****** --default-character-set=utf8mb4 < D:\DevEnv\backup\campusswap_db-20260923-210228.sql"
# 只回滚本次提交（保留后续提交）
git -C D:\DevEnv\projects\campusswap revert <本次 commit>
```

### 7.3 数据库最终口径

本轮一共重建两次（一次是坏版本留下的测试残留，一次是最终回归前的干净基线），已核对：
权限 39 / 角色 3 / 部门 3 / 用户 3 / 分类 4 / 标签 5 / 文档 5 / 已发布 2 / 版本 9 / 收藏 3 / 标签关联 5 / 标签计数 `1,1,1,2,0` / 索引 21。
`verify-m2.ps1`（17）与 `verify-db-deep.ps1`（9）在这一口径上全绿。

---

## 8 复跑清单（按顺序，全绿才算通过）

```powershell
# ── 0) 前置：MySQL80 与 Redis 服务在跑；dev 服务在 10087
$env:JAVA_HOME='D:\DevEnv\02_JDK\jdk-17.0.5'
cd D:\DevEnv\projects\campusswap\backend
.\mvnw.cmd -o spring-boot:run "-Dspring-boot.run.arguments=--spring.jpa.hibernate.ddl-auto=validate"
# 日志重定向到 D:\DevEnv\logs\campusswap-app.log（m4-http 的 Count-Sql 要读它）

# ── 1) 静态 + 数据库类（先在干净库上跑）
$env:MYSQL_ROOT_PASSWORD='123456'   # 注意：这是 **root** 的口令，不是 dev 账号的！
                                    # 旧名 DB_PASSWORD 仍兼容，但别用它 —— 见 §3.6
cd D:\DevEnv\projects\campusswap
foreach ($s in 'verify-m0.ps1','verify-m1.ps1','verify-api-spec.ps1','verify-m2.ps1','verify-db-deep.ps1','verify-m3.ps1') {
  powershell -NoProfile -ExecutionPolicy Bypass -File "docs\03-qa-review\$s"
}

# ── 2) HTTP 类（会改动数据，放后面）
powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m3-http.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m4.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m4-http.ps1 -AppLog D:\DevEnv\logs\campusswap-app.log

# ── 3) JUnit 白盒（4 类 6 用例）
cd backend; .\mvnw.cmd test

# ── 4) 预览稿自检（211 项）
cd ..; F:\node\node.exe docs\02-design\ui-preview.smoke.mjs

# ── 5) 全量机检（507 项）跑完后，库会被 HTTP 用例写脏；要留干净演示库就重建一次：
cmd /c "D:\DevEnv\03_MySQL\bin\mysql.exe -uroot -p****** --default-character-set=utf8mb4 < backend\sql\schema.sql"
cmd /c "D:\DevEnv\03_MySQL\bin\mysql.exe -uroot -p****** --default-character-set=utf8mb4 < backend\sql\data.sql"
```

**期望值**：m0 13 ｜ m1 15 ｜ api-spec 24 ｜ m2 17 ｜ db-deep 9 ｜ m3 36 ｜ m3-http **142** ｜ m4 30 ｜ m4-http **221** = **507**；
JUnit **6/6**；预览稿自检 **211**。

---

## 9 本轮遗留（交给 M6 / 用户决定）

1. **D5 字典缓存**：按用户指示只写文档、不动代码。若答辩需要展示 Cache-Aside，最小增量是"缓存分类树 + 3 个写接口后删缓存 + 改 1 条 SQL 预算断言"，约 0.25 人天。
2. **`versionNum` 的前端落地**：M5 T5.6 已写进计划，实现时要截图留证（编辑 → 撞版本 → 横幅 → 刷新 → 再保存）。
3. **`@Version` 数据库级兜底**：本轮刻意不做。若老师明确要求看到 `WHERE id=? AND version=?`，再加独立列 `lock_version`（改动面见 `DIFF-VS-COURSEWARE-4-7.md §3 B1-②`），`versionNum` 这一层契约不受影响。
4. **`mvnw test` 依赖真库真 Redis**：跑测试时若 dev 服务正在被 HTTP 机检使用，两边会抢数据 —— 纪律：**先跑机检、再跑测试**（或先停 dev 服务）。
