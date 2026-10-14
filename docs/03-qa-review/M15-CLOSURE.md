# M15 收口记录：登录 token 改造为 HS256 JWT（保留 Redis 可撤销）

> 收口日期：2026-10-14　|　机检：`verify-m15.ps1`（静态 13 + 运行时 7 = 20 项）
> 关联：老师《作业说明》「登录认证：密码加密存储；**JWT 登录**；登录状态保存在 **Redis**；退出后 Token **立即失效**」与注意事项「登录认证鉴权必须是真实的后端链路」
> 设计：`ARCHITECTURE §5.2`（已按新口径重写）、`ADR-04`

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
| **篡改 payload（签名不动）** | → **401**（验签拦下，未查 Redis） |
| 乱造 token | → **401** |
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

---

## 5 与冻结文档的偏差（已同步）

| 项 | 原冻结口径 | 现在 |
|---|---|---|
| Token 形态 | `UUID` 去横线 32 位随机串 | **HS256 JWT**（205 字符） |
| 是否用 JWT | 明确否决（`ARCHITECTURE §5.2` 旧版 / `ADR-04`） | **采用**，配 Redis 撤销名单；旧结论与理由保留在 §5.2 的变更说明里 |
| 依赖 | 无 JWT 库 | `jjwt 0.12.6`（Boot BOM 不管辖，显式版本） |
| 登录状态 | `login:token:*`（键 = 随机串） | `login:token:*`（键 = **JWT 原文**），语义与"退出立即失效""一键踢人"完全不变 |
