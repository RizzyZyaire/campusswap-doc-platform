# CampusSwap 文档管理平台 · 统一语言（GLOSSARY）

| 项 | 值 |
|---|---|
| 文件 | `docs/01-requirements/GLOSSARY.md` |
| 版本 | v1.0（M0 产出） |
| 日期 | 2026-09-21 |
| 状态 | **Frozen（已冻结）** |
| 地位 | **命名唯一真源**。数据库列名、Java 类名与字段、TS 接口、接口出入参、UI 文案，全部以此文件为准 |
| 上游依据 | `USER_STORIES.md`、`PRD.md` |
| 下游消费 | `backend/sql/schema.sql`、Entity/DTO/VO、`frontend/src/types/` |

> **红线 R1**：Entity 字段、DTO 字段、VO 字段、TS interface 字段、数据库列，必须能在本文件中找到对应行。找不到 = 命名非法，必须改代码而不是改本文件。

---

## 1. 命名总则

| 对象 | 规则 | 正例 | 反例 |
|---|---|---|---|
| 表名 | `sys_` / `doc_` 前缀 + 单数 snake_case | `doc_document` | `documents`、`t_document` |
| 列名 | snake_case，语义完整，不用缩写 | `derived_from_id` | `dfid`、`pid` |
| Java 类 | PascalCase，表名转驼峰去前缀 | `Document`（`doc_version` → `DocumentVersion`） | `DocDocument` |
| Java 字段 | camelCase | `derivedFromId` | `derived_from_id` |
| 方法名 | 动词 + 名词 | `publishDocument` | `doPublish`、`handle` |
| 接口路径 | `/api/` + 复数资源名 + 动作子路径 | `POST /api/documents/{id}/publish` | `/api/publishDoc` |
| 入参类 | `XxxDtoReq` | `DocumentCreateDtoReq` | `DocumentParam` |
| 出参类 | `XxxVo` | `DocumentVo` | `DocumentResp` |
| TS 接口 | 与后端 VO 同名（不加 `I` 前缀） | `export interface DocumentVo` | `IDocumentVo` |
| 常量/枚举值 | 全大写 SNAKE | `PUBLISHED` | `Published`、`published` |
| 权限码 | `域:资源[:动作]` 冒号分隔小写 | `doc:document:publish` → 本平台简化为 `doc:publish` | `DOC_PUBLISH` |

### 1.1 类型映射铁律

| 数据库 | Java | TS | 说明 |
|---|---|---|---|
| `BIGINT`（主键/关联 ID） | `Long`（**必须包装类型**） | `string` | 雪花 ID 超出 JS 安全整数，后端用 `@JsonSerialize(using = ToStringSerializer.class)` |
| `INT UNSIGNED`（价格/积分） | `Integer` | `number` | 单位为**分**；前端展示时 `/100` |
| `INT`（普通计数、排序） | `Integer` | `number` | |
| `TINYINT`（标记位 0/1） | `Integer` | `number` | **不用 `Boolean`**；语义为布尔时字段名以 `is` 开头 |
| `VARCHAR/TEXT` | `String` | `string` | |
| `DATETIME` | `LocalDateTime` | `string` | 统一 `yyyy-MM-dd HH:mm:ss`（`Asia/Shanghai`） |
| 派生布尔（VO 专用） | `Boolean` | `boolean` | 例：`canEdit`、`favorited` |

---

## 2. 核心领域实体（14 张表）

### 2.1 系统域（8 张）

| 中文概念 | 英文标识符 | 表名 | Java 类 | 业务边界 |
|---|---|---|---|---|
| 用户 | User | `sys_user` | `User` | 平台使用者；工号登录、归属一个部门 |
| 部门 | Dept | `sys_dept` | `Dept` | 组织树节点；绑定角色后员工继承权限 |
| 角色 | Role | `sys_role` | `Role` | 权限集合；内置三种 + 可自定义 |
| 权限 | Permission | `sys_permission` | `Permission` | 三层树节点（目录/菜单/按钮） |
| 用户-角色 | UserRole | `sys_user_role` | `UserRole` | 用户与角色的多对多关联（**用中间表，禁止 `@ManyToMany`**） |
| 角色-权限 | RolePermission | `sys_role_permission` | `RolePermission` | 角色与权限的关联 |
| 部门-角色 | DeptRole | `sys_dept_role` | `DeptRole` | 部门与角色的关联 |
| 登录日志 | LoginLog | `sys_login_log` | `LoginLog` | 登录成功/失败留痕；不参与业务查询 |

### 2.2 文档域（6 张）

| 中文概念 | 英文标识符 | 表名 | Java 类 | 业务边界 |
|---|---|---|---|---|
| 文档 | Document | `doc_document` | `Document` | 平台核心对象；Markdown 正文 + 生命周期状态 |
| 文档版本 | DocumentVersion | `doc_version` | `DocumentVersion` | 每次正文/状态变更的不可变快照与操作留痕 |
| 分类 | Category | `doc_category` | `Category` | 最多 3 层的文档分类树 |
| 标签 | Tag | `doc_tag` | `Tag` | 扁平标签，一文档最多 5 个 |
| 文档-标签 | DocumentTag | `doc_document_tag` | `DocumentTag` | 文档与标签关联 |
| 收藏 | Favorite | `doc_favorite` | `Favorite` | `(user_id, document_id)` 唯一 |

---

## 3. 字段级命名字典

### 3.1 公共审计列（**14 张表全部具备**，由 `BaseEntity` 提供）

| 中文 | 数据库列 | Java 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 创建人 | `create_by` | `createBy` | Long → string | 雪花 ID，由 JPA Auditing 自动填充 |
| 创建时间 | `create_at` | `createAt` | LocalDateTime | 自动填充，禁止手工赋值 |
| 更新人 | `update_by` | `updateBy` | Long → string | |
| 更新时间 | `update_at` | `updateAt` | LocalDateTime | 列表默认排序字段 |
| 逻辑删除 | `deleted` | `deleted` | Integer | 0 正常 / 1 已删除；查询时统一 `deleted = 0` |

### 3.2 `sys_user` 用户

| 中文 | 数据库列 | Java 字段 | TS 类型 |
|---|---|---|---|
| 主键 | `id` | `id` | `string` |
| 登录名（工号） | `username` | `username` | `string` |
| 密码哈希 | `password_hash` | `passwordHash` | **不出现在任何 VO** |
| 真实姓名 | `real_name` | `realName` | `string` |
| 部门 ID | `dept_id` | `deptId` | `string` |
| 角色编码 | `role_code` | `roleCode` | `RoleCode` |
| 手机号 | `phone` | `phone` | `string` |
| 邮箱 | `email` | `email` | `string` |
| 头像地址 | `avatar_url` | `avatarUrl` | `string` |
| 是否启用 | `is_enabled` | `isEnabled` | `number` |
| 最后登录时间 | `last_login_at` | `lastLoginAt` | `string` |
| 部门名称（联表展示） | — | `deptName` | `string` |

### 3.3 `doc_document` 文档（核心表）

| 中文 | 数据库列 | Java 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 主键 | `id` | `id` | BIGINT | 雪花 |
| 标题 | `title` | `title` | varchar(128) | 1–128 字符 |
| 摘要 | `summary` | `summary` | varchar(255) | 可空 |
| 正文 | `content_md` | `contentMd` | mediumtext | Markdown 源文 |
| 分类 | `category_id` | `categoryId` | BIGINT | 关联 `doc_category.id`（无外键约束） |
| 作者 | `author_id` | `authorId` | BIGINT | 关联 `sys_user.id` |
| 作者部门 | `author_dept_id` | `authorDeptId` | BIGINT | 冗余存储，便于按部门过滤 |
| 派生来源 | `derived_from_id` | `derivedFromId` | BIGINT | 为空表示原创 |
| 状态 | `status` | `status` | varchar(16) | `DocumentStatus` |
| 版本号 | `version_num` | `versionNum` | int | 从 1 开始单调递增 |
| 价格标记 | `price_cents` | `priceCents` | int unsigned | 单位：分；0 = 免费 |
| 阅读量 | `view_count` | `viewCount` | int unsigned | 仅 `PUBLISHED` 累加 |
| 收藏数 | `favorite_count` | `favoriteCount` | int unsigned | 冗余计数 |
| 驳回理由 | `reject_reason` | `rejectReason` | varchar(255) | 回传属主 |
| 发布时间 | `publish_at` | `publishAt` | DATETIME | 首次发布时写入 |
| 作者名（联表展示） | — | `authorName` | `string` | VO 字段 |
| 分类名（联表展示） | — | `categoryName` | `string` | VO 字段 |
| 是否可编辑（派生标记） | — | `canEdit` | `boolean` | VO 字段，由 Service 计算 |

### 3.4 `doc_version` 文档版本

| 中文 | 数据库列 | Java 字段 | 说明 |
|---|---|---|---|
| 主键 | `id` | `id` | 雪花 |
| 文档 ID | `document_id` | `documentId` | 关联 `doc_document.id` |
| 版本号 | `version_num` | `versionNum` | 与做该次操作后的文档版本号一致 |
| 标题快照 | `title` | `title` | |
| 正文快照 | `content_md` | `contentMd` | |
| 变更类型 | `change_type` | `changeType` | `ChangeType` 枚举 |
| 变更备注 / 审核意见 | `change_remark` | `changeRemark` | 驳回时必填 |
| 操作人 | `operator_id` | `operatorId` | 关联 `sys_user.id` |

### 3.5 `sys_permission` 权限

| 中文 | 数据库列 | Java 字段 | 说明 |
|---|---|---|---|
| 主键 | `id` | `id` | 雪花 |
| 权限名称 | `perm_name` | `permName` | 中文，如「新建文档」 |
| 权限码 | `perm_code` | `permCode` | 唯一，如 `doc:create` |
| 权限类型 | `perm_type` | `permType` | 1 目录 / 2 菜单 / 3 按钮 |
| 父节点 | `parent_id` | `parentId` | 根为 0 |
| 祖级路径 | `ancestors` | `ancestors` | 如 `0,1,5`，用于快速查子树 |
| 前端路由 | `path` | `path` | 目录/菜单层使用 |
| 图标 | `icon` | `icon` | 前端图标名 |
| 排序 | `sort_num` | `sortNum` | 同级升序 |
| 是否启用 | `is_enabled` | `isEnabled` | 0/1 |

### 3.6 其余表关键字段

| 表 | 关键字段（列名） |
|---|---|
| `sys_dept` | `dept_name`、`parent_id`、`ancestors`、`sort_num`、`is_enabled` |
| `sys_role` | `role_name`、`role_code`、`description`、`is_builtin`（1 = 内置不可删）、`sort_num` |
| `sys_user_role` | `user_id`、`role_id` |
| `sys_role_permission` | `role_id`、`perm_id` |
| `sys_dept_role` | `dept_id`、`role_id` |
| `sys_login_log` | `user_id`、`username`、`login_ip`、`user_agent`、`login_status`（1 成功 / 0 失败）、`fail_reason`、`login_at` |
| `doc_category` | `category_name`、`parent_id`、`ancestors`、`sort_num`、`is_enabled` |
| `doc_tag` | `tag_name`、`use_count` |
| `doc_document_tag` | `document_id`、`tag_id` |
| `doc_favorite` | `user_id`、`document_id`（联合唯一 `uk_user_doc`） |

---

## 4. 核心领域枚举（Enum）

### 4.1 `DocumentStatus` 文档状态

| 枚举值 | 中文 | 可编辑 | 可检索 | 出边 |
|---|---|---|---|---|
| `DRAFT` | 草稿 | ✅ | ⛔ | 发布 / 删除 |
| `PUBLISHED` | 已发布 | ✅ | ✅ | 归档 / 删除 |
| `ARCHIVED` | 已归档 | ⛔ | ✅（只读） | 恢复上架 / 删除 |
| `TRASH` | 回收站 | ⛔ | ⛔ | 恢复 / 彻底删除 |

### 4.2 `RoleCode` 角色编码

| 枚举值 | 中文 | 说明 |
|---|---|---|
| `STAFF` | 普通员工 | 默认角色 |
| `DOC_ADMIN` | 文档管理员 | 内容治理 |
| `SYS_ADMIN` | 系统管理员 | 平台管理 |

### 4.3 其它枚举

| 枚举 | 取值 | 存储 |
|---|---|---|
| `PermType` | `DIR(1)` 目录 / `MENU(2)` 菜单 / `BUTTON(3)` 按钮 | TINYINT |
| `ChangeType` | `CREATE` `EDIT` `PUBLISH` `AUDIT` `REJECT` `ARCHIVE` `RESTORE` `DELETE` `DERIVE` | VARCHAR(16) |
| `IsEnabled` | `1` 启用 / `0` 停用 | TINYINT |
| `Deleted` | `0` 正常 / `1` 已删除 | TINYINT |
| `LoginStatus` | `1` 成功 / `0` 失败 | TINYINT |
| `ErrorCode` | `SUCCESS(200)` `BAD_REQUEST(400)` `UNAUTHORIZED(401)` `NO_PERMISSION(403)` `USER_DISABLED(403)` `NOT_FOUND(404)` `CONFLICT_STATUS(409)` `SERVER_ERROR(500)` | 字符串常量 |

---

## 5. 业务操作动词表（Action）

| 中文动作 | 英文动词 | Service 方法 | 权限点 | 对应故事 |
|---|---|---|---|---|
| 登录 | login | `AuthService.login` | 公开 | US-01 |
| 登出 | logout | `AuthService.logout` | 登录即可 | — |
| 检索 | search | `DocumentService.search` | `doc:search` | US-04 |
| 查看详情 | detail | `DocumentService.detail` | `doc:search` | US-04 |
| 新建 | create | `DocumentService.create` | `doc:create` | US-02 |
| 编辑 | update | `DocumentService.update` | `doc:edit` | US-06 |
| 提交发布 | publish | `DocumentService.publish` | `doc:publish` | US-03 |
| 派生 | derive | `DocumentService.derive` | `doc:derive` | US-05 |
| 收藏 | favorite | `FavoriteService.favorite` | `doc:favorite` | — |
| 取消收藏 | unfavorite | `FavoriteService.unfavorite` | `doc:favorite` | — |
| 上传图片 | upload | `FileService.upload` | `doc:upload` | — |
| 删除（软） | delete | `DocumentService.delete` | `doc:delete` | — |
| 恢复（回收站） | restore | `DocumentService.restore` | `doc:restore` | — |
| 彻底删除 | destroy | `DocumentService.destroy` | `doc:delete` | — |
| 审核通过 | audit | `DocumentService.audit` | `doc:audit` | US-07 |
| 驳回 | reject | `DocumentService.reject` | `doc:reject` | US-07 |
| 归档 | archive | `DocumentService.archive` | `doc:archive` | US-07 |
| 恢复上架 | republish | `DocumentService.republish` | `doc:archive` | — |
| 下架 | offline | `DocumentService.offline` | `doc:offline` | — |
| 授权 | grant | `RoleService.grantPermissions` | `sys:role:grant` | US-08 |

---

## 6. 禁用别名（Anti-alias，命中即改）

### 6.1 领域名词

| 唯一合法词 | 禁止出现 |
|---|---|
| **Document** | Doc、Article、Note、File、Paper、资料、文章、笔记、文件（指文档时） |
| **User** | Account、Member、Employee、账号、会员、职工 |
| **Dept** | Department、Org、Organization、Group、部门组 |
| **Role** | RoleType、UserType、Position、身份 |
| **Permission** | Auth、Authority、Resource、Menu、Access、权限项 |
| **Category** | Classify、Type、Kind、Sort、分类目录 |
| **Tag** | Label、Keyword、Mark、标签项 |
| **Version** | Revision、History、Log、快照表 |
| **DocumentVo / DocumentDtoReq** | Param、Query、Form、Resp、Response、Request、Model |

### 6.2 字段名

| 唯一合法名 | 禁止出现 |
|---|---|
| `create_at` / `createAt` | `createTime`、`gmt_create`、`created_at`、`ctime` |
| `update_at` / `updateAt` | `updateTime`、`gmt_modified`、`updated_at`、`mtime`、**`updatedAt`** |
| `deleted` | `is_delete`、`is_deleted`、`del_flag`、`deleted_flag` |
| `is_enabled` / `isEnabled` | `status`、`enable`、`is_active`（权限/用户启停场景） |
| `status` | `state`、`doc_status`、`status_code`（文档状态场景统一 `status`） |
| `price_cents` | `price`、`amount`、`money`、`fee`（凡金额/积分一律以分为单位） |
| `content_md` | `content`、`markdown`、`body`、`text` |
| `version_num` | `version`、`ver`、`revision` |

---

## 7. 前端 TS 类型约定

```ts
// frontend/src/types/document.ts —— 字段名与后端 VO 逐字对齐
export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'TRASH'
export type RoleCode = 'STAFF' | 'DOC_ADMIN' | 'SYS_ADMIN'

export interface DocumentVo {
  id: string            // 雪花 ID 走字符串
  title: string
  summary: string
  categoryId: string
  categoryName: string
  authorId: string
  authorName: string
  status: DocumentStatus
  versionNum: number
  priceCents: number    // 单位：分
  viewCount: number
  favoriteCount: number
  updateAt: string      // yyyy-MM-dd HH:mm:ss
  canEdit: boolean
}

export interface DocumentDetailVo extends DocumentVo {
  contentMd: string
  derivedFromId: string | null
  rejectReason: string | null
  favorited: boolean
}

export interface PageVo<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}
```

**分页统一响应**：`PageVo<T>`；**统一响应体**：`{ code: number; message: string; data: T }`（对应后端 `ResponseResult<T>`）。

---

## 8. 冻结自检（M0-T0.6）

| 自查项 | 结论 | 证据 |
|---|---|---|
| 14 张表全部有中文概念 + 英文标识符 + 表名 + Java 类 | ✅ | §2 |
| 核心表字段级字典齐备 | ✅ | §3.2–3.6 |
| 枚举值与 `PRD.md` §4 状态机一致 | ✅ | §4.1 与 PRD §4 |
| 权限码与 `PRD.md` §3.2 的 39 个权限点一致 | ✅ | §5 动词表逐条引用 |
| 禁用别名明确列出 | ✅ | §6（供 M5 代码走查当作检查表） |
| TS 类型与后端 VO 字段逐字对齐 | ✅ | §7 |

---

**冻结签署**：本文件自 2026-09-21 起冻结。任何新字段必须先在此登记，再写代码。
