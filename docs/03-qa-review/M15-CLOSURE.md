# M15 收口记录：登录 token 改造为 HS256 JWT（保留 Redis 可撤销）

> 收口日期：2026-10-14　|　机检：`verify-m15.ps1`（静态 13 + 运行时 7 = 20 项）
> 关联：老师《作业说明》「登录认证：密码加密存储；**JWT 登录**；登录状态保存在 **Redis**；退出后 Token **立即失效**」与注意事项「登录认证鉴权必须是真实的后端链路」
> 设计：`ARCHITECTURE §5.2`（已按新口径重写）、`ADR-04`
>
> **2026-10-15 补记：验签已接入 `LoginInterceptor`（调用 `TokenService.resolve`），不再是死代码。**
> 收口当天 `resolve()` 在 main 源码里**没有调用点**（只在单测 `Ac10JwtTokenTest` 里被调用），
> 也就是说本文档与 `源码阅读说明.md` 当时写的"每个请求先验签再查 Redis"**在真实请求路径上不成立**。
> 现已接线并实测（见 §3 的 2026-10-15 两行）：篡改 payload 的假票即使被写进 Redis 也 401「登录凭证无效」，
> 而接线前同样一张假票是 **200**（拿到 admin 全量资料）。

---

## 1 为什么要改

M1 冻结时本项目**刻意否决了 JWT**（理由是"JWT 签发后无法主动作废"，`ARCHITECTURE §5.2` 旧版 + `ADR-04`）。

老师《作业说明》把三项要求并列写死：**JWT 登录** + 登录状态保存在 Redis + 退出后 Token 立即失效。
这三条其实**不互斥**：

| 要求 | 谁来满足 |
|---|---|
| JWT 登录 | `JwtService` 用 **HS256** 签发 `header.payload.signature` |
| 登录状态保存在 Redis | 签发同时把 **JWT 原文**写进 `login:token:{jwt} → userId`（TTL = JWT 有效期 = 2 小时） |
| 退出后 Token 立即失效 | 登出/停用/改密 **DEL** 该 Redis 键 —— 签名仍有效的 JWT 立刻作废（纯 JWT 做不到） |

于是采取"**JWT 自证身份 + Redis 负责撤销**"并存方案：既满足字面要求，又保住原有的"一键踢人"能力。
权限仍不塞进 JWT（塞了会出现"改了权限但 token 里的权限还没过期"的不一致），继续从 `perm:user:{userId}` 读。

---

## 2 交付物

| 层 | 文件 |
|---|---|
| 签发/校验 | `common/security/JwtService.java`（HS256；claims `iss/sub/jti/iat/exp`；密钥 <32 字节直接拒绝构造） |
| 服务 | `system/service/impl/TokenServiceImpl.java`（issue：签发 + 写 Redis + 登记 user:tokens；resolve：**先验签，再查 Redis**；revoke / revokeAll） |
| 接线（2026-10-15） | `common/security/LoginInterceptor.java`：拿到 Bearer token 后调 `TokenService#resolve`（验签逻辑不重复实现），把结果映射成 401「登录凭证无效」（验签不过）/ 401「登录状态已失效」（会话已撤销）/ 500「登录状态校验失败」（Redis 不可用）；`Ac02DraftCreateTest` 的 AC-02.3 同步覆盖这三条分支（用例总数仍 54） |
| 依赖 | `pom.xml` 新增 `io.jsonwebtoken:jjwt-{api,impl,jackson}:0.12.6`（Boot 4.1.1 的 BOM **不管辖** jjwt，必须显式指定版本） |
| 配置 | `campusswap.security.jwt.{secret,issuer,ttl-minutes}`：dev/demo/test 带默认值；**prod 只读 `JWT_SECRET` 且不给默认值**（缺了就启动失败，好过弱密钥裸奔） |
| 测试 | `Ac10JwtTokenTest`（9 例：签发验签 / 篡改拒收 / 换密钥拒收 / 签发者不符拒收 / 弱密钥拒绝 / 过期拒收 / **登出立即失效** / 一键下线 / Redis 无记录拒收） |
| 机检 | `verify-m15.ps1`（A 静态 13 / B 运行时 7，全绿） |
| 文档 | `ARCHITECTURE §5.2` 重写 + `ADR-04` 修订、`CODE-TOUR` 对应物表与"你要能讲"同步 |

---

## 3 实测证据（2026-10-14）

| 项 | 结果 |
|---|---|
| token 形态 | 长度 **205**、3 段、`header = {"alg":"HS256"}` |
| payload | `{"iss":"campusswap","sub":"1","jti":"c2b90b0b…","iat":1791939827,"exp":1791947027}`（`exp-iat = 7200s = 2h`） |
| 正常鉴权 | `GET /api/auth/me` → **200** |
| **篡改 payload（签名不动）** | → **401**（验签拦下，未查 Redis）⚠️ 2026-10-15 更正：当时 HTTP 侧走的是"只查 Redis"的拦截器，这一行的 401 其实是**因为 Redis 里没有这张假票**；接口层真正验签是 2026-10-15 接入的，见下面两行 |
| 乱造 token | → **401** |
| **伪造票（篡改 exp、签名不动）写进 Redis 再请求**（2026-10-15） | **接线前 → 200**（`login:token:<假票>` = 1，直接拿到 admin 全量资料）；**接线后 → 401**「登录凭证无效，请重新登录」，日志 `JwtService: JWT signature does not match locally computed signature` |
| 对照：验签通过但会话已撤销（2026-10-15） | 登出后同一张真票 → 401「登录状态已失效，请重新登录」；Redis 侧 `CLIENT PAUSE` 6s（`spring.data.redis.timeout=3s`）→ **500**「登录状态校验失败，请稍后重试」（不是 401），抖动过后同一张票仍 200 |
| Redis 登录状态 | `keys login:token:*` → **41 个**（值 = userId、TTL 6658s 递减）；`user:tokens:*` 3 个集合 |
| **登出** | 该键立即消失（41 → 40）；**同一 JWT 再访问 → 401**；`user:tokens:1` 成员数 35 → 34 |
| 单元测试 | `mvnw -o test` **54/54**（M15 新增 9 例） |

---

## 4 实测缺陷与修法

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 应用**启动失败**：`Could not resolve placeholder 'campusswap.security.jwt.secret'` | 我把 `security:` 块追加在 yml 末尾，落进了上一个顶层键（`spring:` / `logging:`）下面，成了 `spring.security...` / `logging.security...` | 改为**顶层 `campusswap:` 块**（4 个 profile 文件全部修正）—— 教训：YAML 追加块必须确认顶层键归属 |
| 2 | `verify-m3-http.ps1` 断言 `login.token.32chars` 失败 | token 从 32 位随机串变成 JWT | 断言改为 JWT 形状（3 段 + `eyJ` 前缀） |
| 3 | 机检脚本又被我写进中文注释 | 项目铁律：`.ps1` 必须纯 ASCII（PS 5.1 按 ANSI 读无 BOM 脚本） | 注释改英文，非 ASCII 字节复检为 0 |
| **4** | **`TokenServiceImpl#resolve` 在 main 源码里没有任何调用点 —— 验签在真实请求路径上是死代码**（2026-10-15 排查文档口径时发现；`LoginInterceptor` 当时只查 Redis，所以"篡改 payload → 401"其实是 Redis 未命中） | M15 只补了"签发 + 校验能力"，漏了把校验**接进唯一入口**。最坏情形：攻击者手里一张篡改过 payload 的假票，只要它恰好存在于 Redis（例如被塞进去），接口就认 | `LoginInterceptor` 改为调 `TokenService#resolve`（验签 + Redis + userId 比对），并按失败原因分派 401「登录凭证无效」/ 401「登录状态已失效」/ 500「登录状态校验失败」；`Ac02DraftCreateTest` AC-02.3 增加"篡改 payload → 401 且文案是验签失败"的断言（**未新增用例，54 个不变**） |

---

## 5 与冻结文档的偏差（已同步）

| 项 | 原冻结口径 | 现在 |
|---|---|---|
| Token 形态 | `UUID` 去横线 32 位随机串 | **HS256 JWT**（205 字符） |
| 是否用 JWT | 明确否决（`ARCHITECTURE §5.2` 旧版 / `ADR-04`） | **采用**，配 Redis 撤销名单；旧结论与理由保留在 §5.2 的变更说明里 |
| 依赖 | 无 JWT 库 | `jjwt 0.12.6`（Boot BOM 不管辖，显式版本） |
| 登录状态 | `login:token:*`（键 = 随机串） | `login:token:*`（键 = **JWT 原文**），语义与"退出立即失效""一键踢人"完全不变 |
