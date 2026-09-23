# CampusSwap 代码导读（CODE-TOUR）—— 项目是怎么搭起来的 + 对着代码怎么读

| 项 | 值 |
|---|---|
| 文件 | `docs/02-design/CODE-TOUR.md` |
| 版本 | v1.0 ｜ 日期 2026-09-23 |
| 状态 | **学习用导读**（解释性文件，不是冻结件；与实现冲突时以实现 + 冻结文档为准） |
| 生成依据 | 本轮仓库实测（提交历史、文件计数、服务状态）+ 冻结文档（PRD / GLOSSARY / ARCHITECTURE / API_SPECIFICATION / UI_UX_SPECIFICATION）+ 三份收口记录（M3-CLOSURE / M4-CLOSURE / AUDIT-M0-M2） |
| 读者 | 项目作者本人 —— 目标不是"知道做了什么"，而是"能不看代码口述清楚每个决策的理由" |
| 老师评语（2026-09-23） | 底层原理一样，Spring Security 那套只是更规范 → 因此 §6.1 专门给了**术语翻译表**，用来把我们的实现讲成老师的词汇 |

## 0 三种读法

| 你有多少时间 | 读哪些 | 目标 |
|---|---|---|
| 30 分钟 | §1 + §2 + §5 + §6 | 建立全局心智模型，能回答"这套系统怎么跑" |
| 半天 | 再加 §3 + §4 | 按里程碑对着提交看代码，知道每一层是谁在什么时候加的 |
| 答辩前 | §6.2 的 10 个问题 | 每题**先自己说一遍**再看答案，说不顺的就是要回头读的 |

---

## 1 四条铁律（这套项目为什么可复盘）

循环：**冻结口径 → 落代码 → 写一个能重跑的机检 → 收口记录（含缺陷与偏差）→ 提交推送**

| # | 铁律 | 为什么 | 违反过的代价 |
|---|---|---|---|
| 1 | 先改文档再改代码 | 口径是唯一真源；文档没定就写实现，等于把设计藏进代码里 | M1 期间改过主键与库名，靠"先改文档"才没有连锁返工 |
| 2 | 每个结论都必须能被一条命令重新证明 | 报告"应该没问题"不算证据 | 机检脚本 `verify-*.ps1` 全部可重跑，`-AppLog` 参数能回放 SQL 条数 |
| 3 | 测量优先于假设 | 索引、SQL 条数、页面大小都靠实测定 | `EXPLAIN` 实测把索引从 `idx_doc_created_by` 改成 `idx_doc_created_by_updated`（28.4 ms → 0.135 ms） |
| 4 | 缺陷与偏差全部写进收口记录 | "没说的失败"下一次还会再犯 | 11 个实测缺陷、8 个检查器自身 bug 全部留档 |

**第 4 条里最容易被忽略的一点**：机检脚本自己也会错。所以我们做过 **6 项变异测试**——故意把代码改错，看机检会不会红；不会红说明那条检查是假的（详见 `AUDIT-M0-M2.md`）。

---

## 2 现状数字（2026-09-23 实测，不是估的）

| 项 | 值 |
|---|---|
| 提交 | **11 次**，`main` == `origin/main` == `edbe00c` |
| 被跟踪文件 | **191**：`backend/*.java` **150**、`docs/*.md` **13**、`docs/03-qa-review/*.ps1` **9**、`*.sql` **3** |
| 机检规模 | 9 个脚本、**420 个断言/检查点**（13+15+24+16+9+36+125+30+152） |
| 接口 | **55 个**（OpenAPI 3.0.3，52 个 schema，40 条 path） |
| 数据库 | `campusswap_db`：**14 张表 / 20 个索引**，零物理外键（靠机检守逻辑外键） |
| 权限 | **39 个权限点**、3 个角色、3 个部门 |
| 鉴权 | **4 道关卡**；4 族 Redis 键（`login:token:` 2h、`user:tokens:` 2h、`perm:user:` 30min、`view:doc:{docId}:{userId}` 30min） |
| 前端 | `frontend/` **只有骨架目录 + `public/.gitkeep`**，业务代码 0 行（M5 未开工） |
| 本地服务 | Redis 6379 正在监听；后端 10087 空闲（未启动）；MySQL80 服务自启 |

---

## 3 五个里程碑 + 两轮复核

### M0 需求冻结 —— `e8d951d`（2026-09-21）
- **做了什么**：`USER_STORIES`（8 个故事 / 24 条 BDD）、`PRD`（24 条业务规则、9 条显式非目标）、`GLOSSARY`（字段唯一真源、39 个权限点、14 张表字段级定义）。
- **关键决策**：先冻"权限点 + 表结构 + 字段名"，再谈接口；非目标写进文档（O3 真实支付 / O4 移动端 / O5 邮件短信通知 / O8 Docker / O9 演示视频）。
- **证据**：`verify-m0.ps1` 13/13。
- **你要能讲**：为什么先冻权限点？因为接口、前端按钮显隐、越权测试三处都引用同一批权限码，改一次要连锁改 5 个地方。

### M1 设计定稿 —— `747e47a` + `104999e`
- **做了什么**：`ARCHITECTURE`（33.7 KB）、`API_SPECIFICATION`（≈120 KB）、`UI_UX_SPECIFICATION`（98 KB）。
- **关键决策（两次）**：① 冻结架构口径：分层、事务边界、缓存策略、**登录方案（§5.2：拦截器 + Redis 不透明 token，明确否决 JWT）**；② 你拍板"全面对齐老师课件"后的一次**冻结决策变更**：主键 `BIGINT AUTO_INCREMENT`、审计列 `created_at/created_by/updated_at/updated_by/deleted`、中间表复合主键无 `id`、库名 `docs_db → campusswap_db`、包结构"按模块分包 + entity 顶层"、端口 10087 / Vite 5173。③ 课件 3.1 落地：JPA 口径"**写 ID、读关联**"。
- **证据**：`verify-m1.ps1` 15/15 + `verify-api-spec.ps1` 24/24。
- **你要能讲**：登录方案为什么这么定——依据老师的需求工程 SOP + "防 IDOR"红线 + Redis 会话口径，加上 JWT 不可撤销（要撤销就得在 Redis 存版本号比对，等于没省 Redis）。**这就是老师问"为什么不用我那套"的完整答案。**

### M2 数据库落地 —— `6397964`
- **做了什么**：`backend/sql/{schema.sql,data.sql,perf-fixture.sql}`；14 张表 + 20 索引 + 种子数据（39 权限点三层树 / 3 角色 / 3 部门 / 4 分类 / 5 标签 / 5 篇覆盖四种状态的文档 / 9 条版本留痕 / 3 条收藏）。
- **关键决策**：`ddl-auto: none`（表由 SQL 脚本建，Hibernate 只 `validate`）；软删除用 `deleted` 列 + 唯一列改写；树形用 `parentId + ancestors` 物化路径。
- **证据**：`verify-m2.ps1` 16/16；`EXPLAIN-NOTES.md`（Q1 0.149 ms / Q2 0.221 ms / Q3 0.135 ms，对照组全表扫描 18.2 ms）。
- **你要能讲**：索引要覆盖"WHERE + ORDER BY 的组合"，不是越多越好；排序键不进索引会退化成 filesort。

### M0~M2 复核（两轮）—— `fb7c76c` + `0087123`
- **做了什么**：跨文档对账（DB 62 列 / 39 权限码 / 111 个字段名）、**6 项变异测试**、N+1 审计与 SQL 条数预算（写进 `ARCHITECTURE §10.5`）；`verify-db-deep.ps1` 9 项（DDL↔实库逐列 diff=0、14 组逻辑外键孤儿 0 行、树形 `ancestors` 链一致、角色权限集合 = PRD §3.3、审计列行为实测）。
- **你要能讲**："测试通过"≠"测试有效"，变异测试是证明后者的手段。

### M3 后端骨架 + RBAC —— `1c19b6b`
- **做了什么**：Spring Boot 4.1.1 工程；`common` 16 类（api / exception / security / util）、`config` 4 类、`entity` 21 + 枚举 5、14 个仓储、7 个服务、**25 个端点**；**四道鉴权关卡**跑通。
- **关键决策**：① 权限合并压成**一条 UNION SQL**（用户直授权 ∪ 角色权限 ∪ 部门继承角色）；② 权限缓存 30 分钟 + **事务提交后失效**（`TxUtil.afterCommit`）；③ 不透明 token 存 `login:token:{token}`，TTL 2 小时；④ 改密/停用走 `user:tokens:{userId}` 集合，支持"踢全部会话"。
- **证据**：`verify-m3.ps1` 36/36、`verify-m3-http.ps1` 125/125、`--spring.jpa.hibernate.ddl-auto=validate` 启动通过。
- **你要能讲**：四道关卡分别防什么（§5、§6.2 Q5）。

### M4 文档业务 —— `d66b07d` + `edbe00c`
- **做了什么**：30 个文档域接口（Document 15 / Review 5 / Category 4 / Tag 4 / File 1 / Stat 1）；版本快照 9 种类型；状态机 T2/T3/T5~T11 全分支；图片三重白名单上传；统计三计数单 SQL。
- **关键技术点**：① 列表用 **Criteria + DTO 构造器投影**（SQL 里根本不出现 `content_md`）；② 回收站 / 收藏走**原生 SQL**（`@SQLRestriction` 会自动加 `deleted = 0`，而回收站要的正是 `deleted = 1`）；③ 列序与类型容错集中在 `DocumentColumns`（MySQL `INT UNSIGNED` → Long、`DATETIME` → Timestamp）；④ `PageableExecutionUtils` 在末页省掉 count。
- **证据**：`verify-m4-http.ps1` 152/152（含 10 项 SQL 预算断言）、`verify-m4.ps1` 30/30、`TEST_CHECKLIST.md` 21/21。
- **实测 SQL 条数（常数）**：检索 3（末页）/ 4（满页）/ 5（带分类筛选）、我的 3、回收站 1、收藏 3、审核队列 3、分类树 1、标签 1、统计 1。

### 老师框架对账（2026-09-23，未改代码）
- **做了什么**：逐行读老师示例工程 `backend(2)` 的 security 包，产出 `docs/03-qa-review/DIFF-VS-TEACHER.md`（20 行对账表 + 三条路线 + 工作量）。
- **发现的坑（重要）**：老师那份 `SecurityConfig` 引用的 `SmsCodeAuthenticationProvider`、`WeChatAuthenticationProvider` **两个类在工程里不存在**，pom 里也没有 security / jose4j 依赖，`UserController.login()` 是 `return "";` 的桩 → 它是"形态参考"，不是可运行基线。
- **结论（老师 2026-09-23 答复）**：原理一致，那套只是更规范 → **架构不改**，重心转向"把已有代码读懂讲清"。

---

## 4 代码地图（150 个 Java 文件）

| 包 | 类数 | 职责 | 想读懂它先看 |
|---|---|---|---|
| `common/api` | 5 | 统一响应体与错误码、分页出入参、审计 VO | `ResponseResult` → `ErrorCode` |
| `common/exception` | 2 | 业务异常 + 全局异常处理器（400/401/403/404/409/500 全中文） | `GlobalExceptionHandler` |
| `common/security` | 6 | **四道关卡的主体** | `LoginInterceptor` → `SecurityContext` → `RequiresPermission` → `PermissionAspect` → `RedisKeys` |
| `common/util` | 3 | 事务后置动作、时间/字符串工具 | `TxUtil`（`afterCommit`） |
| `config` | 4 | CORS / JPA 审计 / Redis 序列化 / MVC 拦截器与静态资源 | `WebMvcConfig` |
| `entity`（+`enums`） | 21 + 5 | BaseEntity + 14 实体 + 6 复合主键类 + 5 枚举 | `BaseEntity` → `Document`（看 `@SQLDelete` / `@SQLRestriction`） |
| `system/{controller,service,repository,dto,vo}` | 5 / 14 / 10 / 14 / 8 | 用户 / 角色 / 权限 / 部门 + 登录登出 | `UserServiceImpl` 的权限合并查询 |
| `document/{controller,service,repository,dto,vo}` | 6 / 9 / 12 / 17 / 8 | 文档域全部业务 | `DocumentQueryRepositoryImpl`（Criteria + 投影）→ `DocumentServiceImpl`（状态机与归属校验） |

**读代码的顺序建议**：`WebMvcConfig` → `LoginInterceptor` → `SecurityContext` → `PermissionAspect` → `AuthController`（登录怎么发 token）→ `DocumentQueryRepositoryImpl`（读）→ `DocumentServiceImpl`（写）→ `DocumentColumns` + 原生 SQL（回收站/收藏）→ `GlobalExceptionHandler`。

---

## 5 一次请求的完整旅程

以 `GET /api/documents?keyword=xx&pageNum=1` 为例：

1. **CORS**（`config/CorsConfig`）放行 Vite 5173。
2. **关卡①`LoginInterceptor`**：取 `Authorization` 头 → 查 Redis `login:token:{token}` → 得到 userId。查不到 → 401；**Redis 本身异常 → 500**（不能把抖动当"token 失效"，这是 M4 实测缺陷⑥）。
3. **关卡②`SecurityContext`**：userId 写入 ThreadLocal，业务层随处可读。
4. **关卡③`PermissionAspect`**：读 `@RequiresPermission("doc:search")` → 查 `perm:user:{id}` 缓存（30 分钟）→ 未命中用那条 UNION SQL 现算 → 缺权限 403。
5. **Controller**：`@Valid` 校验 + 分页 DTO。
6. **关卡④ Service 归属校验**：针对"某一条具体数据"的操作（编辑/删除/审核/恢复）再查归属，**防水平越权（IDOR）**。
7. **数据层**：Criteria 组条件 → DTO 构造器投影 → 末页跳过 count → SQL 条数落在 `ARCHITECTURE §10.5` 预算内。
8. **出口**：`ResponseResult{code,message,data}`；异常统一由 `GlobalExceptionHandler` 翻译成中文 + 正确 HTTP 码。
9. **写操作收尾**：缓存失效放 `TxUtil.afterCommit` —— 只有事务真的提交了才删缓存。

**记住这条链路，M3/M4 的代码就读懂了 80%。**

---

## 6 对照老师那套 + 必答问题

### 6.1 术语翻译表（老师问起就这么答）

| 老师那套（Spring Security + JWT） | 我们的对应物 | 文件 |
|---|---|---|
| `SecurityFilterChain` | `WebMvcConfig.addInterceptors` | `config/WebMvcConfig.java` |
| `WHITE_LIST` + `requestMatchers().permitAll()` | `excludePathPatterns("/api/auth/login","/api/auth/logout")` | 同上（我们只放行 2 条，更保守） |
| `anyRequest().authenticated()` | `addPathPatterns("/api/**")` 全拦 | 同上 |
| `JwtFilter` | `LoginInterceptor`（每请求查一次 Redis） | `common/security/LoginInterceptor.java`（104 行） |
| `SecurityContextHolder` | `SecurityContext`（ThreadLocal） | 同目录 |
| `UserDetailsService` + `authorities` | 登录查用户 + **登录后按需加载 39 个权限点** | `PermissionAspect` + 权限仓储 |
| `@PreAuthorize("hasAuthority('x')")` | `@RequiresPermission("x")`（53 处 / 11 个文件） | 各 Controller |
| `AuthenticationEntryPoint` / `AccessDeniedHandler` | `GlobalExceptionHandler` → `ErrorCode.UNAUTHORIZED(401)` / `NO_PERMISSION(403)` | `common/api/ErrorCode.java` |
| `ProviderManager`（Dao / Sms / WeChat） | 目前只有密码登录一个入口 —— **这是我们唯一真的少的能力**（短信/微信没做） |
| JWT 不可撤销 → Redis 比对 `token_version` | 直接删 Redis 键即失效（少一层签名与版本比对） | `common/security/RedisKeys.java` |

一句话版本：**认证层我们用的是拦截器 + 一条 Redis 查询，老师用的是过滤器链 + Provider；授权层我们比他的示例更细（他示例的 `authorities` 是空集，我们有 39 个权限点、角色/部门继承、30 分钟缓存、改完即时生效）。原理一致，规范性上 Spring Security 是行业标准件。**

### 6.2 十个必答问题

| # | 问题 | 一句话答案 | 看哪里 | 怎么自己验 |
|---|---|---|---|---|
| 1 | 为什么不用 JWT？ | 不可撤销；要撤销就得 Redis 存版本号比对，等于没省 Redis | `ARCHITECTURE §5.2` | 读该节 + `DIFF-VS-TEACHER.md §7` |
| 2 | 服务端怎么认人？ | Redis `login:token:{token}` → userId，TTL 2h | `LoginInterceptor` + `RedisKeys` | 登录后 `redis-cli KEYS login:*` |
| 3 | 权限怎么算出来？ | 直授权 ∪ 角色权限 ∪ 部门继承角色，1 条 UNION SQL，缓存 30 分钟 | `PermissionAspect` + 权限仓储 | 开 SQL 日志数条数 |
| 4 | 改权限为什么立刻生效？ | 授权接口写入后在事务提交后删缓存 | `TxUtil.afterCommit` | 收回权限后用旧 token 请求 → 立刻 403 |
| 5 | 四道关卡各防什么？ | ①你是谁 ②传身份 ③你能不能 ④这条数据是不是你的 | `common/security/` | 用 staff 的 token 改 admin 的文档 → 403 |
| 6 | 列表为什么快？ | DTO 构造器投影（SQL 不含 `content_md`）+ 末页省 count | `DocumentQueryRepositoryImpl` | SQL 日志看 select 字段 |
| 7 | 为什么"写 ID、读关联"？ | 写模型用 ID 避免级联，读模型才取关联名 | `ARCHITECTURE §10.4` | 读决策树 |
| 8 | 软删除为什么改写唯一列？ | 唯一索引不认"已删除"，否则同名编码重建 500 | `Document` / `Role` 等实体的 `@SQLDelete` | 删一个标签再建同名标签 |
| 9 | 树形 `ancestors` 为什么不能裸 `LIKE '0,1%'`？ | 本库 id 恰好 1/10/100，段位复用会误判 | 分类/部门/权限仓储 | 把裸 LIKE 改回去跑机检会红 |
| 10 | 怎么证明没有 N+1？ | 每个读接口写死 SQL 条数预算，机检数条数；`pageSize` 1→100 条数不变 | `ARCHITECTURE §10.5` + m4-http 的 10 项预算断言 | `verify-m4-http.ps1 -AppLog "$env:TEMP\campusswap-app.log"` |

---

## 7 踩过的坑（"AI 写的代码最需要你警惕"的部分）

**11 个实测缺陷**（M3 五个 + M4 六个），最值得记的 5 个：

1. 软删除行仍占唯一索引 → 重建同名编码 500（`@SQLDelete` 把 `code` 改写成 `code#del#id`）。
2. 树形子孙用裸 `LIKE '0,1%'` → 段位复用误判（改整段匹配 `= :path OR LIKE CONCAT(:path,',%')`）。
3. 登出被拦截器挡成 401，但规范要求**重复登出幂等 200** → 放行进 Controller + 手写 token 解析。
4. 参数校验把 Spring 英文原文与内部类名吐给前端 → 翻译成中文 + 列出可选枚举值。
5. 一次 Redis 抖动被当作"token 失效" → 瞬时 401；改为 Redis 异常 500、token 真无效才 401，并打 WARN（含 token 前 8 位）。

M4 另外四个：统计原生查询声明 `Object[]` 被 Spring Data 再包一层导致 500；`moveToTrash` 没递增 `version_num` 导致恢复时快照撞 `uk_doc_version`（409）；重复删除/恢复/彻底删除对回收站状态返回 404 而非约定 409；发布/派生/编辑对回收站文档语义错误。

**8 个检查器自身的 bug**：中文过滤条件被 GBK 静默吃掉导致检查"永远通过"、片段接口被误当 JPA 仓储计数、JOIN FETCH 规则过粗、端点正则漏掉裸 `@GetMapping`、集合误按字符串比对、中文进正则崩解析……**结论：机检也要被检验（变异测试）。**

**3 个 PowerShell 工具坑**：无 BOM 的 UTF-8 脚本被按 ANSI 读（中文崩解析，所以机检脚本一律纯 ASCII）；`Add-Member` 加到 Hashtable 上 `ConvertTo-Json` 不序列化（产出 `{}`，要写 `[pscustomobject]@{}`）；相对路径的 `[IO.File]` 按进程 cwd 解析而不是 `cd`。

---

## 8 怎么自己跑起来（命令全部实测过路径）

```powershell
# ① 起后端（开发配置：端口 10087，库 campusswap_db，Redis 6379）
$env:JAVA_HOME = 'D:\DevEnv\02_JDK\jdk-17.0.5'
Set-Location D:\DevEnv\projects\campusswap\backend
.\mvnw spring-boot:run *> "$env:TEMP\campusswap-app.log"
#   日志写文件的两个理由：verify-m4-http 的 -AppLog 要读它；避免 mvnw clean 删不掉被占用的 target 文件

# ② 静态 / 文档机检（不需要服务）
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m0.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m1.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-api-spec.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m3.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m4.ps1

# ③ 数据库机检（需要 DB_PASSWORD）
$env:DB_PASSWORD = '123456'
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m2.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-db-deep.ps1

# ④ 接口机检（需要 ① 在跑；退出码 = 失败数，0 = 全绿）
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m3-http.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\DevEnv\projects\campusswap\docs\03-qa-review\verify-m4-http.ps1 -AppLog "$env:TEMP\campusswap-app.log"

# ⑤ 直接看库
D:\DevEnv\03_MySQL\bin\mysql.exe -uroot -p123456 campusswap_db

# ⑥ 直接看 Redis（登录后能看到 login:token:* / perm:user:*）
D:\DevEnv\04_Redis\redis-cli.exe -p 6379 KEYS 'login:*'
D:\DevEnv\04_Redis\redis-cli.exe -p 6379 KEYS 'perm:*'
```

> 演示账号：`admin/Admin@123`（SYS_ADMIN）、`docadmin/Doc@123456`（DOC_ADMIN）、`staff/Staff@123`（STAFF）。

---

## 9 欠账与 M5 开工前的两个待拍板点

**欠账**：M5 前端未开工（`frontend/` 只有骨架目录）；M6 测试与评审、M7 交付未做；Apifox 手动导入 + 发请求待你操作；防火墙 3306/6379 的入站放行规则未按建议收窄；`DIFF-VS-TEACHER.md` 仍为未跟踪文件（未提交）。

**待拍板（M5 前）**：

1. **分类 / 标签的 6 个写接口要不要给界面入口？** 后端已实现 `POST/PUT/DELETE /api/categories/**` 与 `/api/tags/**`，权限点 `doc:category:edit` / `doc:tag:edit` 也在权限树里；但 `UI_UX_SPECIFICATION §5.3` 把它们判为"路径清单缺位 → 不渲染入口"（§8.7 的"分类与标签"tab 因此是只读的）。两条路：① 补入口（需修订 §5.3 与 §8.7，加 4 个按钮）；② 保持只读（在 M5 收口记录里写明理由）。
2. **`sys_user_permission`（用户级直授权）要不要给界面入口？** 中间表与权限合并逻辑都在，M3 已实测"直授权立即生效"，但接口清单里没有写接口 → 界面上没有入口。当前默认：不渲染，只在文档里说明。
