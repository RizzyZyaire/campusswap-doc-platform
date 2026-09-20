# CampusSwap 文档管理平台 · 大作业执行手册

**版本 v2.0（已冻结）**　·　更新：2026-09-21　·　维护：本项目开发者

> **这份文件是唯一权威口径。**
> 1. 本文件里没有出现的东西，一律不做；出现的东西，按它执行。
> 2. 与任何其它说法冲突时，以本文件为准。
> 3. 读者可以是人，也可以是 AI Agent：读完本文件 → 读 `docs/01-requirements/` → 直接从 §7 的任务看板开工。
> 4. 老师已确认的口径（2026-09-21）：**方案不必卡太死**、**根目录只放 `docs/ backend/ frontend/`**、**Redis 用本机自带的**、**做完必须上传 GitHub 且不提交产物目录**、**只按源码评分**。

---

## 0. 一分钟了解项目

| 项 | 内容 |
|---|---|
| **做什么** | 单位内部 **Markdown 文档管理平台**（品牌名 CampusSwap）：撰写、分类、检索、**派生复用**、版本管理、审核归档，全程 RBAC 控权 |
| **两大模块** | ① **系统与权限管理**（用户/角色/权限/部门 + 3 张授权中间表 + 部门-角色表 = 8 张表）<br>② **文档业务管理**（文档/分类/标签/版本/收藏/阅读记录 = 6 张表） |
| **三个角色** | `STAFF` 普通员工（写与读自己的）｜`DOC_ADMIN` 文档管理员（审核/分类/标签/全量文档）｜`SYS_ADMIN` 系统管理员（用户/角色/权限/部门） |
| **技术栈** | 前端 Vue 3 + TypeScript(strict) + Vite + Tailwind CSS + Pinia + Axios；后端 Java 17 + Spring Boot 4.1+ + Spring Data JPA(Hibernate 6) + Jakarta Validation + Lombok + Hutool；MySQL 8（InnoDB / utf8mb4_unicode_ci）；Redis 5.0.14（本机）；Nginx（仅部署可选） |
| **交付物** | 根目录三个文件夹 `docs/ backend/ frontend/` + **GitHub 仓库地址** |
| **评分口径** | **只看源码**：规范对齐、注释齐全、分层清晰、SQL 效率、红线零违反 |
| **总工期参考** | 10~14 个工作日（M0~M7，见 §7） |
| **唯一数据源** | `docs/01-requirements/GLOSSARY.md`（字段命名）+ `sql/schema.sql`（表结构），二者与代码必须字字一致 |

---

## 1. 已冻结决策（不再讨论）

| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| 1 | 业务定位 | 单位内部 Markdown 文档管理平台 | 已确认 |
| 2 | 前端 | **必须自己做**，按 §6 规范实现全部页面 | 老师明确 |
| 3 | 权限表层级 | **3 层**：① 目录/模块 ② 菜单/页面 ③ 按钮/操作点；落库用 `parent_id + ancestors`，加层不必改表 | 老师授权自定 |
| 4 | Redis | **用本机 Redis 5.0.14**，三处用途：登录 Token/会话、权限合并结果缓存、文档阅读量计数 | 老师明确"用你自己的先" |
| 5 | 交付目录 | 根目录**只放** `docs/ backend/ frontend/`；收纳规则见 §3.2 | 老师明确 |
| 6 | 交付方式 | **必须上传 GitHub**（建议 Private）；后端不提交 `logs/ target/ uploads/`，前端不提交 `dist/ node_modules/` | 老师明确（附目录图） |
| 7 | 图片上传 | **支持**：Markdown 内嵌图片，单图 ≤5MB，落盘 `backend/uploads/`，**不入 Git**（老师 backend 有 `uploads/` 目录） | 由老师目录结构反推确认 |
| 8 | 包管理器 | **前端统一用 pnpm**（与 `AGENTS.md` 的 `pnpm run typecheck/lint` 一致），仓库里只保留 `pnpm-lock.yaml` | 二选一决断 |
| 9 | 构建命令 | 后端用 `./mvnw`（Maven Wrapper，与老师工程一致）；wrapper 下载慢时改 `distributionUrl` 为阿里云镜像 | 老师工程结构 |
| 10 | 金额 | 一律整数分 `price_cents INT UNSIGNED` / `priceCents`；**不做真实支付结算**，`price_cents` 只表示"0=免费，>0=需积分兑阅" | 老师红线 + 课件 |
| 11 | 检索方案 | MySQL `LIKE` / FULLTEXT + 索引，**不引入 Elasticsearch** | MVP 边界 |
| 12 | 数据库 | 库名 `docs_db`；表前缀 `sys_`（系统域）/ `doc_`（文档域） | 沿用老师工程 |
| 13 | Java 版本 | **编译目标 17**；可用 Record/Stream 等现代写法，不依赖 21 独有语法 | 老师工程实际配置 |
| 14 | 演示范畴 | 老师不看效果演示 → **不写演示脚本**，把精力投入源码与测试 | 老师明确 |

---

## 2. 硬约束（违反即扣分，逐条可检查）

### 2.1 编码六红线（来自老师《AGENTS.md》）

| # | 红线 | 检查方式 |
|---|---|---|
| R1 | 字段不得脑补：Entity / DTO / VO / TS Interface / DB 列名 五处必须与 `GLOSSARY.md` + `schema.sql` 完全一致 | 交叉 grep 比对 |
| R2 | 金额禁止浮点：全链路 `priceCents` / `price_cents`（整数分），前端仅展示时 `/100` | `grep -ri "double\|float" **/Price*` 应为空 |
| R3 | Controller 入参必须 `@Valid` + Jakarta Validation 注解 + **中文错误提示** | 检查每个 `@RequestBody` |
| R4 | **禁止越权（IDOR）**：更新/下架/删除/派生必须在 Service 层校验属主（`createBy == currentUserId` 或有管理权限） | 越权用例必须返回 403 |
| R5 | **禁止 Entity 穿透前端**：Controller 出参必须是 VO（脱敏），实体不出 Service | 检查返回类型 |
| R6 | 前端禁止内联 `style="..."`（用 Tailwind 原子类）；TypeScript **禁止 `any`** | `pnpm run lint` + `grep -rn "any" src/types` |

### 2.2 DDL 六条（来自老师建表提示词）

1. 每张表显式写 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`
2. 金额列 `INT UNSIGNED`（分）
3. 主键 `BIGINT` **雪花算法、程序生成、不写 AUTO_INCREMENT**
4. **不建任何外键约束**（关联靠逻辑外键 + 代码校验）
5. 高频查询列建索引/复合索引，注意最左前缀
6. **每张表、每个字段都要有 `COMMENT`**

### 2.3 分层五条 + 注释规范

| 层 | 只做这些 | 绝不做 |
|---|---|---|
| Controller | 路由、`@Valid` 校验转发、调 Service、包装统一响应 | 业务逻辑、直接调 Repository |
| Service | 业务规则、事务（`@Transactional`）、属主校验、DTO↔Entity↔VO 转换 | 写原生 SQL、返回实体给 Controller |
| ServiceImpl | 实现 Service 接口（接口/实现分离） | — |
| Repository | Spring Data JPA 接口、`@Query`、投影查询 | 业务判断 |
| Entity / DTO / VO | 见 §5 | DTO 复用 Entity；VO 带敏感字段（如 `password_hash`） |

**注释（必须）**：每个类写类注释并含 `@author`；每个 public 方法写 `@param` / `@return`；复杂业务规则写行内说明。

### 2.4 前端四条

1. 目录固定：`src/{api,types,components,views,stores,router,utils}`
2. 类型对齐：`src/types` 的 Interface 与 `GLOSSARY.md` 一致；**所有雪花 ID 声明为 `string`**（防 JS 精度丢失）
3. Axios 统一封装：请求拦截带 Token；响应拦截统一处理 401（跳登录）/403（提示无权限）/业务错误码
4. 每页必须有四种状态：空、加载中、错误、无权限

---

## 3. 交付物与目录（最终形态）

### 3.1 最终目录树

```text
campusswap/                        ← 交付根目录（只有三个文件夹）
├── docs/                          📋 全部文档
│   ├── 01-requirements/           #  USER_STORIES.md · PRD.md · GLOSSARY.md
│   ├── 02-design/                 #  ARCHITECTURE.md · API_SPECIFICATION.md · UI_UX_SPECIFICATION.md · apifox-export/
│   ├── 03-qa-review/              #  tasks.md · TEST_CHECKLIST.md · CODE_REVIEW.md
│   └── 04-prompts/                #  P1~P7 阶段指令卡片（留档，可复现）
├── backend/                       ☕ Spring Boot 4.1 工程（包名 com.campusswap）
│   ├── .mvn/  mvnw  mvnw.cmd       #  Maven Wrapper（验收命令用 ./mvnw）
│   ├── .gitignore                 #  已就绪：忽略 target/ logs/ uploads/ 等
│   ├── sql/schema.sql             #  物理 DDL（从 sql/ 复制而来，满足三文件夹约束）
│   ├── uploads/                   #  运行期图片目录（.gitkeep 占位，不入 Git）
│   └── src/main/java/com/campusswap/
│       ├── common/                #  Result<T> · PageResult<T> · ErrorCode · GlobalExceptionHandler · BusinessException
│       ├── config/                #  SnowflakeConfig · JpaAuditConfig · RedisConfig · WebMvcConfig · PermissionAspect
│       ├── controller/            #  AuthController · UserController · RoleController · PermissionController · DeptController · DocumentController · CategoryController · TagController
│       ├── service/ + service/impl/
│       ├── repository/            #  14 个 JPA 接口
│       ├── entity/                #  BaseEntity + 14 个实体
│       ├── dto/                   #  入参（@Valid）
│       ├── vo/                    #  出参（脱敏）
│       └── util/                  #  工具类（md 处理、文件存储等）
└── frontend/                      💻 Vue 3 + TS 工程
    ├── .gitignore                 #  已就绪：忽略 node_modules/ dist/ 等
    ├── src/{api,types,components,views,stores,router,utils}
    └── package.json · vite.config.ts · tailwind.config.js · tsconfig.json
```

### 3.2 收纳规则（把规范产物塞进三个文件夹）

| 原始位置 | 最终位置 | 命令 |
|---|---|---|
| `sql/schema.sql` | `backend/sql/schema.sql` | `mkdir -p backend/sql && cp sql/schema.sql backend/sql/` |
| `prompts/` | `docs/04-prompts/` | `cp -r prompts docs/04-prompts` |
| `tasks.md` | `docs/03-qa-review/tasks.md` | `mv tasks.md docs/03-qa-review/` |
| `tests/` | `backend/src/test/`（JUnit） | 直接写在工程内 |
| `uploads/` | `backend/uploads/`（不入 Git） | `.gitkeep` 占位 |
| `deploy/` | `backend/deploy/nginx.conf`（可选） | 有则附，无则不做 |

---

## 4. 数据模型（14 张表 · 权威字段表）

### 4.1 公共字段（所有业务表都有）

| 列名 | 类型 | 约束 | 注释 |
|---|---|---|---|
| `id` | `BIGINT` | PK，**无 AUTO_INCREMENT**（雪花） | 主键ID |
| `create_by` | `BIGINT` | NOT NULL DEFAULT 0 | 创建人ID |
| `create_at` | `DATETIME(6)` | NOT NULL，实体侧 `updatable=false` | 创建时间 |
| `update_by` | `BIGINT` | NOT NULL DEFAULT 0 | 最后更新人ID |
| `update_at` | `DATETIME(6)` | NOT NULL | 最后更新时间 |
| `is_deleted` | `TINYINT` | NOT NULL DEFAULT 0 | 逻辑删除：0正常 1已删除 |

### 4.2 系统域（8 张表）

| 表名 | 关键列（除公共字段外） | 索引 |
|---|---|---|
| `sys_user` | `username` VARCHAR(64) 唯一、`real_name` VARCHAR(64)、`password_hash` VARCHAR(255)、`avatar_url` VARCHAR(255)、`department_id` BIGINT、`role_code` VARCHAR(32)、`is_enabled` TINYINT | `uk_user_username`、`idx_user_dept`、`idx_user_role` |
| `sys_role` | `role_code` VARCHAR(32) 唯一（STAFF/DOC_ADMIN/SYS_ADMIN）、`role_name` VARCHAR(64)、`status` TINYINT、`remark` VARCHAR(255) | `uk_role_code` |
| `sys_permission` | `parent_id` BIGINT DEFAULT 0、`ancestors` VARCHAR(500)、`perm_code` VARCHAR(64) 唯一（如 `doc:publish`）、`perm_name` VARCHAR(64)、`perm_type` TINYINT（1目录 2菜单 3按钮）、`path` VARCHAR(255)、`order_num` INT | `uk_perm_code`、`idx_perm_parent` |
| `sys_user_role` | `user_id`、`role_id` | `uk_user_role(user_id,role_id)`、`idx_ur_role` |
| `sys_user_permission` | `user_id`、`permission_id` | `uk_user_perm(user_id,permission_id)` |
| `sys_role_permission` | `role_id`、`permission_id` | `uk_role_perm(role_id,permission_id)` |
| `sys_dept` | `parent_id` DEFAULT 0、`ancestors` VARCHAR(500)、`dept_name` VARCHAR(50)、`order_num` INT、`leader_user_id` BIGINT、`status` TINYINT | `idx_dept_parent`、`uk_dept_name(parent_id,dept_name)` |
| `sys_dept_role` | `dept_id`、`role_id` | `uk_dept_role(dept_id,role_id)` |

**权限 3 层落库示例**（`parent_id` + `ancestors`）：

```sql
-- ① 目录/模块层
(1, 0, '0',        '文档中心',  'doc:center', 1),
(2, 0, '0',        '系统管理',  'sys:center', 1),
-- ② 菜单/页面层（父=1 或 2）
(10, 1, '0,1',     '我的文档',  'doc:mine',   2),
(11, 1, '0,1',     '文档检索',  'doc:search', 2),
(20, 2, '0,2',     '用户管理',  'sys:user',   2),
-- ③ 按钮/操作点层（父=10 等）
(100, 10, '0,1,10', '新建文档', 'doc:create', 3),
(101, 10, '0,1,10', '发布文档', 'doc:publish',3),
(102, 10, '0,1,10', '删除文档', 'doc:delete', 3);
```
> 查"文档中心下全部权限"：`WHERE ancestors LIKE '0,1%'`（0 递归）；再加第 4 层无需改表。

### 4.3 文档域（6 张表）

| 表名 | 关键列 | 索引 |
|---|---|---|
| `doc_document` | `title` VARCHAR(128)、`summary` VARCHAR(255)、`content_md` MEDIUMTEXT、`category_id` BIGINT、`derived_from_id` BIGINT NULL、`price_cents` INT UNSIGNED DEFAULT 0、`status` VARCHAR(20)（DRAFT/PUBLISHED/ARCHIVED/TRASH）、`version_num` INT DEFAULT 1、`view_count` INT DEFAULT 0 | `idx_doc_category`、`idx_doc_status`、`idx_doc_created_by`、`FULLTEXT(title,summary)`（可选） |
| `doc_category` | `parent_id` DEFAULT 0、`ancestors`、`name` VARCHAR(50)、`order_num`、`status` | `idx_cat_parent` |
| `doc_tag` | `tag_name` VARCHAR(32) 唯一、`use_count` INT DEFAULT 0 | `uk_tag_name` |
| `doc_document_tag` | `document_id`、`tag_id` | `uk_doc_tag(document_id,tag_id)`、`idx_dt_tag` |
| `doc_version` | `document_id`、`version_num` INT、`content_md` MEDIUMTEXT、`change_log` VARCHAR(255) | `uk_doc_version(document_id,version_num)` |
| `doc_favorite` | `user_id`、`document_id` | `uk_fav(user_id,document_id)`、`idx_fav_doc` |

### 4.4 权限合并算法（Service 层实现，结果缓存进 Redis）

```text
effectivePermissions(userId):
  roles     = sys_user_role(userId) ∪ sys_role(所属部门 via sys_dept_role)
  fromRoles = ⋃ sys_role_permission(roles)
  direct    = sys_user_permission(userId)
  return dedupe(fromRoles ∪ direct)          // Set<String> perm_code
```
- 缓存键：`perm:user:{userId}`，TTL 30 分钟；
- **失效时机**：给用户/角色/部门增删授权、改动角色权限、停用用户时主动 `DEL`；
- 鉴权入口：自定义注解 `@RequiresPermission("doc:publish")` + AOP 切面校验（见 §5.5）。

### 4.5 文档状态机（写进 PRD，代码里用枚举实现）

```mermaid
stateDiagram-v2
    [*] --> DRAFT: 新建
    DRAFT --> PUBLISHED: 提交发布(属主/管理员)
    DRAFT --> TRASH: 删除
    PUBLISHED --> ARCHIVED: 归档(管理员)
    PUBLISHED --> TRASH: 下架(管理员)
    ARCHIVED --> PUBLISHED: 恢复(管理员)
    TRASH --> DRAFT: 恢复
    TRASH --> [*]: 彻底删除(管理员)
```
规则：`TRASH` 可恢复（不能是死胡同）；只有 `DRAFT` 可编辑；`PUBLISHED` 修改后版本号 +1。

---

## 5. 实体与后端实现规约

### 5.1 JPA 实体七戒律（来自课件 2.1，逐条自查）

| # | 戒律 | 正确写法 |
|---|---|---|
| 1 | 禁 `@Data` | `@Getter @Setter @ToString(callSuper=true) @NoArgsConstructor @AllArgsConstructor @Builder` |
| 2 | 必须无参构造 | 同上（Hibernate 代理需要） |
| 3 | 主键用包装类 `Long` | `private Long id;`（禁 `long`） |
| 4 | 禁 `ddl-auto=update` | `spring.jpa.hibernate.ddl-auto=none`，表结构走 `schema.sql` |
| 5 | 禁 `@ManyToMany` | 中间表建**显式实体**（可挂 `create_by/create_at`） |
| 6 | 禁自关联对象 | 树形用 `parentId` + `ancestors` 字段 |
| 7 | 雪花 ID 序列化为 String | `@JsonSerialize(using = ToStringSerializer.class)` |

**配套写法**：
```java
@Entity
@Table(name = "doc_document",
       indexes = { @Index(name="idx_doc_status", columnList="status"),
                   @Index(name="idx_doc_category", columnList="category_id") },
       comment = "文档主表")
@SQLDelete(sql = "UPDATE doc_document SET is_deleted = 1 WHERE id = ?")
@SQLRestriction("is_deleted = 0")
public class Document extends BaseEntity { … }
```
- 大文本：`@Lob @Column(name="content_md", columnDefinition="MEDIUMTEXT COMMENT 'Markdown 正文'")`
- 枚举：`@Enumerated(EnumType.STRING)`（禁 ORDINAL）
- 创建人/创建时间：`@Column(updatable = false)`
- 审计自动填充：`BaseEntity` 加 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedBy/@CreatedDate/@LastModifiedBy/@LastModifiedDate`，配置类开 `@EnableJpaAuditing`

### 5.2 统一响应与错误码

```java
// common/Result.java
public record Result<T>(int code, String message, T data, long timestamp) { … }
// common/PageResult.java
public record PageResult<T>(List<T> list, long total, int pageNum, int pageSize) { … }
```
错误码枚举：`SUCCESS(200)`、`PARAM_INVALID(400)`、`UNAUTHORIZED(401)`、`FORBIDDEN(403)`、`NOT_FOUND(404)`、`CONFLICT_STATUS(409)`、`USER_DISABLED(1001)`、`NO_PERMISSION(1002)`。

### 5.3 DTO / VO 规则

| 用途 | 类名示例 | 规则 |
|---|---|---|
| 登录入参 | `LoginDtoReq` | 独立类，不复用 User 实体 |
| 登录出参 | `LoginDtoResp`（token）+ `LoginVo`（用户信息 + 权限码组合） | 组合数据用 VO |
| 新增用户入参 | `UserDTO` | 含 `@NotBlank` 等校验与中文提示 |
| 文档出参 | `DocumentVo` / `DocumentDetailVo` / `DocumentListVo` | 脱敏；列表用轻量 VO |
| 分页包装 | `PageResult<DocumentListVo>` | 统一结构 |

### 5.4 注释模板（每个 public 方法都要）

```java
/**
 * 发布文档：把草稿流转为已发布，并生成一条版本记录。
 *
 * @author 你的名字
 * @param docId      文档ID（雪花ID）
 * @param operatorId 操作人ID（用于属主校验与审计）
 * @return 更新后的文档 VO
 * @throws BusinessException 当前用户既非属主也无管理权限时抛出 403
 */
public DocumentVo publish(Long docId, Long operatorId) { … }
```

### 5.5 权限校验实现（防 IDOR 的统一入口）

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission { String value(); }     // 如 @RequiresPermission("doc:publish")

@Aspect @Component @RequiredArgsConstructor
public class PermissionAspect {
    private final PermissionCacheService permissionCacheService;
    @Before("@annotation(requiresPermission)")
    public void check(JoinPoint jp, RequiresPermission requiresPermission) {
        Long userId = SecurityContext.currentUserId();
        if (!permissionCacheService.permissionsOf(userId).contains(requiresPermission.value())) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);   // → 403
        }
    }
}
```
**属主校验**（业务级，必须有）：
```java
if (!doc.getCreateBy().equals(operatorId) && !hasPerm(operatorId, "doc:manage")) {
    throw new BusinessException(ErrorCode.NO_PERMISSION);
}
```

---

## 6. 前端实现规约

### 6.1 页面清单（全部要实现）

| 路由 | 页面 | 权限点 | 关键交互 |
|---|---|---|---|
| `/login` | 登录 | — | 表单校验、错误提示、登录后写 Pinia + localStorage |
| `/docs` | 文档列表 | `doc:mine` / `doc:search` | 关键词/分类/标签筛选、分页、状态标签、空/加载/错误态 |
| `/docs/:id` | 文档详情 | 有权查看 | md 渲染（DOMPurify）、收藏、阅读量、派生按钮 |
| `/docs/edit/:id?` | Markdown 编辑器 | `doc:create` | 左右分栏（编辑/预览）、图片上传（≤5MB）、保存草稿、提交发布 |
| `/my` | 我的文档 | `doc:mine` | 列表 + 状态筛选 + 回收站入口 + 恢复 |
| `/review` | 审核队列 | `doc:review` | 通过/驳回（驳回理由必填）、版本对比 |
| `/admin/docs` | 文档管理 | `doc:manage` | 归档/下架/分类与标签维护 |
| `/admin/system` | 用户/角色/权限/部门 | `sys:*` | 用户 CRUD、角色授权（权限树勾选）、部门树与角色绑定 |

### 6.2 关键实现规则

- **ID 类型**：所有雪花 ID 在 TS 里是 `string`（后端已 `ToStringSerializer`）
- **Axios**：`src/api/request.ts` 统一实例；响应拦截 `code !== 200 → 提示 message`；401 → 清 token 跳登录；403 → 提示"无权限"
- **Pinia**：`useUserStore`（token、用户信息、权限码数组）；`hasPerm(code)` 供按钮级控制
- **路由守卫**：未登录跳 `/login`；无权限跳 `/403`
- **Markdown**：`markdown-it` 渲染 + `DOMPurify.sanitize()` 清洗（防 XSS）
- **样式**：只用 Tailwind 原子类；主题令牌写在 `tailwind.config.js`
- **文件上传**：前端先校验类型/大小，再 POST 到 `/api/upload/image`

---

## 7. 执行计划（M0 → M7）

> 用法：按顺序执行；每个任务都是可勾选项。**任务完成的标准 = 里程碑 DoD 通过 + 在 `docs/03-qa-review/tasks.md` 打勾并写 3 句以内变动说明。**

### 起步（今天就做这三件）
1. 在 GitHub 新建**空**仓库（Private）：`https://github.com/<账号>/campusswap-doc-platform.git`
2. 建立目录骨架：`campusswap/{docs/{01-requirements,02-design,03-qa-review,04-prompts},backend,frontend}`
3. 把本文件复制到 `docs/MASTER-PLAN.md`，开始 **M0-T0.1**

---

### M0 需求冻结（0.5~1 天）

**目标**：产出需求三剑客并冻结，后续设计与代码以它为准。**前置**：无。

- [x] **T0.1** 创建目录 `docs/01-requirements/`
- [x] **T0.2** 用 **P1** 生成"三角色用户旅程 + 8 个原子用户故事"，人工审校后写入 `docs/01-requirements/USER_STORIES.md`（角色固定 `STAFF/DOC_ADMIN/SYS_ADMIN`；编号固定 `US-01~US-08`）
- [x] **T0.3** 用 **P2** 为每个故事补 **Given-When-Then** 验收标准（每故事 **1 正常流 + ≥2 异常流**），追加进同一文件
- [x] **T0.4** 用 **P3** 生成 `docs/01-requirements/PRD.md`（必含：系统概述与 MVP 边界、RBAC 权限矩阵、Mermaid 状态机（对应本计划 §4.5）、模块详规与业务规则字典、NFR、显式非目标）
- [x] **T0.5** 用 **P4** 生成 `docs/01-requirements/GLOSSARY.md`（实体/枚举/业务动作三类表）
- [x] **T0.6** 冻结自检（5 条红线，全绿才进 M1）：
      - [x] INVEST 完备（无史诗级大故事，每故事 1~2 天可测完）
      - [x] BDD 覆盖（8 故事 × ≥3 条断言 = ≥24 条 `Given`）
      - [x] 状态机无死胡同（TRASH 可恢复、每状态都有出口）
      - [x] 非目标已锁（在线支付/物流/ES/协同编辑 明确写入 Out of Scope）
      - [x] 命名单源（"文档"全篇只用 `Document`）

**产出**：`USER_STORIES.md`、`PRD.md`、`GLOSSARY.md`
**DoD**：T0.6 五条全绿；`GLOSSARY.md` 中每个实体都能在实体表（§2）与字段字典（§3）中找到对应表名
**验证**：
```bash
ls docs/01-requirements/                                # 三个文件都在
grep -c "Given" docs/01-requirements/USER_STORIES.md   # ≥ 24
grep -c "^### US-" docs/01-requirements/USER_STORIES.md # 8（故事为三级标题）
powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m0.ps1   # 13 项机检，全绿则输出 ALL GREEN
```

**M0 收口记录（2026-09-21）**：需求三剑客已产出并冻结，机检脚本 `docs/03-qa-review/verify-m0.ps1` 连续两次运行 **13/13 全绿**（8 个故事 / 24 条 BDD 断言 / 39 个权限点 / 14 张表 / 24 条业务规则 / 9 条显式非目标）。
审校期间修正 2 处：① `US-04` 输出字段 `updatedAt` → `updateAt`（对齐 GLOSSARY §6.2 禁用别名）；② 本手册验证命令的锚点由 `^## US-` 改为 `^### US-`（故事实际为三级标题）。

---

### M1 设计定稿（1 天）

**目标**：把需求翻译成架构、接口契约、UI 规范。**前置**：M0 冻结通过。

- [ ] **T1.1** 用 **P6-1** 生成 `docs/02-design/ARCHITECTURE.md`：分层架构图、请求流转、RBAC 权限合并算法（§4.4）、Redis 键设计与失效时机、事务边界、统一响应与全局异常、鉴权拦截链路
- [ ] **T1.2** 用 **P6-2** 生成 `docs/02-design/API_SPECIFICATION.md`：逐接口表（模块｜方法｜路径｜入参 DTO｜出参 VO｜权限点｜错误码｜示例 JSON），覆盖全部 8 个用户故事
- [ ] **T1.3** 用 **P6-3** 生成 `docs/02-design/UI_UX_SPECIFICATION.md`：路由表（§6.1）、每页组件树、四态设计、Tailwind 令牌、表单校验规则与中文文案
- [ ] **T1.4** 在 APIFOX 建项目并录入接口（或基于 OpenAPI 导入），确保每条都能直接发送
- [ ] **T1.5** 交叉检查：接口出参字段 ⊂ GLOSSARY 术语，无新增字段

**DoD**：接口清单与 §6.1 页面清单一一对应；每接口有权限点与错误码；APIFOX 接口齐备
**验证**：`grep -cE "^\| *(GET|POST|PUT|DELETE)" docs/02-design/API_SPECIFICATION.md`（数量 ≥ 25）

---

### M2 数据库落地（0.5 天）

**目标**：`docs_db` 建好 14 张表，符合 §2.2。**前置**：M1 完成。

- [ ] **T2.1** 建库：`CREATE DATABASE IF NOT EXISTS docs_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
- [ ] **T2.2** 用 **P5** 生成 `sql/schema.sql`（14 张表，字段严格按 §4）
- [ ] **T2.3** DBeaver 打开该文件 → **Alt+X** 执行 → 全部建表成功
- [ ] **T2.4** 跑下方三条校验 SQL：外键数 0、主键无 `auto_increment`、空注释列 0
- [ ] **T2.5** 生成 `sql/data.sql` 种子数据：1 个 `SYS_ADMIN`（`admin / Admin@123`，BCrypt 哈希）、3 个角色、§4.2 的 9 条权限、2 个部门、3 篇示例文档 + 分类 + 标签
- [ ] **T2.6** 收纳：`mkdir -p backend/sql && cp sql/schema.sql backend/sql/`

**DoD**：14 张表齐；无外键；主键无自增；`price_cents` 为 `int unsigned`；注释 0 缺失
**验证**：
```sql
SELECT COUNT(*) AS fk_count FROM information_schema.TABLE_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA='docs_db' AND CONSTRAINT_TYPE='FOREIGN KEY';          -- 期望 0
SELECT TABLE_NAME,COLUMN_NAME,EXTRA FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='docs_db' AND COLUMN_KEY='PRI';                            -- EXTRA 无 auto_increment
SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='docs_db' AND (COLUMN_COMMENT='' OR COLUMN_COMMENT IS NULL);-- 期望空
SELECT TABLE_NAME,COLUMN_TYPE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='docs_db' AND COLUMN_NAME='price_cents';                   -- int unsigned
```

---

### M3 后端骨架 + RBAC（2~3 天）

**目标**：工程可启动、统一响应/异常/鉴权齐备，系统域接口全部可用。**前置**：M2 建表完成。

- [ ] **T3.1** 初始化后端工程（包名 `com.campusswap`，Java 17）：依赖 = web、data-jpa、mysql-connector-j、validation、data-redis、lombok、hutool-all、test
- [ ] **T3.2** `common/`：`Result<T>`、`PageResult<T>`、`ErrorCode`、`BusinessException`、`GlobalExceptionHandler`
- [ ] **T3.3** `config/`：`SnowflakeConfig`、`JpaAuditConfig`（`@EnableJpaAuditing` + `AuditorAware`）、`RedisConfig`、`WebMvcConfig`
- [ ] **T3.4** `entity/`：`BaseEntity` + 14 个实体（**按 §5.1 七戒律**）
- [ ] **T3.5** `repository/`：14 个接口（含 `findByUsername`、`existsByPermCode`、分页查询等）
- [ ] **T3.6** `dto/` `vo/`：登录三件套 + 用户/角色/权限/部门/文档各自的 DTO 与 VO
- [ ] **T3.7** `service` + `service/impl`：`AuthService`（登录/登出/改密）、`UserService`、`RoleService`、`PermissionService`、`DeptService`；写操作加 `@Transactional`
- [ ] **T3.8** 鉴权：`@RequiresPermission` + `PermissionAspect`（§5.5）+ `PermissionCacheService`（Redis 缓存 §4.4 结果）
- [ ] **T3.9** `controller/`：登录、用户 CRUD、角色 CRUD 与授权、权限树查询、部门树 CRUD；入参 `@Valid`、出参 VO
- [ ] **T3.10** 验证：`./mvnw clean compile` 零错误；APIFOX 依次跑通「登录 → 查权限树 → 新增用户 → 查询用户列表 → 给用户授角色」

**DoD**：编译零错误；登录返回 token 与用户 VO（**不含 `password_hash`**）；未授权访问返回 403；越权改他人数据返回 403
**验证**：
```bash
cd backend && ./mvnw clean compile
curl -s -X POST http://localhost:10086/backend/api/auth/login \
     -H "Content-Type: application/json" -d '{"username":"admin","password":"Admin@123"}'
```

---

### M4 文档业务（2~3 天）

**目标**：8 个用户故事的后端能力全部实现。**前置**：M3 通过。

- [ ] **T4.1** 文档创建/保存草稿（标题、摘要、正文、分类、标签）→ `US-02`
- [ ] **T4.2** 提交发布：`DRAFT → PUBLISHED`，同时写 `doc_version` → `US-03`
- [ ] **T4.3** 编辑与版本：每次保存 `version_num + 1` 并记录版本 → `US-06`
- [ ] **T4.4** 检索分页：关键词 + 分类（含子孙，`ancestors LIKE`）+ 标签 + 状态；只返回有权查看的 → `US-04`
- [ ] **T4.5** 派生：`derived_from_id` 指向源文档、正文预填；源文档无权访问返回 403 → `US-05`
- [ ] **T4.6** 收藏 + 阅读量：`doc_favorite` CRUD；阅读量用 Redis `INCR`，异步/定时回写 `view_count`
- [ ] **T4.7** 审核：`DOC_ADMIN` 通过/驳回（驳回理由必填）→ `US-07`
- [ ] **T4.8** 归档 / 回收站 / 恢复（状态机 §4.5 全分支）
- [ ] **T4.9** 图片上传：≤5MB，类型白名单（jpg/png/webp），存 `backend/uploads/yyyy/MM/`，返回访问 URL；文件名校验防路径穿越
- [ ] **T4.10** 分类树与标签维护接口（`DOC_ADMIN`）

**DoD**：`US-02~US-08` 每条 BDD 断言都有一条通过记录（写入 `docs/03-qa-review/TEST_CHECKLIST.md`）

---

### M5 前端实现（3~4 天）

**目标**：§6.1 的 8 个页面全部可用，通过类型检查与 lint。**前置**：M3/M4 接口可用。

- [ ] **T5.1** 初始化：`pnpm create vite frontend --template vue-ts` → 安装 `vue-router pinia axios tailwindcss markdown-it dompurify`；**只保留 `pnpm-lock.yaml`**
- [ ] **T5.2** `src/types/`：按 GLOSSARY 手写全部 Interface（**雪花 ID 用 `string`**）
- [ ] **T5.3** `src/api/`：`request.ts`（Axios 实例 + 拦截器）+ 按模块拆分的接口函数
- [ ] **T5.4** `src/stores/user.ts`：token、用户信息、权限码；`hasPerm(code)` 工具
- [ ] **T5.5** `src/router/`：路由表（§6.1）+ 守卫（未登录→登录；无权限→403）
- [ ] **T5.6** 页面实现：登录 → 文档列表 → 详情 → 编辑器（md 分栏预览 + 图片上传）→ 我的文档 → 审核队列 → 文档管理 → 系统管理
- [ ] **T5.7** 每页四态（空/加载/错误/无权限）与中文文案
- [ ] **T5.8** 自检：`pnpm run typecheck && pnpm run lint` 全绿

**DoD**：三条角色旅程本地可点通；`frontend/src` 无 `any`、无内联 `style=`
**验证**：
```bash
cd frontend && pnpm run typecheck && pnpm run lint
grep -rn "style=" src/ || echo "✅ 无内联样式"
```

---

### M6 测试与代码审查（1 天）

- [ ] **T6.1** Service 层单测：与 `USER_STORIES.md` 的 BDD 断言 1:1 对应
- [ ] **T6.2** `./mvnw clean test` 全绿
- [ ] **T6.3** 异常路径回归（逐条留记录）：越权改他人文档→403、状态冲突→409、非法参数→400、重复收藏→幂等/409
- [ ] **T6.4** `docs/03-qa-review/TEST_CHECKLIST.md` + `CODE_REVIEW.md`（对照 §2 逐条自查）
- [ ] **T6.5** `docs/03-qa-review/tasks.md` 全部勾选 + 每项 3 句变动说明

**DoD**：单测全绿；异常路径有记录；审查清单无未通过项

---

### M7 交付与上传（0.5 天）

- [ ] **T7.1** 目录裁剪：确保根目录**只有** `docs/`、`backend/`、`frontend/`（§3.2）
- [ ] **T7.2** 清理产物：删 `target/`、`logs/`、`uploads/` 内容（留 `.gitkeep`）、`node_modules/`、`dist/`
- [ ] **T7.3** Git 初始化与忽略自检（§9.4）
- [ ] **T7.4** 推送 GitHub（Private），仓库地址写进 `README.md` 与 `docs/MASTER-PLAN.md` 顶部
- [ ] **T7.5** 最终两条验收命令全绿 + §10 清单逐项打勾

**DoD**：GitHub 仓库可访问；`git ls-files` 不含产物目录；两条命令全绿

---

### 7.1 关键路径与时间预算

| 里程碑 | 工期 | 并行建议 |
|---|---|---|
| M0 需求冻结 | 0.5~1 天 | — |
| M1 设计定稿 | 1 天 | 与 M2 部分并行 |
| M2 数据库 | 0.5 天 | — |
| M3 后端骨架+RBAC | 2~3 天 | 前端 T5.1~T5.5 可提前 |
| M4 文档业务 | 2~3 天 | 前端页面可并行 |
| M5 前端 | 3~4 天 | — |
| M6 测试审查 | 1 天 | — |
| M7 交付上传 | 0.5 天 | — |
| **合计** | **10~14 天** | 后端接口一出就并行推进前端 |

---

## 8. Prompt 库（复制即用）

> 每步产出后**人工审校**再进入下一步；把产出文件路径写进对话，让 AI 直接落盘。

### P1 用户旅程与原子故事

```text
你是一名拥有 10 年经验的敏捷产品经理。项目是"单位内部文档管理平台"：
文档以 Markdown 为主，核心模式是让单位内部人员管理文档、快速检索文档，
并借助已有文档更快捷地编写新的 Markdown 文档。

请输出：
1. 「STAFF 普通员工」「DOC_ADMIN 文档管理员」「SYS_ADMIN 系统管理员」三个角色的端到端用户旅程地图（按时间轴）；
2. 8 个符合 INVEST 原则的原子用户故事，编号 US-01~US-08，采用"As a… I want to… So that…"三段式；
   覆盖：登录、创建草稿、提交发布、检索、派生复用、编辑自己的文档、管理员审核、系统管理员授权；
3. 严格 MVP 边界：不做在线支付/结算、不做物流、不引入 Elasticsearch、不做协同编辑。

输出为 Markdown，可直接写入 docs/01-requirements/USER_STORIES.md。
```

### P2 BDD 验收标准

```text
针对上一轮的 8 个用户故事，为每个故事编写 Given-When-Then 验收标准，追加到
docs/01-requirements/USER_STORIES.md。

硬性要求：
1. 每个故事：1 条正常流 + 至少 2 条异常边界流；
2. 异常流必须覆盖：未登录、无权限(403)、越权修改他人文档(IDOR)、字段非法(400)、
   状态冲突（如已发布再次提交）、重复提交/重复收藏、源文档不可见时派生；
3. 断言必须可判定，能直接翻译成 JUnit 测试方法名。
```

### P3 系统级 PRD

```text
根据已确认的 docs/01-requirements/USER_STORIES.md，升维编写 docs/01-requirements/PRD.md，必须包含：
1. 系统概述与 MVP 业务边界；
2. 角色与 RBAC 权限矩阵（Guest / STAFF / DOC_ADMIN / SYS_ADMIN 四维对比）；
3. 文档全生命周期状态机，用 Mermaid stateDiagram-v2 绘制：DRAFT / PUBLISHED / ARCHIVED / TRASH，
   标注每个流转的触发角色与前置条件，确保无死胡同；
4. 功能模块详规与业务规则字典（含权限点清单 perm_code 表）；
5. NFR：安全（BCrypt、响应脱敏、XSS 清洗、防 IDOR）、资源（正文上限、图片≤5MB、类型白名单）、
   性能（列表分页、Redis 缓存权限树、索引策略）；
6. 显式非目标清单（Out of Scope）。
```

### P4 统一领域语言词汇表

```text
基于已冻结的 PRD.md 与 USER_STORIES.md，输出 docs/01-requirements/GLOSSARY.md。
以 Markdown 表格分类列出：核心领域实体 / 核心领域枚举 / 业务操作动词。
列：中文领域概念 | 统一英文标识符 | 数据库与代码命名约定 | 业务含义与边界定义。
必须锁死：文档=Document、分类=Category、标签=Tag、权限=Permission、部门=Department、
正文=contentMd/content_md、价格=priceCents/price_cents（整数分）、
状态枚举 DocumentStatus(DRAFT/PUBLISHED/ARCHIVED/TRASH)、角色 RoleCode(STAFF/DOC_ADMIN/SYS_ADMIN)。
```

### P5 物理建表 DDL

```text
你是一名资深数据库架构师。请参考已冻结的 docs/01-requirements/PRD.md 与 GLOSSARY.md，
为单位内部文档管理平台（CampusSwap）编写生产级 MySQL 8.0 建表脚本 sql/schema.sql。

规范与硬性约束：
1. 每张表显式指定 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci；
2. 金额字段统一 INT UNSIGNED price_cents（单位分，0=免费赠送），严禁 FLOAT/DOUBLE；
3. 主键 BIGINT 雪花算法，程序生成，禁止 AUTO_INCREMENT；禁止创建任何外键约束；
4. 高频查询列建索引或复合索引（注意最左前缀）；
5. 所有表与字段必须有清晰完整的 COMMENT；
6. 所有业务表包含公共字段：id / create_by / create_at / update_by / update_at / is_deleted；
7. 表清单必须覆盖：
   sys_user, sys_role, sys_permission, sys_user_role, sys_user_permission, sys_role_permission,
   sys_dept, sys_dept_role, doc_document, doc_category, doc_tag, doc_document_tag, doc_version, doc_favorite；
8. sys_permission / sys_dept / doc_category 必须采用 parent_id + ancestors 祖先链设计（多层树）；
9. 输出单个 SQL 文件，并在文件头用注释给出表设计说明。
```

### P6 设计三件套（分三条发送）

```text
[P6-1] 依据已冻结的 PRD 与 GLOSSARY，输出 docs/02-design/ARCHITECTURE.md：
分层架构图、请求流转、RBAC 权限合并算法、Redis 键设计与失效时机、事务边界、
统一响应与全局异常、鉴权拦截链路（自定义注解 + AOP）。

[P6-2] 输出 docs/02-design/API_SPECIFICATION.md：按模块列出全部接口，逐条给出
模块 | 方法 | 路径 | 入参 DTO 字段（含校验规则与中文提示） | 出参 VO 字段 | 所需权限点 | 错误码 | 示例 JSON。
接口必须覆盖 8 个用户故事，字段名严格取自 GLOSSARY。

[P6-3] 输出 docs/02-design/UI_UX_SPECIFICATION.md：
路由表、每个页面的组件树、四态设计（空/加载/错误/无权限）、Tailwind 设计令牌、
表单校验规则与中文文案、md 渲染与图片上传交互细节。
```

### P7 任务看板拆解

```text
依据 docs/01-requirements/USER_STORIES.md 与 docs/02-design/API_SPECIFICATION.md，
生成 docs/03-qa-review/tasks.md 原子任务看板：
1. 按里程碑 M3~M7 分组；
2. 每个任务：编号 T-x.x、目标文件（1~3 个）、验收标准（引用对应 US 编号的 BDD 断言）、前后依赖、勾选框；
3. 粒度 30 分钟~半天；
4. 末尾附"完成定义"：任务完成后必须（a）跑通验收命令（b）勾选任务（c）写 3 句以内变动说明。
```

---

## 9. Git 与 GitHub 规范

### 9.1 不提交清单（老师明确）

| 端 | 目录 | 处理 |
|---|---|---|
| backend | `logs/`、`target/`、`uploads/` | 已在 `backend/.gitignore`（`uploads/` 用 `.gitkeep` 占位） |
| frontend | `node_modules/`、`dist/` | 已在 `frontend/.gitignore` |
| 各端 | `.idea/`、`*.iml`、`.vscode/` | 已忽略 |
| 任何位置 | 真实密码/密钥 | 见 9.3 |

### 9.2 上传步骤

```bash
cd campusswap
git init
git add .
git status                      # ★ 确认 target/ logs/ uploads/ node_modules/ dist/ 不在待提交列表
git commit -m "chore: 初始化 CampusSwap 文档管理平台"
git branch -M main
git remote add origin https://github.com/<账号>/campusswap-doc-platform.git
git push -u origin main         # 国内失败先开加速器（Clash Verge :7897）
```

### 9.3 密码不入库（必须处理）

```yaml
spring:
  datasource:
    username: root
    password: ${DB_PASSWORD:}    # 本地设环境变量 DB_PASSWORD=123456
```
或拆出 `application-local.yml`（写真实密码）并加入 `.gitignore`。仓库建议 **Private**。

### 9.4 提交前自检

```bash
git ls-files | grep -E "target/|node_modules/|logs/|uploads/|dist/" \
  && echo "❌ 有产物被跟踪，执行 git rm -r --cached <路径>" || echo "✅ 干净"
grep -rn "password" backend/src/main/resources/ | grep -v '\${'   # 不应出现明文密码
```

---

## 10. 最终验收清单（提交前逐条打勾）

**文档**
- [ ] `docs/01-requirements/USER_STORIES.md`（8 故事 + ≥24 条 BDD 断言）
- [ ] `docs/01-requirements/PRD.md`（含 Mermaid 状态机、RBAC 矩阵、NFR、Out of Scope）
- [ ] `docs/01-requirements/GLOSSARY.md`（实体/枚举/动词三类表）
- [ ] `docs/02-design/` 三件套齐全
- [ ] `docs/03-qa-review/tasks.md` 全勾 + 测试清单 + 审查记录
- [ ] APIFOX 接口文档导出（`docs/02-design/apifox-export/`）

**数据库**
- [ ] `backend/sql/schema.sql` 建出 14 张表；外键数 0；主键无自增；`price_cents` 为 `int unsigned`；注释 0 缺失

**后端源码**
- [ ] `./mvnw clean test` 全绿
- [ ] 分层正确：Controller 无业务、Service 无 SQL、出参全 VO
- [ ] 注释：每类有 `@author`；每个 public 方法有 `@param`/`@return`
- [ ] 红线自检：无浮点金额、入参全 `@Valid`+中文提示、写操作有属主校验、实体不透传
- [ ] 鉴权：`@RequiresPermission` 生效；越权返回 403 有测试记录

**前端源码**
- [ ] `pnpm run typecheck && pnpm run lint` 全绿
- [ ] 无 `any`、无内联 `style`
- [ ] 8 个页面 + 四态齐全；雪花 ID 类型为 `string`

**交付**
- [ ] 根目录只有 `docs/ backend/ frontend/`
- [ ] 已上传 GitHub，`git ls-files` 不含产物目录
- [ ] 仓库无明文密码；README 含启动说明与仓库地址

---

## 11. 风险与对策

| 风险 | 触发场景 | 对策 |
|---|---|---|
| 字段漂移 | AI 生成代码时自造字段名 | 生成前把 `GLOSSARY.md` + `schema.sql` 贴进上下文；生成后 grep 比对 |
| 实体穿透 | 图省事直接返回实体 | Controller 返回类型必须是 `Result<XxxVo>`；审查时 grep 返回类型 |
| 雪花 ID 精度丢失 | 前端 `...018300` 变 `...018000` | 实体侧 `@JsonSerialize(ToStringSerializer)`；TS 声明 `string` |
| 越权（IDOR） | 只凭 id 改数据 | Service 层属主校验 + `@RequiresPermission` 双保险 |
| N+1 查询 | 列表里循环查关联 | 关联统一用 `Long xxxId`；需要 JOIN 时用投影查询/`@EntityGraph` |
| 软删除失效 | 已删数据仍被查出 | `@SQLDelete` + `@SQLRestriction`，业务里不手写 `is_deleted` 条件 |
| md 渲染 XSS | 正文含脚本 | `DOMPurify.sanitize()` 后再渲染 |
| Maven wrapper 下载慢 | 首次 `./mvnw` 卡住 | `.mvn/wrapper/maven-wrapper.properties` 的 `distributionUrl` 换阿里云镜像 |
| 两套 lock 文件 | npm 与 pnpm 混用 | 只保留 `pnpm-lock.yaml`，删 `package-lock.json` |
| 产物误入库 | 忘记 `.gitignore` | 每次提交前跑 §9.4 自检 |

---

## 附录 A　术语表（GLOSSARY 起始草案，P4 产出后替换）

| 中文概念 | 英文标识符 | 数据库/代码命名 | 说明 |
|---|---|---|---|
| 用户 | `User` | `sys_user` / `User` | 工号或账号登录；一人属一个部门 |
| 角色 | `Role` | `sys_role` / `RoleCode` | `STAFF` / `DOC_ADMIN` / `SYS_ADMIN` |
| 权限 | `Permission` | `sys_permission` / `permCode` | 3 层树；如 `doc:publish` |
| 部门 | `Department` | `sys_dept` / `deptId` | 树形；`parentId + ancestors` |
| 文档 | `Document` | `doc_document` / `Document` | md 正文 + 状态 + 版本 |
| 分类 | `Category` | `doc_category` / `categoryId` | 树形 |
| 标签 | `Tag` | `doc_tag` / `tagId` | 与文档多对多（显式中间表） |
| 版本 | `DocumentVersion` | `doc_version` / `versionNum` | 线性递增 |
| 价格 | `priceCents` | `price_cents` | INT UNSIGNED，单位分，0=免费 |
| 派生来源 | `derivedFromId` | `derived_from_id` | 文档血缘 |
| 状态 | `DocumentStatus` | `status` | `DRAFT`/`PUBLISHED`/`ARCHIVED`/`TRASH` |
| 逻辑删除 | `isDeleted` | `is_deleted` | 0/1，配 `@SQLRestriction` |

---

## 附录 B　本机环境（已就绪）

| 项 | 现状 |
|---|---|
| JDK | `D:\DevEnv\02_JDK\jdk-21.0.6`（JAVA_HOME）与 `jdk-17.0.5`（编译目标 17） |
| Maven | 3.9.16（`D:\DevEnv\05_Maven`）+ 阿里云镜像 + 本地仓库 `D:\DevEnv\05_Maven\repository` |
| MySQL | 8.0.46，服务 `MySQL80` 自启，`root / 123456`，业务库 `docs_db` |
| Redis | 5.0.14，服务 `Redis`，端口 6379，仅监听 127.0.0.1 |
| IDE | IntelliJ IDEA Ultimate 2026.2.2（用户数据 `D:\DevEnv\08_IDEA-userdata`） |
| 数据库客户端 | DBeaver 25.2.0（已配连接）、Navicat Premium Lite 17、IDEA 数据库面板 |
| 接口调试 | Apifox（已安装） |
| 端口 | 后端 dev **10086**（context-path `/backend`），prod 10180 |
| 可复用资产 | `D:\DevEnv\projects\backend`：`BaseEntity`、`SnowflakeConfig`、`JpaAuditConfig`、`ResponseResult`、`User`/`Document` 实体、`UserRepository` —— 可迁移改造 |

---

## 附录 C　给下一个 Agent 的首轮指令（复制即用）

```text
你是本项目的全栈架构师。请完整阅读《CampusSwap 文档管理平台 · 大作业执行手册》（v2.0）
与 docs/01-requirements/ 下的三份需求文档，然后把 docs/03-qa-review/tasks.md 中第一个未完成任务落地。

必须遵守（违反即返工）：
1. 字段命名只认 docs/01-requirements/GLOSSARY.md 与 backend/sql/schema.sql，禁止自造字段；
2. 金额一律整数分（priceCents / price_cents），禁止 float/double；
3. Controller 入参必须 @Valid + Jakarta Validation + 中文错误提示；出参必须是 VO，禁止实体穿透；
4. 写操作（更新/删除/发布/派生）必须在 Service 层做属主校验，防越权；
5. 关联一律逻辑外键（Long xxxId），禁止数据库外键；树形用 parentId + ancestors；
6. 所有雪花 ID 序列化为 String（@JsonSerialize(using = ToStringSerializer.class)）；
7. 每个类写类注释含 @author；每个 public 方法写 @param / @return；
8. 每个任务完成后必须：跑 ./mvnw clean compile test（前端 pnpm run typecheck && pnpm run lint）→
   在 tasks.md 勾选 → 输出 3 句以内的关键变动说明；
9. 修改范围最小化，不重构与当前任务无关的代码。
```

---

## 附录 D　变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-20 | v1.0 初版：汇总三份课件（需求工程 SOP / JPA 实体规约 / 老师 AGENTS.md）与碎片要求 |
| 2026-09-21 | **v2.0 冻结版**：业务定为文档管理平台；前端自研；权限 3 层；Redis 用本机；交付三文件夹 + GitHub（含忽略清单）；评分只看源码；删除全部"待确认"表述，新增 M0~M7 可勾选任务看板与 7 组 Prompt |
