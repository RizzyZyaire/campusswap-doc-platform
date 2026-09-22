# CampusSwap 文档管理平台 · 接口规格说明书（API_SPECIFICATION）

| 项 | 值 |
|---|---|
| 文件 | `docs/02-design/API_SPECIFICATION.md` |
| 版本 | v1.0 |
| 日期 | 2026-09-21 |
| 状态 | **Frozen（已冻结）** |
| 上游依据 | **`GLOSSARY.md` v2.1（命名与类型唯一真源，冲突时一律以它为准）**、`PRD.md`（§3.2 权限点 / §4 状态机 / §6 业务规则与错误码）、`USER_STORIES.md`（US-01~US-08 / 24 条 BDD）、`MASTER-PLAN.md` §5.5（注解式鉴权） |
| 下游消费 | `backend/src/main/java/com/campusswap/{controller,dto,vo}`、`frontend/src/api/`、Apifox 接口集合、M3~M5 的接口测试 |
| 覆盖范围 | **55 个接口**，覆盖 9 个模块与全部 8 个用户故事 |

**口径声明（必读）**：本文件所有字段名、类型、枚举取值一律取自 `GLOSSARY.md` v2.1。`PRD.md` / `USER_STORIES.md` / `MASTER-PLAN.md §5.2 §5.3` 中仍残留若干**已作废的旧写法**，迁移对照表见 **附录 A**；凡旧写法与本文件冲突，一律以本文件为准。替换只改字段名与类名，**不改接口形状与语义**。

---

## 1. 元信息

| 项 | 值 |
|---|---|
| 基础路径 | `/api`（完整路径 = `/api` + 本文件路径列，例如 `/api/documents/{id}/publish`） |
| 请求内容类型 | `application/json;charset=UTF-8`；唯一例外 `POST /api/upload/image` 为 `multipart/form-data` |
| 响应内容类型 | `application/json;charset=UTF-8` |
| 鉴权方式 | 请求头 `Authorization: Bearer <token>` |
| 主键形态 | **JSON 中一律为字符串**（后端 `Long` + `@JsonSerialize(using = ToStringSerializer.class)`，GLOSSARY §1.1） |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`，时区 `Asia/Shanghai`（BR-22） |
| 金额格式 | `priceCents`：整数，单位「分」；0 = 免费（BR-17） |

---

## 2. 通用约定

### 2.1 统一响应体 `ResponseResult<T>`

```java
public record ResponseResult<T>(int code, String message, T data) { }
```

**无时间戳字段**（GLOSSARY §1「统一响应」行规定结构为 `{ code, message, data }`；MASTER-PLAN §5.2 的旧命名已作废，见附录 A）。

成功响应（HTTP 200）：

```json
{
  "code": 200,
  "message": "成功",
  "data": {}
}
```

失败响应（HTTP 状态码与 `code` 保持一致，BR-23）：

```json
{
  "code": 403,
  "message": "无权限执行该操作",
  "data": null
}
```

- `data` 为业务数据；无返回内容的接口（新增/修改/删除类）`data` 为 `null`。
- 参数校验失败时 `message` 为**中文提示**，取自 Jakarta Validation 注解的 `message` 属性，多字段同时失败时按字段顺序返回第一条。

### 2.2 分页 `PageVo<T>`

```json
{
  "list": [],
  "total": 0,
  "pageNum": 1,
  "pageSize": 10
}
```

- 所有列表接口一律返回 `PageVo<T>`，**不返回裸数组**（BR-05、NFR-P4）。
- 树形接口（权限树/部门树/分类树）返回数组，不分页。

### 2.3 类型与时区铁律

| 场景 | 规则 |
|---|---|
| 所有 ID（主键、`deptId`、`categoryId`、`tagIds`、`roleIds`…） | JSON 字符串，如 `"1001"` |
| 时间 | `yyyy-MM-dd HH:mm:ss`，如 `"2026-09-21 14:08:31"` |
| 枚举 | 大写字面量字符串：`DRAFT`/`PUBLISHED`/`ARCHIVED`/`TRASH`、`ACTIVE`/`LOCKED`/`DISABLED`、`DIR`/`MENU`/`BUTTON` |
| 标记位 | 数字 `0`/`1`（如 `isBuiltin`），**不是** `true`/`false` |
| 派生布尔 | 仅 VO 字段用布尔：`canEdit`、`favorited`、`confirm` |
| 计数与版本号 | 数字：`viewCount`、`favoriteCount`、`versionNum`、`useCount` |
| VO 出参（实体型） | 一律继承基类 `AuditVo`（`createdAt` / `createdBy` / `updatedAt` / `updatedBy`），见 §2.7.1 |
| VO 出参中的逻辑删除 | **任何 VO 都不得出现 `deleted` 字段**：逻辑删除是持久层细节（`@SQLDelete` + `@SQLRestriction`），对前端完全不可见 |
| 权限 / 部门 / 分类节点 | 三类节点**没有启停列**（GLOSSARY v2.1 的 `sys_permission` / `sys_dept` / `doc_category` 均无启停字段，见附录 A 对照），下线方式**只有删除**；因此相关接口不提供任何 enable / disable 字段或子资源 |

### 2.4 鉴权与权限校验

| 标注 | 含义 | 实现 |
|---|---|---|
| `公开` | 无需请求头 | 网关放行；Controller 不做 token 校验 |
| `登录即可` | 只校验 token 有效，不校验权限点 | 拦截器校验 token → 注入 `currentUserId` |
| `权限点` | 校验 token **且** 校验权限码 | `@RequiresPermission("<权限码>")` + `PermissionAspect`（MASTER-PLAN §5.5） |

- token 有效期 2 小时；登出直接 `DEL login:token:{token}` 并把它从 `user:tokens:{userId}` 集合里移除（**不写黑名单** —— 删掉即失效，黑名单只会多占一份内存；见 ARCHITECTURE §5.2/§7）。
- 权限码集合来源：`sys_user_role` + `sys_role_permission` + `sys_dept_role` + `sys_user_permission` 合并去重，缓存 key `perm:user:{userId}`，TTL 30 分钟，授权变更即时失效（BR-18）。
- **越权一律 403 `NO_PERMISSION`**；涉及已存在资源的读写接口，Service 层必须再做归属校验（防 IDOR，BR-21）。

### 2.5 错误码表（引用 PRD §6.1 + GLOSSARY §4.2 `ErrorCode`）

| code | 枚举名 | 语义 | HTTP | 典型触发 |
|---|---|---|---|---|
| 200 | `SUCCESS` | 成功 | 200 | — |
| 400 | `BAD_REQUEST` | 参数校验失败 / 业务规则拒绝 | 400 | 标题超长、分页越界、驳回无理由 |
| 401 | `UNAUTHORIZED` | 未登录、token 缺失/失效/已登出 | 401 | 未带 `Authorization` |
| 403 | `NO_PERMISSION` | 已登录但缺权限点，或非资源属主 | 403 | 改他人文档、非管理员调审核 |
| 403 | `USER_DISABLED` | 账号状态非 `ACTIVE`（`LOCKED`/`DISABLED`） | 403 | 停用账号登录 |
| 404 | `NOT_FOUND` | 资源不存在或已被彻底删除 | 404 | 文档 ID 不存在 |
| 409 | `CONFLICT_STATUS` | 状态冲突 / 唯一约束冲突 | 409 | 重复发布、归档文档被编辑、工号重复 |
| 500 | `SERVER_ERROR` | 服务器内部错误（不暴露堆栈） | 500 | 未捕获异常 |

### 2.6 列表接口公共查询字段（GLOSSARY §3.6，前端 TS 同名）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `pageNum` | number | ≥ 1，默认 1 | 页码 |
| `pageSize` | number | 1–100，默认 10 | 每页条数；越界返回 400（BR-05） |
| `keyword` | string | ≤ 64 字符，可空 | 关键词，匹配标题/摘要（用户与角色接口匹配 `username`/`realName`/`name`） |
| `status` | string | 可空 | 状态筛选，取值同对应枚举 |
| `categoryId` | string | 可空 | 分类筛选 |
| `tagIds` | string[] | 可空 | 标签筛选（多标签 **AND** 命中，需同时包含全部所选标签） |
| `deptId` | string | 可空 | 部门筛选 |
| `startTime` | string | 可空，`yyyy-MM-dd HH:mm:ss` | 起始时间（闭区间），按 `created_at` 过滤 |
| `endTime` | string | 可空，`yyyy-MM-dd HH:mm:ss` | 结束时间；与 `startTime` 成对使用，`startTime > endTime` 返回 400「开始时间不能晚于结束时间」 |
| `sort` | `DocumentSort` | 可空，默认 `updatedAt_desc` | 列表排序方式，取值 `updatedAt_desc` / `publishAt_desc` / `viewCount_desc` |

> **排序与命名说明（与前端规范对齐）**：列表排序统一用**单个** `sort` 字段（类型 `DocumentSort`，取值 `updatedAt_desc` / `publishAt_desc` / `viewCount_desc`，默认 `updatedAt_desc`）。`sortOrder` 在本文件中**只有一个语义**——角色 / 权限 / 分类 / 部门 / 标签等实体 VO 的**排序号**（数据库列 `sort_order`，数字），与列表排序无关，二者不得混用。
> 本节字段清单与 `GLOSSARY.md` §3.6 逐行一致；出参 VO 与 DTO 的字段级真源是 `GLOSSARY.md` **§3.7**（含 `authorId` / `operatorId` 等展示层命名 ↔ 数据库列的映射表）。

### 2.7 公共 VO 字典（§4 各接口出参字段的唯一定义处）

> 约定：§4 中接口**首次**出现的 VO 给出完整字段表；其后同 VO 的接口以字段清单引用，字段名与本字典逐字一致。

#### 2.7.1 通用包装

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| `ResponseResult<T>` | `code` | number | 状态码，200 = 成功 |
| | `message` | string | 提示信息（中文） |
| | `data` | T | 业务数据，可为 `null` |
| `PageVo<T>` | `list` | T[] | 当前页数据 |
| | `total` | number | 总条数 |
| | `pageNum` | number | 当前页码 |
| | `pageSize` | number | 每页条数 |
| `PageDtoReq` | `pageNum` | number | 页码，≥ 1，默认 1 |
| | `pageSize` | number | 每页条数，1–100，默认 10 |
| `AuditVo` | `createdAt` | string | 创建时间 |
| | `createdBy` | string | 创建人用户 ID（字符串） |
| | `updatedAt` | string | 最后修改时间 |
| | `updatedBy` | string | 最后修改人用户 ID（字符串） |

**VO 继承与出参铁律（三条）**

1. **实体型 VO 统一继承 `AuditVo`**：`UserVo`、`UserInfoVo`、`RoleVo`、`PermissionVo`、`DeptVo`、`DocumentVo`（含子类 `DocumentDetailVo`）、`CategoryVo`、`TagVo`、`DocumentVersionVo`。它们自动携带 `createdAt`、`createdBy`、`updatedAt`、`updatedBy` 四个审计字段，**下文字段表中不再逐行重复**，仅在继承行标注。
2. **任何 VO 都不得出现 `deleted` 字段**：软删除只在持久层生效（`deleted = 1`），接口层永不暴露。界面需要区分「已删除」时，用业务状态表达（如 `DocumentStatus.TRASH`），不得新增删除标记字段。
3. **组合 / 派生 VO 不继承 `AuditVo`**：`LoginVo`、`RolePermissionVo`、`DeptRoleVo`、`FavoriteVo`、`ImageVo`、`StatVo`、`PageVo<T>`、`ResponseResult<T>` —— 它们是跨表组合或纯计算结果，没有单一实体归属。
4. **展示层命名与数据库列分离**（GLOSSARY §3.7 映射表）：文档作者在 VO 中用 `authorId` + `authorName`，版本操作人用 `operatorId` + `operatorName`，二者都对应数据库列 `created_by`（**不是新增列**）；`deptName`、`categoryName` 只存在于 VO，不落库。禁止把 `authorId` / `operatorId` 用作实体字段或列名。

> 示例阅读约定：**树形示例**（`PermissionVo` / `DeptVo` / `CategoryVo`）为保持层级可读，只展示业务字段与 `children`；实际响应同样包含继承自 `AuditVo` 的四个审计字段。

#### 2.7.2 用户域（`sys_user` / `sys_dept` / `sys_role` / `sys_permission`）

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| `LoginVo` | `token` | string | 访问令牌，后续放 `Authorization: Bearer`（有效期 2 小时，见 BR-19） |
| | `userInfo` | UserInfoVo | 用户信息（该 VO 继承 `AuditVo`） |
| | `roles` | string[] | 角色编码集合，如 `["STAFF"]`（与 `userInfo.roles` 同值，便于前端直接取用） |
| | `permissions` | string[] | 权限码集合，前端按钮级控制用（与 `userInfo.permissions` 同值） |
| `UserInfoVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段 |
| | `id` | string | 用户 ID |
| | `username` | string | 登录名（工号） |
| | `realName` | string | 真实姓名 |
| | `deptId` | string | 部门 ID |
| | `deptName` | string | 部门名称 |
| | `roles` | string[] | 角色编码集合，如 `["STAFF"]` |
| | `avatarUrl` | string | 头像相对 URL，可空 |
| | `permissions` | string[] | 权限码集合 |
| `UserVo` | **继承 `AuditVo`** | — | 本 VO 另有 `createdAt` / `createdBy` / `updatedAt` / `updatedBy` |
| | `id` | string | 用户 ID |
| | `username` | string | 登录名（工号） |
| | `realName` | string | 真实姓名 |
| | `deptId` | string | 部门 ID |
| | `deptName` | string | 部门名称 |
| | `email` | string | 邮箱，可空 |
| | `phone` | string | 手机号，可空 |
| | `avatarUrl` | string | 头像相对 URL，可空 |
| | `status` | UserStatus | `ACTIVE` / `LOCKED` / `DISABLED` |
| | `roles` | string[] | 角色编码集合 |
| | `lastLoginAt` | string | 最后登录时间，可空 |
| `RoleVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段 |
| `RoleVo` | `id` | string | 角色 ID |
| | `name` | string | 角色名称 |
| | `code` | RoleCode | 角色编码，创建后不可改 |
| | `description` | string | 角色描述，可空 |
| | `isBuiltin` | number | 1 = 内置角色（不可删除） |
| | `sortOrder` | number | 排序号（升序） |
| | `—` | — | 角色权限规模不在此 VO 中返回，需按角色查询 4.3.5 |
| `RolePermissionVo` | `roleId` | string | 角色 ID |
| | `permissionIds` | string[] | 该角色已勾选的权限 ID 集合 |
| `PermissionVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段 |
| | `id` | string | 权限 ID |
| | `name` | string | 权限名称（中文） |
| | `code` | string | 权限编码，如 `doc:publish` |
| | `type` | PermType | `DIR` / `MENU` / `BUTTON` |
| | `parentId` | string | 父节点 ID，根为 `"0"` |
| | `ancestors` | string | 祖级路径，如 `"0,1,10"` |
| | `path` | string | 前端路由，目录/菜单层用，可空 |
| | `icon` | string | 前端图标名，可空 |
| | `sortOrder` | number | 同级排序号 |
| | `children` | PermissionVo[] | 子节点（叶子为空数组） |
| `DeptVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段 |
| | `id` | string | 部门 ID |
| | `name` | string | 部门名称 |
| | `parentId` | string | 父部门 ID，根为 `"0"` |
| | `ancestors` | string | 祖级路径 |
| | `sortOrder` | number | 同级排序号 |
| | `children` | DeptVo[] | 子部门（叶子为空数组） |
| `DeptRoleVo` | `deptId` | string | 部门 ID |
| | `roleIds` | string[] | 该部门已绑定角色 ID 集合 |

#### 2.7.3 文档域（`doc_document` / `doc_version` / `doc_category` / `doc_tag` / `doc_favorite`）

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| `DocumentVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段，并额外提供 `authorId` / `authorName` |
| | `id` | string | 文档 ID |
| | `title` | string | 标题 |
| | `summary` | string | 摘要，可空 |
| | `categoryId` | string | 分类 ID，`"0"` = 未分类 |
| | `categoryName` | string | 分类名称，未分类时为「未分类」 |
| | `authorId` | string | **文档作者 ID**（展示层命名，对应数据库列 `created_by`；与继承自 `AuditVo` 的 `createdBy` 同值同源，见 §2.7.1 铁律 4） |
| | `authorName` | string | 作者姓名 |
| | `status` | DocumentStatus | `DRAFT` / `PUBLISHED` / `ARCHIVED` / `TRASH` |
| | `versionNum` | number | 版本号，从 1 起 |
| | `priceCents` | number | 价格标记（分），0 = 免费 |
| | `viewCount` | number | 阅读量 |
| | `favoriteCount` | number | 收藏数 |
| | `canEdit` | boolean | 当前用户是否可编辑（Service 计算：属主且状态为 `DRAFT`/`PUBLISHED`） |
| `DocumentDetailVo` | **继承 `DocumentVo` 全部字段** | — | 详情页专用，额外字段如下 |
| | `contentMd` | string | Markdown 正文 |
| | `derivedFromId` | string \| null | 派生来源文档 ID，原创为 `null` |
| | `rejectReason` | string \| null | 驳回理由，无驳回为 `null` |
| | `favorited` | boolean | 当前用户是否已收藏 |
| | `tags` | TagVo[] | 标签集合（最多 5 个） |
| `TagVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段 |
| | `id` | string | 标签 ID |
| | `name` | string | 标签名 |
| | `useCount` | number | 被引用文档数 |
| `CategoryVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段 |
| | `id` | string | 分类 ID |
| | `name` | string | 分类名称 |
| | `parentId` | string | 父分类 ID，根为 `"0"` |
| | `ancestors` | string | 祖级路径 |
| | `sortOrder` | number | 同级排序号 |
| | `children` | CategoryVo[] | 子分类（叶子为空数组） |
| `DocumentVersionVo` | **继承 `AuditVo`** | — | 本 VO 另有四个审计字段，并额外提供 `operatorId` / `operatorName` |
| | `id` | string | 版本记录 ID |
| | `documentId` | string | 文档 ID |
| | `versionNum` | number | 版本号 |
| | `title` | string | 该版本标题快照 |
| | `contentMd` | string | 该版本正文快照 |
| | `changeType` | ChangeType | `CREATE`/`EDIT`/`PUBLISH`/`AUDIT`/`REJECT`/`ARCHIVE`/`RESTORE`/`DELETE`/`DERIVE` |
| | `changeRemark` | string | 变更备注 / 审核意见，可空 |
| | `operatorId` | string | **操作人 ID**（展示层命名，对应数据库列 `created_by`，见 §2.7.1 铁律 4） |
| | `operatorName` | string | 操作人姓名 |
| `FavoriteVo` | `documentId` | string | 文档 ID |
| | `favorited` | boolean | 操作后是否已收藏 |
| | `favoriteCount` | number | 操作后文档收藏数 |

#### 2.7.4 文件与统计

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| `ImageVo` | `url` | string | 图片相对 URL，如 `/uploads/2026/09/8f3c1a2b.png` |
| `StatVo` | `myDocumentCount` | number | 我的文档总数（不含回收站） |
| | `myFavoriteCount` | number | 我的收藏数 |
| | `publishedCount` | number | 平台已发布文档数 |

---

## 3. 接口总表（55 条）

> 「用途」列说明该接口**解决什么问题、为哪个页面/故事服务**。

### 3.1 认证（3）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 1 | 认证 | POST | `/api/auth/login` | 一次请求换取 token + 用户信息 + 权限码集合，前端据此渲染菜单与按钮，避免每次操作都回查权限 | 公开 | `LoginDtoReq` | `LoginVo` | 400 / 401 / 403 |
| 2 | 认证 | POST | `/api/auth/logout` | 让当前 token 立即进入黑名单失效，解决公用电脑上「关掉页面后 token 仍可被复用」的隐患 | 登录即可 | 无 | `Void` | 401 |
| 3 | 认证 | GET | `/api/auth/me` | 刷新页面后重建用户态与权限码（授权变更后无需重新登录即可拿到新权限） | 登录即可 | 无 | `UserInfoVo` | 401 |

### 3.2 用户管理（6）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 4 | 用户 | GET | `/api/users` | 按关键词/部门/状态分页查人，让系统管理员定位账号并进入编辑；响应不含 `passwordHash` | `sys:user` | `UserPageDtoReq` | `PageVo<UserVo>` | 400 / 401 / 403 |
| 5 | 用户 | POST | `/api/users` | 新员工入职建档：一次绑定部门与角色，账号立即可登录并继承部门权限 | `sys:user:add` | `UserCreateDtoReq` | `UserVo` | 400 / 401 / 403 / 409 |
| 6 | 用户 | GET | `/api/users/{id}` | 打开编辑前取单个账号的完整信息（含角色集合），避免前端从列表缓存里猜 | `sys:user` | 无 | `UserVo` | 401 / 403 / 404 |
| 7 | 用户 | PUT | `/api/users/{id}` | 修改姓名/部门/角色；换部门后权限按新部门即时重算，组织调整无需重建账号 | `sys:user:edit` | `UserUpdateDtoReq` | `UserVo` | 400 / 401 / 403 / 404 |
| 8 | 用户 | PUT | `/api/users/{id}/status` | 停用或冻结账号并**立即踢下线**（清 token 与权限缓存），离职与异常账号即刻失效 | `sys:user:disable` | `UserStatusDtoReq` | `UserVo` | 400 / 401 / 403 / 404 |
| 9 | 用户 | PUT | `/api/users/{id}/password` | 管理员替忘记密码的员工重置密码，重置后该用户所有在线 token 失效 | `sys:user:reset` | `UserPasswordDtoReq` | `Void` | 400 / 401 / 403 / 404 |

### 3.3 角色管理（6）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 10 | 角色 | GET | `/api/roles` | 列出全部角色及各自权限点数量，让授权页知道「要改哪个角色、它现在多大权限」 | `sys:role` | `RolePageDtoReq` | `PageVo<RoleVo>` | 400 / 401 / 403 |
| 11 | 角色 | POST | `/api/roles` | 新增自定义角色（如「实习生」「外部协作」），把权限集合从代码常量里解放出来 | `sys:role:add` | `RoleDtoReq` | `RoleVo` | 400 / 401 / 403 / 409 |
| 12 | 角色 | PUT | `/api/roles/{id}` | 改角色名称与描述；`code` 必须与现有值一致（不可改），避免鉴权口径被悄悄改坏 | `sys:role:edit` | `RoleDtoReq` | `RoleVo` | 400 / 401 / 403 / 404 / 409 |
| 13 | 角色 | DELETE | `/api/roles/{id}` | 删除废弃角色；被用户引用或被部门绑定时拒绝删除，防止权限悬空 | `sys:role:delete` | 无 | `Void` | 401 / 403 / 404 / 409 |
| 14 | 角色 | GET | `/api/roles/{id}/permissions` | 读取角色已勾选的权限 ID 集合，让权限树回显当前授权状态 | `sys:role` | 无 | `RolePermissionVo` | 401 / 403 / 404 |
| 15 | 角色 | PUT | `/api/roles/{id}/permissions` | 覆盖式保存角色权限，保存后清空该角色下所有用户的权限缓存 → **授权即时生效** | `sys:role:grant` | `RolePermissionDtoReq` | `Void` | 400 / 401 / 403 / 404 |

### 3.4 权限管理（4）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 16 | 权限 | GET | `/api/permissions/tree` | 一次取回三层权限树（目录→菜单→按钮），前端直接渲染勾选树，省掉 N 次请求 | `sys:perm` | 无 | `PermissionVo[]` | 401 / 403 |
| 17 | 权限 | POST | `/api/permissions` | 新增权限节点（新页面/新按钮），新功能可纳入 RBAC 而不用改代码常量 | `sys:perm:add` | `PermissionCreateDtoReq` | `PermissionVo` | 400 / 401 / 403 / 409 |
| 18 | 权限 | PUT | `/api/permissions/{id}` | 改权限名称/类型/父节点/排序，支持子树移动；禁止移到自身子孙下形成环 | `sys:perm:edit` | `PermissionUpdateDtoReq` | `PermissionVo` | 400 / 401 / 403 / 404 / 409 |
| 19 | 权限 | DELETE | `/api/permissions/{id}` | 下线废弃权限点；有子节点或被角色引用时拒绝删除，杜绝悬空权限码 | `sys:perm:delete` | 无 | `Void` | 401 / 403 / 404 / 409 |

### 3.5 部门管理（6）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 20 | 部门 | GET | `/api/depts/tree` | 取回部门树与各部门人数，同时供组织维护页与用户表单的部门下拉使用 | `sys:dept` | 无 | `DeptVo[]` | 401 / 403 |
| 21 | 部门 | POST | `/api/depts` | 新增部门节点，支持组织扩张与拆分 | `sys:dept:add` | `DeptCreateDtoReq` | `DeptVo` | 400 / 401 / 403 / 409 |
| 22 | 部门 | PUT | `/api/depts/{id}` | 改名、换父部门、调排序，支持部门在树内整体移动 | `sys:dept:edit` | `DeptUpdateDtoReq` | `DeptVo` | 400 / 401 / 403 / 404 / 409 |
| 23 | 部门 | DELETE | `/api/depts/{id}` | 删除空部门；有子部门或在职用户时拒绝，避免用户挂到不存在的部门下 | `sys:dept:delete` | 无 | `Void` | 401 / 403 / 404 / 409 |
| 24 | 部门 | GET | `/api/depts/{id}/roles` | 查看部门当前绑定的角色，回答「这个人为什么有这些权限」 | `sys:dept` | 无 | `DeptRoleVo` | 401 / 403 / 404 |
| 25 | 部门 | PUT | `/api/depts/{id}/roles` | 把角色授予部门 → 新员工入职即继承权限；保存后清空该部门用户权限缓存 | `sys:role:grant` | `DeptRoleDtoReq` | `Void` | 400 / 401 / 403 / 404 |

### 3.6 文档业务（15）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 26 | 文档 | GET | `/api/documents` | 关键词 + 分类 + 标签三维检索**已发布**文档，只返回有权可见内容，承载平台核心的「找得到」场景 | `doc:search` | `DocumentSearchDtoReq` | `PageVo<DocumentVo>` | 400 / 401 / 403 |
| 27 | 文档 | GET | `/api/documents/mine` | 只看自己的文档并按状态筛选（草稿/已发布/归档），一屏管理个人产出 | `doc:mine` | `DocumentMineDtoReq` | `PageVo<DocumentVo>` | 400 / 401 / 403 |
| 28 | 文档 | GET | `/api/documents/trash` | 列出回收站文档，为误删提供找回入口 | `doc:mine` | `DocumentTrashDtoReq` | `PageVo<DocumentVo>` | 400 / 401 / 403 |
| 29 | 文档 | POST | `/api/documents` | 新建草稿落库（支持分次写作），返回完整详情供编辑器继续编辑 | `doc:create` | `DocumentCreateDtoReq` | `DocumentDetailVo` | 400 / 401 / 403 |
| 30 | 文档 | GET | `/api/documents/{id}` | 打开详情（正文 + 标签 + 收藏态 + 版本号 + 驳回理由），并对已发布文档累计阅读量 | `doc:search` | 无 | `DocumentDetailVo` | 401 / 403 / 404 |
| 31 | 文档 | PUT | `/api/documents/{id}` | 保存对本人文档的修改，版本号 +1 并写版本快照，事后可回溯「改了什么」 | `doc:edit` | `DocumentUpdateDtoReq` | `DocumentDetailVo` | 400 / 401 / 403 / 404 / 409 |
| 32 | 文档 | POST | `/api/documents/{id}/publish` | 把草稿发布出去让同事能检索到（`DRAFT`→`PUBLISHED`，写发布版本与发布时间） | `doc:publish` | 无 | `DocumentDetailVo` | 401 / 403 / 404 / 409 |
| 33 | 文档 | POST | `/api/documents/{id}/derive` | 基于可见文档派生新草稿（预填正文、记录 `derivedFromId`），免除重复劳动 | `doc:derive` | `DocumentDeriveDtoReq` | `DocumentDetailVo` | 401 / 403 / 404 / 409 |
| 34 | 文档 | DELETE | `/api/documents/{id}` | 软删除进回收站（可恢复），把误删从「不可逆」变成「可挽回」 | `doc:delete` | 无 | `Void` | 401 / 403 / 404 / 409 |
| 35 | 文档 | POST | `/api/documents/{id}/restore` | 把回收站文档恢复为草稿，找回误删内容 | `doc:restore` | 无 | `DocumentDetailVo` | 401 / 403 / 404 / 409 |
| 36 | 文档 | DELETE | `/api/documents/{id}/destroy` | 彻底删除（物理删除并清理版本、收藏、标签关联）；必须带二次确认参数防误触 | `doc:delete` | `DocumentDestroyDtoReq` | `Void` | 400 / 401 / 403 / 404 / 409 |
| 37 | 文档 | GET | `/api/documents/{id}/versions` | 按版本号倒序列出历史快照与变更类型，回答「谁在什么时候改了什么」 | `doc:mine` | `PageDtoReq` | `PageVo<DocumentVersionVo>` | 400 / 401 / 403 / 404 |
| 38 | 文档 | POST | `/api/documents/{id}/favorite` | 收藏文档形成个人常用入口；重复收藏幂等成功不报错 | `doc:favorite` | 无 | `FavoriteVo` | 401 / 403 / 404 |
| 39 | 文档 | DELETE | `/api/documents/{id}/favorite` | 取消收藏并同步收藏计数 | `doc:favorite` | 无 | `FavoriteVo` | 401 / 403 / 404 |
| 40 | 文档 | GET | `/api/favorites` | 分页查看我的收藏（仅返回当前仍可见的文档），支撑「我的收藏」入口 | `doc:favorite` | `FavoritePageDtoReq` | `PageVo<DocumentVo>` | 400 / 401 / 403 |

### 3.7 审核与治理（5）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 41 | 审核 | GET | `/api/review/documents` | 按状态取待治理列表（默认 `PUBLISHED`）作为审核队列数据源，管理员一屏处理 | `doc:review` | `ReviewPageDtoReq` | `PageVo<DocumentVo>` | 400 / 401 / 403 |
| 42 | 审核 | POST | `/api/documents/{id}/audit` | 审核通过并留痕（写 `AUDIT` 版本 + 意见），回答「这篇是谁审的、依据什么」 | `doc:audit` | `DocumentAuditDtoReq` | `DocumentDetailVo` | 400 / 401 / 403 / 404 / 409 |
| 43 | 审核 | POST | `/api/documents/{id}/reject` | 驳回并把理由回传属主（状态退回 `DRAFT`），让作者明确知道怎么改 | `doc:reject` | `DocumentRejectDtoReq` | `DocumentDetailVo` | 400 / 401 / 403 / 404 / 409 |
| 44 | 治理 | POST | `/api/documents/{id}/archive` | 归档过时或不合规内容（`PUBLISHED`→`ARCHIVED` 只读）：保留可追溯、停止继续扩散 | `doc:archive` | `DocumentAuditDtoReq` | `DocumentDetailVo` | 400 / 401 / 403 / 404 / 409 |
| 45 | 治理 | POST | `/api/documents/{id}/republish` | 归档内容恢复上架（`ARCHIVED`→`PUBLISHED`），修正误归档 | `doc:archive` | 无 | `DocumentDetailVo` | 401 / 403 / 404 / 409 |

### 3.8 分类与标签（8）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 46 | 分类 | GET | `/api/categories/tree` | 取回三层分类树，供检索页筛选与编辑器选择分类 | `doc:search` | 无 | `CategoryVo[]` | 401 / 403 |
| 47 | 分类 | POST | `/api/categories` | 新建分类节点，让文档拥有稳定的归类维度 | `doc:category:edit` | `CategoryCreateDtoReq` | `CategoryVo` | 400 / 401 / 403 / 409 |
| 48 | 分类 | PUT | `/api/categories/{id}` | 改名、移动、排序分类；禁止移到自身子孙下形成环 | `doc:category:edit` | `CategoryUpdateDtoReq` | `CategoryVo` | 400 / 401 / 403 / 404 / 409 |
| 49 | 分类 | DELETE | `/api/categories/{id}` | 删除空分类；该分类下仍有文档时拒绝并提示先迁移 | `doc:category:edit` | 无 | `Void` | 401 / 403 / 404 / 409 |
| 50 | 标签 | GET | `/api/tags` | 分页查标签及使用量，支持按热度挑标签 | `doc:search` | `TagPageDtoReq` | `PageVo<TagVo>` | 400 / 401 / 403 |
| 51 | 标签 | POST | `/api/tags` | 新建标签（编辑器内即时建标签），避免「想打标却没有这个词」 | `doc:tag:edit` | `TagCreateDtoReq` | `TagVo` | 400 / 401 / 403 / 409 |
| 52 | 标签 | PUT | `/api/tags/{id}` | 标签改名（等价于合并同义标签），改名后所有引用处自动生效 | `doc:tag:edit` | `TagUpdateDtoReq` | `TagVo` | 400 / 401 / 403 / 404 / 409 |
| 53 | 标签 | DELETE | `/api/tags/{id}` | 删除废弃标签并清理文档关联，防止标签越积越乱 | `doc:tag:edit` | 无 | `Void` | 401 / 403 / 404 |

### 3.9 文件与统计（2）

| 序号 | 模块 | 方法 | 路径 | 用途 | 权限点 | 入参 DTO | 出参 VO | 主要错误码 |
|---|---|---|---|---|---|---|---|---|
| 54 | 文件 | POST | `/api/upload/image` | 上传 Markdown 正文里的图片并返回相对 URL；扩展名/MIME/大小三重白名单拦截非法文件 | `doc:upload` | `multipart` 表单字段 `file` | `ImageVo` | 400 / 401 / 403 |
| 55 | 统计 | GET | `/api/stats/overview` | 返回个人与平台计数卡片（我的文档/草稿/收藏、平台已发布/待治理/总阅读量），首页一眼看全局 | `doc:center` | 无 | `StatVo` | 401 / 403 |

### 3.10 接口统计汇总

| 项 | 值 |
|---|---|
| 接口总数 | **55** |
| 按方法 | GET **18** / POST **18** / PUT **11** / DELETE **8** |
| 按鉴权 | 公开 **1** / 登录即可 **2** / 权限点 **52** |
| 按模块 | 认证 3 · 用户 6 · 角色 6 · 权限 4 · 部门 6 · 文档 15 · 审核与治理 5 · 分类与标签 8 · 文件与统计 2 |

---

## 4. 逐模块详规

> 阅读约定：入参 DTO 一律给完整字段表；出参 VO 给出字段清单，字段的类型与说明以 §2.7 字典为准（该字典即出参字段表）。
> 每条接口均标注**权限点**与**主要错误码**；涉及状态流转的接口标注 PRD §4.2 的边编号（T1~T10，另见 §7）。

### 4.1 模块 M-01：认证（3 条）

#### 4.1.1 `POST /api/auth/login` 登录

**用途与价值**：一次请求同时换取 token、用户信息与权限码集合，前端凭权限码渲染菜单与按钮，后续操作不再反复回查权限。

**权限点**：公开（无需 `Authorization` 头）

**入参** `LoginDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `username` | string | 是 | 去首尾空格；4–64 字符 | 用户名不能为空，且长度需在4到64之间 |
| `password` | string | 是 | 6–64 字符；**不 trim**（首尾空格是密码的一部分） | 密码不能为空，且长度需在6到64之间 |

**出参** `LoginVo`（字段：`token`、`userInfo`、`roles`、`permissions`；完整定义见 §2.7.2）。`roles` 与 `userInfo.roles` 均为**角色编码数组**（如 `["STAFF"]`）：GLOSSARY v2.1 已取消用户表的单一角色列，角色统一经 `sys_user_role` 关联。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 用户名或密码格式不合法 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 用户名不存在 **或** 密码错误（两种情况文案完全一致，防用户名枚举，NFR-S2） | 用户名或密码错误 |
| 403 `USER_DISABLED` | `sys_user.status` 为 `LOCKED` 或 `DISABLED` | 账号已停用或冻结，请联系系统管理员 |

- 校验通过后更新 `last_login_at`，并预写权限缓存 `perm:user:{userId}`（BR-18）。
- 对应 US-01 的 AC-01.1 / AC-01.2 / AC-01.3。
- 响应体**绝不包含** `passwordHash`（NFR-S1）。

**示例**

请求：

```http
POST /api/auth/login
Content-Type: application/json;charset=UTF-8

{
  "username": "u1001",
  "password": "Staff@123"
}
```

响应：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOjEwMDF9.7c9f1a2b",
    "userInfo": {
      "id": "1001",
      "username": "u1001",
      "realName": "张伟",
      "deptId": "20",
      "deptName": "技术部",
      "roles": ["STAFF"],
      "avatarUrl": null,
      "permissions": ["doc:center", "doc:mine", "doc:search", "doc:create", "doc:edit", "doc:publish", "doc:delete", "doc:restore", "doc:derive", "doc:favorite", "doc:upload"]
    },
    "roles": ["STAFF"],
    "permissions": ["doc:center", "doc:mine", "doc:search", "doc:create", "doc:edit", "doc:publish", "doc:delete", "doc:restore", "doc:derive", "doc:favorite", "doc:upload"]
  }
}
```

#### 4.1.2 `POST /api/auth/logout` 登出

**用途与价值**：把当前 token 立即拉黑，消除公用电脑上「关掉页面后 token 仍可被复用」的隐患。

**权限点**：登录即可

**入参**：无请求体（token 取自 `Authorization` 头）

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | token 缺失、已失效或已在黑名单中 | 登录状态已失效，请重新登录 |

- token 直接 `DEL`（登出、停用、重置密码），**不维护黑名单**；重复登出**幂等**：已失效 token 再调也返回 200，避免前端并发登出报错。
- 前端约定：无论成功失败都清空本地 token 与 Pinia 用户态，跳转 `/login`。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": null
}
```

#### 4.1.3 `GET /api/auth/me` 获取当前登录用户

**用途与价值**：页面刷新或权限变更后重建用户态与权限码，避免把权限写死在 localStorage 导致「授权改了但界面不生效」。

**权限点**：登录即可

**入参**：无

**出参** `UserInfoVo`（字段：`id`、`username`、`realName`、`deptId`、`deptName`、`roles`、`avatarUrl`、`permissions`，另含继承自 `AuditVo` 的四个审计字段；完整定义见 §2.7.2）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | token 缺失或失效 | 登录状态已失效，请重新登录 |
| 403 `USER_DISABLED` | 账号已非 `ACTIVE`（被停用/冻结后 token 仍在有效期内） | 账号已停用或冻结，请联系系统管理员 |
| 500 `SERVER_ERROR` | 权限缓存与数据库均不可用 | 服务暂时不可用，请稍后重试 |

- 权限码优先读 Redis 缓存，未命中则回源数据库重建（BR-18）。
- 前端路由守卫在每次进入受保护路由时调用本接口刷新权限（MASTER-PLAN §6.2）。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "1001",
    "username": "u1001",
    "realName": "张伟",
    "deptId": "20",
    "deptName": "技术部",
    "roles": ["STAFF"],
    "avatarUrl": null,
    "permissions": ["doc:center", "doc:mine", "doc:search", "doc:create", "doc:edit", "doc:publish", "doc:delete", "doc:restore", "doc:derive", "doc:favorite", "doc:upload"]
  }
}
```

### 4.2 模块 M-02：用户管理（6 条）

#### 4.2.1 `GET /api/users` 用户列表

**用途与价值**：按关键词/部门/状态分页查人，让系统管理员快速定位账号并进入编辑；响应永不带密码哈希。

**权限点**：`sys:user`

**入参** `UserPageDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符；匹配 `username` 或 `real_name` 模糊 | 关键词长度不能超过64个字符 |
| `status` | string | 否 | `ACTIVE` / `LOCKED` / `DISABLED` | 账号状态取值非法 |
| `deptId` | string | 否 | 数字字符串；为空 = 全部部门 | 部门ID格式不正确 |

**出参** `PageVo<UserVo>`

`UserVo` 字段（完整定义见 §2.7.2）：`id`、`username`、`realName`、`deptId`、`deptName`、`email`、`phone`、`avatarUrl`、`status`、`roles`、`lastLoginAt`，另含**继承自 `AuditVo`** 的 `createdAt`、`createdBy`、`updatedAt`、`updatedBy`。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界或 `status` 取值非法（BR-05，越界一律 400，不静默纠正） | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:user` | 无权限执行该操作 |

- 默认排序 `updated_at` 倒序。
- 部门名与角色集合用批量 `IN` 查询补齐，禁止 N+1（NFR-P2）。
- 已逻辑删除用户（`deleted = 1`）不出现在结果中（由 `@SQLRestriction` 保证）。

#### 4.2.2 `POST /api/users` 新增用户

**用途与价值**：新员工入职建档：一次绑定部门与角色，账号立即可登录并自动继承部门权限。

**权限点**：`sys:user:add`

**入参** `UserCreateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `username` | string | 是 | 4–64 字符；字母/数字/下划线；全局唯一 | 登录名不能为空，且只能包含字母、数字与下划线 |
| `realName` | string | 是 | 1–64 字符 | 姓名不能为空且不超过64个字符 |
| `deptId` | string | 是 | 必须存在于 `sys_dept` | 部门不存在，请重新选择 |
| `email` | string | 否 | 邮箱格式；≤ 128 字符 | 邮箱格式不正确 |
| `phone` | string | 否 | 11 位数字（中国大陆手机号） | 手机号格式不正确 |
| `roles` | string[] | 否 | 角色**编码**数组（如 `["STAFF"]`）；每项必须存在于 `sys_role.code`；不传 = 仅绑定 `STAFF` | 角色编码不存在，请重新选择 |
| `password` | string | 是 | ≥ 8 字符，且同时含字母与数字 | 初始密码至少8位且需同时包含字母和数字 |

**出参** `UserVo`（字段清单同 §4.2.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 任一字段校验失败；或 `deptId` / `roles` 指向不存在的记录 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:user:add` | 无权限执行该操作 |
| 409 `CONFLICT_STATUS` | `username` 命中唯一索引 `uk_sys_user_username` | 登录名已存在，请更换 |

- 密码以 BCrypt（strength 10）写入 `password_hash`（BR-20）。
- 新用户 `status` 固定为 `ACTIVE`；`created_by` / `updated_by` 由 JPA Auditing 填充为当前操作人。
- 角色关系写入 `sys_user_role`（复合主键 `(user_id, role_id)`）。

**示例**

请求：

```http
POST /api/users
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json;charset=UTF-8

{
  "username": "u1002",
  "realName": "李娜",
  "deptId": "20",
  "email": "lina@example.com",
  "phone": "13800000002",
  "roles": ["STAFF"],
  "password": "Staff@123"
}
```

响应：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "1002",
    "username": "u1002",
    "realName": "李娜",
    "deptId": "20",
    "deptName": "技术部",
    "email": "lina@example.com",
    "phone": "13800000002",
    "avatarUrl": null,
    "status": "ACTIVE",
    "roles": ["STAFF"],
    "lastLoginAt": null,
    "createdAt": "2026-09-21 14:20:05",
    "updatedAt": "2026-09-21 14:20:05"
  }
}
```

#### 4.2.3 `GET /api/users/{id}` 用户详情

**用途与价值**：打开编辑前取单个账号的完整信息（含角色 ID 集合），避免前端从列表缓存里拼数据出错。

**权限点**：`sys:user`

**入参**：路径参数 `id`（用户 ID，字符串形式的数字）

**出参** `UserVo`（字段清单同 §4.2.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:user` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 用户不存在或已被逻辑删除 | 用户不存在或已被删除 |

- 响应不含 `passwordHash`（NFR-S1）。
- `roles` 返回角色编码数组，编辑表单用角色 ID 时由前端经 `GET /api/roles` 映射。

#### 4.2.4 `PUT /api/users/{id}` 编辑用户

**用途与价值**：修改姓名、部门与角色；换部门后权限按新部门即时重算，组织调整不必重建账号。

**权限点**：`sys:user:edit`

**入参** `UserUpdateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `realName` | string | 是 | 1–64 字符 | 姓名不能为空且不超过64个字符 |
| `deptId` | string | 是 | 必须存在于 `sys_dept` | 部门不存在，请重新选择 |
| `email` | string | 否 | 邮箱格式；≤ 128 字符 | 邮箱格式不正确 |
| `phone` | string | 否 | 11 位数字 | 手机号格式不正确 |
| `roles` | string[] | 否 | 角色**编码**数组；每项必须存在于 `sys_role.code`；传空数组 = 清空自定义角色 | 角色编码不存在，请重新选择 |

**出参** `UserVo`（字段清单同 §4.2.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；请求体出现 `username` 或 `password` 字段（本接口不接受这两项） | 登录名与密码不可通过本接口修改 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:user:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 用户不存在 | 用户不存在或已被删除 |

- `username` 与密码**不可**经本接口修改（密码走 4.2.6）。
- 部门或角色发生变化时，立即删除该用户权限缓存 `perm:user:{id}`（BR-18）；`sys_user_role` 用「先删后插」覆盖式更新，同一事务内完成。

#### 4.2.5 `PUT /api/users/{id}/status` 停用/启用用户

**用途与价值**：停用或冻结账号并**立即踢下线**（清 token 与权限缓存），离职与异常账号当场失效，而不是等 token 自然过期。

**权限点**：`sys:user:disable`

**入参** `UserStatusDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `status` | string | 是 | 仅 `ACTIVE` / `LOCKED` / `DISABLED` | 账号状态取值非法，仅支持 ACTIVE、LOCKED、DISABLED |

**出参** `UserVo`（字段清单同 §4.2.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `status` 非法；或操作对象是当前登录用户自己（防管理员自锁，本文件登记） | 不能修改自己的账号状态 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:user:disable` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 用户不存在 | 用户不存在或已被删除 |

- 状态改为 `LOCKED` / `DISABLED` 时：删除该用户全部 token（强制下线）+ 删除权限缓存 `perm:user:{id}`。
- 状态改回 `ACTIVE` = 启用账号，用户可用原密码重新登录。

**示例**

请求：

```http
PUT /api/users/1002/status
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json;charset=UTF-8

{ "status": "DISABLED" }
```

响应（`data` 中 `status` 已变更，其余字段同 §4.2.1）：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "1002",
    "username": "u1002",
    "realName": "李娜",
    "deptId": "20",
    "deptName": "技术部",
    "email": "lina@example.com",
    "phone": "13800000002",
    "avatarUrl": null,
    "status": "DISABLED",
    "roles": ["STAFF"],
    "lastLoginAt": "2026-09-21 14:31:02",
    "createdAt": "2026-09-21 14:20:05",
    "updatedAt": "2026-09-21 15:02:10"
  }
}
```

#### 4.2.6 `PUT /api/users/{id}/password` 重置密码

**用途与价值**：管理员替忘记密码的员工重置密码；重置后该用户所有在线 token 立即失效，避免旧会话继续可用。

**权限点**：`sys:user:reset`

**入参** `UserPasswordDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `newPassword` | string | 是 | ≥ 8 字符，且同时含字母与数字 | 新密码至少8位且需同时包含字母和数字 |

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 新密码强度不足 | 新密码至少8位且需同时包含字母和数字 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:user:reset` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 用户不存在 | 用户不存在或已被删除 |

- 新密码 BCrypt 哈希后覆盖 `password_hash`（BR-20），**不返回**任何密码信息。
- 重置成功后删除该用户全部 token，用户须用新密码重新登录。

### 4.3 模块 M-03：角色管理（6 条）

#### 4.3.1 `GET /api/roles` 角色列表

**用途与价值**：列出全部角色供授权页选择「要改哪个角色」；某角色的权限规模由 4.3.5 单独查询。

**权限点**：`sys:role`

**入参** `RolePageDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符；匹配 `name` 或 `code` | 关键词长度不能超过64个字符 |

**出参** `PageVo<RoleVo>`

`RoleVo` 字段（完整定义见 §2.7.2）：`id`、`name`、`code`、`description`、`isBuiltin`、`sortOrder`，另含继承自 `AuditVo` 的四个审计字段。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role` | 无权限执行该操作 |

- 排序：`is_builtin` 降序（内置角色在前）→ `sort_order` 升序。
- 角色权限规模**不在列表接口返回**（不为一个数字多查一次关联表）：进入授权页时按角色调 4.3.5 取已勾选集合。

#### 4.3.2 `POST /api/roles` 新增角色

**用途与价值**：新增自定义角色（如「实习生」「外部协作」），把权限集合从代码常量里解放出来。

**权限点**：`sys:role:add`

**入参** `RoleDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符 | 角色名称不能为空且不超过64个字符 |
| `code` | string | 是 | 2–32 字符；大写字母/数字/下划线；全局唯一 | 角色编码只能包含大写字母、数字与下划线 |
| `description` | string | 否 | ≤ 255 字符 | 角色描述不能超过255个字符 |

**出参** `RoleVo`（字段清单同 §4.3.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role:add` | 无权限执行该操作 |
| 409 `CONFLICT_STATUS` | `code` 命中唯一索引 `uk_sys_role_code` | 角色编码已存在，请更换 |

- 新建角色 `is_builtin = 0`（内置三角色仅由种子数据写入，见 M2 `sql/data.sql`）。
- `code` 创建后不可修改（BR-02）。
- 新角色初始权限为空，需再调 4.3.6 授权。

#### 4.3.3 `PUT /api/roles/{id}` 编辑角色

**用途与价值**：改角色名称/描述/排序；`code` 不可改，避免鉴权口径被悄悄改坏。

**权限点**：`sys:role:edit`

**入参** `RoleDtoReq`（请求体 JSON；与新增角色共用同一个 DTO）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符 | 角色名称不能为空且不超过64个字符 |
| `code` | string | 是 | 必须与当前角色编码**完全一致**（编码不可修改，BR-02） | 角色编码不可修改 |
| `description` | string | 否 | ≤ 255 字符 | 角色描述不能超过255个字符 |

**出参** `RoleVo`（字段清单同 §4.3.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；`code` 缺失或与当前角色编码不一致（编码不可改，BR-02） | 角色编码不可修改 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 角色不存在 | 角色不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 目标为内置角色（`is_builtin = 1`）且尝试改 `name` | 内置角色名称不可修改 |

- 内置角色允许改 `description`，不允许改 `name`、不允许删除。
- 修改后清空该角色下所有用户的权限缓存（BR-18）。

#### 4.3.4 `DELETE /api/roles/{id}` 删除角色

**用途与价值**：删除废弃角色；被用户引用或被部门绑定时拒绝删除，防止出现「权限悬空」的账号。

**权限点**：`sys:role:delete`

**入参**：路径参数 `id`

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role:delete` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 角色不存在 | 角色不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 内置角色（`is_builtin = 1`）；或仍被 `sys_user_role` / `sys_dept_role` 引用 | 该角色仍被用户或部门使用，请先解除绑定 |

- 删除成功后清理该角色的关联：`sys_role_permission`（同事务）。
- 内置三角色（`STAFF` / `DOC_ADMIN` / `SYS_ADMIN`）永远不可删除。

#### 4.3.5 `GET /api/roles/{id}/permissions` 查询角色权限

**用途与价值**：读取角色已勾选的权限 ID 集合，让权限树回显当前授权状态。

**权限点**：`sys:role`

**入参**：路径参数 `id`（角色 ID）

**出参** `RolePermissionVo`（字段：`roleId`、`permissionIds`；完整定义见 §2.7.2）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 角色不存在 | 角色不存在或已被删除 |

- `permissionIds` 为该角色在 `sys_role_permission` 中的全部 `permission_id`（含父节点与按钮节点，前端勾选树据此回显）。
- 权限树本体由 4.4.1 提供，两接口配合完成「树 + 勾选态」渲染。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "roleId": "2",
    "permissionIds": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]
  }
}
```

#### 4.3.6 `PUT /api/roles/{id}/permissions` 角色授权

**用途与价值**：覆盖式保存角色权限，保存后清空该角色下所有用户的权限缓存 → **授权即时生效**，无需用户重新登录。

**权限点**：`sys:role:grant`（对应 US-08）

**入参** `RolePermissionDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `permissionIds` | string[] | 是 | 每个 ID 必须存在于 `sys_permission`；空数组 = 清空该角色全部权限 | 权限节点不存在，请刷新后重试 |

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `permissionIds` 含不存在的权限 ID | 权限节点不存在，请刷新后重试 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role:grant` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 角色不存在 | 角色不存在或已被删除 |

- **覆盖式保存**：事务内先删该角色在 `sys_role_permission` 的全部记录，再按入参批量插入（`(role_id, permission_id)` 复合主键）。
- 保存成功后：查出该角色下所有用户 → 逐个删除 `perm:user:{userId}`（BR-18），保证下次鉴权读到新权限（US-08 AC-08.1）。
- 传入父节点 ID 不会自动补齐子节点，也不自动向上补父：**只保存入参给出的集合**（前端勾选树负责半选逻辑）。

**示例**

请求：

```http
PUT /api/roles/2/permissions
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json;charset=UTF-8

{
  "permissionIds": ["1", "2", "3", "6", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27"]
}
```

响应：

```json
{
  "code": 200,
  "message": "成功",
  "data": null
}
```

### 4.4 模块 M-04：权限管理（4 条）

#### 4.4.1 `GET /api/permissions/tree` 权限树

**用途与价值**：一次取回三层权限树（目录 → 菜单 → 按钮），前端直接渲染勾选树，省掉逐层请求。

**权限点**：`sys:perm`

**入参**：无

**出参** `PermissionVo[]`（根节点数组，子节点递归在 `children` 中）

`PermissionVo` 字段（完整定义见 §2.7.2）：`id`、`name`、`code`、`type`、`parentId`、`ancestors`、`path`、`icon`、`sortOrder`、`children`，另含继承自 `AuditVo` 的四个审计字段。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:perm` | 无权限执行该操作 |

- 排序：层级 `DIR` → `MENU` → `BUTTON`，同级按 `sortOrder` 升序。
- 一次性返回**全量**树（39 个权限点规模，不分页、不懒加载）。
- 树的组装在 Service 层按 `parent_id` 递归完成，禁止在 Controller 拼装。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "id": "1",
      "name": "文档中心",
      "code": "doc:center",
      "type": "DIR",
      "parentId": "0",
      "ancestors": "0",
      "path": "/docs",
      "icon": "document",
      "sortOrder": 1,
      "children": [
        {
          "id": "2",
          "name": "我的文档",
          "code": "doc:mine",
          "type": "MENU",
          "parentId": "1",
          "ancestors": "0,1",
          "path": "/my",
          "icon": "folder",
          "sortOrder": 1,
          "children": [
            {
              "id": "11",
              "name": "新建文档",
              "code": "doc:create",
              "type": "BUTTON",
              "parentId": "2",
              "ancestors": "0,1,2",
              "path": null,
              "icon": null,
              "sortOrder": 1,
              "children": []
            }
          ]
        }
      ]
    }
  ]
}
```

#### 4.4.2 `POST /api/permissions` 新增权限

**用途与价值**：新增权限节点（新页面或新按钮），让新功能纳入 RBAC，而不必改代码常量。

**权限点**：`sys:perm:add`

**入参** `PermissionCreateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符 | 权限名称不能为空且不超过64个字符 |
| `code` | string | 是 | 2–64 字符；小写字母/数字/冒号；全局唯一 | 权限编码只能包含小写字母、数字与冒号 |
| `type` | string | 是 | 仅 `DIR` / `MENU` / `BUTTON` | 权限类型取值非法 |
| `parentId` | string | 是 | 必须存在于 `sys_permission`；根节点传 `"0"` | 父节点不存在，请重新选择 |
| `path` | string | 否 | ≤ 128 字符；`type = DIR/MENU` 时必填 | 目录与菜单必须填写前端路由 |
| `icon` | string | 否 | ≤ 64 字符 | 图标名不能超过64个字符 |
| `sortOrder` | number | 否 | ≥ 0，默认 0 | 排序号必须大于等于0 |

**出参** `PermissionVo`（字段清单同 §4.4.1，`children` 为空数组）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；或层级越级（`DIR` 的父必须是 `"0"`，`MENU` 的父必须是 `DIR`，`BUTTON` 的父必须是 `MENU`） | 权限层级不合法：目录的上级只能是根，菜单的上级必须是目录，按钮的上级必须是菜单 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:perm:add` | 无权限执行该操作 |
| 409 `CONFLICT_STATUS` | `code` 命中唯一索引 `uk_sys_permission_code` | 权限编码已存在，请更换 |

- `ancestors` 由后端自动计算：父节点的 `ancestors` + `,` + 父节点 `id`；根节点为 `"0"`。
- 层级约束落实 PRD §3.1 的三层结构（2 目录 / 9 菜单 / 28 按钮）。

#### 4.4.3 `PUT /api/permissions/{id}` 编辑权限

**用途与价值**：改权限名称/类型/父节点/排序，支持子树整体移动；禁止移到自身子孙下形成环。

**权限点**：`sys:perm:edit`

**入参** `PermissionUpdateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符 | 权限名称不能为空且不超过64个字符 |
| `type` | string | 是 | 仅 `DIR` / `MENU` / `BUTTON` | 权限类型取值非法 |
| `parentId` | string | 是 | 必须存在于 `sys_permission`；不能是自己或自己的子孙 | 不能将节点移动到其子节点下 |
| `path` | string | 否 | ≤ 128 字符；`type = DIR/MENU` 时必填 | 目录与菜单必须填写前端路由 |
| `icon` | string | 否 | ≤ 64 字符 | 图标名不能超过64个字符 |
| `sortOrder` | number | 否 | ≥ 0 | 排序号必须大于等于0 |

**出参** `PermissionVo`（字段清单同 §4.4.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；请求体出现 `code` 字段；层级越级；`parentId` 是自己或自己的子孙（US-08 AC-08.3） | 不能将节点移动到其子节点下 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:perm:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 权限节点不存在 | 权限节点不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 目标节点是内置权限点且新 `code` 与既有节点冲突（`code` 本身不可改） | 权限编码不可修改 |

- 环检测：`parentId` 命中「自身 `id`」或「自身 `ancestors` 中包含的任一 ID」即拒绝。
- 父节点变化后，**事务内级联重写所有子孙节点的 `ancestors`**（基于旧前缀替换）。
- 修改后清空受影响用户的权限缓存（权限编码未变时仅清父级路径相关缓存）。

#### 4.4.4 `DELETE /api/permissions/{id}` 删除权限

**用途与价值**：下线废弃权限点；有子节点或被角色/用户引用时拒绝删除，杜绝悬空权限码。

**权限点**：`sys:perm:delete`

**入参**：路径参数 `id`

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:perm:delete` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 权限节点不存在 | 权限节点不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 存在子节点；或仍被 `sys_role_permission` / `sys_user_permission` 引用 | 该权限仍有子节点或被角色、用户引用，请先解除 |

- 删除前必须同时满足：无子节点、无角色引用、无用户直授引用（BR-03）。
- 删除成功后清理该权限在 `sys_role_permission`、`sys_user_permission` 中的残留关联（同事务）。

### 4.5 模块 M-05：部门管理（6 条）

#### 4.5.1 `GET /api/depts/tree` 部门树

**用途与价值**：取回部门树，同时服务于组织维护页与用户表单的部门下拉，保证两处口径一致。

**权限点**：`sys:dept`

**入参**：无

**出参** `DeptVo[]`（根节点数组，子部门递归在 `children` 中）

`DeptVo` 字段（完整定义见 §2.7.2）：`id`、`name`、`parentId`、`ancestors`、`sortOrder`、`children`，另含继承自 `AuditVo` 的四个审计字段。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:dept` | 无权限执行该操作 |

- 排序：同级按 `sortOrder` 升序，`sortOrder` 相同按 `id` 升序。
- 部门人数**不在 VO 中返回**（GLOSSARY §3.7 的 `DeptVo` 无该字段）：删除部门时的「仍有员工」校验在服务端执行，前端凭 4.5.4 的 409 与提示文案引导用户先转移人员。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "id": "1",
      "name": "总部",
      "parentId": "0",
      "ancestors": "0",
      "sortOrder": 1,
      "children": [
        {
          "id": "20",
          "name": "技术部",
          "parentId": "1",
          "ancestors": "0,1",
          "sortOrder": 1,
          "children": []
        }
      ]
    }
  ]
}
```

#### 4.5.2 `POST /api/depts` 新增部门

**用途与价值**：新增部门节点，支撑组织扩张与拆分。

**权限点**：`sys:dept:add`

**入参** `DeptCreateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符；同一父节点下不可重名 | 部门名称不能为空且不超过64个字符 |
| `parentId` | string | 是 | 必须存在于 `sys_dept`；根部门传 `"0"` | 上级部门不存在，请重新选择 |
| `sortOrder` | number | 否 | ≥ 0，默认 0 | 排序号必须大于等于0 |

**出参** `DeptVo`（字段清单同 §4.5.1，`children` 为空数组）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；同父节点下 `name` 重复 | 同级部门下已存在同名部门 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:dept:add` | 无权限执行该操作 |
| 409 `CONFLICT_STATUS` | 数据库唯一约束冲突（`sys_dept` 同父同级同名） | 同级部门下已存在同名部门 |

- `ancestors` 自动计算：父节点 `ancestors` + `,` + 父节点 `id`；根部门为 `"0"`。

#### 4.5.3 `PUT /api/depts/{id}` 编辑部门

**用途与价值**：改名、换上级部门、调排序，支持部门在组织树内整体移动。

**权限点**：`sys:dept:edit`

**入参** `DeptUpdateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符；同一父节点下不可重名 | 部门名称不能为空且不超过64个字符 |
| `parentId` | string | 是 | 必须存在；不能是自己或自己的子孙 | 不能将部门移动到其子部门下 |
| `sortOrder` | number | 否 | ≥ 0 | 排序号必须大于等于0 |

**出参** `DeptVo`（字段清单同 §4.5.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；`parentId` 是自己或自己的子孙；同级重名 | 不能将部门移动到其子部门下 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:dept:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 部门不存在 | 部门不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 唯一约束冲突 | 同级部门下已存在同名部门 |

- 父部门变化后，**事务内级联重写所有子孙部门的 `ancestors`**。
- 部门变更后清空该部门下所有用户的权限缓存（部门角色可能变化，BR-18）。

#### 4.5.4 `DELETE /api/depts/{id}` 删除部门

**用途与价值**：删除空部门；有子部门或在职用户时拒绝，避免用户挂到不存在的部门下。

**权限点**：`sys:dept:delete`

**入参**：路径参数 `id`

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:dept:delete` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 部门不存在 | 部门不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 存在子部门；或该部门下仍有未删除用户 | 该部门下仍有子部门或员工，请先转移 |

- 删除成功后清理该部门在 `sys_dept_role` 中的绑定（同事务）。

#### 4.5.5 `GET /api/depts/{id}/roles` 查询部门角色

**用途与价值**：查看部门当前绑定的角色，回答「这个部门的人为什么有这些权限」。

**权限点**：`sys:dept`

**入参**：路径参数 `id`（部门 ID）

**出参** `DeptRoleVo`（字段：`deptId`、`roleIds`；完整定义见 §2.7.2）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:dept` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 部门不存在 | 部门不存在或已被删除 |

#### 4.5.6 `PUT /api/depts/{id}/roles` 部门绑定角色

**用途与价值**：把角色授予部门 → 新员工入职即自动继承权限；保存后清空该部门用户权限缓存，授权即时生效。

**权限点**：`sys:role:grant`（对应 US-08）

**入参** `DeptRoleDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `roleIds` | string[] | 是 | 每个 ID 必须存在于 `sys_role`；空数组 = 清空绑定 | 角色不存在，请重新选择 |

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `roleIds` 含不存在的角色 ID | 角色不存在，请重新选择 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `sys:role:grant` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 部门不存在 | 部门不存在或已被删除 |

- **覆盖式保存**：事务内先删该部门在 `sys_dept_role` 的全部记录，再批量插入 `(dept_id, role_id)` 复合主键记录。
- 命名分工（避免与用户接口混淆）：**用户接口**的 `roles` 是**角色编码**数组（如 `["STAFF"]`，对应 `sys_role.code`）；**部门绑定角色**用 `roleIds`（**角色 ID** 数组，取值来自 4.3.1 角色列表），与 4.5.5 的 `DeptRoleVo.roleIds` 形成输入输出闭环。
- 保存后：查出该部门下所有用户 → 逐个删除 `perm:user:{userId}`（BR-18）。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": null
}
```

### 4.6 模块 M-06：文档业务（15 条）

#### 4.6.1 `GET /api/documents` 检索文档

**用途与价值**：关键词 + 分类 + 标签三维检索**已发布**文档，只返回有权可见的内容，承载平台「找得到」的核心场景（US-04）。

**权限点**：`doc:search`

**入参** `DocumentSearchDtoReq`（查询参数，继承 `PageDtoReq`）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符；对 `title`、`summary` 做 `LIKE` 模糊匹配 | 关键词长度不能超过64个字符 |
| `categoryId` | string | 否 | 分类 ID；传父分类 = 含全部子孙分类 | 分类ID格式不正确 |
| `tagIds` | string[] | 否 | 标签 ID 数组；**AND 命中**（文档需同时包含全部所选标签） | 标签ID格式不正确 |
| `status` | string | 否 | 本接口只查已发布：可空或 `PUBLISHED`，传其它值返回 400 | 本接口仅支持查询已发布文档 |
| `startTime` | string | 否 | `yyyy-MM-dd HH:mm:ss`，闭区间；与 `endTime` 成对使用 | 时间格式不正确，应为 yyyy-MM-dd HH:mm:ss |
| `endTime` | string | 否 | 同上；`startTime > endTime` 报错 | 开始时间不能晚于结束时间 |
| `sort` | string | 否 | `DocumentSort`：`updatedAt_desc`（默认）/ `publishAt_desc` / `viewCount_desc` | 排序方式仅支持 updatedAt_desc、publishAt_desc、viewCount_desc |

**出参** `PageVo<DocumentVo>`

`DocumentVo` 字段（完整定义见 §2.7.3）：`id`、`title`、`summary`、`categoryId`、`categoryName`、`authorId`、`authorName`、`status`、`versionNum`、`priceCents`、`viewCount`、`favoriteCount`、`canEdit`，另含继承自 `AuditVo` 的 `createdAt`、`createdBy`、`updatedAt`、`updatedBy`。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界；`sort` 取值非法；`status` 传了非 `PUBLISHED` 的值 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:search` | 无权限执行该操作 |

- **只返回 `status = PUBLISHED`** 的文档（US-04 AC-04.3：他人的 `DRAFT` 不得出现），过滤条件写在 Repository 查询方法内（PRD 不变式 I4）。
- 关键词无命中时返回 `200` + `list: []` + `total: 0`，**不是** 404 或 500（US-04 AC-04.2）。
- 列表项的 `canEdit` 仅当「当前用户是作者」且状态属于 `DRAFT`/`PUBLISHED` 时为 `true`。
- 作者名与分类名批量补齐，禁止 N+1（NFR-P2）。
- **动态条件用 `JpaSpecificationExecutor` + Criteria API 组合**（课件 3.1 §3）：分类、状态、关键词、时间区间任一为空则自动跳过，全程类型安全，**禁止字符串 SQL 拼接**。
- **列表走 DTO 构造函数投影**（课件 3.1 §2.4）：只查列表所需列，**不含 `contentMd` 大文本**；整条链路的 SQL 条数必须为常数（≤ 3 条）且不随 `pageSize` 增长（课件 3.1 §2 验收）。
- 需要实体对象做业务判断的查询用 `@EntityGraph(attributePaths = {...})`（课件 3.1 §2.3）；仅按 ID 批量补名称用 `findAllById` + Map 分组，禁止循环查库（课件 3.1 红线二）。

**示例**

```http
GET /api/documents?keyword=%E6%8E%A5%E5%8F%A3&categoryId=5&pageNum=1&pageSize=10&sort=updatedAt_desc
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "list": [
      {
        "id": "5001",
        "title": "CampusSwap 接口规范",
        "summary": "后端接口约定与错误码说明",
        "categoryId": "5",
        "categoryName": "后端开发",
        "createdBy": "1001",
        "authorId": "1001",
        "authorName": "张伟",
        "status": "PUBLISHED",
        "versionNum": 3,
        "priceCents": 0,
        "viewCount": 128,
        "favoriteCount": 9,
        "createdAt": "2026-09-15 10:02:11",
        "updatedAt": "2026-09-21 11:36:05",
        "canEdit": true
      }
    ],
    "total": 1,
    "pageNum": 1,
    "pageSize": 10
  }
}
```

#### 4.6.2 `GET /api/documents/mine` 我的文档

**用途与价值**：只看自己的文档并按状态筛选（草稿/已发布/归档），一屏管理个人产出；作者条件由后端强制注入，前端无法越权查询他人。

**权限点**：`doc:mine`

**入参** `DocumentMineDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符 | 关键词长度不能超过64个字符 |
| `status` | string | 否 | 仅 `DRAFT` / `PUBLISHED` / `ARCHIVED`；不传 = 全部（不含回收站） | 文档状态取值非法 |

**出参** `PageVo<DocumentVo>`（字段清单同 §4.6.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界；`status` 取值非法（含 `TRASH`，回收站走 4.6.3） | 文档状态取值非法 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:mine` | 无权限执行该操作 |

- 查询条件强制为 `created_by = 当前用户ID`，**不接受前端传入作者参数**（防越权）。
- 默认排序 `updated_at` 倒序。

#### 4.6.3 `GET /api/documents/trash` 回收站列表

**用途与价值**：列出回收站文档，为误删提供找回入口（配合 4.6.10 恢复、4.6.11 彻底删除）。

**权限点**：`doc:mine`

**入参** `DocumentTrashDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符 | 关键词长度不能超过64个字符 |

**出参** `PageVo<DocumentVo>`（字段清单同 §4.6.1，`status` 恒为 `TRASH`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:mine` | 无权限执行该操作 |

- 普通用户只看到自己删除的文档；拥有 `doc:manage` 的管理员可见全部（权限点仍以 `doc:mine` 为入口）。
- 回收站查询必须绕过 `@SQLRestriction("deleted = 0")`，用 `@Query` 原生条件显式查 `deleted = 1`。

#### 4.6.4 `POST /api/documents` 新建文档草稿

**用途与价值**：新建草稿落库，支持分次写作；返回完整详情，编辑器可直接继续编辑（US-02）。

**权限点**：`doc:create`

**入参** `DocumentCreateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `title` | string | 是 | 1–128 字符 | 文档标题不能为空且不超过128字 |
| `summary` | string | 否 | ≤ 255 字符 | 文档摘要不能超过255字 |
| `contentMd` | string | 否 | ≤ 100000 字符 | 文档正文不能超过100000字 |
| `categoryId` | string | 否 | 必须存在于 `doc_category`；不传 = `"0"`（未分类） | 分类不存在，请重新选择 |
| `tagIds` | string[] | 否 | 每个 ID 必须存在于 `doc_tag`；**最多 5 个** | 每篇文档最多只能选择5个标签 |
| `priceCents` | number | 否 | ≥ 0；默认 0（免费） | 价格标记不能为负数 |

**出参** `DocumentDetailVo`

`DocumentDetailVo` 字段（继承 `DocumentVo` 全部字段，另加下列；完整定义见 §2.7.3）：`contentMd`、`derivedFromId`、`rejectReason`、`favorited`、`tags`。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 任一字段校验失败；`categoryId` / `tagIds` 指向不存在的记录；标签超过 5 个 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:create` | 无权限执行该操作 |

- 新建后：`status = DRAFT`、`versionNum = 1`、`authorId = 当前用户ID`（持久层写入 `created_by`，作者即创建人）、`priceCents` 默认 0（US-02 AC-02.1）。
- 同时写入一条 `doc_version`（`changeType = CREATE`，`versionNum = 1`，`changeRemark = 创建文档`）。
- 标签写入 `doc_document_tag_rel`，并同步递增 `doc_tag.use_count`（同事务）。

**示例**

请求：

```http
POST /api/documents
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json;charset=UTF-8

{
  "title": "CampusSwap 接口规范",
  "summary": "后端接口约定与错误码说明",
  "contentMd": "# 接口规范\n\n所有响应统一为 ResponseResult。",
  "categoryId": "5",
  "tagIds": ["31", "32"],
  "priceCents": 0
}
```

响应：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "5001",
    "title": "CampusSwap 接口规范",
    "summary": "后端接口约定与错误码说明",
    "categoryId": "5",
    "categoryName": "后端开发",
    "createdBy": "1001",
    "authorId": "1001",
    "authorName": "张伟",
    "status": "DRAFT",
    "versionNum": 1,
    "priceCents": 0,
    "viewCount": 0,
    "favoriteCount": 0,
    "createdAt": "2026-09-21 14:40:12",
    "updatedAt": "2026-09-21 14:40:12",
    "canEdit": true,
    "contentMd": "# 接口规范\n\n所有响应统一为 ResponseResult。",
    "derivedFromId": null,
    "rejectReason": null,
    "favorited": false,
    "tags": [
      { "id": "31", "name": "接口", "useCount": 8 },
      { "id": "32", "name": "规范", "useCount": 3 }
    ]
  }
}
```

#### 4.6.5 `GET /api/documents/{id}` 文档详情

**用途与价值**：打开详情（正文 + 标签 + 收藏态 + 版本号 + 驳回理由），并对已发布文档累计阅读量，支撑阅读与后续操作入口。

**权限点**：`doc:search`

**入参**：路径参数 `id`

**出参** `DocumentDetailVo`（字段清单同 §4.6.4）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:search`；或非作者访问他人的 `DRAFT` / `ARCHIVED` / `TRASH` 文档 | 无权限查看该文档 |
| 404 `NOT_FOUND` | 文档不存在或已被彻底删除 | 文档不存在或已被删除 |

- **可见性规则**：`PUBLISHED` 对全部登录用户可见；`DRAFT`、`ARCHIVED`、`TRASH` **仅作者本人与拥有 `doc:manage` 的管理员**可见，否则 403（防 IDOR，BR-21）。
- **阅读量**：仅当文档为 `PUBLISHED` 时 `view_count` +1；同一用户 30 分钟内重复打开不重复计数（Redis `SETNX`，键 `view:doc:{docId}:user:{userId}`，TTL 1800 秒，BR-09）。
- `draft` 作者访问自己的草稿不累加阅读量（只统计已发布内容的传播量）。

#### 4.6.6 `PUT /api/documents/{id}` 编辑文档

**用途与价值**：保存对本人文档的修改，版本号 +1 并写版本快照，事后可回溯「改了什么」（US-06）。

**权限点**：`doc:edit`

**入参** `DocumentUpdateDtoReq`（请求体 JSON；继承 `DocumentCreateDtoReq` 并新增 `id`，业务字段与新增**同规则**）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `id` | string | 是 | 必须与路径 `{id}` 完全一致 | 请求体中的文档ID与路径不一致 |
| `title` | string | 是 | 1–128 字符 | 文档标题不能为空且不超过128字 |
| `summary` | string | 否 | ≤ 255 字符 | 文档摘要不能超过255字 |
| `contentMd` | string | 否 | ≤ 100000 字符 | 文档正文不能超过100000字 |
| `categoryId` | string | 否 | 必须存在于 `doc_category` | 分类不存在，请重新选择 |
| `tagIds` | string[] | 否 | 每个 ID 必须存在；最多 5 个；传空数组 = 清空标签 | 每篇文档最多只能选择5个标签 |
| `priceCents` | number | 否 | ≥ 0 | 价格标记不能为负数 |

**出参** `DocumentDetailVo`（字段清单同 §4.6.4）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；`categoryId` / `tagIds` 不存在 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:edit`；**或当前用户不是作者**（US-06 AC-06.2，越权不得改动任何字段） | 无权限修改该文档 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档状态为 `ARCHIVED` 或 `TRASH`（只读，US-06 AC-06.3 / BR-11） | 归档文档为只读，请先恢复上架 |

- 属主校验在 Service 层完成：`created_by != 当前用户ID` 且无 `doc:manage` → 403，且**不产生任何写入**。
- 任一次成功保存：`versionNum` +1（BR-06），刷新 `updated_by` / `updated_at`，新增一条 `doc_version`（`changeType = EDIT`）。
- 允许编辑的状态：`DRAFT`、`PUBLISHED`（PRD 不变式 I1）。

#### 4.6.7 `POST /api/documents/{id}/publish` 提交发布

**用途与价值**：把草稿发布出去让同事能检索到，并写入发布时间与发布版本，形成「内容正式生效」的时间点（US-03）。

**权限点**：`doc:publish`

**入参**：无请求体（路径参数 `id`）

**出参** `DocumentDetailVo`（字段清单同 §4.6.4）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:publish`；或当前用户不是作者 | 无权限发布该文档 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档已是 `PUBLISHED`（US-03 AC-03.2）；或处于 `TRASH`（US-03 AC-03.3）；或 `contentMd` 为空 | 文档当前状态不允许发布 |

- 状态流转 `DRAFT → PUBLISHED`（PRD §4.2 **T2**）：`versionNum` +1、`publish_at` 首次写入、`reject_reason` 清空。
- 新增 `doc_version`（`changeType = PUBLISH`）。
- `contentMd` 为空时拒绝发布（正文为空的「已发布文档」对检索无价值）。

#### 4.6.8 `POST /api/documents/{id}/derive` 派生文档

**用途与价值**：基于可见文档派生一份新草稿（预填正文、记录 `derivedFromId` 血缘），免除重复劳动（US-05）。

**权限点**：`doc:derive`

**入参** `DocumentDeriveDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `title` | string | 否 | 1–128 字符；不传 = 「源文档标题（副本）」 | 文档标题不能为空且不超过128字 |

**出参** `DocumentDetailVo`（字段清单同 §4.6.4；其中 `derivedFromId` 为源文档 ID）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `title` 超长 | 文档标题不能为空且不超过128字 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:derive`；或源文档对当前用户不可见（他人 `DRAFT`，US-05 AC-05.2） | 无权限访问源文档 |
| 404 `NOT_FOUND` | 源文档不存在 | 源文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 源文档处于 `TRASH`（US-05 AC-05.3） | 回收站文档不可派生，请先恢复 |

- 新文档：`status = DRAFT`、`versionNum = 1`、`contentMd` 预填源文档正文、`categoryId` 继承源文档、`derivedFromId = 源文档 ID`、`authorId = 当前用户ID`（持久层写 `created_by`）。
- **源文档不被修改**（不递增其版本号、不动其计数），派生关系只在 `derived_from_id` 单向记录。

#### 4.6.9 `DELETE /api/documents/{id}` 删除文档（进回收站）

**用途与价值**：软删除进回收站（可恢复），把误删从「不可逆」变成「可挽回」（BR-07）。

**权限点**：`doc:delete`

**入参**：路径参数 `id`

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:delete`；或当前用户既不是作者也无 `doc:manage` | 无权限删除该文档 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档已在 `TRASH` 中 | 文档已在回收站中 |

- 状态流转 `DRAFT → TRASH`（**T3**）、`PUBLISHED → TRASH`（**T6**）、`ARCHIVED → TRASH`（**T8**）。
- 由 `@SQLDelete` 置 `deleted = 1` 并刷新 `updated_by` / `updated_at`；**不物理删除**。
- 新增 `doc_version`（`changeType = DELETE`）。

#### 4.6.10 `POST /api/documents/{id}/restore` 恢复文档

**用途与价值**：把回收站文档恢复为草稿，找回误删内容（BR-07）。

**权限点**：`doc:restore`

**入参**：无请求体（路径参数 `id`）

**出参** `DocumentDetailVo`（字段清单同 §4.6.4；`status` 为 `DRAFT`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:restore`；或非作者且无 `doc:manage` | 无权限恢复该文档 |
| 404 `NOT_FOUND` | 文档不存在或已被彻底删除 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档不在 `TRASH` 中 | 仅回收站中的文档可以恢复 |

- 状态流转 `TRASH → DRAFT`（**T9**）：`deleted = 0`，`versionNum` +1，新增 `doc_version`（`changeType = RESTORE`）。
- 恢复后回到草稿态（不直接回到已发布），作者确认内容后再走 4.6.7 发布。

#### 4.6.11 `DELETE /api/documents/{id}/destroy` 彻底删除

**用途与价值**：物理删除文档并清理版本、收藏、标签关联；必须携带二次确认参数，避免误触造成不可逆损失（BR-08）。

**权限点**：`doc:delete`

**入参** `DocumentDestroyDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `confirm` | boolean | 是 | 必须为 `true` | 彻底删除需要二次确认 |

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `confirm` 缺失或不为 `true` | 彻底删除需要二次确认 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:delete`；或非作者且无 `doc:manage` | 无权限彻底删除该文档 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档**不在** `TRASH` 中（BR-08：只能彻底删除回收站文档） | 仅回收站中的文档可以彻底删除 |

- 状态流转 `TRASH → 物理删除`（**T10**），同一事务内级联清理：`doc_version`、`doc_favorite`、`doc_document_tag_rel`，并递减 `doc_tag.use_count`。
- 物理删除不可恢复；前端必须先弹二次确认框再调用本接口。

#### 4.6.12 `GET /api/documents/{id}/versions` 版本历史

**用途与价值**：按版本号倒序列出历史快照与变更类型，回答「谁在什么时候改了什么」，也是审核判断的依据。

**权限点**：`doc:mine`

**入参**：路径参数 `id` + 查询参数 `PageDtoReq`

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |

**出参** `PageVo<DocumentVersionVo>`

`DocumentVersionVo` 字段（完整定义见 §2.7.3）：`id`、`documentId`、`versionNum`、`title`、`contentMd`、`changeType`、`changeRemark`、`operatorId`、`operatorName`，另含继承自 `AuditVo` 的四个审计字段。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:mine`；或非作者且无 `doc:manage` | 无权限查看该文档版本 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |

- 排序：`version_num` 倒序（最新在前）。
- 版本记录**只增不改**（不可变快照），`changeRemark` 在 `REJECT` / `AUDIT` / `ARCHIVE` 类型下为审核意见（BR-12）。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "list": [
      {
        "id": "9003",
        "documentId": "5001",
        "versionNum": 3,
        "title": "CampusSwap 接口规范",
        "changeType": "PUBLISH",
        "changeRemark": "首次发布",
        "operatorId": "1001",
        "operatorName": "张伟",
        "createdAt": "2026-09-21 11:36:05"
      },
      {
        "id": "9002",
        "documentId": "5001",
        "versionNum": 2,
        "title": "CampusSwap 接口规范",
        "changeType": "EDIT",
        "changeRemark": null,
        "operatorId": "1001",
        "operatorName": "张伟",
        "createdAt": "2026-09-20 16:20:41"
      }
    ],
    "total": 3,
    "pageNum": 1,
    "pageSize": 10
  }
}
```

> 说明：版本列表示例为便于阅读省略了 `contentMd`（正文快照）与继承自 `AuditVo` 的四个审计字段，实际响应均包含。

#### 4.6.13 `POST /api/documents/{id}/favorite` 收藏文档

**用途与价值**：收藏文档形成个人常用入口；重复收藏幂等成功，前端无需先查询再决定调哪个接口（BR-10）。

**权限点**：`doc:favorite`

**入参**：无请求体（路径参数 `id`）

**出参** `FavoriteVo`（字段：`documentId`、`favorited`、`favoriteCount`；完整定义见 §2.7.3）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:favorite`；或该文档对当前用户不可见 | 无权限收藏该文档 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |

- 关系写入 `doc_favorite`，复合主键 `(user_id, document_id)`；重复收藏返回 `200` 且不重复累加计数（幂等）。
- 首次收藏成功后 `doc_document.favorite_count` +1。

#### 4.6.14 `DELETE /api/documents/{id}/favorite` 取消收藏

**用途与价值**：取消收藏并同步收藏计数，保证列表上的收藏数与实际关系一致。

**权限点**：`doc:favorite`

**入参**：无请求体（路径参数 `id`）

**出参** `FavoriteVo`（字段清单同 §4.6.13，`favorited` 为 `false`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:favorite` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |

- 删除 `doc_favorite` 中 `(当前用户, 文档)` 记录；若原本未收藏则**幂等**返回 200。
- 计数保护：`favorite_count` 减 1 且不小于 0。

#### 4.6.15 `GET /api/favorites` 我的收藏

**用途与价值**：分页查看我的收藏（只返回当前仍可见的文档），支撑「我的收藏」入口与快速回到常用内容。

**权限点**：`doc:favorite`

**入参** `FavoritePageDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符；匹配标题/摘要 | 关键词长度不能超过64个字符 |

**出参** `PageVo<DocumentVo>`（字段清单同 §4.6.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:favorite` | 无权限执行该操作 |

- 查询条件：`doc_favorite.user_id = 当前用户ID` 且文档未被逻辑删除。
- 收藏的文档若已归档（`ARCHIVED`）仍返回，`status` 字段给出真实状态，前端以灰色标签提示；已彻底删除的文档不再出现。
- 排序：收藏时间（`doc_favorite.created_at`）倒序。

### 4.7 模块 M-07：审核与治理（5 条）

#### 4.7.1 `GET /api/review/documents` 待治理列表

**用途与价值**：按状态取待治理文档（默认已发布），作为审核队列的数据源，让管理员一屏处理平台内容（US-07）。

**权限点**：`doc:review`

**入参** `ReviewPageDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符；匹配标题/摘要 | 关键词长度不能超过64个字符 |
| `status` | string | 否 | 仅 `PUBLISHED` / `ARCHIVED`；默认 `PUBLISHED` | 文档状态取值非法 |

**出参** `PageVo<DocumentVo>`（字段清单同 §4.6.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界；`status` 传了 `DRAFT` / `TRASH`（审核队列不处理这两种状态） | 文档状态取值非法 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:review` | 无权限执行该操作 |

- 管理员可见**全部用户**的文档（不受作者限制），这是与 4.6.2「我的文档」的关键差别。
- 默认排序 `updated_at` 倒序；列表项 `canEdit` 对管理员恒为 `false`（管理员用审核/归档接口治理，而不是直接改他人正文）。

#### 4.7.2 `POST /api/documents/{id}/audit` 审核通过

**用途与价值**：审核通过并留痕（写 `AUDIT` 版本 + 意见），回答「这篇是谁审的、依据什么」，形成可追溯的内容治理记录（US-07）。

**权限点**：`doc:audit`

**入参** `DocumentAuditDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `remark` | string | 是 | 1–255 字符（BR-12：审核意见必填） | 审核意见不能为空且不超过255字 |

**出参** `DocumentDetailVo`（字段清单同 §4.6.4）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `remark` 为空或超长 | 审核意见不能为空且不超过255字 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:audit`（US-07 AC-07.2：`STAFF` 调用一律 403） | 无权限执行该操作 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档状态不是 `PUBLISHED` | 仅已发布文档可以审核通过 |

- 审核通过**不改变 `status`**（保持 `PUBLISHED`），仅新增 `doc_version`（`changeType = AUDIT`，`changeRemark = remark`，操作人记为该管理员：持久层写 `created_by`，出参展示为 `operatorId` / `operatorName`）。
- `versionNum` +1，使审核动作在版本历史中可见。

#### 4.7.3 `POST /api/documents/{id}/reject` 驳回文档

**用途与价值**：驳回并把理由回传属主（状态退回草稿），让作者明确知道要怎么改，避免「不知道为什么被打回」（US-07）。

**权限点**：`doc:reject`

**入参** `DocumentRejectDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `reason` | string | 是 | 1–255 字符（BR-12：驳回理由必填） | 驳回理由不能为空 |

**出参** `DocumentDetailVo`（字段清单同 §4.6.4；`status` 为 `DRAFT`、`rejectReason` 为本次理由）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `reason` 为空或超长（US-07 AC-07.3） | 驳回理由不能为空 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:reject` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档状态不是 `PUBLISHED` | 仅已发布文档可以驳回 |

- 状态流转 `PUBLISHED → DRAFT`（本文件登记为 **T11**，PRD §4.2 尚未列出该边，见 §7）。
- 写入 `doc_document.reject_reason = reason`（作者在详情页可见），`versionNum` +1，新增 `doc_version`（`changeType = REJECT`，`changeRemark = reason`）。
- 驳回后文档回到草稿态，作者修改后可再次走 4.6.7 发布。

**示例**

请求：

```http
POST /api/documents/5001/reject
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json;charset=UTF-8

{ "reason": "第三章缺少接口鉴权说明，请补充后重新提交" }
```

响应（节选关键字段，`data` 结构同 §4.6.4）：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "5001",
    "title": "CampusSwap 接口规范",
    "status": "DRAFT",
    "versionNum": 4,
    "rejectReason": "第三章缺少接口鉴权说明，请补充后重新提交",
    "updatedAt": "2026-09-21 15:10:22",
    "canEdit": true
  }
}
```

#### 4.7.4 `POST /api/documents/{id}/archive` 归档文档

**用途与价值**：归档过时或不合规内容（转为只读）：内容保留可追溯，同时停止继续扩散（不再出现在检索结果里）（US-07）。

**权限点**：`doc:archive`

**入参** `DocumentAuditDtoReq`（请求体 JSON；字段与校验同 4.7.2，`remark` 必填 1–255 字符）

**出参** `DocumentDetailVo`（字段清单同 §4.6.4；`status` 为 `ARCHIVED`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `remark` 为空或超长 | 审核意见不能为空且不超过255字 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:archive` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档状态不是 `PUBLISHED` | 仅已发布文档可以归档 |

- 状态流转 `PUBLISHED → ARCHIVED`（PRD §4.2 **T5**）：归档后文档**只读**（PRD 不变式 I1），任何写接口返回 409（BR-11）。
- 新增 `doc_version`（`changeType = ARCHIVE`，`changeRemark = remark`，操作人记为当前管理员：持久层写 `created_by`，出参展示为 `operatorId` / `operatorName`）。
- 归档文档不出现在 4.6.1 检索结果中（该接口只查 `PUBLISHED`），但作者仍可在 4.6.2 的 `ARCHIVED` 筛选中看到。

#### 4.7.5 `POST /api/documents/{id}/republish` 恢复上架

**用途与价值**：把归档内容恢复上架，用于修正误归档，让内容重新可被检索（US-07 治理闭环的回程边）。

**权限点**：`doc:archive`

**入参**：无请求体（路径参数 `id`）

**出参** `DocumentDetailVo`（字段清单同 §4.6.4；`status` 为 `PUBLISHED`、`rejectReason` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:archive` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 文档不存在 | 文档不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 文档状态不是 `ARCHIVED` | 仅已归档文档可以恢复上架 |

- 状态流转 `ARCHIVED → PUBLISHED`（PRD §4.2 **T7**）：清空 `reject_reason`，`versionNum` +1，新增 `doc_version`（`changeType = PUBLISH`，`changeRemark = 恢复上架`）。
- `publish_at` 保持首次发布时间不变（它是「内容首次生效」的时间戳，不因恢复上架而改写）。

### 4.8 模块 M-08：分类与标签（8 条）

#### 4.8.1 `GET /api/categories/tree` 分类树

**用途与价值**：取回三层分类树，同时供检索页筛选与编辑器选择分类，保证两处口径一致。

**权限点**：`doc:search`

**入参**：无

**出参** `CategoryVo[]`（根节点数组，子分类递归在 `children` 中）

`CategoryVo` 字段（完整定义见 §2.7.3）：`id`、`name`、`parentId`、`ancestors`、`sortOrder`、`children`，另含继承自 `AuditVo` 的四个审计字段。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:search` | 无权限执行该操作 |

- 排序：同级按 `sortOrder` 升序。
- 分类最多 3 层（BR-13），前端选择器按层级缩进展示。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "id": "5",
      "name": "后端开发",
      "parentId": "0",
      "ancestors": "0",
      "sortOrder": 1,
      "children": [
        {
          "id": "7",
          "name": "Spring Data JPA",
          "parentId": "5",
          "ancestors": "0,5",
          "sortOrder": 1,
          "children": []
        }
      ]
    }
  ]
}
```

#### 4.8.2 `POST /api/categories` 新增分类

**用途与价值**：新建分类节点，让文档拥有稳定的归类维度，避免分类体系僵化。

**权限点**：`doc:category:edit`

**入参** `CategoryCreateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符；同一父分类下不可重名 | 分类名称不能为空且不超过64个字符 |
| `parentId` | string | 是 | 必须存在于 `doc_category`；根分类传 `"0"` | 上级分类不存在，请重新选择 |
| `sortOrder` | number | 否 | ≥ 0，默认 0 | 排序号必须大于等于0 |

**出参** `CategoryVo`（字段清单同 §4.8.1，`children` 为空数组）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；同级重名；**层级超过 3 层**（BR-13） | 分类最多支持3层 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:category:edit` | 无权限执行该操作 |
| 409 `CONFLICT_STATUS` | 唯一约束冲突 | 同级分类下已存在同名分类 |

- `ancestors` 自动计算：父分类 `ancestors` + `,` + 父分类 `id`。

#### 4.8.3 `PUT /api/categories/{id}` 编辑分类

**用途与价值**：改名、移动、排序分类；禁止移到自身子孙下，保证分类树始终是无环结构。

**权限点**：`doc:category:edit`

**入参** `CategoryUpdateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–64 字符；同一父分类下不可重名 | 分类名称不能为空且不超过64个字符 |
| `parentId` | string | 是 | 必须存在；不能是自己或自己的子孙；移动后总层级 ≤ 3 | 不能将分类移动到其子分类下 |
| `sortOrder` | number | 否 | ≥ 0 | 排序号必须大于等于0 |

**出参** `CategoryVo`（字段清单同 §4.8.1）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 字段校验失败；`parentId` 是自己或自己的子孙；移动后层级超过 3 层 | 不能将分类移动到其子分类下 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:category:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 分类不存在 | 分类不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 唯一约束冲突 | 同级分类下已存在同名分类 |

- 父分类变化后，**事务内级联重写所有子孙分类的 `ancestors`**。

#### 4.8.4 `DELETE /api/categories/{id}` 删除分类

**用途与价值**：删除空分类；该分类下仍有文档时拒绝并提示先迁移，杜绝「文档挂在不存在的分类上」（BR-13）。

**权限点**：`doc:category:edit`

**入参**：路径参数 `id`

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:category:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 分类不存在 | 分类不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 存在子分类；或该分类下仍有未删除文档（含 `DRAFT`/`PUBLISHED`/`ARCHIVED`） | 该分类下仍有子分类或文档，请先迁移文档 |

- 判定范围：`doc_document.category_id = id` 且 `deleted = 0`；回收站文档不阻塞删除（彻底删除时分类归属已无意义）。
- 删除成功后该分类下不再有任何引用（同事务保证）。

#### 4.8.5 `GET /api/tags` 标签列表

**用途与价值**：分页查标签及使用量，支持按热度挑标签，也便于管理员发现应合并的同义标签。

**权限点**：`doc:search`

**入参** `TagPageDtoReq`（查询参数）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `pageNum` | number | 否 | ≥ 1，默认 1 | 页码必须大于等于1 |
| `pageSize` | number | 否 | 1–100，默认 10 | 每页条数必须在1到100之间 |
| `keyword` | string | 否 | ≤ 64 字符；匹配 `name` | 关键词长度不能超过64个字符 |

**出参** `PageVo<TagVo>`

`TagVo` 字段（完整定义见 §2.7.3）：`id`、`name`、`useCount`，另含继承自 `AuditVo` 的四个审计字段。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 分页越界 | 返回 `message` 原文 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:search` | 无权限执行该操作 |

- 排序：`use_count` 倒序（热度优先）→ `id` 升序。
- 编辑器内的标签选择器用 `pageSize=100` 一次拉取热门标签。

#### 4.8.6 `POST /api/tags` 新增标签

**用途与价值**：编辑器内即时建标签（如输入「RBAC」后发现没有这个词），避免作者因缺词而放弃打标。

**权限点**：`doc:tag:edit`

**入参** `TagCreateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–16 字符；全局唯一（BR-14） | 标签名称不能为空且不超过16个字符 |

**出参** `TagVo`（字段清单同 §4.8.5，新建时 `useCount` 为 0）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `name` 为空或超过 16 字符 | 标签名称不能为空且不超过16个字符 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:tag:edit` | 无权限执行该操作 |
| 409 `CONFLICT_STATUS` | `name` 命中唯一索引 `uk_doc_tag_name` | 标签已存在，请直接选择 |

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": { "id": "33", "name": "RBAC", "useCount": 0 }
}
```

#### 4.8.7 `PUT /api/tags/{id}` 编辑标签

**用途与价值**：标签改名（等价于合并同义标签），改名后所有引用处自动生效，一次修正全站口径。

**权限点**：`doc:tag:edit`

**入参** `TagUpdateDtoReq`（请求体 JSON）

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `name` | string | 是 | 1–16 字符；全局唯一 | 标签名称不能为空且不超过16个字符 |

**出参** `TagVo`（字段清单同 §4.8.5）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | `name` 为空或超过 16 字符 | 标签名称不能为空且不超过16个字符 |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:tag:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 标签不存在 | 标签不存在或已被删除 |
| 409 `CONFLICT_STATUS` | 新名称与既有标签重复 | 标签已存在，请直接选择 |

- 标签与文档的关联存放在 `doc_document_tag_rel`，改名不需要改动关联表（关联的是 `tag_id`）。

#### 4.8.8 `DELETE /api/tags/{id}` 删除标签

**用途与价值**：删除废弃标签并清理文档关联，防止标签越积越乱；已引用该标签的文档不会受影响，只是少一个标签。

**权限点**：`doc:tag:edit`

**入参**：路径参数 `id`

**出参**：`Void`（`data` 为 `null`）

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:tag:edit` | 无权限执行该操作 |
| 404 `NOT_FOUND` | 标签不存在 | 标签不存在或已被删除 |

- 同一事务内：删除 `doc_tag` 记录 + 删除 `doc_document_tag_rel` 中该 `tag_id` 的全部关联。
- 关联文档数量较多时仍需**同步完成**（不允许异步补偿），保证「标签列表」与文档标签展示一致。

### 4.9 模块 M-09：文件与统计（2 条）

#### 4.9.1 `POST /api/upload/image` 上传图片

**用途与价值**：上传 Markdown 正文里的图片并返回相对 URL；扩展名 + MIME + 大小三重白名单拦截非法文件，文件名 UUID 化防路径穿越（BR-15、NFR-S6）。

**权限点**：`doc:upload`

**入参**：`multipart/form-data`，表单字段名 **`file`**

| 字段 | 类型 | 必填 | 校验规则 | 中文错误提示 |
|---|---|---|---|---|
| `file` | file | 是 | 扩展名 ∈ `jpg`/`jpeg`/`png`/`webp`/`gif`；MIME 需与扩展名一致；单文件 ≤ 5MB | 仅支持 jpg、jpeg、png、webp、gif 格式，且单张不超过5MB |

**出参** `ImageVo`

`ImageVo` 字段（完整定义见 §2.7.4）：`url`。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 400 `BAD_REQUEST` | 未选择文件；扩展名不在白名单；MIME 与扩展名不符；文件超过 5MB | 仅支持 jpg、jpeg、png、webp、gif 格式，且单张不超过5MB |
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:upload` | 无权限执行该操作 |

- 存储路径：`backend/uploads/yyyy/MM/{uuid}.{ext}`，入库与返回均为**相对 URL**（如 `/uploads/2026/09/8f3c1a2b.png`）。
- 原始文件名只回显、不落库、不参与路径拼接。
- 前端先做本地类型/大小预校验再上传（MASTER-PLAN §6.2），服务端校验**不可省略**。
- `uploads/` 目录不提交 Git（`backend/.gitignore` 已忽略，仅保留 `.gitkeep`）。

**示例**

```http
POST /api/upload/image
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: multipart/form-data; boundary=----Boundary

------Boundary
Content-Disposition: form-data; name="file"; filename="架构图.png"
Content-Type: image/png
```

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "url": "/uploads/2026/09/8f3c1a2b7d1e4f60.png"
  }
}
```

#### 4.9.2 `GET /api/stats/overview` 统计概览

**用途与价值**：返回个人与平台的三个核心计数（我的文档数、我的收藏数、平台已发布文档数），让首页卡片一眼看到个人产出与平台规模。

**权限点**：`doc:center`

**入参**：无

**出参** `StatVo`

`StatVo` 字段（完整定义见 §2.7.4）：`myDocumentCount`、`myFavoriteCount`、`publishedCount`。

**业务规则与错误码**

| 错误码 | 触发条件 | 前端提示 |
|---|---|---|
| 401 `UNAUTHORIZED` | 未登录 | 登录状态已失效，请重新登录 |
| 403 `NO_PERMISSION` | 缺少 `doc:center` | 无权限执行该操作 |

- 计数口径：
  - `myDocumentCount` = `created_by = 当前用户ID`、未逻辑删除、且状态不为 `TRASH` 的文档数。
  - `myFavoriteCount` = `doc_favorite.user_id = 当前用户ID` 且文档未逻辑删除的记录数。
  - `publishedCount` = 全平台 `status = PUBLISHED` 的文档数。
- 聚合查询在 Service 层用一条 `@Query` 完成，禁止 3 次单独 `count()`（NFR-P2）。
- 待治理数量与阅读量**不在本接口返回**：待治理数量由 4.7.1 列表的 `total` 提供，阅读量在文档列表/详情中按需展示。

**示例**

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "myDocumentCount": 12,
    "myFavoriteCount": 7,
    "publishedCount": 86
  }
}
```

---

## 5. 故事覆盖矩阵

| 故事 | 名称 | 覆盖接口（序号） | 对应验收标准 |
|---|---|---|---|
| US-01 | 登录认证 | 1 `login`、2 `logout`、3 `me` | AC-01.1（返回 token + 权限码、不含 `passwordHash`）→ 1；AC-01.2（401 文案不区分用户/密码错）→ 1；AC-01.3（非 `ACTIVE` 返回 403 `USER_DISABLED`）→ 1 |
| US-02 | 创建并保存草稿 | 29 `POST /documents`、46 `categories/tree`、50 `tags`（表单数据源） | AC-02.1（`DRAFT` + `versionNum=1` + `authorId=当前用户` + `priceCents=0`）→ 29；AC-02.2（标题校验 400 中文提示）→ 29；AC-02.3（无 token 401）→ 29 |
| US-03 | 提交发布 | 32 `publish`、30 `GET /documents/{id}`（发布后校验） | AC-03.1（`PUBLISHED` + 版本 +1 + 写 `doc_version`）→ 32；AC-03.2（重复发布 409）→ 32；AC-03.3（`TRASH` 发布 409）→ 32 |
| US-04 | 检索文档 | 26 `GET /documents`、30 `GET /documents/{id}`、46 `categories/tree`、50 `tags` | AC-04.1（分页 + `total` + 默认 `updatedAt` 倒序）→ 26；AC-04.2（无命中 200 + 空列表）→ 26；AC-04.3（他人 `DRAFT` 不出现）→ 26 |
| US-05 | 派生复用 | 33 `derive`、30 `GET /documents/{id}`（取源文档） | AC-05.1（新 `DRAFT`、`derivedFromId`、正文预填、源文档不变）→ 33；AC-05.2（源文档不可见 403）→ 33；AC-05.3（源文档 `TRASH` 409）→ 33 |
| US-06 | 编辑自己的文档 | 31 `PUT /documents/{id}`、30 `GET /documents/{id}` | AC-06.1（保存成功 + 版本 +1 + 写版本）→ 31；AC-06.2（非属主 403 且内容不变）→ 31；AC-06.3（`ARCHIVED` 只读 409）→ 31 |
| US-07 | 审核与治理 | 41 `review/documents`、42 `audit`、43 `reject`、44 `archive`、45 `republish`、30 `GET /documents/{id}`、37 `versions` | AC-07.1（归档成功 + 意见与操作人留痕）→ 44；AC-07.2（`STAFF` 调审核 403）→ 42 / 43 / 44；AC-07.3（驳回无理由 400）→ 43 |
| US-08 | 配置权限并授权 | 15 `PUT /roles/{id}/permissions`、14 `GET /roles/{id}/permissions`、16 `permissions/tree`、25 `PUT /depts/{id}/roles`、24 `GET /depts/{id}/roles`、17 `POST /permissions`、18 `PUT /permissions/{id}` | AC-08.1（授权保存 + 清缓存即时生效）→ 15；AC-08.2（非 `SYS_ADMIN` 403）→ 15 / 17 / 18；AC-08.3（权限节点移到自身子孙下 400）→ 18 |

**结论**：8 个故事全部有接口承载，`US-07`（5 个专用接口）与 `US-08`（7 个接口）覆盖最厚，与它们「治理 + 授权」的复杂度相符。

---

## 6. 页面—接口映射

| 前端路由 | 页面 | 调用接口（序号） |
|---|---|---|
| `/login` | 登录 | 1 |
| `/docs` | 文档列表 / 检索 | 26、46、50、55、30、3、38、39 |
| `/docs/:id` | 文档详情 | 30、37、33、38、39 |
| `/docs/edit/:id?` | Markdown 编辑器 | 29、31、32、34、30、46、50、51、54 |
| `/my` | 我的文档 | 27、28、34、35、36、30、31、32、40、55 |
| `/review` | 审核队列 | 41、42、43、44、45、30、37 |
| `/admin/docs` | 文档管理（治理 + 分类标签） | 26、30、44、45、46、47、48、49、50、51、52、53 |
| `/admin/system` | 用户 / 角色 / 权限 / 部门 | 4、5、6、7、8、9、10、11、12、13、14、15、16、17、18、19、20、21、22、23、24、25 |
| 全局（App 顶栏 / 路由守卫） | 登出、刷新用户态 | 2、3 |

**覆盖结论**：55 个接口**每一个**都至少被一个页面调用（校验方式见 §8）。`/admin/system` 承载系统域 22 个接口，是前端工作量最大的页面；实现时按「用户 → 部门 → 角色 → 权限」顺序分四个 Tab 落地。

---

## 7. 状态机与接口的对应

### 7.1 状态迁移边（引用 PRD §4.2 的 T1~T10，另加本文件登记的 T11）

| 边 | 源状态 → 目标状态 | 触发接口（序号） | 权限点 | 副作用 |
|---|---|---|---|---|
| **T1** | — → `DRAFT` | 29 `POST /api/documents` | `doc:create` | `versionNum=1`，写 `doc_version`（`CREATE`） |
| **T2** | `DRAFT` → `PUBLISHED` | 32 `POST /api/documents/{id}/publish` | `doc:publish` | 版本 +1，写 `publish_at`，清 `reject_reason`，写版本（`PUBLISH`） |
| **T3** | `DRAFT` → `TRASH` | 34 `DELETE /api/documents/{id}` | `doc:delete` | 软删除 `deleted=1`，写版本（`DELETE`） |
| **T4** | `PUBLISHED` → `PUBLISHED` | 31 `PUT /api/documents/{id}` | `doc:edit` | 版本 +1，刷新 `updated_at`，写版本（`EDIT`） |
| **T5** | `PUBLISHED` → `ARCHIVED` | 44 `POST /api/documents/{id}/archive` | `doc:archive` | 转只读，写版本（`ARCHIVE` + 意见） |
| **T6** | `PUBLISHED` → `TRASH` | 34 `DELETE /api/documents/{id}` | `doc:delete` | 软删除，写版本（`DELETE`） |
| **T7** | `ARCHIVED` → `PUBLISHED` | 45 `POST /api/documents/{id}/republish` | `doc:archive` | 清 `reject_reason`，版本 +1，写版本（`PUBLISH`） |
| **T8** | `ARCHIVED` → `TRASH` | 34 `DELETE /api/documents/{id}` | `doc:delete` | 软删除，写版本（`DELETE`） |
| **T9** | `TRASH` → `DRAFT` | 35 `POST /api/documents/{id}/restore` | `doc:restore` | `deleted=0`，版本 +1，写版本（`RESTORE`） |
| **T10** | `TRASH` → 物理删除 | 36 `DELETE /api/documents/{id}/destroy` | `doc:delete` | 级联清理 `doc_version`、`doc_favorite`、`doc_document_tag_rel` |
| **T11**（本文件登记） | `PUBLISHED` → `DRAFT` | 43 `POST /api/documents/{id}/reject` | `doc:reject` | 写 `reject_reason`，版本 +1，写版本（`REJECT` + 理由） |

### 7.2 不改状态但写留痕的审核接口

| 接口（序号） | 状态变化 | 留痕 |
|---|---|---|
| 42 `POST /api/documents/{id}/audit` | 无（保持 `PUBLISHED`） | 版本 +1，写 `doc_version`（`AUDIT` + 意见） |
| 38 / 39 收藏、39 取消收藏 | 无 | 只改 `doc_favorite` 与 `favorite_count`，不写 `doc_version` |
| 30 `GET /api/documents/{id}` | 无 | 只累加 `view_count`（Redis 去重，BR-09） |

### 7.3 不变式与接口的绑定（PRD §4.3）

| 不变式 | 承载接口 |
|---|---|
| I1 只有 `DRAFT` / `PUBLISHED` 可改正文，`ARCHIVED` 只读 | 31（`ARCHIVED`/`TRASH` 返回 409） |
| I2 `version_num` 单调递增 | 29 / 31 / 32 / 35 / 42 / 43 / 44 / 45 |
| I3 无死胡同（每个状态至少一条出边） | T2/T3、T4/T5/T6、T7/T8、T9/T10 全部有接口承载（见 §7.1） |
| I4 只有非 `TRASH` 的已发布文档进入检索结果 | 26（只查 `PUBLISHED`） |
| I5 非法状态转移一律 409 `CONFLICT_STATUS` | 31 / 32 / 34 / 35 / 36 / 42 / 43 / 44 / 45 |

---

## 8. 冻结自检

| 自查项 | 结论 | 证据 |
|---|---|---|
| 接口总数与要求一致 | ✅ | 总表 §3 = **55 条**（GET 18 / POST 18 / PUT 11 / DELETE 8），§4 逐条详规也是 55 条 |
| 出参统一规则 | ✅ | 实体型 VO 全部继承 `AuditVo`（§2.7.1）；55 条接口出参**均无 `deleted` 字段**；权限/部门/分类接口无 enable/disable 字段或子资源 |
| 与前端字段口径逐字对齐 | ✅ | 列表排序单字段 `sort`（`DocumentSort`）、标签筛选 `tagIds`（AND）、文档作者 `authorId`+`authorName`、版本操作人 `operatorId`+`operatorName`、角色/版本 DTO 用 `RoleDtoReq`、用户角色入参 `roles`、图片出参 `ImageVo`、统计出参 `StatVo` 三字段——全部与 `GLOSSARY.md` §3.6 / §3.7 一致 |
| 停用接口方法口径 | ✅ | 用户停用/启用统一为 `PUT /api/users/{id}/status`（入参 `UserStatusDtoReq{status}`，取值 `ACTIVE`/`LOCKED`/`DISABLED`），与前端规范一致 |
| 路径与要求清单逐字一致 | ✅ | 55 条路径与任务清单一一对应，无增删改（脚本比对通过） |
| 8 个故事全覆盖 | ✅ | §5：US-01~US-08 每个故事至少 1 个接口，US-07 = 5 个、US-08 = 7 个 |
| 每条接口都有权限点 | ✅ | §3 总表「权限点」列 55/55 非空；公开 1 条、登录即可 2 条、权限点 52 条 |
| 每条接口都有错误码 | ✅ | §3 总表「主要错误码」列 + §4 每条接口的「业务规则与错误码」表 |
| 每个接口至少被一个页面调用 | ✅ | §6：9 行映射覆盖 1~55 全部序号 |
| 状态机边全部有接口承载 | ✅ | §7.1：T1~T10 + T11 共 11 条边逐条绑定接口 |
| 字段名全部取自 GLOSSARY v2.1 | ✅ | 出参取自 §2.7 字典（同 GLOSSARY §3）；主键 `Long` 序列化为字符串、审计列用 `created_at`/`created_by`/`updated_at`/`updated_by`/`deleted`、金额用 `price_cents` |
| 禁用别名 0 命中（正文） | ✅ | 第 1~8 章正文与全部字段定义中 **0 命中**；作废名仅集中在**附录 A 迁移对照表**（刻意保留作迁移依据，代码中禁止使用） |
| 响应示例符合规范 | ✅ | 全部示例包在 `ResponseResult` 内，ID 为字符串，时间为 `yyyy-MM-dd HH:mm:ss` |

**冻结签署**：本文件 v1.0 自 2026-09-21 起冻结。接口新增或字段变更必须先改本文件与 `GLOSSARY.md`，再改代码。

---

## 附录 A：作废写法对照表（DEPRECATED — anti-alias reference）（迁移既有文档用，**代码中禁止出现**）

> 用途：`PRD.md` / `USER_STORIES.md` / `MASTER-PLAN.md §5.2 §5.3` 迁移到 GLOSSARY v2.1 时的逐项对照依据。
> 约束：本表右列为**唯一合法名**；左列仅作为历史写法出现在本附录，**不得出现在 Entity / DTO / VO / TS 类型 / 接口字段中**。

| 作废写法 | GLOSSARY v2.1 唯一合法名 | 影响范围 |
|---|---|---|
| `is_enabled` / `isEnabled` | `status`（`ACTIVE` / `LOCKED` / `DISABLED`） | 用户启停 |
| `role_code` / `roleCode` | `Role.code` + `sys_user_role` 关联 | 用户与角色 |
| `author_id`（数据库列名） | 数据库列统一用 `created_by`；文档 VO 的**展示层字段是 `authorId` + `authorName`**（合法命名，见 GLOSSARY §3.7 映射表，不是新增列） | 文档作者 |
| `operator_id`（数据库列名） | 数据库列统一用 `created_by`；版本 VO 的**展示层字段是 `operatorId` + `operatorName`**（合法命名） | 版本操作人 |
| `update_at` / `updateAt` / `create_by` / `update_by` | `updated_at` / `updatedAt` / `created_by` / `updated_by` | 全部审计列 |
| `is_deleted` / `del_flag` / `deleted_flag` | `deleted` | 逻辑删除标记 |
| `perm_code` / `perm_name` / `perm_type` | `code` / `name` / `type` | 权限表字段 |
| `dept_name` / `category_name` / `tag_name` / `role_name` | `name` | 各表名称字段 |
| `sort_num` / `order_num` / `seq` | `sort_order` | 排序字段 |
| `sortBy` + `sortOrder`（当列表排序方向用） | 单个 `sort`，类型 `DocumentSort`（`updatedAt_desc` / `publishAt_desc` / `viewCount_desc`） | 列表排序入参 |
| `tagId`（单数，列表筛选） | `tagIds`（字符串数组，AND 命中） | 标签筛选入参 |
| `UploadVo`（含 name/size） | `ImageVo`（仅 `url`） | 图片上传出参 |
| `permissionCount`、`userCount`、`expiresIn`、`publishAt`、`lastLoginAt` | 不在 VO 中返回（按 GLOSSARY §3.7 字段字典执行） | 角色/部门/认证/文档出参 |
| `Result<T>` | `ResponseResult<T>` | 统一响应体 |
| `PageResult<T>` / `PageInfo<T>` | `PageVo<T>` | 分页包装 |
| `DocumentListVo` | `DocumentVo` | 文档列表出参 |
| `DocumentTag` | `DocumentTagRel` | 文档-标签关联表 |
| `sys_login_log` | `sys_user_permission` | 系统域第 8 张表（GLOSSARY v2.1 变更记录 ⑦） |
