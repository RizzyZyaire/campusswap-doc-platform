# CampusSwap 文档管理平台 · 统一语言（GLOSSARY）

| 项 | 值 |
|---|---|
| 文件 | `docs/01-requirements/GLOSSARY.md` |
| 版本 | **v2.1（对齐老师《1.2 数据库物理建模》示例口径）** |
| 日期 | 2026-09-21 |
| 状态 | **Frozen（已冻结）** |
| 地位 | **命名唯一真源**。数据库列名、Java 类名与字段、TS 接口、接口出入参、UI 文案，全部以此文件为准 |
| 上游依据 | 老师课件《1.2 示例-数据库物理建表脚本(MySQL版)》《1.2 项目案例介绍》《2.1 专题指南》 |
| 下游消费 | `backend/sql/schema.sql`、Entity/DTO/VO、`frontend/src/types/` |

**变更记录**

| 版本 | 变更 |
|---|---|
| v1.0（M0） | 首版 |
| **v2.1（M1）** | ① 主键 `BIGINT AUTO_INCREMENT`（原：雪花禁用自增）；② 审计列统一 `created_at/created_by/updated_at/updated_by/deleted`（原：create_at/create_by/update_at/update_by/deleted）；③ 中间表改复合主键、无 `id` 列；④ `name`/`code`/`type` 泛用命名；⑤ 排序列 `sort_order`；⑥ `sys_user.status` 字符串枚举取代 `is_enabled`；⑦ `sys_login_log` → `sys_user_permission`（对齐课件 P5 表清单与权限合并算法）；⑧ 取消 `sys_user.role_code` 列，角色走中间表。理由：与老师课件示例同风格，降低评分口径风险 |

> **红线 R1**：Entity 字段、DTO 字段、VO 字段、TS interface 字段、数据库列，必须能在本文件中找到对应行。找不到 = 命名非法，必须改代码而不是改本文件。

---

## 1. 命名总则

| 对象 | 规则 | 正例 | 反例 |
|---|---|---|---|
| 表名 | `sys_` / `doc_` 前缀 + 单数 snake_case；中间表以 `_rel` 结尾 | `doc_document`、`doc_document_tag_rel` | `documents`、`t_document`、`doc_document_tag` |
| 列名 | snake_case，语义完整，不用缩写 | `derived_from_id` | `dfid`、`pid` |
| Java 类 | PascalCase，表名转驼峰去前缀 | `Document`、`DocumentTagRel` | `DocDocument` |
| Java 字段 | camelCase | `derivedFromId` | `derived_from_id` |
| 方法名 | 动词 + 名词 | `publishDocument` | `doPublish`、`handle` |
| 接口路径 | `/api/` + 复数资源名 + 动作子路径 | `POST /api/documents/{id}/publish` | `/api/publishDoc` |
| 入参类 | `XxxDtoReq` | `DocumentCreateDtoReq` | `DocumentParam`、`DocumentDTO` |
| 出参类 | `XxxVo` | `DocumentVo` | `DocumentResp`、`DocumentListVo` |
| 分页出参 | `PageVo<T>` | `PageVo<DocumentVo>` | `PageResult`、`PageInfo` |
| 统一响应 | `ResponseResult<T>` = `{ code, message, data }` | — | `Result`、`ApiResponse`、`R` |
| TS 接口 | 与后端 VO 同名（不加 `I` 前缀） | `export interface DocumentVo` | `IDocumentVo` |
| 常量/枚举值 | 全大写 SNAKE | `PUBLISHED`、`ACTIVE` | `Published`、`active` |
| 权限码 | `域:资源[:动作]`，冒号分隔小写 | `doc:publish`、`sys:role:grant` | `DOC_PUBLISH` |
| 索引名 | `idx_<表名去前缀>_<列>`；唯一索引 `uk_<表名去前缀>_<列>` | `idx_doc_cat_status_updated`、`uk_sys_user_username` | `index1`、`idx1` |

### 1.1 类型映射铁律

| 数据库 | Java | TS | 说明 |
|---|---|---|---|
| `BIGINT`（主键/关联 ID） | `Long`（**必须包装类型**，禁 `long`） | `string` | 主键 `AUTO_INCREMENT`；**后端统一用 `@JsonSerialize(using = ToStringSerializer.class)` 序列化为字符串**，前端一律 `string`，杜绝 JS 大整数精度问题与类型摇摆 |
| `INT UNSIGNED`（价格/积分） | `Integer` | `number` | 单位为**分**；前端展示时 `/100` |
| `INT`（普通计数、排序、版本号） | `Integer` | `number` | |
| `TINYINT`（标记位 0/1） | `Integer` | `number` | **不用 `Boolean`**；语义为布尔时字段名以 `is` 开头（如 `isBuiltin`） |
| `VARCHAR` 存枚举 | Java `enum` + `@Enumerated(EnumType.STRING)` | 字面量联合类型 | **禁 `ORDINAL`**（防错位） |
| `VARCHAR/TEXT` | `String` | `string` | |
| `DATETIME` | `LocalDateTime` | `string` | 统一 `yyyy-MM-dd HH:mm:ss`（`Asia/Shanghai`） |
| 派生布尔（VO 专用） | `Boolean` | `boolean` | 例：`canEdit`、`favorited` |

### 1.2 公共审计列（**所有业务表都有**，由 `BaseEntity` 提供，DDL 逐字采用老师示例）

| 列名 | 类型 | 约束（与课件示例一致） | 注释 | Java 字段 |
|---|---|---|---|---|
| `id` | `BIGINT` | `NOT NULL AUTO_INCREMENT`，`PRIMARY KEY` | 唯一自增主键 | `Long id` |
| `created_at` | `DATETIME` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | 记录创建时间 | `LocalDateTime createdAt` |
| `created_by` | `BIGINT` | `NOT NULL DEFAULT 0`（0 = 系统初始化） | 创建人用户ID | `Long createdBy` |
| `updated_at` | `DATETIME` | `NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 最后更新时间 | `LocalDateTime updatedAt` |
| `updated_by` | `BIGINT` | `NOT NULL DEFAULT 0` | 最后修改人用户ID | `Long updatedBy` |
| `deleted` | `TINYINT` | `NOT NULL DEFAULT 0`（0 正常 / 1 已逻辑删除） | 软删除标记 | `Integer deleted` |

**实现要点**：`BaseEntity` 加 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedBy/@CreatedDate/@LastModifiedBy/@LastModifiedDate`，配置类开 `@EnableJpaAuditing`；实体类加 `@SQLDelete(sql = "UPDATE 表名 SET deleted = 1 WHERE id = ?")` + `@SQLRestriction("deleted = 0")`，业务代码**永不手写 `deleted = 0` 条件**。

**例外**：纯关联中间表（`sys_user_role`、`sys_user_permission`、`sys_role_permission`、`sys_dept_role`、`doc_document_tag_rel`、`doc_favorite`）**没有 `id` 列**，采用复合主键，仅保留 `created_at`（+ 视需要保留 `created_by`）。它们不是实体主表，不继承 `BaseEntity`。

---

## 2. 核心领域实体（14 张表）

### 2.1 系统域（8 张）

| 中文概念 | 英文标识符 | 表名 | Java 类 | 业务边界 |
|---|---|---|---|---|
| 部门 | Dept | `sys_dept` | `Dept` | 组织树节点；绑定角色后员工继承权限 |
| 用户 | User | `sys_user` | `User` | 平台使用者；工号登录、归属一个部门 |
| 角色 | Role | `sys_role` | `Role` | 权限集合；3 个内置角色 + 可自定义 |
| 权限 | Permission | `sys_permission` | `Permission` | 三层树节点（DIR 目录 / MENU 菜单 / BUTTON 按钮） |
| 用户-角色 | UserRole | `sys_user_role` | `UserRole` | 复合主键 `(user_id, role_id)` |
| 用户-权限（直授） | UserPermission | `sys_user_permission` | `UserPermission` | 复合主键 `(user_id, permission_id)`，个别授权 |
| 角色-权限 | RolePermission | `sys_role_permission` | `RolePermission` | 复合主键 `(role_id, permission_id)` |
| 部门-角色 | DeptRole | `sys_dept_role` | `DeptRole` | 复合主键 `(dept_id, role_id)` |

### 2.2 文档域（6 张）

| 中文概念 | 英文标识符 | 表名 | Java 类 | 业务边界 |
|---|---|---|---|---|
| 文档 | Document | `doc_document` | `Document` | 平台核心对象；Markdown 正文 + 生命周期状态 |
| 文档版本 | DocumentVersion | `doc_version` | `DocumentVersion` | 每次正文/状态变更的不可变快照与操作留痕 |
| 分类 | Category | `doc_category` | `Category` | 最多 3 层的文档分类树 |
| 标签 | Tag | `doc_tag` | `Tag` | 扁平标签，一文档最多 5 个 |
| 文档-标签 | DocumentTagRel | `doc_document_tag_rel` | `DocumentTagRel` | 复合主键 `(document_id, tag_id)` |
| 收藏 | Favorite | `doc_favorite` | `Favorite` | 复合主键 `(user_id, document_id)` |

---

## 3. 字段级命名字典

### 3.1 `sys_user` 用户

| 中文 | 数据库列 | Java 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 登录名（工号） | `username` | `username` | varchar(64) | **唯一** `uk_sys_user_username` |
| 密码哈希 | `password_hash` | `passwordHash` | varchar(128) | BCrypt；**不出现在任何 VO** |
| 真实姓名 | `real_name` | `realName` | varchar(64) | |
| 部门 | `dept_id` | `deptId` | BIGINT | 关联 `sys_dept.id`；一人一部门 |
| 邮箱 | `email` | `email` | varchar(128) | 可空 |
| 手机号 | `phone` | `phone` | varchar(20) | 可空 |
| 头像地址 | `avatar_url` | `avatarUrl` | varchar(255) | 相对 URL |
| 账号状态 | `status` | `status` | varchar(32) | `UserStatus`：ACTIVE / LOCKED / DISABLED |
| 最后登录时间 | `last_login_at` | `lastLoginAt` | DATETIME | 登录成功时更新 |
| 部门名称（联表展示） | — | `deptName` | — | VO 字段 |
| 角色码集合（联表展示） | — | `roles` | — | VO 字段：`string[]` |

### 3.2 `doc_document` 文档（核心表）

| 中文 | 数据库列 | Java 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 标题 | `title` | `title` | varchar(128) | 1–128 字符 |
| 摘要 | `summary` | `summary` | varchar(255) | 可空 |
| 正文 | `content_md` | `contentMd` | MEDIUMTEXT | Markdown 源文（**不做富文本**） |
| 分类 | `category_id` | `categoryId` | BIGINT | 关联 `doc_category.id`；0 = 未分类 |
| 生命周期状态 | `status` | `status` | varchar(32) | `DocumentStatus`：DRAFT / PUBLISHED / ARCHIVED / TRASH |
| 版本号 | `version_num` | `versionNum` | int | 从 1 开始单调递增；每次正文写入或状态流转 +1 |
| 价格标记 | `price_cents` | `priceCents` | int unsigned | 单位：分；0 = 免费 |
| 阅读量 | `view_count` | `viewCount` | int | 仅 PUBLISHED 累加（Redis 去重后回写） |
| 收藏数 | `favorite_count` | `favoriteCount` | int | 冗余计数 |
| 派生来源 | `derived_from_id` | `derivedFromId` | BIGINT | 可空；为空表示原创 |
| 驳回理由 | `reject_reason` | `rejectReason` | varchar(255) | 审核驳回时写入，回传属主 |
| 发布时间 | `publish_at` | `publishAt` | DATETIME | 首次发布时写入 |
| 作者 | `created_by` | `createdBy` | BIGINT | **作者 = 创建人**（复用审计列，不另设 author_id） |
| 作者名（联表展示） | — | `authorName` | — | VO 字段 |
| 分类名（联表展示） | — | `categoryName` | — | VO 字段 |
| 是否可编辑（派生标记） | — | `canEdit` | — | VO 字段，Service 计算 |
| 是否已收藏（派生标记） | — | `favorited` | — | VO 字段，Service 计算 |
| 标签集合（联表展示） | — | `tags` | — | VO 字段：`TagVo[]` |

### 3.3 `doc_version` 文档版本

| 中文 | 数据库列 | Java 字段 | 说明 |
|---|---|---|---|
| 文档 | `document_id` | `documentId` | 关联 `doc_document.id` |
| 版本号 | `version_num` | `versionNum` | 与该次操作后的文档版本号一致 |
| 标题快照 | `title` | `title` | |
| 正文快照 | `content_md` | `contentMd` | |
| 变更类型 | `change_type` | `changeType` | `ChangeType` 枚举 |
| 变更备注 / 审核意见 | `change_remark` | `changeRemark` | 驳回时必填 |
| 操作人 | `created_by` | `createdBy` | 复用审计列（= 操作人） |

### 3.4 `sys_permission` 权限

| 中文 | 数据库列 | Java 字段 | 说明 |
|---|---|---|---|
| 权限名称 | `name` | `name` | 中文，如「新建文档」 |
| 权限编码 | `code` | `code` | 唯一 `uk_sys_permission_code`，如 `doc:create` |
| 权限类型 | `type` | `type` | `PermType`：DIR 目录 / MENU 菜单 / BUTTON 按钮 |
| 父节点 | `parent_id` | `parentId` | 根为 0 |
| 祖级路径 | `ancestors` | `ancestors` | 如 `0,1,10`，用于一次查询整棵子树 |
| 前端路由 | `path` | `path` | 目录/菜单层使用，可空 |
| 图标 | `icon` | `icon` | 前端图标名，可空 |
| 排序 | `sort_order` | `sortOrder` | 同级升序 |

### 3.5 其余表关键字段

| 表 | 关键列（列名 → Java 字段） |
|---|---|
| `sys_dept` | `name`→`name`、`parent_id`→`parentId`、`ancestors`→`ancestors`、`sort_order`→`sortOrder` |
| `sys_role` | `name`→`name`、`code`→`code`（唯一）、`description`→`description`、`is_builtin`→`isBuiltin`（1 = 内置不可删）、`sort_order`→`sortOrder` |
| `sys_user_role` | `user_id`→`userId`、`role_id`→`roleId`、`created_at`→`createdAt` |
| `sys_user_permission` | `user_id`→`userId`、`permission_id`→`permissionId`、`created_at`→`createdAt` |
| `sys_role_permission` | `role_id`→`roleId`、`permission_id`→`permissionId`、`created_at`→`createdAt` |
| `sys_dept_role` | `dept_id`→`deptId`、`role_id`→`roleId`、`created_at`→`createdAt` |
| `doc_category` | `name`→`name`、`parent_id`→`parentId`、`ancestors`→`ancestors`、`sort_order`→`sortOrder` |
| `doc_tag` | `name`→`name`（唯一）、`use_count`→`useCount` |
| `doc_document_tag_rel` | `document_id`→`documentId`、`tag_id`→`tagId`、`created_at`→`createdAt` |
| `doc_favorite` | `user_id`→`userId`、`document_id`→`documentId`、`created_at`→`createdAt` |

### 3.6 查询与分页公共字段（所有列表接口通用，前端 TS 同名）

| 中文 | 字段名 | 类型 | 约束 |
|---|---|---|---|
| 页码 | `pageNum` | number | ≥ 1，默认 1 |
| 每页条数 | `pageSize` | number | 1–100，默认 10 |
| 关键词 | `keyword` | string | 可空，≤ 64 字符，匹配标题/摘要 |
| 状态筛选 | `status` | string | 可空，取值同对应枚举 |
| 分类筛选 | `categoryId` | string | 可空 |
| 标签筛选 | `tagIds` | string[] | 可空，多标签 AND 命中 |
| 部门筛选 | `deptId` | string | 可空 |
| 排序 | `sort` | `DocumentSort` | 可空，默认 `updatedAt_desc`；取值 `updatedAt_desc` / `publishAt_desc` / `viewCount_desc` |

**分页出参**：`PageVo<T>` = `{ list: T[], total: number, pageNum: number, pageSize: number }`。

### 3.7 VO / DTO 字段字典（接口契约的字段级真源）

> 命名规则：`*DtoReq` 为入参、`*Vo` 为出参；`?` 表示可空；`T[]` 为数组。**任何接口出入参字段必须能在此表查到。**

**公共包装**

| 类名 | 字段 |
|---|---|
| `ResponseResult<T>` | `code:number, message:string, data:T` |
| `PageVo<T>` | `list:T[], total:number, pageNum:number, pageSize:number` |
| `PageDtoReq` | `pageNum:number, pageSize:number` |
| `AuditVo`（所有实体型 VO 的基类） | `createdAt:string, createdBy:string, updatedAt:string, updatedBy:string` |

**系统域**

| 类名 | 字段 |
|---|---|
| `LoginDtoReq` | `username:string, password:string` |
| `UserInfoVo` extends `AuditVo` | `id, username, realName, deptId, deptName, roles:string[], avatarUrl, permissions:string[]` |
| `LoginVo` | `token:string, userInfo:UserInfoVo, roles:string[], permissions:string[]` |
| `UserVo` extends `AuditVo` | `id, username, realName, deptId, deptName, roles:string[], phone, email, avatarUrl, status:UserStatus, lastLoginAt` |
| `UserCreateDtoReq` | `username, realName, deptId, roles:string[], phone, email, password` |
| `UserUpdateDtoReq` | `realName, deptId, roles:string[], phone, email` |
| `UserStatusDtoReq` | `status:UserStatus` |
| `RoleVo` extends `AuditVo` | `id, name, code, description, isBuiltin:number, sortOrder:number` |
| `RoleDtoReq` | `name, code, description` |
| `PermissionVo` extends `AuditVo` | `id, name, code, type:PermType, parentId, ancestors, path, icon, sortOrder:number, children:PermissionVo[]` |
| `PermissionUpdateDtoReq` | `name, type:PermType, parentId, sortOrder:number, icon, path` |
| `DeptVo` extends `AuditVo` | `id, name, parentId, ancestors, sortOrder:number, children:DeptVo[]` |

**文档域**

| 类名 | 字段 |
|---|---|
| `DocumentVo` extends `AuditVo` | `id, title, summary, categoryId, categoryName, authorId, authorName, status:DocumentStatus, versionNum:number, priceCents:number, viewCount:number, favoriteCount:number, canEdit:boolean` |
| `DocumentDetailVo` extends `DocumentVo` | `contentMd, derivedFromId:string\|null, rejectReason:string\|null, favorited:boolean, tags:TagVo[]` |
| `DocumentCreateDtoReq` | `title, summary, contentMd, categoryId, tagIds:string[], priceCents:number` |
| `DocumentUpdateDtoReq` extends `DocumentCreateDtoReq` | `id`（必填，必须与路径 `{id}` 一致；业务字段按 create 同规则**全量提交**，不是"只传改动字段"） |
| `DocumentSearchDtoReq` extends `PageDtoReq` | `keyword?, categoryId?, tagIds?:string[], status?:DocumentStatus, sort?:DocumentSort` |
| `DocumentVersionVo` extends `AuditVo` | `id, documentId, versionNum:number, title, contentMd, changeType:string, changeRemark:string\|null, operatorId, operatorName` |
| `CategoryVo` extends `AuditVo` | `id, name, parentId, ancestors, sortOrder:number, children:CategoryVo[]` |
| `TagVo` extends `AuditVo` | `id, name, useCount:number` |
| `ImageVo` | `url:string` |
| `StatVo` | `myDocumentCount:number, myFavoriteCount:number, publishedCount:number` |

**分页查询入参（各列表接口专用）**

| 类名 | 字段 |
|---|---|
| `UserPageDtoReq` | `pageNum?, pageSize?, keyword?, status?:UserStatus, deptId?` |
| `RolePageDtoReq` | `pageNum?, pageSize?, keyword?` |
| `ReviewPageDtoReq` | `pageNum?, pageSize?, keyword?, status?:DocumentStatus` |
| `FavoritePageDtoReq` | `pageNum?, pageSize?, keyword?` |
| `TagPageDtoReq` | `pageNum?, pageSize?, keyword?` |

> 以上 5 个 `*PageDtoReq` 省略 `pageNum` / `pageSize` 时按 §3.6 取默认值 1 / 10，语义等价于 `extends PageDtoReq`。

**系统域补充（新建 / 更新 / 关联）**

| 类名 | 字段 |
|---|---|
| `DeptCreateDtoReq` / `DeptUpdateDtoReq` | `name, parentId, sortOrder?` |
| `CategoryCreateDtoReq` / `CategoryUpdateDtoReq` | `name, parentId, sortOrder?` |
| `TagCreateDtoReq` / `TagUpdateDtoReq` | `name` |
| `PermissionCreateDtoReq` | `name, code, type:PermType, parentId, path?, icon?, sortOrder?` |
| `RolePermissionDtoReq` | `permissionIds:string[]` |
| `DeptRoleDtoReq` | `roleIds:string[]`（**角色 ID** 数组） |
| `RolePermissionVo` | `roleId, permissionIds:string[]` |
| `DeptRoleVo` | `deptId, roleIds:string[]` |

> ⚠️ 易错点：`UserCreateDtoReq.roles` / `UserUpdateDtoReq.roles` 是**角色编码**数组（如 `['STAFF']`），而 `DeptRoleDtoReq.roleIds` / `RolePermissionDtoReq.permissionIds` 是**主键 ID** 数组。两者不可互换。

**文档域补充（审核 / 派生 / 销毁 / 收藏）**

| 类名 | 字段 |
|---|---|
| `DocumentAuditDtoReq` | `remark`（审核通过与归档**共用**） |
| `DocumentRejectDtoReq` | `reason`（驳回理由，必填） |
| `DocumentDeriveDtoReq` | `title?`（缺省时自动生成「原标题（副本）」） |
| `DocumentDestroyDtoReq` | `confirm:boolean`（必须为 `true` 才执行彻底删除，对应 BR-08 二次确认） |
| `FavoriteVo` | `documentId, favorited:boolean, favoriteCount:number` |

**类型别名（TS，非结构化类型）**

| 别名 | 取值 |
|---|---|
| `DocumentStatus` | `'DRAFT' \| 'PUBLISHED' \| 'ARCHIVED' \| 'TRASH'` |
| `UserStatus` | `'ACTIVE' \| 'LOCKED' \| 'DISABLED'` |
| `PermType` | `'DIR' \| 'MENU' \| 'BUTTON'` |
| `RoleCode` | `'STAFF' \| 'DOC_ADMIN' \| 'SYS_ADMIN'` |
| `DocumentSort` | `'updatedAt_desc' \| 'publishAt_desc' \| 'viewCount_desc'` |
| 分页别名 | `DocumentPageVo = PageVo<DocumentVo>`、`UserPageVo = PageVo<UserVo>`、`RolePageVo = PageVo<RoleVo>` |
| 列表/树别名 | `DocumentVersionListVo`、`CategoryTreeVo`、`TagListVo`、`PermissionTreeVo`、`DeptTreeVo` |

**展示层命名 ↔ 数据库列的映射**（这两组名字都合法，不许混用到对方的位置）

| VO 字段（展示层） | 数据库列 / 实体字段 | 说明 |
|---|---|---|
| `authorId`、`authorName` | `created_by` / `createdBy`（联表取名） | 文档作者即创建人 |
| `operatorId`、`operatorName` | `created_by` / `createdBy` | 版本记录的操作人 |
| `deptName`、`categoryName` | 联表取得的名称 | 只出现在 VO，不落库 |
| `updatedAt`、`createdAt` | `updated_at` / `created_at` | 审计时间 |

---

## 4. 核心领域枚举（Enum）

### 4.1 `DocumentStatus` 文档生命周期

| 枚举值 | 中文 | 可编辑 | 可检索 | 出边 |
|---|---|---|---|---|
| `DRAFT` | 草稿 | ✅ | ⛔ | 提交发布 / 删除 |
| `PUBLISHED` | 已发布 | ✅ | ✅ | 归档 / 删除 |
| `ARCHIVED` | 已归档 | ⛔ | ✅（只读） | 恢复上架 / 删除 |
| `TRASH` | 回收站 | ⛔ | ⛔ | 恢复 / 彻底删除 |

### 4.2 其它枚举

| 枚举 | 落库列 | 取值 | 说明 |
|---|---|---|---|
| `UserStatus` | `sys_user.status` | `ACTIVE` 正常 / `LOCKED` 冻结 / `DISABLED` 停用 | 仅 ACTIVE 可登录 |
| `PermType` | `sys_permission.type` | `DIR` 目录 / `MENU` 菜单 / `BUTTON` 按钮 | 三层树 |
| `RoleCode` | `sys_role.code` | `STAFF` 普通员工 / `DOC_ADMIN` 文档管理员 / `SYS_ADMIN` 系统管理员 | 内置角色不可删除 |
| `ChangeType` | `doc_version.change_type` | `CREATE` `EDIT` `PUBLISH` `AUDIT` `REJECT` `ARCHIVE` `RESTORE` `DELETE` `DERIVE` | 版本留痕类型 |
| `Deleted` | `*.deleted` | `0` 正常 / `1` 已删除 | 由 `@SQLDelete` 维护，业务代码不直接写 |
| `ErrorCode` | 响应体 `code` | `SUCCESS(200)` `BAD_REQUEST(400)` `UNAUTHORIZED(401)` `NO_PERMISSION(403)` `USER_DISABLED(403)` `NOT_FOUND(404)` `CONFLICT_STATUS(409)` `SERVER_ERROR(500)` | HTTP 状态码与之保持一致 |

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
| 上传图片 | upload | `FileService.uploadImage` | `doc:upload` | — |
| 删除（进回收站） | delete | `DocumentService.delete` | `doc:delete` | — |
| 恢复 | restore | `DocumentService.restore` | `doc:restore` | — |
| 彻底删除 | destroy | `DocumentService.destroy` | `doc:delete` | — |
| 审核通过 | audit | `DocumentService.audit` | `doc:audit` | US-07 |
| 驳回 | reject | `DocumentService.reject` | `doc:reject` | US-07 |
| 归档 | archive | `DocumentService.archive` | `doc:archive` | US-07 |
| 恢复上架 | republish | `DocumentService.republish` | `doc:archive` | — |
| 下架 | offline | `DocumentService.offline` | `doc:offline` | — |
| 角色授权 | grant | `RoleService.grantPermissions` | `sys:role:grant` | US-08 |
| 停用用户 | disable | `UserService.changeStatus` | `sys:user:disable` | — |

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
| **DocumentTagRel** | DocumentTag、DocTagRel、文档标签表 |
| **DocumentVo / DocumentDtoReq** | Param、Query、Form、Resp、Response、Request、Model、`DocumentListVo` |
| **ResponseResult / PageVo** | Result、ApiResponse、R、PageResult、PageInfo |

### 6.2 字段名

| 唯一合法名 | 禁止出现 |
|---|---|
| `created_at` / `createdAt` | `create_at`、`createAt`、`createTime`、`gmt_create`、`ctime` |
| `updated_at` / `updatedAt` | `update_at`、`updateAt`、`updateTime`、`gmt_modified`、`mtime` |
| `deleted` | `is_deleted`、`isDeleted`、`is_delete`、`del_flag`、`deleted_flag` |
| `status` | `state`、`doc_status`、`user_status`、`status_code` |
| `price_cents` / `priceCents` | `price`、`amount`、`money`、`fee`（凡金额/积分一律以分为单位） |
| `content_md` / `contentMd` | `content`、`markdown`、`body`、`text` |
| `version_num` / `versionNum` | `version`、`ver`、`revision` |
| `sort_order` / `sortOrder` | `sort_num`、`order_num`、`seq`、`weight` |
| `name` / `code` / `type`（角色、权限、分类、标签、部门） | `role_name`、`role_code`、`perm_name`、`perm_code`、`perm_type`、`dept_name`、`category_name`、`tag_name` |
| `sys_user.status` 表达启停 | `is_enabled`、`isEnabled`、`enable`、`is_active` |
| 数据库列 `created_by`（= 文档作者、版本操作人） | 列名 `author_id`、`operator_id`、`creator_id`（**VO 展示层**允许 `authorId` / `authorName` / `operatorId` / `operatorName`，见 §3.7 映射表） |

---

## 7. 前端 TS 类型约定

```ts
// frontend/src/types/document.ts —— 字段名与后端 VO 逐字对齐（完整字典见 §3.7）
export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'TRASH'
export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED'
export type RoleCode = 'STAFF' | 'DOC_ADMIN' | 'SYS_ADMIN'
export type PermType = 'DIR' | 'MENU' | 'BUTTON'
export type DocumentSort = 'updatedAt_desc' | 'publishAt_desc' | 'viewCount_desc'

export interface AuditVo {
  createdAt: string
  createdBy: string
  updatedAt: string
  updatedBy: string
}

export interface DocumentVo extends AuditVo {
  id: string            // 后端 Long 主键统一序列化为 string
  title: string
  summary: string
  categoryId: string
  categoryName: string
  authorId: string      // = 数据库列 created_by
  authorName: string
  status: DocumentStatus
  versionNum: number
  priceCents: number    // 单位：分
  viewCount: number
  favoriteCount: number
  canEdit: boolean
}

export interface DocumentDetailVo extends DocumentVo {
  contentMd: string
  derivedFromId: string | null
  rejectReason: string | null
  favorited: boolean
  tags: TagVo[]
}

export interface PageVo<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}
```

**统一响应体**：`ResponseResult<T>` = `{ code: number; message: string; data: T }`。

---

## 8. 冻结自检

| 自查项 | 结论 | 证据 |
|---|---|---|
| 14 张表全部有中文概念 + 英文标识符 + 表名 + Java 类 | ✅ | §2（8 系统域 + 6 文档域） |
| 审计列与老师 MySQL 示例逐字一致 | ✅ | §1.2 |
| 中间表复合主键、无 `id` 列 | ✅ | §1.2 例外说明 |
| 字段级字典齐备 | ✅ | §3.1–3.6 |
| 枚举与 `PRD.md` §4 状态机一致 | ✅ | §4.1 |
| 权限码与 `PRD.md` §3.2 的 39 个权限点一致 | ✅ | §5 动词表逐条引用 |
| 禁用别名明确列出（M5 代码走查检查表） | ✅ | §6 |
| TS 类型与后端 VO 字段逐字对齐 | ✅ | §7 |

---

**冻结签署**：本文件 v2.1 自 2026-09-21 起冻结。任何新字段必须先在此登记，再写代码。
