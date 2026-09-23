# DIFF-VS-TEACHER — 与老师安全框架的对账、迁移选项与工作量

> 生成时间：2026-09-23 ｜ 对账对象：`D:\DevEnv\projects\backend(2)`（老师示例工程） vs `D:\DevEnv\projects\campusswap`
> **状态：已结案（2026-09-23）。老师答复：底层原理一致，Spring Security 那套只是更规范 → 结论：架构不改，§4 的路线 A/B/C 均不执行。本文件转为决策留档。**
> 结案说明：老师同时提醒"用 AI 生成的代码要好好理解"，故新增 `docs/02-design/CODE-TOUR.md`（代码导读 + 术语翻译 + 10 个必答问题）承接这项要求。
> 机检口径：所有数字均为本轮实测（命令写在 §1 里，可复跑）。

---

## 0 一句话结论

**时机上，"现在改"是全项目最便宜的一刻**（前端 M5 尚未开工，认证契约还没落到任何调用方）。
但老师那套框架本身**跑不起来**（缺两个 Provider 类、pom 无依赖、登录接口是 stub），所以我们的做法必须是
**"按它的形态重写一遍"，而不是"把它的代码搬过来"**。折合人工量：**2~3 人天**，分 5 个阶段，每阶段有独立验收与回滚点。

---

## 1 事实底稿（对账前提）

### 1.1 我们这边（本轮实测）

| 项 | 实测值 | 来源 |
|---|---|---|
| Java 文件总数 | **150** | `Get-ChildItem -Recurse -Filter *.java` |
| 鉴权相关类 | `common/security/` 6 个：`BearerToken(31)` `LoginInterceptor(104)` `PermissionAspect(38)` `RedisKeys(69)` `RequiresPermission(27)` `SecurityContext(69)` | 同上 |
| 引用 `SecurityContext` 的文件 | **10** | 全文匹配 |
| 引用 `LoginInterceptor` 的文件 | **5**（`BearerToken` `LoginInterceptor` `PermissionAspect` `SecurityContext` `WebMvcConfig`） | 全文匹配 |
| `@RequiresPermission` 用量 | **53 处 / 11 个文件**，形如 `@RequiresPermission("doc:search")` | 全文匹配 |
| 拦截器注册 | `WebMvcConfig.addInterceptors`：`/api/**` 全拦，仅放行 `/api/auth/login`、`/api/auth/logout` | 读源码 |
| 依赖 | `webmvc` / `data-jpa` / `validation` / `data-redis` / `mysql-connector-j` / `hutool-all` / `lombok`；**无 security、无任何 JWT 库** | `pom.xml` |
| 错误码 | `ErrorCode`：401 `UNAUTHORIZED`、403 `NO_PERMISSION`、403 `USER_DISABLED`、400/404/409/500 | 读源码 |
| 表 | **14 张，无 `sys_login_log`**（老师有登录记录表，我们 M1 把它换成了 `sys_user_permission`） | `SHOW TABLES` |
| `sys_user` 列 | 16 列；`phone varchar(20) NULL`、**非唯一**；**无** `login_failure_count` / `lock_time` / `token_version`；有 `status`(默认 ACTIVE)、`last_login_at` | `information_schema.COLUMNS` |
| 种子账号手机号 | admin `13800000001`、docadmin `13800000002`、staff `13800000003` —— **短信登录有现成测试数据** | `SELECT id,username,phone` |
| 本地 Maven 仓库 | `spring-boot-starter-security` **已缓存**、`jose4j` **已缓存**（`jjwt` 未缓存） | 扫 `D:\DevEnv\05_Maven\repository` |
| 机检规模 | m3-http **142** 项 / m4-http **218** 项（2026-09-23 M5 前置增补后）；两个脚本中 **与 401/403 直接相关的取值点各 11 处** | 正则统计 |

### 1.2 老师那边（读源码得到的事实，**含其工程自身的缺陷**）

老师工程用的类（`edu.software.backend`，类名却全是 `Aries*` / `com.starrysky.aries.*`，是从别处拷来的骨架）：

`config/SecurityConfig`、`security/SecurityConstant`、`security/jwt/{JwtFilter,JwtUtil}`、
`security/RSAUsernamePasswordAuthenticationFilter`、`security/{AriesAuthenticationSuccessHandler,AriesAuthenticationFailureHandler,AriesUserDetailsService,AriesAuthenticationEntryPoint,AriesAccessDeniedHandler,AriesLogoutSuccessHandler}`、`redis/RedisConstant`。

**读完必须说的三件事（不然后面会照抄一个坏底稿）：**

1. `SecurityConfig` 里 `ProviderManager` 引用了 `SmsCodeAuthenticationProvider` 与 `WeChatAuthenticationProvider` —— **这两个类在全工程里不存在**（只有引用）。该 `SecurityConfig` **编译不过**。
2. `pom.xml` 里**没有** `spring-boot-starter-security`，也没有 jose4j 的坐标（类里却 `import org.jose4j.*`）。
3. 整个工程只有 **32 个 Java 文件、1 个 Controller**；`UserController` 的 `@RequestMapping("/login")` 方法体是 `return "";`（stub）。白名单里的 `/user/login/sms`、`/user/refresh`、`/sms/send`、`/resource/**` **都没有对应端点**。

> 结论：老师给的是一份**"形态参考"，不是"可运行基线"**。我们要学的是它的**结构与口径**（白名单常量、过滤器位置、双令牌、成功/失败处理器、短信登录入口），而不是它的代码。

---

## 2 逐项对账表

| # | 维度 | 老师（backend(2)） | 我们（campusswap） | 差距性质 |
|---|---|---|---|---|
| 1 | 认证入口 | `RSAUsernamePasswordAuthenticationFilter`（`POST /user/login`，phone+password，RSA 解密） | `AuthController` + `LoginInterceptor`（username+password，明文 HTTPS 语义） | **形态不同**（可对齐） |
| 2 | 认证框架 | Spring Security 过滤器链 + `ProviderManager`（Dao / Sms / WeChat 三 Provider） | 无框架：拦截器 + ThreadLocal | **核心差距** |
| 3 | 令牌 | jose4j JWT，access **2h** / refresh **7d**；claims `id`/`token_version`/`user_agent`；subject `aries_token`，issuer `com.starrysky` | 不透明 UUID token，存 Redis `login:token:{token}`→userId，TTL **2h** | **核心差距** |
| 4 | 令牌撤销 | JWT 本身不可撤销 → 靠 Redis 存 `token_version` 比对 + UA 的 sha256 绑定 + 滑动续期 7d | 直接 `DEL` 键即失效（这是 ARCHITECTURE §5.2 当初选不透明 token 的理由） | 老师的方案**并没有更简单**，只是把撤销逻辑挪进了 Redis |
| 5 | 会话存储 | Redis **Hash** `starrysky:aries:authen:{id}`（principal + token_version） | Redis String 单键 + `user:tokens:{userId}` 集合（支持"改密后踢全部"） | 不同实现，能力等价 |
| 6 | 白名单 | `SecurityConstant.WHITE_LIST` **常量数组 8 项**，配 `requestMatchers(...).permitAll()` | 硬编码在 `WebMvcConfig` 的 2 个 `excludePathPatterns` | **易对齐**（10 分钟） |
| 7 | 兜底策略 | `anyRequest().authenticated()`（默认全拦，白名单例外） | 拦截器 `/api/**` 全拦 + 放行 2 条（同思路，写法不同） | 同思路 |
| 8 | 无状态 | `SessionCreationPolicy.STATELESS`、csrf 关、`frameOptions sameOrigin` | Spring MVC 默认（本来就没用 session） | 名义差距 |
| 9 | 过滤器位置 | `addFilterAt(rsaFilter, UsernamePasswordAuthenticationFilter)` + `addFilterBefore(jwtFilter, LogoutFilter)` | 拦截器顺序 = 注册顺序 | 形态不同 |
| 10 | 401/403 出口 | `AriesAuthenticationEntryPoint` / `AriesAccessDeniedHandler`（自定义 JSON） | `GlobalExceptionHandler` + `ErrorCode` 统一响应体 | 双方都有，**要保证响应体一致** |
| 11 | 登出 | `AriesLogoutSuccessHandler` + `/user/logout` 需认证 | `/api/auth/logout` **故意放行**（API 规范要求重复登出幂等 200） | **我们的更严**，迁移时不能丢这条 |
| 12 | 失败风控 | `AriesAuthenticationFailureHandler`：失败计数 `loginFailureCount` + `lockTime`，达 `Constant.USER_LOCK_LOGIN_FAIL_COUNT` 锁定，并写登录记录 | **无**（只有 `status=LOCKED` 枚举值，没有任何触发逻辑） | **真实缺口** |
| 13 | 短信登录 | 白名单有 `/user/login/sms`，`SmsCodeAuthenticationProvider` 缺类；`RedisConstant` 预留短信/验证码前缀 | **无** | **真实缺口** |
| 14 | 微信登录 | 白名单有 `/user/login/wechat`，Provider 缺类 | 无 | 超出作业范围（PRD O4/O5 精神） |
| 15 | 账号注销清理 | 齐全（注销时清 Redis 会话等） | 无（US 里没有注销） | 非目标 |
| 16 | 授权（RBAC） | `AriesUserDetailsService` 的 `authorities = new HashSet<>()`（**空的**）→ 框架只区分"登录/未登录" | **39 权限点 / 3 角色 / 部门继承角色 / 用户直授权 / 30 分钟缓存 / 授权即时生效** | **我们强得多，迁移中绝不能缩水** |
| 17 | 权限校验写法 | 无（框架没做） | `@RequiresPermission("doc:search")` AOP，53 处 | 见 §4 选项 |
| 18 | 数据访问身份 | `SecurityContextHolder.getContext().getAuthentication()` | `SecurityContext.currentUserId()` ThreadLocal，10 个文件引用 | 迁移要统一 |
| 19 | 密码存储 | RSA 传输 + 库里 bcrypt | bcrypt（同） | 无差距（RSA 属加分项，非必需） |
| 20 | 配置外置 | JWT 密钥/有效期散在 `JwtUtil` 常量 | `RedisKeys` 集中 + `application.yml` | 我们更好，保留 |

---

## 3 你点名的 5 样东西 → 我们的等价物

| 你提到的 | 老师怎么做 | 我们现在有什么 | 要补的 |
|---|---|---|---|
| **JWT** | jose4j 双令牌 + Redis `token_version` | 不透明 token（能撤销） | 换成 JWT：`JwtUtil`/`JwtFilter` + refresh 接口 + token_version |
| **Spring Security** | `SecurityConfig` 过滤器链 + `ProviderManager` | 拦截器 + AOP | 新增 `SecurityConfig`；把拦截器职责迁进过滤器 |
| **success + failureHandler** | 成功写 Redis 会话 + 发双令牌；失败计数锁定 | `AuthController` 里一把梭返回 `LoginVo` | **拆成成功/失败处理器是纯重构，能力不增不减** |
| **`SecurityConstant.WHITE_LIST`** | 常量数组 8 项 | `WebMvcConfig` 里 2 条字符串 | 抽成常量类（10 分钟） |
| **`anyRequest()`** | 默认全拦 | 拦截器 `/api/**` 全拦 | 迁移后等价 |
| **短信登录** | 白名单 + Provider（类缺失） | 无 | 验证码生成/校验/频率限制 + 登录入口（**唯一的真新增功能**） |

---

## 4 三条路线（含真实工作量）

> 工作量口径：① "文件数"= 预计新增/改动/停用 的 Java 文件数；② "人天"= 一个熟悉这套代码的人写+验的时间；③ "我这边"= 实际对话回合数（含编译、起服务、跑机检）。

### 路线 A —— 只做增量，不换框架
加 `SecurityConstant.WHITE_LIST` 常量 + 失败计数锁定 + 短信登录（接在现有拦截器体系上）。

- 新增 ~8 类 / 改 ~6 类 / 停用 0；DB +2 列（`login_failure_count`、`lock_time`）+ 1 个唯一索引
- 机检：新增用例 ~30 条，**已有 504 项几乎不受影响**
- 代价：**不满足你"框架也想对齐老师"的诉求**
- **1~1.5 人天**

### 路线 B —— 全对齐（**推荐**）
Spring Security 过滤链 + jose4j 双令牌（access 2h / refresh 7d）+ UA 绑定 + `WHITE_LIST` 常量 + 短信登录 + 失败锁定，**但保留我们 39 个权限点的 RBAC**。

- 新增 **~20 类**（`config/SecurityConfig`、`security/jwt/{JwtUtil,JwtFilter}`、`security/{LoginSuccessHandler,LoginFailureHandler,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler,LogoutSuccessHandler}`、`security/sms/{SmsCodeService,SmsAuthenticationProvider,SmsAuthenticationToken,SmsSender}`、`security/CampusUserDetailsService`、`dto/{SmsSendDtoReq,SmsLoginDtoReq,RefreshTokenDtoReq}` 等）
- 改 **~18 类**（`AuthController`、`SecurityContext`、`WebMvcConfig`、`pom.xml`、`RedisKeys`、`UserService`/`UserRepository` 加手机号与锁定字段、`application.yml`、4 个机检脚本…）
- 停用 **2 类**（`LoginInterceptor`、`WebMvcConfig` 里的拦截器注册；`BearerToken` 保留给静态资源）
- DB：+2 列（`login_failure_count`、`lock_time`）、`phone` 加**唯一索引**、`data.sql`/`schema.sql`/`perf-fixture.sql` 同步
- 文档：`ARCHITECTURE §5.2`（现文明文**否决 JWT**，必须改写并留决策记录）、`API_SPECIFICATION`（认证章节 + 新增 3 接口）、`PRD §8`（O5 措辞 + 新增"短信登录"范围）、`GLOSSARY`（新增 DTO/VO）、`UI_UX_SPECIFICATION`（登录页加短信 Tab）、`openapi-campusswap.json`（55 → ~58 端点）
- 机检：两个 HTTP 脚本的 401/403 取值点各 11 处要重新对口径；新增短信/锁定/刷新/白名单用例 ~40 条；**必须重跑全部 504 项**
- 风险：M3/M4 踩过的 6 个坑（登出幂等、Redis 抖动误判失效、错误响应中文与枚举、软删除唯一索引、IDOR…）要在新链路上**重新验一遍**，不能假定"框架会处理"
- **2~3 人天 ≈ 我这边 10~13 个回合**

### 路线 C —— 全量照搬老师（含微信登录、RSA 传输加密、账号注销清理）
**不建议**：微信登录需要开放平台资质；RSA 传输加密在有 HTTPS 的前提下是安全表演；注销清理我们没有对应 US。
**4~5 人天且答辩几乎用不上。**

---

## 5 你需要做的事（分三类）

### 5.1 必须你拍板的决策（4 个）

| # | 决策 | 选项 | 我的建议 |
|---|---|---|---|
| D1 | 走哪条路线 | A 增量 / **B 全对齐** / C 照搬 | **B**：你的诉求就是"框架 + 短信都对上" |
| D2 | 短信验证码怎么发 | ① 真短信（阿里云/腾讯云）② 开发模式（验证码写日志 + 固定测试码 + 预留 `SmsSender` 接口） | **②**：真短信要实名、签名报备、模板审核（1~3 天）且按条计费，作业演示不值当；接口留好，答辩时说"换成真实实现只需实现一个接口"更有说服力 |
| D3 | 权限点怎么接进 Spring Security | ① 保留 `@RequiresPermission` AOP（认证换框架、授权不动）② 全迁 `@PreAuthorize("hasAuthority('doc:search')")` ③ 两者并存 | **②**：53 处注解的权限串一字不改，是**机械替换**；迁完才叫"真的用上了 Spring Security 的授权" |
| D4 | 流程 | ① **先只改文档（阶段 0），你审完再动代码** ② 文档+代码一起改 | **①**：ARCHITECTURE §5.2 是冻结决策，动它必须有你的明确同意 |

### 5.2 需要你亲自出手的操作（很少）

- **开一次加速器**——仅当你选 D2① 真短信、需要我去查某家云的具体 SDK/API 时；否则**不需要**（`spring-boot-starter-security` 与 `jose4j` 已在本地 Maven 仓库缓存，构建不必联网）。
- **验收时点一遍**：我会给你一份 5 步手动验收清单（密码登录 / 短信登录 / 连错 5 次锁定 / 无权限 403 / 重复登出 200），每题都有预期结果与失败时的第一诊断命令。
- **如果你要录演示视频**（PRD O9 现在是非目标），短信登录过程要出现在视频里 —— 这个决定权在你。

### 5.3 不需要你做的（避免你白忙）

- 不用装任何依赖、不用改 IDEA 配置、不用改 `application.yml`（我改）。
- 不用自己备份代码：动手前我会在 `main` 上打 tag（`pre-security-baseline`）并记录当前 HEAD（`edbe00c`），回滚一条命令。
- 不用手动建表：`schema.sql`/`data.sql` 由我改，`verify-m2.ps1` + `verify-db-deep.ps1` 负责证明它俩能重复执行且与实库一致。

---

## 6 分阶段计划（每阶段独立验收 + 回滚点）

| 阶段 | 内容 | 出口（可复跑的验收） | 回滚点 |
|---|---|---|---|
| **S0 文档**（~1 回合） | 改 `ARCHITECTURE §5.2`（认证方案）、`API_SPECIFICATION`（认证章节 + 3 新接口）、`PRD §8`（范围）、`GLOSSARY`、`UI_UX_SPECIFICATION`（登录页）；`verify-api-spec.ps1` 24 项复绿 | 文档机检全绿 + 你确认 | 不进代码，随时弃 |
| **S1 骨架**（2~3 回合） | +security 依赖、`SecurityConfig`、`WHITE_LIST` 常量、`CampusUserDetailsService`、JSON 401/403 出口；拦截器退役；`@PreAuthorize` 机械替换 53 处 | 密码登录可用；**m3-http 142 / m4-http 218 复绿（口径同步后）**；`ddl-auto=validate` 通过 | `git reset --hard pre-security-baseline` |
| **S2 JWT**（2~3 回合） | jose4j `JwtUtil`/`JwtFilter`、双令牌、`token_version` 撤销、UA 绑定、refresh 接口、改密踢人 | 新增 JWT 用例全绿；撤销/过期/篡改三类反例逐个验；M3 的"踢人即时生效"复验 | tag `pre-jwt` |
| **S3 短信登录**（2 回合） | `SmsCodeService`（Redis TTL + 频率限制）、`SmsAuthenticationProvider`、`/api/auth/sms/send`、`/api/auth/sms/login`、`SmsSender` 接口 + 开发模式实现 | 三账号手机号登录成功；错码/过期/限流/未注册手机号四类反例 | tag `pre-sms` |
| **S4 风控**（1~2 回合） | 失败计数 + `lock_time` 锁定 + 解锁路径 + 登录记录 | 连错 5 次锁定实测；锁定中正确密码也 403；`status=LOCKED` 与自动锁定不打架 | tag `pre-lockout` |
| **S5 回归与收口**（1~2 回合） | 全量重跑 **504 项**（m0 13 / m1 15 / api-spec 24 / m2 17 / db-deep 9 / m3 36 / m3-http 142 / m4 30 / m4-http 218）+ 新增用例；`TEST_CHECKLIST.md` 扩展；`SECURITY-CLOSURE.md`；OpenAPI 补 3 接口 + 桌面副本；提交推送 | 全绿 + 桌面副本 SHA256 一致 + 远端 = 本机 | 全部保留 tag |

**回滚语义**：S1~S4 每阶段结束打一个 tag；任何一步自验失败且 30 分钟内修不掉 → 回到上一个 tag，因此**不会出现"改了一半不动了"的状态**。

---

## 7 诚实风险清单（不粉饰）

1. **"换成 JWT 更简单"是假象**。JWT 不可撤销，老师那套照样要 Redis 存 `token_version` 才能踢人/改密失效 —— 也就是说 S2 做完，"要 Redis" 这件事一点没少，只是令牌形态变了。**换 JWT 的收益是"与老师形态一致 + 可以横向扩展无状态"**，不是"代码更少"。
2. **权限体系有缩水风险**。老师那边 `authorities` 是空集，等于没有授权层；我们的 39 权限点是 M3 的核心资产。**迁移时若把 `@RequiresPermission` 直接删掉换成"登录即可访问"，是实质退步**——所以 D3 我建议走机械替换而不是删。
3. **504 项回归必须重跑，且我预期会有失败**。M3/M4 的 6 个坑都长在鉴权链路上（登出幂等、Redis 抖动、中文错误提示），换链路就是重新踩一遍。**我会把每一次失败都写进收口记录**（复现 → 根因 → 修法 → 复验），不隐藏。
4. **响应体契约不能变**。前端（M5）和 Apifox 依赖 `{code,message,data}` + `ErrorCode` 的 401/403 语义；Spring Security 默认返回的是空体 401/403 + `WWW-Authenticate` 头。**必须写自定义入口点，否则前端要改** —— 这是 S1 的第一条验收。
5. **一个不能碰的既有约定**：`/api/auth/logout` 重复调用必须幂等 200（API 规范 §4.1.2）。Spring Security 的 `logout` 默认是 302/204，**必须保留我们"放行进 Controller"的写法**，否则又是一次"修了 A 坏了 B"。
6. **范围变更会被记录**。`PRD §8 O5`（邮件/短信通知）与"短信登录"是两回事，但没有你的明确同意我不动 §5.2 的冻结决策；S0 就是把这句同意**变成文档里的决策记录**（谁、何时、为什么）。

---

## 8 我不建议做的事（省时间）

- 微信登录（`/user/login/wechat`）：要开放平台资质，PRD O4 已把小程序/移动端列为非目标。
- RSA 传输加密（老师的 `RSAUsernamePasswordAuthenticationFilter`）：本地演示无 HTTPS，加了也只是把明文密码从"明文"变成"可解密"，安全收益接近零、演示解释成本高。
- 账号注销清理：我们的 US 里没有注销，为一个不存在的场景写清理逻辑是负资产。
- 照抄 `Aries*` / `com.starrysky.aries.*` 包名：与 `com.campusswap` 包结构冲突，且会让答辩老师以为我们是整包拷贝。

---

## 9 结案记录（2026-09-23）

你把 §3 的 5 个问题带去问了老师，老师答复：**底层原理一致，Spring Security 那套只是更规范**（并提醒"用 AI 生成的代码要好好理解"）。

据此的处置：

| 项 | 结论 |
|---|---|
| 路线 A（只做增量） | **不执行** |
| 路线 B（全对齐 Spring Security + JWT） | **不执行** —— 认证层保留 HandlerInterceptor + Redis 不透明 token |
| 路线 C（照搬老师全量） | **不执行** |
| 短信验证码登录 | **不做**（PRD §8 O5 维持现状，不构成范围变更） |
| 冻结文档 `ARCHITECTURE §5.2` | **不改**，原决策继续有效 |
| 老师那句"要好好理解" | 落到 `docs/02-design/CODE-TOUR.md`：四条铁律 + 里程碑复盘 + 代码地图 + 一次请求的旅程 + **术语翻译表** + 10 个必答问题 |
| §1.2 提到的老师工程缺陷（缺两个 Provider 类、pom 无依赖、登录是桩） | 不再需要老师补发；本文件保留该记录，供以后引用时避免把它当可运行基线 |

