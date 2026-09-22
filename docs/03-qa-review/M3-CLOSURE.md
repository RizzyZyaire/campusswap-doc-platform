# M3 收口记录 —— 后端骨架 + RBAC

> 日期：2026-09-22　｜　里程碑：M3（`MASTER-PLAN.md` §7）　｜　状态：**已完成并验收**
> 全部结论都有可重跑的机检脚本或原始日志支撑；本文只写"实际跑出来的东西"，不写预期。

---

## 1. 交付物清单（本里程碑新增）

| 类别 | 内容 | 数量 |
|---|---|---|
| 构建 | `backend/pom.xml`（Spring Boot 4.1.1 / Java 17）、Maven Wrapper、`application{,-dev,-prod}.yml` | 5 |
| 公共层 | `common/api/{ResponseResult,PageVo,ErrorCode,PageDtoReq,AuditVo}`、`common/exception/{BusinessException,GlobalExceptionHandler}`、`common/security/{RequiresPermission,PermissionAspect,SecurityContext,LoginInterceptor,BearerToken,RedisKeys}`、`common/util/{IdUtil,TimeUtil,TxUtil}` | 16 |
| 配置 | `config/{JpaAuditConfig,RedisConfig,WebMvcConfig,CorsConfig}` | 4 |
| 实体 | `entity/`：`BaseEntity` + 14 实体 + 6 复合主键类 + 5 枚举 | 26 |
| 仓储 | `system/repository` 8 个 + `document/repository` 6 个（含 2 个 `*Specifications` 动态条件类） | 16 |
| 服务 | `system/service` 7 接口 + `system/service/impl` 7 实现 | 14 |
| 接口层 | `system/dto` 14 个 + `system/vo` 8 个 + `system/controller` 5 个（25 端点） | 27 |
| 机检 | `docs/03-qa-review/verify-m3.ps1`（36 项，静态）、`verify-m3-http.ps1`（122 项，接口） | 2 |
| 接口文档 | `docs/02-design/openapi-campusswap.json`（OpenAPI 3.0.3，25 端点 / 34 schema，供 Apifox 一键导入） | 1 |
| 说明 | 本文件 | 1 |

Java 源文件合计 **114 个 class**（`target/classes` 实测）。

---

## 2. 验收证据（全部可重跑）

| # | 检查 | 命令 | 结果 |
|---|---|---|---|
| 1 | 编译零错误 | `cd backend && .\mvnw.cmd -B clean compile` | `BUILD SUCCESS`，exit 0 |
| 2 | 静态自检 | `verify-m3.ps1` | **PASS=36 / FAIL=0** |
| 3 | 接口验收 | `verify-m3-http.ps1`（需先 `mvnw spring-boot:run`） | **PASS=122 / FAIL=0** |
| 4 | 实体 ↔ 实库结构一致 | `mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.jpa.hibernate.ddl-auto=validate"` | `Started CampusSwapApplication`（Hibernate 校验通过，14 实体全部对得上表） |
| 5 | 幂等性 | 连续两次运行 `verify-m3-http.ps1` | 两次都 122/0（脚本自带测试数据清理与复用） |

`verify-m3-http.ps1` 覆盖：认证 3 条、用户 6 条、角色 6 条、权限 4 条、部门 6 条的全部正常路径 + 401/403/404/409/400 边界 + 层级越级 + 环检测 + 内置角色保护 + 软删除后重建 + 授权即时生效。

---

## 3. SQL 条数实测（对账 `ARCHITECTURE §10.5`）

方法：dev 开 `show-sql`，请求前记录日志行数，请求后统计窗口内的 `Hibernate:` 语句条数。

| 接口 | 预算 | 实测 | 说明 |
|---|---|---|---|
| `GET /api/permissions/tree` | 1 | **1** | 一次查全 + 内存组树 |
| `GET /api/depts/tree` | 1 | **1** | 同上 |
| `GET /api/roles` | 1（原写 3，见 §5 修正） | **1** | 列表 VO 不含权限规模 |
| `GET /api/roles/{id}/permissions` | 2 | **2** | 角色存在性 + 关联表 |
| `GET /api/depts/{id}/roles` | 2 | **2** | 部门存在性 + 关联表 |
| `GET /api/users?pageSize=100`（末页） | 4 | **4** | 末页跳过 count |
| `GET /api/users?pageSize=1`（满页） | 4 | **5** | 多出的是 Spring Data 的分页 count |
| `POST /api/auth/login` | —（无预算） | **6**（冷权限缓存）/ 5（热） | 查用户 + 查用户(更新时间) + 更新 + 部门 + 角色 + 权限合并 |
| `GET /api/auth/me` | —（无预算） | **3** | 用户 + 部门 + 角色（权限走缓存） |

**关键证据（证明不是循环查询）**：`pageSize` 从 1 到 100 条数不变，且批量补名的 SQL 实际形态是

```sql
select ur1_0.role_id, ur1_0.user_id, ur1_0.created_at
  from sys_user_role ur1_0 where ur1_0.user_id in (?, ?, ?, ?, ?)
```

—— 一条 `IN` 查询替代 N 条单查（课件 3.1 红线二）。全仓 `EAGER` 计数 **0**（`verify-m3.ps1` C7b 机检）。

---

## 4. 本轮实测发现的缺陷（含复现路径）

> 这几个都不是"写完就通过"，而是**先失败、定位、修复、再验证**的过程。

### 缺陷 1：软删除行占着唯一索引 → 重建同名编码 500
- **复现**：建角色 `code=INTERN` → 删除 → 再建 `code=INTERN` → 500。
- **根因**：唯一索引 `uk_sys_role_code` 不含 `deleted`；Service 的 `existsByCode` 被 `@SQLRestriction("deleted = 0")` 过滤，看不到已删行，于是前置校验通过、数据库唯一索引报错 → 通用 500。
- **修复**：`@SQLDelete` 改为 `UPDATE sys_role SET deleted = 1, code = CONCAT(LEFT(code,30),'#del#',id) WHERE id = ?`（`sys_permission` 同理），删除即释放唯一键，同时保留可追溯性。
- **验证**：连续两次运行验收脚本，角色/权限创建均 200；库内已删行的编码形如 `M3_TMP_ROLE#del#8`。
- **落文档**：`ARCHITECTURE §10` 规约 18。

### 缺陷 2：树形子孙查询用裸前缀 LIKE 会误判
- **风险路径**：`WHERE ancestors LIKE '0,1%'` 会把 `'0,10'`（根级 10 号节点的子树）当成 `'0,1'` 的后代。本项目权限点 id 恰好是 **1 / 10 / 100 段位复用**，一旦出现 id 以 1 开头的第二个根节点就必然误判。
- **修复**：`ancestors = :path OR ancestors LIKE CONCAT(:path, ',%')`（完整路径段匹配），`PermissionRepository` / `DeptRepository` 已按此实现；`schema.sql` 的示例注释同步改写。
- **落文档**：`ARCHITECTURE §10` 规约 19。

### 缺陷 3：登出被拦截器挡成 401，不满足"重复登出幂等"
- **复现**：登录 → 登出（预期 200）→ 实际 401。
- **根因**：`/api/auth/logout` 走拦截器时，第二次调用 token 已不存在 → 拦截器先返回 401，Controller 根本没被调用；且 Controller 从 `SecurityContext` 取 token 也取不到（拦截器未放行）。
- **修复**：把 logout 加入拦截器排除名单，Controller 用 `BearerToken.parse(Authorization 头)` 取 token；请求头为空才 401。
- **验证**：`logout.first=200` / `logout.second=200` / 登出后旧 token 调 `/api/auth/me` = 401。

### 缺陷 4：参数错误提示泄露 Spring 英文原文与内部类名
- **复现**：`GET /api/users?status=WRONG` 返回
  `Failed to convert property value of type 'java.lang.String' to required type 'com.campusswap.entity.enums.UserStatus' ...`
- **根因**：`@ModelAttribute` 的枚举绑定失败抛 `MethodArgumentNotValidException`，处理器直接取了 `getDefaultMessage()`（Spring 英文原文）。
- **修复**：处理器先判 `FieldError.isBindingFailure()`，反射取 DTO 字段类型，枚举则输出「参数取值非法：status（可选值 ACTIVE / LOCKED / DISABLED）」；请求体侧同样翻译 Jackson 的 `MismatchedInputException`。
- **验证**：四条错误路径全部返回纯中文提示，无英文、无内部类名。

### 缺陷 5：编辑用户接口对 `username`/`password` 静默忽略
- **复现**：`PUT /api/users/{id}` 带 `"username":"hacker"` → 200（字段被丢弃，未改动数据）。
- **问题**：API_SPECIFICATION §4.2.4 要求 400。
- **修复**：DTO 增加两个 `@Null(message="登录名与密码不可通过本接口修改")` 组件专门承接这两个字段（不打开全局 `FAIL_ON_UNKNOWN_PROPERTIES`，避免影响其它接口的扩展字段）。
- **验证**：带 `username` / `password` → 400 + 约定文案；不带 → 200；库内 `username` 仍为 `u_m3test`。

**附带发现**：全局异常处理器原先没有兜 `DataIntegrityViolationException`，并发窗口下的唯一键冲突会变成 500 —— 已加为 409。

> **注**：上述 5 个缺陷都不是"设计时想到的"，而是**先跑出失败、再定位、再修、再复验**。
> 第 5 个与"未知字段"那条还顺带说明了另一件事：**不要用全局 `FAIL_ON_UNKNOWN_PROPERTIES` 图省事** ——
> 一旦打开，M4 文档接口（提交体可能带 `id` 等扩展字段）会被一起拒掉。

---

## 5. 文档同步（口径修正，均已改到文档）

| 文档 | 位置 | 修正 |
|---|---|---|
| `MASTER-PLAN.md` | T3.3 | 取消 `SnowflakeConfig`（M1 已冻结 `BIGINT AUTO_INCREMENT`，不需要雪花 ID） |
| `MASTER-PLAN.md` | T3.6 | 范围澄清：文档域 DTO/VO 随 M4 落地 |
| `MASTER-PLAN.md` | T3.10 验证块 | 原写 `localhost:10086/backend/...`（那是老师练习工程），改为本工程 10087 + 两个机检脚本 |
| `MASTER-PLAN.md` | T4.4 / T4.6 / T4.11 | 子孙查询改完整路径段匹配；阅读量改「SETNX 去重 + `view_count` 原子自增」；SQL 预算补 count 说明 |
| `ARCHITECTURE.md` | §3.2 | 包结构补实际类（`PageDtoReq`/`AuditVo`/`BearerToken`/`RedisKeys`/`IdUtil`/`TimeUtil`/`TxUtil`/`*Specifications`/6 个主键类） |
| `ARCHITECTURE.md` | §5.1 | 拦截器排除名单补 `/api/auth/logout` + 说明为什么 |
| `ARCHITECTURE.md` | §9 | `SUCCESS` 文案 `操作成功` → `成功`（对齐 API_SPECIFICATION §2.1 示例）；异常处理表补 5 行（Bind / HandlerMethodValidation / DataIntegrity / 枚举提示 / 未知字段） |
| `ARCHITECTURE.md` | §10 | 新增规约 18（软删除 + 唯一索引）、19（路径段匹配） |
| `ARCHITECTURE.md` | §10.5 | `/api/roles` 预算 3 → 1；新增「分页 count 查询」说明（末页跳过，实测 4~5 条） |
| `API_SPECIFICATION.md` | §2.4 / §4.1.2 | 登出"写黑名单" → 直接 `DEL` token（与 ARCHITECTURE §5.2/§7 一致） |
| `schema.sql` | §4 / §11 / 附例 | 裸 `LIKE '0,1%'` 示例改为完整路径段匹配，并写明误判原因 |
| `MASTER-PLAN.md` | 附录 D | 新增 v2.3 变更行 |

---

## 6. 与冻结文档的偏差（诚实清单）

| 偏差 | 文档口径 | 实际实现 | 影响 |
|---|---|---|---|
| 未知字段处理 | 仅 §4.2.4 要求"带 username/password 要 400" | 该接口 400 ✓；**其它接口的未知字段仍被忽略** | 无（未开全局严格模式，避免影响后续接口的扩展字段） |
| `UserUpdateDtoReq.roles` 省略语义 | 文档只写"传空数组 = 清空" | 省略（null）= 不改角色；空数组 = 清空 | 更安全；已在实现注释与本表登记 |
| 权限节点编辑后的缓存清理 | §4.4.3 写"修改后清空受影响用户的权限缓存" | **不清理**：权限码本身没变（只有名称/层级/路由变），有效权限集合不变 | 避免无意义的全量清缓存；如需强制生效可重启或改权限点编码 |
| 权限点删除的"清理残留关联" | §4.4.4 既要求"被引用时 409"又要求"删除后清理关联" | 按 409 拒绝（有关联就不给删），因此清理语句是空操作 | 两条要求互斥，取更安全的一条；已登记 |
| 登录时的审计人 | 审计列由 Auditing 填当前操作人 | 登录成功写 `last_login_at` 时上下文尚未建立，`updated_by` 记 0（系统） | 语义可解释（0 = 系统写入，与 DDL 默认值一致） |

---

## 7. 顺延 / 未做项

| 项 | 原因 | 去向 |
|---|---|---|
| 文档域 DTO/VO 与 30 条接口 | 属 M4 范围（本文档 §5 已澄清） | M4 |
| `T4.11` 零 N+1 逐接口点数（文档列表投影） | 需文档接口落地 | M4 |
| Apifox 项目内人工核对 | 需要人工在 Apifox 里点「导入 → 选择 `openapi-campusswap.json`」并试发一次请求 | 用户在 Apifox 中执行（文件已生成并通过 JSON 校验） |
| M4 里程碑 | — | 下一步 |

---

## 8. 数据卫生（验收后已复原）

M3 验收会往库里写测试数据（测试用户、临时角色/权限/部门，删除走软删除留痕）。验收结束后已**用 `schema.sql` + `data.sql` 重建 `campusswap_db`**，把库恢复成"种子数据唯一真源"的状态：

- 重建前实测：`sys_user` 5 行（3 种子 + 2 测试）、`sys_role` 10 行（3 存活 + 7 软删除）、`sys_permission` 53 行（39 存活 + 14 软删除）、`doc_*` 六张表与种子完全一致；
- 重建后：`verify-m2.ps1` **16/16 全绿**、`verify-db-deep.ps1` **9/9 全绿**（39 权限 / 3 角色 11·20·39 / 3 部门 / 3 用户 / 4 分类 / 5 标签 / 5 文档 / 9 版本 / 3 收藏）。
- 说明：重建同时也验证了 `schema.sql` + `data.sql` **可重复执行**（这是 M2 交付物的隐含要求，此前没专门验过）。

---

## 9. 复现步骤（从零到验收）

```powershell
# 0. 环境：MySQL80 / Redis 服务已启动（D:\DevEnv\scripts\start-all.cmd）
cd D:\DevEnv\projects\campusswap\backend

# 1. 编译
$env:JAVA_HOME='D:\DevEnv\02_JDK\jdk-17.0.5'
.\mvnw.cmd -B clean compile

# 2. 静态自检（无需起服务）
powershell -NoProfile -ExecutionPolicy Bypass -File ..\docs\03-qa-review\verify-m3.ps1

# 3. 起服务（另开一个窗口）
.\mvnw.cmd spring-boot:run

# 4. 接口验收（122 项）
powershell -NoProfile -ExecutionPolicy Bypass -File ..\docs\03-qa-review\verify-m3-http.ps1
```

> 注意：跑 `clean` 之前先停掉 `spring-boot:run`，否则 `target` 下的日志文件被占用会导致 `Failed to clean project`。
> 建议把运行日志写到 `%TEMP%` 而不是 `target/` 下。
> 验收完想复原数据：`mysql -uroot -p --default-character-set=utf8mb4 -e "SOURCE backend/sql/schema.sql"` 再 `mysql -uroot -p campusswap_db -e "SOURCE backend/sql/data.sql"`（见 §8）。
