# AUDIT-M0-M2.md — 前序成果复核记录（准确性 / 检查器可信度 / N+1 审计 / 下一步准备）

| 项 | 值 |
|---|---|
| 文件 | `docs/03-qa-review/AUDIT-M0-M2.md` |
| 版本 | v1.0（2026-09-21） |
| 触发 | 用户提问：**"每轮都纠出之前的错漏，先确认之前的准确性；'通过'是真没问题还是碰巧；是高效联合查询还是藏着 N+1；M2 前后还需要准备/检查/开启什么"** |
| 结论 | 复核发现 **1 处真缺口 + 4 处文档滞后**，已全部修复；**6 项变异测试全部被检查器拦截**（证明"通过"不是碰巧）；N+1 已从"口头承诺"升级为**逐接口 SQL 条数预算**（`ARCHITECTURE §10.5`） |

---

## 一、准确性复核：文档之间、文档与数据库之间是否真的对齐

| # | 比对 | 方法 | 结果 |
|---|---|---|---|
| A1 | **数据库列 ↔ GLOSSARY 字段字典** | 取 `information_schema` 全部 62 个非审计列，逐个在 GLOSSARY 中查存在性 | **0 处未收录** ✅ |
| A2 | **PRD 39 个权限码 ↔ 种子数据权限码** | 两侧集合双向差集 | **完全一致**（39 = 39，无单侧多余） ✅ |
| A3 | **接口/前端字段名 ↔ GLOSSARY §3.7** | 抽取接口文档字段表（36 个）+ 前端 TS 属性（75 个），按词边界逐个比对 | **发现 1 处真缺口**：`UserPasswordDtoReq.newPassword`（重置密码接口的入参 DTO）只在接口文档里，未登记 → **已补进 GLOSSARY §3.7** |
| A4 | **旧库名/旧列名残留扫描** | 全仓搜 `docs_db`、`is_deleted`、`create_at`、`role_code` 等 | 发现 4 处文档滞后（见下） |
| A5 | **README ↔ 实际状态** | 人工读全文 | 发现 5 处滞后（见下） |

### 本轮修掉的文档滞后（都是"文档写得比现实旧"）

| # | 位置 | 问题 | 处理 |
|---|---|---|---|
| 1 | `README.md` | 库名仍写 `docs_db`；后端端口仍写 `10086` + context-path `/backend`；包结构仍是旧的扁平分层；进度 M0~M2 未勾；"关联用逻辑外键"与"雪花 ID"是旧口径 | 全文重写为 M2 现状（含 4 个机检脚本的运行命令） |
| 2 | `MASTER-PLAN.md` §1 决策表第 12 条 | 库名仍写 `docs_db` | 改为 `campusswap_db` + 应用账号 `campusswap_dev` |
| 3 | `MASTER-PLAN.md` §2.2 第 3 条 | 主键仍写"雪花、不写 AUTO_INCREMENT"（与 v2.1 决策冲突） | 改为 `BIGINT AUTO_INCREMENT` + 中间表复合主键 |
| 4 | `ARCHITECTURE.md` §14 | 数据源账号写 `root` | 改为 `campusswap_dev`（最小权限）+ 迁移用 SQL |
| 5 | `GLOSSARY.md` §3.3 | `doc_version` 字段表未注明"除公共审计列外"（DB 里它有完整 5 个审计列） | 补说明（本次一并修正） |

### 判定为"可接受、不修剪"的三处

| 项 | 为什么保留 |
|---|---|
| `ARCHITECTURE.md` / `data.sql` 头部提及 `docs_db` | 只出现在变更说明里（"旧库 docs_db → campusswap_db"），是**变更留痕**，删掉反而丢失历史 |
| GLOSSARY §6 禁用别名表里出现 `is_deleted` / `role_code` 等旧名 | 该表的存在目的就是列出禁用词；机检用 ASCII 标记（`Anti-alias`）跳过该节 |
| API 文档附录 A 保留 29 个作废写法 | 迁移对照价值 > 洁净度；正文已 0 命中（机检 C7 断言） |

---

## 二、检查器可信度：**变异测试**（证明"通过"不是碰巧）

方法：故意把每类问题植入 → 跑检查器 → **必须报 FAIL** → 用 `git checkout` / SQL 还原 → 再跑必须恢复全绿。

| # | 植入的缺陷 | 期望被哪项拦截 | 实测结果 |
|---|---|---|---|
| M1 | 往 `ARCHITECTURE.md` 塞禁用别名 `perm_code` | `verify-m1` C7 | ✅ `[FAIL] C7 -- ARCH=1 API=0 UI=0` |
| M2 | 往接口文档塞不存在的权限码 `doc:nonexistent` | `verify-m1` C11 | ✅ `[FAIL] C11 -- unknown=doc:nonexistent` |
| M3 | 破坏一条 BDD 断言（去掉一个 `**Given**`） | `verify-m0` C3 | ✅ `[FAIL] C3 -- Given=0 When=24 Then=24` |
| M4 | 插入非法状态文档（`status='BOGUS'`） | `verify-m2` C16（顺带 C14） | ✅ `[FAIL] C16 -- doc.status=1` + `[FAIL] C14 -- documents=6(want 5)` |
| M5 | 把 `staff` 密码改成明文 `123456` | `verify-m2` C15 | ✅ `[FAIL] C15 -- 1 user without BCrypt hash` |
| M6 | 删掉 `idx_doc_status_updated` 索引 | `verify-m2` C12（顺带 C13） | ✅ `[FAIL] C12 -- missing=idx_doc_status_updated` + `[FAIL] C13 -- status=[NULL]` |

**6/6 全部拦截，还原后 4 个检查器全部恢复全绿**（M0 13/13、M1 15/15、API 24/24、M2 16/16）。结论：**这些"通过"是检查器真的在检查，不是空转。**

> 本轮之前还修过检查器自身的 3 个"假绿"隐患：① 中文过滤条件在 PS 5.1 下被 GBK 误读后**静默失效**（改用 ASCII 标记）；② 单元素数组被 PowerShell 解包导致 `$r[0]` 取到首字符（`verify-m2` 的半数检查曾因此返回空值）；③ `--defaults-extra-file` 未指定库名导致 `No database selected` 被 `2>$null` 吞掉。三者都已修复并在脚本内注释留证。

---

## 三、N+1 审计：现在是"高效的多表查询"还是"循环里的性能杀手"？

### 3.1 现状（M2 阶段）

- **代码层尚不存在 Java 代码**（M3 才开始写），所以"有没有 N+1"此刻取决于**设计是否把口子堵死**。
- M1 时我对 N+1 的处理偏弱：架构里只有一句"用 IN 批量补齐"，接口文档只有一条"禁止 N+1"。**没有预算、没有验收手段** → 这就是最容易在 M3/M4 写出 N+1 的地方。

### 3.2 已堵死的口子（本轮新增 `ARCHITECTURE §10.5`）

| 风险点 | 典型 N+1 写法 | 本设计的对策 |
|---|---|---|
| 列表要显示分类名/作者名 | 循环里 `doc.getCategory().getName()` | 主表分页 + `findAllById` / `IN` **批量补名**（预算 3 条 SQL） |
| 列表要显示标签 | `Page<Document>` 配集合型 `JOIN FETCH tags` | **明令禁止**（Hibernate 会先查全部 ID 再分页，`HHH000104`）；标签只在详情页取，且一次 JOIN 取完 |
| 列表误带大文本 | `SELECT *` 带出 `content_md` | 列表走 **DTO 构造函数投影**，`DocumentVo` 不含 `contentMd` |
| **树形接口** | 递归查子节点（最隐蔽） | **一次查全表 + 内存建树**，预算 **1 条 SQL** |
| 用户列表要角色码 | 每个用户查一次 `sys_user_role` | 分页后**批量**查 `sys_user_role` + `sys_role`（预算 4 条） |
| 统计卡片 | 多个全表 `COUNT` 叠加 | 一条聚合/`UNION`（预算 3 条，可压到 1~2 条） |
| 权限校验 | 每次请求查三张表 | Redis `perm:user:{userId}` 缓存 + 主动失效（`§6`） |

**判据（可直接验收）**：每个读接口的 SQL 条数是**常数** —— 与 `pageSize`、树节点数、标签数、角色数**无关**。M6 逐接口用 `show-sql` 日志点数，超出预算即判缺陷（`ARCHITECTURE §10.5` 已给出 11 个接口的具体预算：1~4 条）。

### 3.3 数据库侧现状（已在 M2 实测，非估算）

| 查询 | 实测 | 判定 |
|---|---|---|
| 检索主路径（分类+状态+时间倒序） | 0.149 ms，`Index lookup` + 反向扫描，**无 filesort** | 高效 ✅ |
| 审核队列（仅状态+时间倒序） | 0.221 ms，命中专用索引 | 高效 ✅ |
| 我的文档（作者+时间倒序） | 修复前 28.4 ms（读 10 003 行 + 内存排序）→ 修复后 **0.135 ms** | 已修 ✅ |
| 对照组（忽略索引） | 18.2 ms，`Table scan` 20 005 行 + `Sort` | 仅作对照，生产路径不出现 |

---

## 四、M2 收尾与下一步：需要谁准备/检查/开启什么

### 4.1 我已完成（无需你操作）

| 项 | 状态 |
|---|---|
| MySQL80 服务 / Redis 服务 | **Running** ✅（M3 需要 Redis 存 token 与权限缓存，已就绪） |
| 端口 10087（后端）/ 5173（前端） | 空闲 ✅（3306/6379 被服务正常占用） |
| 应用专用数据库账号 | **已建 `campusswap_dev@localhost`**（`mysql_native_password`，仅 `SELECT/INSERT/UPDATE/DELETE ON campusswap_db.*`，无 DDL 权限），已用该账号实测连通并读到 39 权限点 / 5 文档 ✅ |
| 压测夹具 | `backend/sql/perf-fixture.sql`（一键灌 20 000 行 / 一键按标题前缀清理） ✅ |
| JDK 17 / Maven 配置 | JDK 17.0.5 与 21.0.6 并存；Maven 用户级 `settings.xml` 已指向 D 盘仓库 + 阿里云镜像 ✅ |

### 4.2 需要你做的（都不紧急，M3 前后皆可）

| # | 事项 | 怎么做 | 为什么 |
|---|---|---|---|
| U1 | **用 DBeaver 连一次 `campusswap_db`**（可选，但推荐） | 新建 MySQL 连接 → host `localhost` / port `3306` / database `campusswap_db` / user `root` 或 `campusswap_dev` → 首次连会提示下载驱动 → 密码见你本地记录 | 你后面要自己看表、跑 `EXPLAIN`、给老师截图；注意 DBeaver 会按 host/port/db 重建 URL，**连接参数要写在 Driver properties 页** |
| U2 | 若要亲眼看索引效果 | 在 DBeaver 里执行 `EXPLAIN ANALYZE SELECT id,title,updated_at FROM doc_document WHERE deleted=0 AND status='PUBLISHED' ORDER BY updated_at DESC LIMIT 10;` | 应看到 `Index lookup ... using idx_doc_status_updated`；与 `EXPLAIN-NOTES.md` 对照 |
| U3 | IDEA 打开项目（M3 开始后） | `File → Open → D:\DevEnv\projects\campusswap\backend`，SDK 选 17.0.5，Maven 用 wrapper | M3 我会生成 Spring Boot 工程骨架；你也可以等骨架生成后再开，省得导入两次 |
| U4 | 决定 M3 是否要我全自动推进 | 说"继续 M3"即可 | M3 内容较多（工程骨架 + 统一响应/异常 + 四道鉴权 + 系统域接口），我会分批做并每批跑机检 |

### 4.3 风险与遗留

| 项 | 说明 |
|---|---|
| `docs_db` 里练习项目的两张 0 行空表 | 未动，与本项目完全隔离（本项目一律 `campusswap_db`） |
| 本机防火墙 3306/6379 入站放行 | 仍未处理（此前告知过；本机开发不需要入站放行，建议收窄或删除规则） |
| `sys_user_permission` 表为空 | 设计如此：M1 契约未提供用户直授权限的写接口，表结构与合并算法已支持，留待后续需要时再开 |
| T1.4 Apifox 录入 | 按用户指示顺延至 M3 收口（后端可运行后导入并逐条发送） |
