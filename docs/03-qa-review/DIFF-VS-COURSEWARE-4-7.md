# DIFF-VS-COURSEWARE-4-7 — 老师 4.1 / 5.1 / 6.1 / 7.1 / 7.2 / 7.3 课件 vs 我们的实现

> 生成时间：2026-09-23 ｜ 对账对象（6 份课件，桌面 `王伟\课件\`）：
> `4.1 高并发与一致性进阶：乐观锁与 SQL 原子操作.md`、
> `5.1 企业身份认证：Spring Security + JWT + Redis 实战.md`、
> `6.1 企业细粒度权限体系：RBAC 授权模型落地.md`、
> `7.1 Redis 核心架构与底层数据结构全景解密.md`、
> `7.2 业务性能提升：Redis 缓存实战与 Cache-Aside 模式.md`、
> `7.3 工业级双级缓存体系：JetCache (Caffeine + Redis) 大厂实战.md`
> **范围声明：本文件只对账"除 JWT 架构以外"的部分。** JWT / Spring Security 过滤链已在
> `DIFF-VS-TEACHER.md` §9 结案（老师答复"底层原理一样，那套只是更规范"，路线 A/B/C 均不执行），本文件不重复讨论。
> **口径：§1 所有数字都是本轮实测**（命令写在 §8，可复跑）；没有实测支撑的判断一律标"待验"。
>
> ---
> **执行结果（2026-09-23 晚，用户拍板后已落地）**：**A1 / A2 / A3 / B1-① 全部完成**，**D6 不做**，
> **D5 按用户指示降级为"只写文档、不动代码"**。A1 过程中被机检抓到一个真实缺陷（只加 `clearAutomatically`
> 会丢掉未 flush 的挂起写入 → "标签计数 +1、关联表 0 行"），已修复并固化为规则（`ARCHITECTURE §10.6`）与回归测试
> （`TagBindingConsistencyTest`）。复跑证据、偏差清单、回滚方式见 **`COURSEWARE-CLOSURE.md`**。
> **本节以下内容保留为"决策前的对账底稿"**（原始判断不再改动，便于对照"当初怎么想的"）。
> 最终口径：产品机检 **507**（m4-http 218 → 221）+ JUnit **6 用例** + 预览稿自检 **211**，全部 0 失败。

---

## 0 一句话结论

把 6 份课件的 **DoD（验收标准清单）逐条拿来对我们的项目打勾**，结果是：

| 类别 | 数量 | 结论 |
|---|---|---|
| 我们已经达到或**强于**课件 | 15 条 | 不用动（RBAC 模型、Cache-Aside 写路径、混合持久化、序列化规范、401/403 JSON 出口、不信任前端传操作人 ID） |
| **真实差距，建议补** | **3 条** | 4.1 的 `clearAutomatically`、5.1 的 `PasswordEncoder` 抽象、6 份课件共同的"自动化测试绿灯" |
| 真实差距，**要你拍板**（动冻结决策 ADR-07） | 1 条 | 4.1 的"陈旧表单 / 乐观锁 → 409"防线 |
| 课件有、我们**故意不做**（并已写进 ADR/收口记录） | 7 条 | JetCache 双级缓存、权限码全量三段式、`ROLE_` 前缀、`@ManyToMany`、`maxmemory-policy=allkeys-lru`、`@PreAuthorize` 迁移、`doc:detail` 整对象缓存 |

**建议工作量合计 ≈ 1 人天（3 条"建议补"）；若再加 4.1 的 409 防线，另加 0.5 人天。**

一句话：**我们唯一的"结构性空缺"不是功能，而是"没有任何自动化测试类"（`src/test` 是空包，0 个 Java 测试文件）**
——6 份课件里有 4 份的 DoD 点名"单元与集成测试绿灯"，而我们的 504 项验收全靠外部 PowerShell 脚本；
另外两条是小口子：19 处 `@Modifying` 少了一个参数、密码哈希少了一层官方抽象。

---

## 1 事实底稿（对账前提）

### 1.1 我们这边（本轮实测）

| 项 | 实测值 | 来源 |
|---|---|---|
| `@Modifying` 全量位置 | **19 处 / 8 个仓储**（`DocumentRepository` 5、`TagRepository` 3、`DocumentTagRelRepository` 2、`FavoriteRepository` 2、`DocumentVersionRepository` 1、`DeptRoleRepository` 2、`RolePermissionRepository` 2、`UserRoleRepository` 2） | §8 命令 ① |
| `clearAutomatically` / `flushAutomatically` | **0 处**（全工程 grep 无命中） | §8 命令 ① |
| 自动化测试类 | **0 个**（`backend/src/test/java/com/campusswap/` 目录存在但为空） | §8 命令 ② |
| 权限码 | **39 个，两段式与三段式混用**：页面/入口级 `doc:center` `doc:mine` `doc:search` `doc:manage` `sys:user` `sys:role`…；按钮级 `doc:category:edit` `sys:user:disable` `sys:role:grant`… | §8 命令 ③ |
| 权限校验注解 | `@RequiresPermission` **53 处**（等价于课件的 `@PreAuthorize`） | 既有对账 |
| 密码哈希 | **`$2b$10$`**，长度 60，由 `cn.hutool.crypto.digest.BCrypt` 生成（3 个种子账号全部如此） | §8 命令 ④ |
| Spring BCrypt 兼容性 | **实测 `matches = true`**：Spring Security 7.1.1 的 `BCryptPasswordEncoder` 能直接校验我们库里既有的 `$2b$10$` 哈希；`upgradeEncoding = false` | §8 命令 ⑤ |
| 依赖可用性 | `spring-security-crypto 7.1.1` **已在本地仓库**，且 Boot 4.1.1 BOM 管理的 `spring-security.version` **正是 7.1.1** → 加依赖**不需要联网**；`caffeine`、`jetcache` **不在本地仓库** → 要用必须联网下载 | §8 命令 ⑥ |
| Spring 7 的日志 API | `spring-core 7.0.9` 的 pom 直接依赖 **`commons-logging`**（不再有 `spring-jcl`，本地仓库确认无该 artifact） | §8 命令 ⑤/⑥ |
| 业务数据缓存 | **无**（无 `spring-boot-starter-cache`、无 `@Cacheable`、无 Caffeine、无 `RedisTemplate<String,Object>`）；Redis 只用于 **token / 权限集合 / 阅读量去重**，全部走 `StringRedisTemplate` | 读源码 + pom |
| 权限缓存是不是 Cache-Aside | **是**：读 → Redis 未命中则查库并回填 TTL；写 → 授权/部门变更后删缓存，且**删除动作在事务提交之后**（`TxUtil.afterCommit`） | `PermissionCacheServiceImpl` + `PermissionServiceImpl` |
| Redis 实机配置 | `redis_version 5.0.14.1`(Windows) ｜ `maxmemory 0` ｜ `maxmemory-policy noeviction` ｜ `appendonly yes` ｜ `appendfsync everysec` ｜ `save` **为空**（定时 RDB 快照关闭）｜ `aof-use-rdb-preamble yes` ｜ `aof_last_bgrewrite_status:ok` ｜ 当前 `dbsize 0` | §8 命令 ⑦ |
| Redis 键设计 | 4 类键，**全部带 TTL**：`login:token:*` 2h、`user:tokens:*` 2h、`perm:user:*` 30min、`view:doc:*:*` 30min；键名前缀集中在 `RedisKeys`，无裸键 | 读源码 |
| 机检规模 | 产品 504 项（m0 13 / m1 15 / api-spec 24 / m2 17 / db-deep 9 / m3 36 / m3-http 142 / m4 30 / m4-http 218）+ 预览 209 项 | `TEST_CHECKLIST.md` |

### 1.2 课件那边（只摘与"要不要改"有关的原文要求）

| 课件 | 与 JWT 无关的硬要求（原文要点） |
|---|---|
| **4.1** | DoD ①实体加 `@Version`，JPA 自动生成 `WHERE id=? AND version=?`；②两个并发窗口带相同版本号改同一文档 → 第二个被拦截并返回 **HTTP 409 结构化 JSON**；③高频自增用**单条原子 SQL** 且注解必须带 **`clearAutomatically = true`**"杜绝一级缓存脏读"；④**100 线程并发递增浏览量，最终结果精准等于 100**；⑤单元与集成测试绿灯。另：DTO 必须携带 `version`，Service 前置判断 + 持久层 `@Version` 兜底 = "双层防御" |
| **5.1** | DoD：数据库严禁明文存密码，**必须使用 `BCryptPasswordEncoder`**（`PasswordEncoder` 接口 + `SecurityConfig` 里 `@Bean`）；未认证统一由 `AuthenticationEntryPoint` 输出 **401 结构化 JSON**；Controller 用 `@AuthenticationPrincipal` 取登录人，**"彻底告别前端私传操作人 ID 的作弊漏洞"**。其余（过滤器链、`STATELESS`、JWT 双令牌、`jti`）属 JWT/框架形态，不在本次范围 |
| **6.1** | DoD：①RBAC0 **5 张表** + `@ManyToMany` 建模；②登录后角色与权限码正确加载并写入上下文；③控制层用 `@PreAuthorize("hasAuthority(...)")` 建防护网；④权限不足统一 **403 + `AccessDeniedHandler` 结构化 JSON**；⑤单测绿灯。规范：权限码统一 **`<模块>:<资源>:<动作>` 三段式**（`document:doc:create`）、角色码一律 **`ROLE_` 前缀**、权限缓存键 `auth:perms:{userId}` |
| **7.1** | DoD 全为**理解类**（能推导单线程 10 万 QPS、能区分 RDB/AOF、能区分 LRU/LFU、能用 `OBJECT ENCODING` 核验编码）。规范：`maxmemory` + 生产推荐 `allkeys-lru`/`allkeys-lfu`、Key 命名规范、BigKey/HotKey 识别、Redis 4.0+ 混合持久化 `aof-use-rdb-preamble yes` |
| **7.2** | DoD：①Redis 键名**无 `\xac\xed` 乱码**（Key/HashKey 用 `StringRedisSerializer`，Value 用 Jackson + `JavaTimeModule`）；②文档详情具备 **Cache-Aside（`doc:detail:{id}`，TTL 30 分钟）**，"命中缓存时控制台无任何 SQL 打印"；③更新/删除后缓存 Key **被立即物理清除**；④ZSet（`doc:rank:views` + `ZINCRBY`/`ZREVRANGE`）**热门文档 Top 10 接口，响应 < 5ms**；⑤单测绿灯。写路径原文："**先更新数据库 → 后显式删除缓存**" |
| **7.3** | DoD 全为**理解类**（L1 纳秒 vs L2 微秒、`localExpire` 必须显著短于 `expire`、Pub/Sub 广播淘汰闭环、会用 `@Cached`/`@CacheUpdate`/`@CacheInvalidate`）。前提场景是**多节点集群**；并列举了 Spring Cache 的 4 大缺陷（TTL 不能按注解配、多节点本地缓存脏读、…） |

---

## 2 逐条对账（DoD 打勾表）

### 2.1 课件 4.1 —— 乐观锁与 SQL 原子操作

| # | 课件要求 | 我们的现状 | 判定 |
|---|---|---|---|
| 1 | 实体加 `@Version`，更新自动带 `WHERE id=? AND version=?` | **无 `@Version`**；`ARCHITECTURE §17 ADR-07` 明文"不做乐观锁"；`version_num` 是**业务版本号**（每次正文写入/状态流转 +1，写 `doc_version` 留痕，`uk_doc_version` 依赖它） | ❌ 不满足（**是决策，不是遗漏**） |
| 2 | 并发改同一文档 → 第二个 409 结构化 JSON | 现状"后写覆盖"；`GlobalExceptionHandler` **没有** `ObjectOptimisticLockingFailureException` 分支（有 `DataIntegrityViolationException` → 409） | ❌ 不满足 |
| 3 | 高频自增用单条原子 SQL + `clearAutomatically = true` | 原子 SQL ✅（`view_count`/`favorite_count`/`tag.use_count` 全部是 `set x = x + n`）；`clearAutomatically` ❌ **19 处全缺** | ⚠️ **半满足** |
| 4 | 100 线程并发递增 = 恰好 100（无计数丢失） | **没做过**（外部 `.ps1` 只能串行压 HTTP；`src/test` 是空包） | ❌ 未验证 |
| 5 | 单元与集成测试绿灯 | **0 个测试类** | ❌ 不满足 |

**补充判断（诚实版）**：第 3 条现在**不是 bug**——`detail()` 方法上没有 `@Transactional`，`findDetailById` 的只读事务结束后实体**已游离**，
所以 `buildDetail` 里那句 `doc.setViewCount(doc.getViewCount() + 1)` 不会被回写；`favorite()` 虽然事务内持有托管实体，但只读不写、不留脏状态。
**但这属于"靠巧合安全"**：哪天有人给 `detail()` 加一个 `@Transactional`（很自然的改动），`clearAutomatically` 的缺失就会立刻变成"并发下阅读量丢失"。
课件把这个参数称为"杜绝一级缓存脏读"，正是这个道理。

同时，第 1/2 条如果**直接复用 `version_num` 去挂 `@Version`**，会踩一个新坑：`version_num` 每次状态流转（提交/审核/归档/回收）都要 +1，
挂上 `@Version` 后这些操作在高并发下会开始抛"乐观锁冲突"，语义完全错位。**要上 `@Version` 必须新增独立列（如 `lock_version`）**——这就是难度分档的关键。

### 2.2 课件 5.1 —— 认证（剔除 JWT 后只剩 3 条）

| # | 课件要求 | 我们的现状 | 判定 |
|---|---|---|---|
| 1 | 必须用 `BCryptPasswordEncoder`（`PasswordEncoder` 接口 + `@Bean`） | 用 `cn.hutool.crypto.digest.BCrypt` 静态方法；**算法同为 BCrypt、cost 均为 10**，但**没有接口抽象**、没有 `{bcrypt}` 前缀的可升级性（`DelegatingPasswordEncoder`） | ⚠️ **半满足**（算法对、抽象层缺） |
| 2 | 未认证统一 401 结构化 JSON（`AuthenticationEntryPoint`） | `GlobalExceptionHandler` + `ErrorCode.UNAUTHORIZED`（401 `{code,message,data}`），前端已按此契约开发 | ✅ 满足 |
| 3 | 用 `@AuthenticationPrincipal` 取登录人，禁止前端传操作人 ID | `SecurityContext.requireUserId()`（ThreadLocal），**全工程没有任何接口从前端接收操作人 ID**；IDOR 由 Service 归属校验兜底 | ✅ 满足（等价且更严） |
| 4 | 令牌 TTL 与 Redis 对齐、支持多端与一键下线 | `login:token:*` 2h；`user:tokens:{userId}` 集合支持"踢全部会话"（改密/停用即时生效，已有 m3-http/m4-http 实测） | ✅ 满足（比课件更完整） |
| 5 | `STATELESS` + CSRF 关 | 无 Session、无 Cookie，本来如此 | ✅ 名义差异 |

### 2.3 课件 6.1 —— RBAC

| # | 课件要求 | 我们的现状 | 判定 |
|---|---|---|---|
| 1 | RBAC0 **5 张表** + `@ManyToMany` 建模 | **6 张表且更强**：多 `sys_dept_role`（部门继承角色）+ `sys_user_permission`（用户直授权）；但按 `2.1 专题指南`"禁 `@ManyToMany`"，改用手工中间表实体 | ✅ 强于课件（建模手法不同，见 §4.4） |
| 2 | 登录后角色/权限码写入上下文与缓存 | `perm:user:{userId}` Set，TTL 30 分钟，1 条 UNION SQL（直授权 ∪ 角色权限 ∪ 部门继承角色） | ✅ 满足 |
| 3 | `@PreAuthorize("hasAuthority(...)")` 方法级防护网 | `@RequiresPermission` + `PermissionAspect`，**53 处**；写法不同、能力等价（DIFF-VS-TEACHER 的 D3 已定"不迁移"） | ✅ 等价 |
| 4 | 权限不足统一 403 + 结构化 JSON | `ErrorCode.NO_PERMISSION` / `USER_DISABLED` → 403 | ✅ 满足 |
| 5 | 单测绿灯 | 0 个测试类 | ❌ 不满足（同 4.1） |
| 6 | 权限码统一三段式 `<模块>:<资源>:<动作>` | **两段 + 三段混用**（39 个码）；这是我们自己的规范：两段 = 页面/入口，三段 = 按钮级 | ⚠️ 规范不同（不建议改，见 §4.2） |
| 7 | 角色码 `ROLE_` 前缀 | `SYS_ADMIN` / `DOC_ADMIN` / `STAFF`，无前缀 | ⚠️ 规范不同（不建议改，见 §4.3） |
| 8 | 缓存键 `auth:perms:{userId}` | `perm:user:{userId}` | ✅ 命名差异，语义一致 |

### 2.4 课件 7.1 —— Redis 底层（DoD 全是理解类）

| # | 课件要求 | 我们的现状 | 判定 |
|---|---|---|---|
| 1 | 能用 `OBJECT ENCODING` 核验编码形态 | 没做过（没留证据） | ⚠️ 建议补一次演示记录（成本 10 分钟） |
| 2 | 生产推荐 `allkeys-lru` / `allkeys-lfu` | 实机 `maxmemory 0` + `noeviction` | ✅ **故意不照做**（见 §4.5：我们的键是会话与授权数据，被淘汰 = 用户莫名掉线/权限错判；全部键带 TTL + `noeviction` 才是对的） |
| 3 | 混合持久化 `aof-use-rdb-preamble yes` | 实机 **`yes`**，`appendfsync everysec`，`appendonly yes` | ✅ 已是最佳实践 |
| 4 | Key 命名规范、BigKey/HotKey 识别 | `RedisKeys` 集中前缀，4 类键全部带 TTL；`perm:user:{id}` 仅 39 个字符串成员（KB 级），无 BigKey | ✅ 满足 |
| 5 | 理解 RDB/AOF 与淘汰策略 | `ARCHITECTURE §7` 有键设计与失效时机，但**没有一节讲 Redis 自身的持久化与内存策略** | ⚠️ 文档可补一小节（无代码改动） |

### 2.5 课件 7.2 —— Cache-Aside 与热榜

| # | 课件要求 | 我们的现状 | 判定 |
|---|---|---|---|
| 1 | 键与值无 `\xac\xed` JDK 序列化乱码 | 只用 `StringRedisTemplate`（键是明文前缀 + 值就是字符串/集合成员），**根本不存在 `RedisTemplate<String,Object>`**，从源头规避了这个坑 | ✅ 强于课件（课件要写 `RedisConfig` 才修好） |
| 2 | 文档详情 Cache-Aside（`doc:detail:{id}` TTL 30min） | **无业务缓存**；但**权限缓存本身就是标准 Cache-Aside**（读未命中 → 查库 → 回填 TTL；写 → 删缓存） | ⚠️ 有 Cache-Aside，但不在文档详情上（见 §3.4：**不建议缓存整对象**） |
| 3 | 写路径"先更新 DB → 后删缓存" | 我们更进一步：**事务提交后**才删缓存（`TxUtil.afterCommit`），避免"缓存已删、事务回滚"的脏读窗口 | ✅ 强于课件 |
| 4 | ZSet 热榜 `doc:rank:views`，Top 10 < 5ms | 无；现有 `sort=viewCount_desc` 走 DB（`idx_doc_cat_status_updated` 覆盖，`EXPLAIN ANALYZE` 实测 0.149ms） | ❌ 不满足（且与现有排序功能**重叠**，见 §3.5） |
| 5 | 单测绿灯 | 0 个测试类 | ❌ 不满足 |

### 2.6 课件 7.3 —— 双级缓存（JetCache）

| # | 课件要求 | 我们的现状 | 判定 |
|---|---|---|---|
| 1 | L1 Caffeine + L2 Redis，`localExpire` ≪ `expire` | 单级 Redis | ⚠️ 不做（见 §4.1） |
| 2 | 多节点 Pub/Sub 广播淘汰 L1 | 单实例部署，无此问题 | ⚠️ 场景不适用 |
| 3 | 会用 `@Cached`/`@CacheInvalidate` | 无注解式缓存 | ⚠️ 不做 |
| 4 | 理解 Spring Cache 的 4 大缺陷 | 我们没有用它，绕开了缺陷本身 | ✅ 名义满足 |

---

## 3 建议补的（按性价比排序，含真实难度）

> 难度口径与 `DIFF-VS-TEACHER.md` 一致：**改动面**（文件/行）｜**人天**｜**我这边回合数**｜**验收方式**。

### A1. 给 19 处 `@Modifying` 补 `clearAutomatically = true`（课件 4.1 DoD③）

- **改动面**：8 个仓储文件 / 19 处注解；`ARCHITECTURE §10.x` 加一段口径说明；`M5PREP-CLOSURE` 或新开收口记录各记一笔。
- **难度：极小（≈30 分钟，1 个回合）**。纯注解参数，不动 SQL、不动契约。
- **验收**：`mvnw test-compile` + `ddl-auto=validate` 启动 + 重跑 `verify-m3.ps1`(36) / `verify-m4.ps1`(30) / `verify-m4-http.ps1`(218) 全绿；
  另外**顺手做一次"脏读复现"实验**：写一个临时用例，在同一事务里 `findById` → 调用原子自增 → 再 `findById`，对比"加参数前读到旧值 / 加参数后读到新值"，把输出贴进收口记录（这才是"先失败后修"的证据）。
- **风险**：`clearAutomatically` 会清空持久化上下文，若某处在同一事务里先改实体 A 再跑 `@Modifying`，实体 A 的后续脏检查会失效 → **必须逐个复核 19 处的调用序列**（我预计有 2~3 处需要改成 `flushAutomatically = true` 或在批量更新前 `saveAndFlush`）。这是本项唯一的工作量来源。

### A2. 密码哈希换用 Spring 的 `PasswordEncoder` / `BCryptPasswordEncoder`（课件 5.1 DoD①）

- **改动面**：`pom.xml` +1 条依赖（`org.springframework.security:spring-security-crypto`，版本由 Boot BOM 给 = 7.1.1，**本地已缓存，不需要联网**）；
  新增 `config/PasswordEncoderConfig`（或挂在已有 config 类上）1 个 `@Bean`；替换 4 处调用点（`AuthServiceImpl` 2 处、`UserServiceImpl` 2 处，`BCrypt.hashpw/gensalt/checkpw` → `passwordEncoder.encode/matches`）。
- **难度：极小（≈1 小时，1 个回合）**。
- **关键前提已实测**：库中既有的 `$2b$10$` 哈希在 Spring 7.1.1 的 `BCryptPasswordEncoder` 下 **`matches = true`**、`upgradeEncoding = false`
  （探针 `docs/03-qa-review/probes/PwProbe.java`，命令见 §8 ⑤）→ **三个种子账号与 `data.sql` 都不用改**，登录不受影响。
- **验收**：三账号真实登录（admin/docadmin/staff）+ 自助改密（m3-http §8b）+ 新增一版 `data.sql` 时仍用旧哈希能登录；
  `verify-m3-http.ps1`(142) 全绿。
- **附带收益**：`PasswordEncoder` 抽象让"以后换算法"变成加 `{argon2}` 前缀的事；答辩时能直接指着课件说"这里用的是官方实现"。

### A3. 补自动化测试（6 份课件里 4 份 DoD 都点名"单测绿灯"）

这是**唯一的结构性空缺**：我们 504 项验收全在外部 PowerShell 脚本里，`src/test` 是空包。
最小可用集（不求覆盖率，只求把课件点名的三条硬指标变成可复跑的绿灯）：

| 测试类 | 断言什么 | 对应课件 |
|---|---|---|
| `ViewCountConcurrencyTest` | 100 线程并发 `increaseViewCount` → 最终 `view_count` **恰好 +100**，无丢失 | 4.1 DoD④ |
| `PasswordHashCompatTest` | `PasswordEncoder.matches("Admin@123", <库中 $2b$ 哈希>)` = true；新哈希 `encode` 后自校验通过 | 5.1 DoD① |
| `FavoriteCountConcurrencyTest` | 并发收藏/取消收藏后 `favorite_count` = 实际关系表行数（防漂移） | 4.1 DoD③ |

- **难度：中（≈0.5 人天，3~5 个回合）**，难点不在断言而在**测试数据卫生**：
  - 方案 ①（推荐）：测试用 `@SpringBootTest` 连真库，`@BeforeEach` 临时插一行文档 → 跑并发 → `@AfterEach` **物理删除**；
    并发断言必须**每条线程独立事务**（`TransactionTemplate`），不能用 `@Transactional` 回滚（否则 100 个线程互相看不见）。
  - 方案 ②：新建独立库 `campusswap_test`（`schema.sql` + `data.sql` 已验证可重复执行）→ 更干净，但多一套库要维护，且 `verify-m2` 的口径文档要加一句说明。
  - **建议 ①**，并在收口记录里写清"临时行 + 物理删除"的清理逻辑与失败时的兜底（`@AfterEach` 里 `delete` 不依赖断言结果）。
- **验收**：`mvnw test` 绿灯（3 个类全过），并把输出贴进收口记录；同时**故意制造一次失败**（把断言改成 +101）证明测试真的能红。
- **额外收益**：答辩时"我有并发实测"和"我只有静态检查"是两个档次的回答。

### B1. 陈旧表单 / 版本冲突 → 409（课件 4.1 DoD①②，**要你拍板**）

课件把这条叫"Web 全链路防覆盖的双层防御"。**我的建议是只做第一层，不做第二层**：

| 方案 | 做法 | 改动面 | 难度 | 评价 |
|---|---|---|---|---|
| **B1-① 推荐** | 复用现成的 `version_num`：`DocumentUpdateDtoReq` 增加 `versionNum`（前端带它读到的那一版）→ Service 比较，不一致返回 **409**（`ErrorCode.CONFLICT_STATUS`，提示"该文档已被他人修改，请刷新后重试"） | DTO 1 + Service 1 + 4 份文档 + `verify-m4-http.ps1` 里编辑类调用点 + OpenAPI；**不改表、不加依赖** | **小~中（≈0.5 人天，2~3 回合）** | 拿到课件 DoD①② 的语义（陈旧表单被拦 + 409 结构化 JSON），且 `version_num` 本来就是"每次写入 +1、单调递增"，天然就是版本令牌 |
| B1-② 不推荐 | 给 `doc_document` 加独立列 `lock_version` + `@Version`（JPA 自动拼 `WHERE id=? AND version=?`） | 改表（schema/data/perf-fixture 三个 SQL）+ 实体 + 14 处 Save 路径 + `ddl-auto=validate` + 机检 | **中~大（≈1~1.5 人天，4~6 回合）** | 收益是"能演示那条 SQL"；代价是所有保存路径都可能抛 `ObjectOptimisticLockingFailureException`，要给全局异常加分支、要在 14 处评估重试语义，**高风险低收益** |

**必须说清的两点**：
1. **不能把 `@Version` 挂在 `version_num` 上**——它是业务版本号（提交/审核/归档/回收都要 +1），挂 `@Version` 会让"状态流转"和"并发冲突检测"两套语义互相污染。
2. 这条要动 **`ARCHITECTURE §17 ADR-07`（现文明文"不做乐观锁"）**，属**冻结决策变更**，必须你明确同意后才动；同意后我会按惯例写决策记录（谁、何时、为什么改）。
3. 前端 M5 未开工 → **现在改是最便宜的一刻**（这个论证与 `DIFF-VS-TEACHER` §0 同源）。

---

## 4 课件有、我们**故意不做**的（逐条给理由）

### 4.1 JetCache / Caffeine 双级缓存（7.3）

- **场景不符**：课件前提是"多节点集群 + L1 减少网络往返"。我们是**单实例 + 本机 MySQL/Redis**，`EXPLAIN ANALYZE` 实测本机查询 **0.149ms**，加 L1 的收益接近于零（甚至为负：多一层堆对象与失效逻辑）。
- **与我们的核心资产直接冲突**：AC-08.1 要求"授权变更**即时**生效"，L1 本地缓存会引入最长 `localExpire` 的脏读窗口 → 与 BR-18 打架。
- **成本**：`caffeine` / `jetcache` **不在本地仓库**，要用得联网下载；还要处理 Pub/Sub 广播、`@Cached` 注解、单测与文档。
- **结论：不做。** 但会在 `ARCHITECTURE §17 ADR`（或收口记录）里留一段"为什么单实例不上双级缓存"，把课件的知识点变成**判断力**而不是缺项。

### 4.2 权限码统一改成三段式（6.1）

- 现状 39 个码是"两段（页面/入口）+ 三段（按钮级）"混用，这是**有意设计**：`doc:center` 表示"能进文档中心"，`sys:user:disable` 表示"能停用账号"。
- 全量改名的改动面：DB 39 行 + 3 张关联表 + **53 处注解** + 8 份文档 + 4 个机检脚本 + OpenAPI；**纯机械但漏一处就是线上 403**。
- **结论：不改。** 在 `GLOSSARY` 里把命名规范写死（两段 = 入口，三段 = 按钮），并给一张"我们的码 ↔ 课件三段式"的映射示例，答辩时说明"我们按资源层级分级，而不是无差别三段"。

### 4.3 角色码加 `ROLE_` 前缀（6.1）

`ROLE_` 前缀只在 Spring Security 的 `hasRole()` 语义下有意义（框架会自动补前缀）；我们不用该框架，前缀纯属噪音，改了要动 DB + 文档 + 前端（未开工）。
**结论：不改**，在 ADR 里留一句。

### 4.4 `@ManyToMany` 建模（6.1）

老师两份材料**自相矛盾**：`6.1` 用 `@ManyToMany`，`2.1 专题指南` 明令"禁 `@ManyToMany`（中间表要能挂扩展字段、要能做审计）"。我们按 `2.1` 做（手工中间表实体）。
**结论：维持现状**，在 `CODE-TOUR` 或 ADR 里留下一句"两份材料口径冲突，我们按 2.1"。

### 4.5 `maxmemory-policy = allkeys-lru`（7.1）

- 课件的推荐前提是"Redis 当**纯缓存**用"；我们的键是**会话（token）与授权（权限集合）**——被 LRU 淘汰的后果是**用户莫名掉线、权限错判**，是静默故障。
- 我们的实际防线是"**所有键都带 TTL**"（2h / 30min / 30min）+ `noeviction`（写满时宁可报错也不静默丢会话）。
- 真实存在的唯一缺口：本机 `maxmemory 0`（不限制）。开发机无所谓；**生产要设上限**（建议 512MB~1GB 并保持 `noeviction`，靠 TTL 自然回收）。
- **结论：本机不改配置文件（改它要重启 Redis 服务，属你的操作）**；只在文档里写清"为什么不照抄课件 + 生产怎么配"。

### 4.6 `@PreAuthorize` 迁移（6.1）

`DIFF-VS-TEACHER.md` 的 D3 已定"不迁移"，理由不变（53 处机械替换的收益只是形态一致，风险是鉴权链路重验）。
**结论：不改。**

### 4.7 `doc:detail:{id}` 整对象缓存（7.2）

课件的 DoD 是缓存文档详情。**直接照做有坑**：我们的详情 VO 里除了文档行，还有 `categoryName`（分类表）、`authorName`（用户表）、`tags`（关联表）、
**`favorited`（按访问者变化）**、**`canEdit`（按权限变化）** → 整对象缓存会把"给 A 算的收藏态/编辑权"发给 B。
失效点也远超课件的"更新与删除文档"：分类改名、用户改名、标签增删、发布/驳回/归档/下线/删除/恢复/彻底删除/派生共 **≥12 处**。
**结论：不做整对象缓存。** 若要落地 7.2 的 Cache-Aside，**改缓"字典数据"**更正确：分类树 / 标签列表（10 分钟 TTL，写接口 4+4 处失效）——
**这是可选增量（≈0.25 人天）**，需要你点头才做。

---

## 5 需要你拍板的决策

| # | 决策 | 选项 | 我的建议 |
|---|---|---|---|
| **D1** | A1 `clearAutomatically` 加固做不做 | 做 / 不做 | **做**（30 分钟，零契约影响，且能产出一份"脏读复现"证据） |
| **D2** | A2 换官方 `BCryptPasswordEncoder` 做不做 | 做 / 不做 | **做**（1 小时；兼容性已实测通过，jar 已缓存不需要联网） |
| **D3** | A3 补 JUnit 测试做不做、用哪个数据方案 | 做（方案①临时行 / 方案②独立测试库）/ 不做 | **做，方案①**（课件 DoD 点名，且这是唯一"我们完全覆盖不到"的验收类别） |
| **D4** | B1 版本冲突 409 做不做、做哪一档 | B1-① 复用 `version_num`（应用层，推荐）/ B1-② 新增 `lock_version` + `@Version`（重量级）/ 都不做 | **B1-①**（拿到课件语义，不动表不加依赖；需要你同意改 ADR-07） |
| D5 | 可选：缓存字典数据（分类树/标签）示范 Cache-Aside | 做 / 不做 | **看你答辩诉求**：想讲 Cache-Aside 就做（0.25 人天），不想就只写进文档 |
| D6 | 可选：ZSet 热榜接口 `doc:rank:views` + Top 10 | 做 / 不做 | **默认不做**：与现有 `sort=viewCount_desc` **功能重叠**，属新需求（PRD 没有"热榜"），加了还要前端榜单区块 |

---

## 6 若你同意，执行顺序（每步独立验收 + 回滚点）

| 阶段 | 内容 | 出口 | 回滚 |
|---|---|---|---|
| S1 | A1 + A2（两个"极小"项一起做） | `mvnw` 编译 + `ddl-auto=validate` + m3-http 142 / m4-http 218 全绿 + 脏读复现证据 + 三账号登录实测 | `git reset --hard <本阶段前的 HEAD>` |
| S2 | A3 三个测试类 | `mvnw test` 绿灯 + 故意改坏一次证明能红 | 删 `src/test` 新增文件即回退 |
| S3 | B1-①（需 D4 同意） | 编辑接口版本冲突实测 409 + 正常编辑仍 200 + 机检全绿 + ADR-07 决策记录 | tag `pre-version-check` |
| S4 | 收口 | 新增用例并入 `TEST_CHECKLIST.md`；`COURSEWARE-CLOSURE.md`（对齐记录）；全量重跑 504 + 209；提交推送 + 桌面副本 | 全部保留 tag |

---

## 7 诚实风险清单（不粉饰）

1. **A1 不是"修 bug"，是"补一处靠巧合安全"**。当前代码在现有调用序列下不会丢计数；我会在收口记录里如实写清"复现实验是在人为构造的事务里做的"，不夸大成"我们发现了并发 bug"。
2. **A3 有污染种子数据的风险**。方案①的临时行必须 `@AfterEach` 物理删除；若测试中途崩在删之前，`verify-m2.ps1`（doc 计数=5）会红。我会先写好清理逻辑，并在测试后自动重跑 `verify-m2` 兜底。
3. **A2 的唯一真实风险是"依赖树变化"**：`spring-security-crypto` 会带 `commons-logging`（`spring-core 7.0.9` 已经依赖它，本地已缓存），**不会**引入 `spring-security-web/config`，
   所以**不会**带来过滤器链与自动配置——这一条我会在改完后用 `mvnw -o dependency:tree` 的输出贴进收口记录证明，而不是口头保证。
4. **B1-① 若做成"可选字段"，防护力打折**：前端不传就等于没校验。我建议**必填**（M5 未开工，现在定契约最便宜），代价是 `verify-m4-http.ps1` 里编辑类调用点要同步补字段。
5. **课件 6.1 与 2.1 自相矛盾、7.1 的推荐不适用于我们的键性质、7.3 的前提是多节点**——这三处我都不照抄。如果老师的验收明确要求"照课件形态做"，请告诉我，我按 D5/D6 另开可选增量，不擅自扩范围。
6. **本文件不构成范围变更**：除 D4/D5/D6 三个需要你点头的项外，A1/A2/A3 都不改任何冻结契约（DTO/VO/端点/权限码/表结构）。

---

## 8 本轮实测命令（可复跑）

```powershell
# ① @Modifying 全量位置与 clearAutomatically 缺失情况
Get-ChildItem -Path backend\src -Recurse -Filter *.java | Select-String -Pattern '@Modifying'
Get-ChildItem -Path backend\src -Recurse -Filter *.java | Select-String -Pattern 'clearAutomatically|flushAutomatically'

# ② 测试类数量（预期：无输出 = 0 个）
Get-ChildItem -Path backend\src\test -Recurse -Filter *.java

# ③ 39 个权限码（看两段/三段混用）
& $MYSQL -uroot -p123456 -N -B -e "select code from campusswap_db.sys_permission order by id;"

# ④ 密码哈希前缀（预期 $2b$10$，长度 60）
& $MYSQL -uroot -p123456 -N -B -e "select username,left(password_hash,7),length(password_hash) from campusswap_db.sys_user;"

# ⑤ Spring BCrypt 兼容性探针（预期 matches=true / upgradeEncoding=false）
#    探针源码：docs\03-qa-review\probes\PwProbe.java
$repo='D:\DevEnv\05_Maven\repository'
$cp="$repo\org\springframework\security\spring-security-crypto\7.1.1\spring-security-crypto-7.1.1.jar;" +
    "$repo\commons-logging\commons-logging\1.3.6\commons-logging-1.3.6.jar;" +
    "$repo\org\springframework\spring-core\7.0.9\spring-core-7.0.9.jar"
& 'D:\DevEnv\02_JDK\jdk-17.0.5\bin\java.exe' -cp $cp docs\03-qa-review\probes\PwProbe.java 'Admin@123' '<库中 admin 的 password_hash>'

# ⑥ 依赖是否已缓存 + Boot BOM 管理的版本
Get-ChildItem "$repo\org\springframework\security\spring-security-crypto" -Directory
Select-String -Path "$repo\org\springframework\boot\spring-boot-dependencies\4.1.1\spring-boot-dependencies-4.1.1.pom" -Pattern 'spring-security.version'
Get-ChildItem "$repo\com\github\ben-manes\caffeine" -Directory -ErrorAction SilentlyContinue   # 预期：不存在
Get-ChildItem "$repo\com\alicp\jetcache" -Directory -ErrorAction SilentlyContinue               # 预期：不存在

# ⑦ Redis 实机配置
& 'D:\DevEnv\04_Redis\redis-cli.exe' config get maxmemory
& 'D:\DevEnv\04_Redis\redis-cli.exe' config get maxmemory-policy
& 'D:\DevEnv\04_Redis\redis-cli.exe' config get appendonly
& 'D:\DevEnv\04_Redis\redis-cli.exe' config get appendfsync
& 'D:\DevEnv\04_Redis\redis-cli.exe' config get save
& 'D:\DevEnv\04_Redis\redis-cli.exe' config get aof-use-rdb-preamble
& 'D:\DevEnv\04_Redis\redis-cli.exe' info persistence
& 'D:\DevEnv\04_Redis\redis-cli.exe' dbsize
```
