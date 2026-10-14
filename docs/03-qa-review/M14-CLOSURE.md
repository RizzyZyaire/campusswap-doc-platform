# M14 收口记录：操作日志（AOP 审计 + 查询接口 + 管理页）

> 收口日期：2026-10-14　|　机检：`verify-m14.ps1`（静态 21 + 数据库 3 + 接口 7）
> 关联：老师《作业说明》功能模块「操作日志：AOP 记录重要写操作，提供日志查询」
> 设计：`ARCHITECTURE §19`；接口：`API_SPECIFICATION §3.14 / §4.10`；字段：`GLOSSARY §3.10`；表：`sys_operation_log`（第 15 张）

---

## 1 交付物

| 层 | 文件 |
|---|---|
| 表结构 | `backend/sql/schema.sql` 第 15 节（19 列 + 主键 + 3 个查询索引；每列带中文 `COMMENT`） |
| 实体 | `entity/SysOperationLog.java`（`@SQLDelete` + `@SQLRestriction` 沿用全站软删除口径） |
| 切面 | `common/log/OperationLogAspect.java`（控制器包切点 + 只记 `POST/PUT/DELETE`；模块/动作推断；入参脱敏截断；失败留痕） |
| 注解 | `common/log/OperationLog.java`（`@OperationLog(module, action, params)`，可选、用于补中文名） |
| 落库 | `common/log/OperationLogWriter.java`（`@Async("operationLogExecutor")` 独立 Bean） |
| 线程池 | `config/AsyncConfig.java`（核心 1 / 最大 2 / 队列 1000 / `CallerRunsPolicy` / 关机等待 10s） |
| 查询 | `system/repository/OperationLogSpecifications.java`（Criteria 组合条件）、`service(+impl)`、`controller/OperationLogController.java` |
| DTO/VO | `system/dto/OperationLogPageDtoReq.java`、`system/vo/OperationLogVo.java` |
| 前端 | `types/log.ts`、`api/logs.ts`、`views/admin/OperationLogView.vue`；路由 `/admin/logs`；侧栏新增「系统运维 → 操作日志」（`PERM.sysCenter`） |
| 测试 | `Ac09OperationLogTest`（5 例：自动留痕 / 路径关键词动作 / 密码打码 / 失败留痕 / 读接口不记） |
| 机检 | `verify-m14.ps1`（A 静态 21 / B 数据库 3 / C 接口 7） |

---

## 2 核心设计（为什么这么做）

| 决策 | 选择 | 理由 |
|---|---|---|
| 切点 | **控制器包 + 写方法判定**（不是逐接口加注解） | 漏加注解就漏一条审计，这是审计最常见的失效方式；注解改为"可选补充中文名" |
| 中文模块/动作 | `@OperationLog` 优先，否则路径段 + 路径关键词 + HTTP 方法推断 | 自动覆盖全部写接口（实测 `/api/documents/12/publish` → 「文档 · 发布」），重点接口再用注解精修 |
| 记录范围 | 只记 `POST/PUT/DELETE` | 读接口记进来，日志表就退化成访问日志（也会被首页轮询刷爆） |
| 脱敏 | `password` / `token` / `secret` 字段一律 `***`，正文截断 1000 字符 | 日志不能成为凭据泄露面，也不能当数据仓库（实测登录日志：`{"username":"admin","password":"***"}`） |
| 失败留痕 | 异常原样抛出，`success=0` + 中文原因 | 审计要回答"谁尝试做了什么、成没成"；实测重复建标签 → 409 且日志记下"标签已存在，请直接选择" |
| 不影响业务 | `finally` 记录 + 整体 try/catch；落库异步（有界队列 + CallerRuns）；审计列显式赋值 | 日志功能故障绝不能让业务请求失败；异步线程没有 ThreadLocal，操作人必须从请求线程带过去 |
| 权限 | 沿用 `sys:center`（仅 SYS_ADMIN），**不新增权限点** | 保持 M0 冻结的 39 个权限点口径不变（机检 A17 守住）；审计是管理员只读视图 |
| 表数 | 14 → **15 张** | 老师明确要求"提供日志查询"，落库是自然做法；同步更新 GLOSSARY/PRD/ARCHITECTURE/CODE-TOUR 与机检基线 |

---

## 3 实测缺陷与修法（先失败后修）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 登录日志的操作人账号为空 | `LoginDtoReq` 是 **record**，访问器是 `username()` 而非 `getUsername()`，反射取不到 | 切面同时尝试 `getUsername` / `username` 两种访问器；实测登录日志现在记下 `admin` |
| 2 | 把 `PUT` 接口当 `POST` 调返回 **500**（"未预期的服务器异常"） | `HttpRequestMethodNotSupportedException` 落到兜底分支 | 新增 405 处理器，返回中文提示「请求方法 POST 不支持该接口，请改用 PUT/DELETE」 |
| 3 | `mvnw test` 编译失败：`new CategoryCreateDtoReq()` / `setName` 不存在 | 该 DTO 是 **record**（`name/parentId/sortOrder`） | 测试改用 record 构造器 |
| 4 | 单测"登录账号"用例失败（期望 `admin`，实得 null） | 用例里保留了 `SecurityContext.set(...)`，走不到"未登录"分支 | 用例先 `SecurityContext.clear()` |
| 5 | `verify-m2` C7「列注释缺失 19 个」 | 我用命令行 `CREATE TABLE` 建实表**没带列注释**（schema.sql 里有） | 改为用 `schema.sql` 原文 DDL 重建实表 → 列注释缺失 0 |
| 6 | `verify-m0/m2` 表数、`verify-m2` 自增表与审计列基线失败 | 新增第 15 张表必然触发（这正是机检的价值） | 基线 14 → 15、自增表 8 → 9、审计列表 8 → 9，并同步文档 |
| 7 | `verify-m14.ps1` 里出现中文（A2/A9/A17 断言） | 机检脚本必须纯 ASCII（PS 5.1 按 ANSI 读无 BOM 脚本，中文会静默失效） | 中文比较值改用码点构造（`[char]0x65B0 + [char]0x589E` = "新增"）；文件非 ASCII 字节 0 |

---

## 4 与冻结文档的偏差

| 项 | 原冻结口径 | 现在 | 理由 |
|---|---|---|---|
| 表数量 | 14 张 | **15 张**（+`sys_operation_log`） | 老师《作业说明》要求"提供日志查询"，必须落库；四份文档与 4 个机检基线已同步 |
| 权限点 | 39 个 | **39 个（不变）** | 复用 `sys:center`，不新增权限点（避免牵动 PRD §3.2 全清单与 5 处机检） |
| 接口总数 | 62 条 | **64 条**（+2） | `GET /api/logs`、`GET /api/logs/filters` |
| `verify-db-deep` 逻辑外键 | 14 组 | **15 组**（+`oplog.user`） | 新表 `user_id` 也要守"逻辑外键无孤儿" |

---

## 5 给老师手册的反馈

1. **AOP 记日志别用"逐接口加注解"**：漏一个就漏一条审计，而且新同学加接口时最容易忘。用**包级切点 + 写方法判定**，注解只做中文名的精修。
2. **异步落库要拆独立 Bean**：`@Async` 靠代理生效，同类内部调用**不会异步**（这是"加了 @Async 还是同步"的经典坑）。
3. **审计列在异步线程里必须显式赋值**：Spring Data JPA 的 `AuditingEntityListener` 依赖 `SecurityContext` 的 ThreadLocal，异步线程里拿不到操作人。
4. **入参一定要脱敏**：登录接口的 `password` 若原样落库，日志表本身就成了泄露渠道；本项目实测打码结果 `{"username":"admin","password":"***"}`。
