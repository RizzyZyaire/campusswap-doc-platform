# CampusSwap 文档管理平台 · 前端 UI/UX 设计规格说明书

| 项 | 值 |
|---|---|
| 文件 | `docs/02-design/UI_UX_SPECIFICATION.md` |
| 版本 | v1.0 |
| 日期 | 2026-09-21 |
| 状态 | **Frozen（已冻结）** |
| 上游依据 | `docs/MASTER-PLAN.md` §2.4（前端四条）、§5.3（DTO / VO 规则）、§6（前端实现规约，§6.1 页面清单）；`docs/01-requirements/PRD.md`（§3.2 权限点 39 个 / §4 状态机 / §5 功能点 F1-01~F2-20 / §6 业务规则 BR-01~BR-24 / §6.1 错误码）；`docs/01-requirements/GLOSSARY.md`（字段命名唯一真源 / §6 禁用别名 / §7 TS 类型）；`docs/01-requirements/USER_STORIES.md`（8 个故事 / 24 条 BDD） |
| 下游消费 | `frontend/src/`（`api/` `types/` `components/` `views/` `stores/` `router/` `utils/`）、`docs/03-qa-review/tasks.md`（M5 任务拆解）、M6 前端代码走查清单 |
| 范围边界 | 本文件定义页面、路由、组件、类型、样式令牌与交互；**不定义后端接口契约**（契约见 `docs/02-design/API_SPECIFICATION.md`），接口路径逐字取自本任务给定的可用路径清单 |

## 0. 阅读约定（三条硬约定）

1. **命名**：字段一律用 `GLOSSARY.md` 的 camelCase 名，并以 §0.1 的口径基线为准（与 §0.1 冲突的旧写法作废）；领域名词只使用 `GLOSSARY.md` §6 的合法词，该节列出的任何别名在本文件 0 出现；新增 VO 字段在本文件 §5.1 首次登记，M1 收口时回填 `GLOSSARY.md` §3。
2. **样式**：只用 Tailwind 原子类（令牌见 §2）；模板内不出现内联 style（红线 R6）；复合样式写进 `src/main.css` 的 `@layer components`。
3. **类型**：主键（后端 64 位自增 `BIGINT`）统一序列化为字符串，前端 TS 全部为 `string`；不使用 TypeScript 顶层通配逃逸类型（红线 R6），需要宽松类型时用 `unknown` + 类型守卫。

### 0.1 口径基线（baseline · anti-alias reference）（2026-09-21 用户拍板，对齐老师课件示例）

本文件按下列**新口径**编写；`GLOSSARY.md` 中与本节冲突的旧写法作废（GLOSSARY 同步重写中）。前端可感知的差异如下：

| # | 项目 | 新口径（本文件采用） | 作废的旧写法 |
|---|---|---|---|
| 1 | 审计列 | `created_at` / `created_by` / `updated_at` / `updated_by` / `deleted`；前端 TS `createdAt` / `createdBy` / `updatedAt` / `updatedBy` / `deleted` | `create_at` / `create_by` / `update_at` / `update_by` |
| 2 | 主键 | 后端 64 位自增 `BIGINT AUTO_INCREMENT`，仍统一序列化为字符串；前端 TS 一律 `string`（**不写 number**） | 雪花 ID（前端类型不变，仍是 `string`） |
| 3 | 用户状态（仅 `sys_user`） | `status: UserStatus`，取值 `ACTIVE`（正常）/ `LOCKED`（冻结）/ `DISABLED`（停用） | `sys_user.is_enabled` 布尔启停写法 |
| 4 | 名称 / 编码 / 类型 / 排序列 | 角色、权限、分类、标签、部门统一用 `name` / `code`；权限类型 `type: 'DIR' \| 'MENU' \| 'BUTTON'`；排序 `sort_order` → 前端 `sortOrder` | `role_name` / `perm_name` / `category_name` / `tag_name` / `dept_name`、`perm_code`、`perm_type`、`sort_num` |
| 5 | 中间表 | `doc_document_tag_rel`；`sys_user_role` / `sys_user_permission` / `sys_role_permission` / `sys_dept_role` / `doc_favorite` 为复合主键、**无 `id` 列**（对前端无字段影响） | `doc_document_tag`；中间表带自增主键 |
| 6 | 分页与响应体 | `PageVo<T>`：`{ list, total, pageNum, pageSize }`；统一响应体 `{ code, message, data }` | 无（不变） |
| 7 | 用户与角色 | **纯中间表关系**，`sys_user.role_code` 列取消；登录 / 我的信息出参返回 `roles: string[]`（角色码数组）+ `permissions: string[]` | `roleCode: RoleCode` 单值字段 |
| 8 | 逻辑删除 | 不暴露给用户：`deleted` 不进入任何 VO，界面不出现「逻辑删除」概念；回收站完全由文档 `status = 'TRASH'` 表达 | 界面上出现「已删除」标记 |
| 9 | 权限 / 部门 / 分类的启停 | **不存在启停能力**：`sys_permission` / `sys_dept` / `doc_category` 没有启停列；节点失效只由软删除表达，前端按钮一律叫「删除」 | 启停列、启用 / 停用开关、启停筛选、启停徽标 |

**前后端一致性提示**：`deleted` 只用于后端查询条件（`deleted = 0`），前端既不展示也不提交；`sys_user_permission`（用户级直授权中间表）在本规格接口清单内没有写接口，界面对应入口不渲染（见 §5.3）。

---

## 1. 路由总表（8 条，一条不多一条不少）

路由与权限点逐字取自 `MASTER-PLAN.md` §6.1。

| # | 路由 | 路由名 | 页面名 | 视图文件 | 权限点（守卫） | 用途与价值（没有它会怎样） |
|---|---|---|---|---|---|---|
| 1 | `/login` | `login` | 登录 | `src/views/auth/LoginView.vue` | 公开 | 换取 token 与权限码，是进入平台的唯一入口；没有它则所有接口恒返回 401，平台完全不可用 |
| 2 | `/docs` | `docs` | 文档列表（检索） | `src/views/document/DocumentListView.vue` | `doc:search`（无此码但有 `doc:mine` 时重定向 `/my`） | 用关键词 / 分类 / 标签三维检索已发布文档，把「找文档」从问同事变成自助查询；没有它则文档写入后无人能找到，V2「找得到」价值归零 |
| 3 | `/docs/:id` | `docs-detail` | 文档详情 | `src/views/document/DocumentDetailView.vue` | 有权查看（`doc:search`；属主可看自己的非 `PUBLISHED` 文档） | 渲染 Markdown 正文并提供收藏 / 派生 / 版本历史 / 驳回理由；没有它则检索结果点不开，US-07 的「驳回理由回传属主」闭环断掉 |
| 4 | `/docs/edit/:id?` | `docs-edit` | Markdown 编辑器 | `src/views/document/DocumentEditView.vue` | `doc:create`（新建）/ `doc:edit`（编辑，且后端 `canEdit=true` 才可写） | 左右分栏写 Markdown、上传图片、保存草稿、提交发布；没有它则平台无法产生内容，V1「写得快」与全部文档来源都消失 |
| 5 | `/my` | `my` | 我的文档 | `src/views/document/MyDocumentView.vue` | `doc:mine` | 个人资产台账：草稿接着写、已发布改版、误删从回收站恢复、收藏回看；没有它则 BR-07「软删除可恢复」在界面上无处操作，草稿只能靠记住 ID |
| 6 | `/review` | `review` | 审核队列 | `src/views/review/ReviewView.vue` | `doc:review` | 内容治理工作台：待治理一屏可见 + 版本对比给判断依据 + 驳回理由必填留痕；没有它则审核靠口头通知，US-07 的三条断言无页面可验收 |
| 7 | `/admin/docs` | `admin-docs` | 文档管理（治理） | `src/views/admin/DocumentAdminView.vue` | `doc:manage` | 全平台文档的归档 / 恢复上架 / 彻底删除，加分类与标签只读浏览与治理概览；没有它则违规内容只能物理删除，无法「下架留痕 + 只读可查」 |
| 8 | `/admin/system` | `admin-system` | 用户 / 角色 / 权限 / 部门 | `src/views/admin/SystemView.vue` | `sys:center`（页内各 tab 分别校验 `sys:user` / `sys:role` / `sys:perm` / `sys:dept`） | 把「谁能做什么」变成可配置：用户 CRUD、角色授权（权限树勾选）、部门树绑角色；没有它则新增员工、调整权限只能改数据库，US-08 无法在界面完成 |

### 1.1 异常路由（守卫目标，不计入 8 条业务路由）

| 路由 | 路由名 | 视图文件 | 用途 |
|---|---|---|---|
| `/403` | `forbidden` | `src/views/error/ForbiddenView.vue` | 已登录但权限不足时的落点；说明缺哪个权限并给出返回入口 |
| `/404` | `not-found` | `src/views/error/NotFoundView.vue` | 路由不存在时的落点；避免白屏 |
| `/` | — | — | 无独立视图，守卫按权限重定向：有 `doc:search` → `/docs`，否则有 `doc:mine` → `/my`，否则 → `/403` |

### 1.2 路由守卫判定表

| 情形 | 判定条件 | 动作 |
|---|---|---|
| 目标为公开路由 | `meta.public === true`（仅 `/login`） | 放行；若已登录则重定向 `/docs`（有 `doc:search`）或 `/my` |
| 未登录 | `useUserStore().token` 为空 | 重定向 `/login?redirect=<encodeURIComponent(to.fullPath)>` |
| 已登录但无路由权限 | `meta.perm` 存在且 `hasPerm(meta.perm)` 为 false 且 `meta.permAny` 全部不命中 | 重定向 `/403?perm=<meta.perm>` |
| 有 token 无用户信息 | `token` 非空且 `userInfo === null`（刷新页面） | 先 `await fetchMe()`（`GET /api/auth/me`）再走权限判定；该调用失败（401）→ 清 token 跳 `/login` |
| 目标路由不存在 | 未匹配到任何 `path` | 重定向 `/404` |

---

## 2. 全局设计令牌（tailwind.config.js）

```js
// frontend/tailwind.config.js
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#2563eb',
          50: '#eff6ff', 100: '#dbeafe', 200: '#bfdbfe', 300: '#93c5fd', 400: '#60a5fa',
          500: '#3b82f6', 600: '#2563eb', 700: '#1d4ed8', 800: '#1e40af', 900: '#1e3a8a'
        },
        gray: {
          50: '#f8fafc', 100: '#f1f5f9', 200: '#e2e8f0', 300: '#cbd5e1', 400: '#94a3b8',
          500: '#64748b', 600: '#475569', 700: '#334155', 800: '#1e293b', 900: '#0f172a'
        },
        success: { DEFAULT: '#16a34a', light: '#dcfce7', dark: '#15803d' },
        warning: { DEFAULT: '#d97706', light: '#fef3c7', dark: '#b45309' },
        danger:  { DEFAULT: '#dc2626', light: '#fee2e2', dark: '#b91c1c' },
        info:    { DEFAULT: '#0284c7', light: '#e0f2fe', dark: '#0369a1' }
      },
      spacing: { '4.5': '1.125rem', '18': '4.5rem', sidebar: '15rem', header: '3.5rem' },
      borderRadius: { control: '0.375rem', card: '0.5rem', pill: '9999px' },
      boxShadow: {
        card: '0 1px 2px 0 rgb(15 23 42 / 0.06), 0 1px 3px 0 rgb(15 23 42 / 0.10)',
        pop: '0 10px 15px -3px rgb(15 23 42 / 0.10), 0 4px 6px -4px rgb(15 23 42 / 0.10)',
        focus: '0 0 0 3px rgb(37 99 235 / 0.35)'
      },
      fontSize: {
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
        code: ['0.8125rem', { lineHeight: '1.25rem' }]
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'Microsoft YaHei', 'sans-serif'],
        mono: ['JetBrains Mono', 'Consolas', 'monospace']
      },
      maxWidth: { content: '72rem' },
      screens: { md: '768px', lg: '1024px', xl: '1366px' }
    }
  },
  plugins: []
}
```

### 2.1 用法约束（8 条）

| # | 约束 |
|---|---|
| T1 | 只用令牌类名：`bg-primary-600`、`text-gray-700`、`rounded-card`、`shadow-card`。**禁止任意值**写法（形如方括号内联色值或像素，如 `bg-[#2563eb]`、`text-[13px]`） |
| T2 | 模板内**禁止内联 style 属性**（红线 R6）；复合样式统一定义在 `src/main.css` 的 `@layer components`（如 `.markdown-body`、`.editor-split`） |
| T3 | 状态色语义固定：`success` = 已发布 / 成功提示；`warning` = 草稿 / 待处理；`info` = 已归档 / 中性提示；`danger` = 回收站 / 危险操作 / 错误；`gray` = 禁用 |
| T4 | 字号固定：正文与表格 `text-sm`；页面标题 `text-2xl`；区块标题 `text-lg`；辅助信息 `text-xs`；代码块 `font-mono text-code` |
| T5 | 间距只用 4 的倍数刻度（`p-1`~`p-10`、`gap-2`/`gap-4`/`gap-6`）与语义刻度（`w-sidebar` 240px、`h-header` 56px） |
| T6 | 图标统一走 `src/components/common/AppIcon.vue`（内联 SVG，`name` 属性传图标名），尺寸只用 `h-4 w-4` 或 `h-5 w-5` |
| T7 | 焦点环统一 `focus:outline-none focus:shadow-focus`；可点击元素必须有 `cursor-pointer` 与 `hover:` 态 |
| T8 | 暗色模式本期不做：不写 `dark:` 变体。最小可用视口 1366×768（NFR-C1），内容区 `max-w-content` |

### 2.2 文档状态 `DocumentStatus` 徽标配色映射

| 状态值 | 中文（GLOSSARY §4.1） | 类名 |
|---|---|---|
| `DRAFT` | 草稿 | `bg-warning-light text-warning-dark` |
| `PUBLISHED` | 已发布 | `bg-success-light text-success-dark` |
| `ARCHIVED` | 已归档 | `bg-info-light text-info-dark` |
| `TRASH` | 回收站 | `bg-danger-light text-danger-dark` |

### 2.3 用户状态 `UserStatus` 徽标配色映射与行为（`StatusTag.vue` `mode="user-status"`）

| 状态值 | 中文 | 类名 | 能否登录 | 界面可执行操作 |
|---|---|---|---|---|
| `ACTIVE` | 正常 | `bg-success-light text-success-dark` | ✅ 允许 | 全部状态变更按钮可用 |
| `LOCKED` | 冻结 | `bg-warning-light text-warning-dark` | ⛔ 拒绝（403） | 可改为 `ACTIVE` / `DISABLED` |
| `DISABLED` | 停用 | `bg-gray-200 text-gray-600` | ⛔ 拒绝（403） | 可改为 `ACTIVE` / `LOCKED` |

> `StatusTag.vue` 是全站唯一的状态徽标组件：文档状态走 `mode="document"`（默认，映射见 §2.2），用户状态走 `mode="user-status"`（本节）。状态值一律以枚举字面量比较，禁止用数字或中文做判断。

---

## 3. 目录结构与通用组件

### 3.1 目录树（顶层目录固定为 `MASTER-PLAN.md` §2.4 与 §6.2 规定的 7 个，不新增顶层目录）

```text
frontend/
├── index.html
├── tailwind.config.js
├── vite.config.ts
└── src/
    ├── main.ts                       # 应用入口：挂载 Pinia、Router、注册 v-perm 指令
    ├── App.vue                       # 根组件：只放 <RouterView />
    ├── main.css                      # Tailwind 指令 + @layer components 通用类
    ├── markdown.css                  # Markdown 渲染区样式（被 main.css 引入）
    ├── api/
    │   ├── request.ts                # Axios 统一实例（拦截器见 §5.2）
    │   ├── auth.ts                   # 登录 / 登出 / 当前用户
    │   ├── user.ts  role.ts  permission.ts  dept.ts
    │   ├── document.ts  category.ts  tag.ts
    │   ├── review.ts  upload.ts  stat.ts
    ├── types/
    │   ├── common.ts                 # ResponseResult<T> / PageVo<T> / PageDtoReq / AuditVo / ErrorCode
    │   ├── auth.ts                   # LoginDtoReq / LoginVo / UserInfoVo
    │   ├── document.ts               # DocumentStatus / DocumentVo / DocumentDetailVo / DocumentCreateDtoReq / DocumentUpdateDtoReq / DocumentSearchDtoReq / DocumentVersionVo / CategoryVo / TagVo / ImageVo
    │   └── admin.ts                  # UserStatus / PermType / RoleCode / AuditVo / UserVo / UserCreateDtoReq / UserUpdateDtoReq / RoleVo / RoleDtoReq / PermissionVo / PermissionUpdateDtoReq / DeptVo / StatVo / PageDtoReq
    ├── components/
    │   ├── common/                   # AppLayout AppHeader AppSidebar AppIcon TabNav DataTable PaginationBar
    │   │                             # EmptyState LoadingState ErrorState NoPermHint ConfirmDialog FormField StatusTag PermButton
    │   ├── markdown/                 # MarkdownEditor EditorToolbar MarkdownPreview
    │   ├── document/                 # DocumentFilterBar DocumentTable DocumentMetaPanel CategoryTreeSelect TagSelect VersionDrawer RejectReasonAlert
    │   ├── review/                   # ReviewFilterBar ReviewTable AuditDrawer VersionCompare RejectDialog
    │   └── admin/                    # UserPanel UserEditDialog ResetPasswordDialog RolePanel RoleEditDialog
    │                                 # PermissionPanel PermissionGrantDialog DeptPanel DeptRoleDialog
    │                                 # GovernanceOverview GovernanceTable CategoryTagPanel
    ├── views/
    │   ├── auth/LoginView.vue
    │   ├── document/DocumentListView.vue  DocumentDetailView.vue  DocumentEditView.vue  MyDocumentView.vue
    │   ├── review/ReviewView.vue
    │   ├── admin/DocumentAdminView.vue  SystemView.vue
    │   └── error/ForbiddenView.vue  NotFoundView.vue
    ├── router/
    │   ├── index.ts                  # createRouter + 挂载 guard
    │   ├── routes.ts                 # 8 条业务路由 + 403/404 + 重定向
    │   └── guard.ts                  # beforeEach 守卫（§1.2）
    ├── stores/
    │   └── user.ts                   # useUserStore（§4.1）
    └── utils/
        ├── format.ts                 # 时间展示、priceCents 分转元、resolveAssetUrl
        ├── markdown.ts               # markdown-it 实例 + DOMPurify 清洗（§7.1）
        ├── toast.ts                  # 统一轻提示（成功 / 失败 / 警告）
        ├── usePermission.ts          # hasPerm / hasAnyPerm（§4.2）
        ├── permissionDirective.ts    # v-perm 指令实现
        ├── useImageUpload.ts         # 图片上传三入口 + 进度 + 重试（§7.3）
        ├── useConfirm.ts             # 二次确认（BR-08）
        └── usePageSync.ts           # 列表页分页 / 筛选状态与 URL query 同步
```

### 3.2 通用组件职责表

| 组件 | 职责（一句话） |
|---|---|
| `common/AppLayout.vue` | 应用外壳：左侧 `AppSidebar` + 顶部 `AppHeader` + 内容区 `<RouterView />` |
| `common/AppHeader.vue` | 面包屑、当前用户名与角色中文名（`roles` 多角色用顿号连接，中文映射取自 `GLOSSARY.md` §4.2）、退出登录入口 |
| `common/AppSidebar.vue` | 按权限码过滤后的两级导航（`doc:center` 目录组 + 菜单项） |
| `common/TabNav.vue` | 页内 tab 切换（受控组件，`v-model` 绑当前 tab key） |
| `common/DataTable.vue` | 通用表格：列定义、行点击、内置骨架加载态与横向滚动 |
| `common/PaginationBar.vue` | 分页条：`pageNum` / `pageSize`（可选 10 / 20 / 50 / 100，受 BR-05 上限 100 约束）/ `total` |
| `common/EmptyState.vue` | 空态容器：图标 + 主文案 + 副文案 + 可选主行动按钮 |
| `common/LoadingState.vue` | 加载态容器：骨架行数可配（表格 / 卡片 / 正文三种预设） |
| `common/ErrorState.vue` | 错误态容器：错误文案 + 服务端 message + 重试按钮 |
| `common/NoPermHint.vue` | 内联无权限提示条：说明缺少的权限名称，不阻断整页 |
| `common/ConfirmDialog.vue` | 二次确认弹窗：标题、正文、确认文案可配，危险操作按钮用 `danger` 色 |
| `common/FormField.vue` | 表单字段外壳：标签、必填星号、错误红字、错误聚焦锚点 |
| `common/StatusTag.vue` | 文档状态徽标（配色见 §2.2）与用户 `status` 三态徽标（见 §2.3） |
| `common/PermButton.vue` | 权限按钮：`perm` 属性不含当前用户权限码时**不渲染**，`variant` 控制样式 |
| `markdown/MarkdownEditor.vue` | 左栏 Markdown 源文输入（`textarea`）+ 光标位置与选区管理 + 粘贴 / 拖拽上传入口 |
| `markdown/EditorToolbar.vue` | 工具栏：加粗、标题、列表、引用、代码块、链接、图片上传按钮 |
| `markdown/MarkdownPreview.vue` | 右栏渲染：`markdown-it` 渲染 + `DOMPurify.sanitize()` 清洗后 `v-html` |
| `document/DocumentFilterBar.vue` | 检索条件区：关键词、分类树、标签多选、排序、清空筛选 |
| `document/DocumentTable.vue` | 文档列表表格（检索 / 我的 / 治理三处复用，列与操作按钮由 props 控制） |
| `document/DocumentMetaPanel.vue` | 文档元信息表单区：标题、摘要、分类、标签、价格标记 |
| `document/CategoryTreeSelect.vue` | 分类树下拉选择（数据来自 `GET /api/categories/tree`，最多 3 层，BR-13） |
| `document/TagSelect.vue` | 标签多选（最多 5 个，BR-14），支持从 `GET /api/tags` 结果过滤 |
| `document/VersionDrawer.vue` | 版本历史抽屉：按 `versionNum` 倒序，展示 `changeType` / `changeRemark` / 操作人 / 时间 |
| `document/RejectReasonAlert.vue` | 驳回理由横幅（`rejectReason` 非空时显示，属主可见） |
| `review/ReviewFilterBar.vue` | 审核队列筛选：状态、关键词、作者 |
| `review/ReviewTable.vue` | 审核列表表格：提交人、状态、更新时间、审核入口 |
| `review/AuditDrawer.vue` | 审核详情抽屉：正文预览 + 通过（备注）/ 驳回（理由）操作区 |
| `review/VersionCompare.vue` | 版本并排对比：左旧右新，按行高亮差异 |
| `review/RejectDialog.vue` | 驳回弹窗：理由必填（BR-12），未填时按钮禁用并给红字 |
| `admin/GovernanceOverview.vue` | 治理概览卡片：平台已发布数、我的文档数、我的收藏数（`GET /api/stats/overview`） |
| `admin/GovernanceTable.vue` | 治理列表：状态筛选 + 归档 / 恢复上架 / 彻底删除（彻底删除走 `ConfirmDialog` 输入标题二次确认） |
| `admin/CategoryTagPanel.vue` | 分类树与标签只读浏览面板（含文档计数展示位） |
| `admin/UserPanel.vue` | 用户列表：关键词 / 部门 / `status` 三态筛选 + 分页 |
| `admin/UserEditDialog.vue` | 用户新增与编辑弹窗（新增含初始密码，编辑不含密码，见 §6） |
| `admin/ResetPasswordDialog.vue` | 重置密码弹窗：新密码 + 确认密码 |
| `admin/RolePanel.vue` | 角色列表：内置角色（`isBuiltin=1`）不显示删除按钮 |
| `admin/RoleEditDialog.vue` | 角色新增与编辑弹窗（编辑态 `code` 只读，BR-02） |
| `admin/PermissionPanel.vue` | 权限树浏览与单节点编辑（`GET /api/permissions/tree` + `PUT/DELETE /api/permissions/{id}`） |
| `admin/PermissionGrantDialog.vue` | 角色授权弹窗：三层权限树勾选（目录 → 菜单 → 按钮），保存走 `POST /api/roles/{id}/permissions` |
| `admin/DeptPanel.vue` | 部门树浏览（`GET /api/depts/tree`）+ 授权入口 |
| `admin/DeptRoleDialog.vue` | 部门绑定角色弹窗（`POST /api/depts/{id}/roles`） |

---

## 4. 权限驱动渲染

### 4.1 `useUserStore`（`src/stores/user.ts`）

```ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserInfoVo, LoginDtoReq, LoginVo } from '../types/auth'
import { login as loginApi, logout as logoutApi, fetchMe } from '../api/auth'

const TOKEN_KEY = 'campusswap_token'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const userInfo = ref<UserInfoVo | null>(null)
  const permissions = ref<string[]>([])

  const isLogin = computed<boolean>(() => token.value !== '')
  const roles = computed<string[]>(() => userInfo.value?.roles ?? [])

  async function login(payload: LoginDtoReq): Promise<void> {
    const data: LoginVo = await loginApi(payload)
    token.value = data.token
    userInfo.value = data.userInfo
    permissions.value = data.permissions
    localStorage.setItem(TOKEN_KEY, data.token)
  }

  async function fetchMe(): Promise<void> {
    const data: UserInfoVo = await fetchMe()
    userInfo.value = data
    permissions.value = data.permissions
  }

  function hasPerm(code: string): boolean {
    return permissions.value.includes(code)
  }

  function hasAnyPerm(codes: string[]): boolean {
    return codes.some((code) => permissions.value.includes(code))
  }

  async function logout(): Promise<void> {
    try {
      await logoutApi()
    } finally {
      clear()
    }
  }

  function clear(): void {
    token.value = ''
    userInfo.value = null
    permissions.value = []
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, userInfo, permissions, isLogin, roles, login, fetchMe, hasPerm, hasAnyPerm, logout, clear }
})
```

### 4.2 `hasPerm` 组合式函数与 `v-perm` 指令

```ts
// src/utils/usePermission.ts
import { useUserStore } from '../stores/user'

export function hasPerm(code: string): boolean {
  return useUserStore().hasPerm(code)
}

export function hasAnyPerm(codes: string[]): boolean {
  return useUserStore().hasAnyPerm(codes)
}
```

```ts
// src/utils/permissionDirective.ts —— 在 main.ts 中 app.directive('perm', permDirective)
import type { Directive } from 'vue'
import { useUserStore } from '../stores/user'

export const permDirective: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const store = useUserStore()
    const need = binding.value
    const allowed = Array.isArray(need) ? store.hasAnyPerm(need) : store.hasPerm(need)
    if (!allowed) {
      el.parentElement?.removeChild(el)
    }
  }
}
```

### 4.3 按钮级控制的三种写法（按场景择一，禁止混用同一按钮）

| 场景 | 写法 | 示例 |
|---|---|---|
| 单个动作按钮 | `PermButton`（无权限即不渲染） | `<PermButton perm="doc:publish" variant="primary" @click="onPublish">提交发布</PermButton>` |
| 现有元素上挂权限 | `v-perm` 指令 | `<button v-perm="'doc:delete'" class="..." @click="onDelete">删除</button>` |
| 组合条件（权限 + 业务状态） | `v-if` + `hasPerm()` / `hasAnyPerm()` | `v-if="hasPerm('doc:edit') && detail.canEdit"` |

**硬规则**：前端隐藏按钮只是体验优化；每一次写操作仍由后端 `@RequiresPermission` 与 Service 层属主校验兜底（PRD §2 尾注 / BR-21）。前端**不得**把权限判定结果当作接口安全的依据。

### 4.4 侧栏导航按权限码过滤

| 菜单项 | 权限码 | 目标路由 | 显示条件 |
|---|---|---|---|
| 文档中心（目录组） | `doc:center` | — | 组内至少一项可见时渲染目录组标题 |
| 文档检索 | `doc:search` | `/docs` | 含 `doc:search` |
| 我的文档 | `doc:mine` | `/my` | 含 `doc:mine` |
| 审核队列 | `doc:review` | `/review` | 含 `doc:review` |
| 文档治理 | `doc:manage` | `/admin/docs` | 含 `doc:manage` |
| 系统管理（目录组） | `sys:center` | — | 组内至少一项可见时渲染目录组标题 |
| 用户管理 / 角色管理 / 权限管理 / 部门管理 | `sys:user` / `sys:role` / `sys:perm` / `sys:dept` | `/admin/system?tab=user` 等 | 各自含对应权限码 |

过滤实现：菜单描述数组写成常量（含 `perm` 与 `path`），渲染前用 `hasPerm` 过滤，目录组再判断 `children.length > 0`。**不使用** `GET /api/permissions/tree` 动态生成导航（该接口只服务于系统管理页的权限树展示与授权勾选）。

### 4.5 `/403` 与 `/404` 页面规格

| 页面 | 视觉 | 文案 | 交互 |
|---|---|---|---|
| `/403` | 居中卡片：锁形图标（`danger` 色圆形底）+ 标题 + 说明 | 标题「403 无访问权限」；说明「当前账号缺少访问该页面的权限，请联系系统管理员分配」；若 URL 带 `?perm=xxx` 追加一行「缺少权限：`xxx`」 | 主按钮「返回首页」（按 §1.1 规则重定向）；次按钮「退出登录」（`useUserStore().logout()` 后跳 `/login`） |
| `/404` | 居中卡片：问号图标（`gray` 色圆形底）+ 标题 + 说明 | 标题「404 页面不存在」；说明「地址可能输入有误，或该内容已被移除」 | 主按钮「返回首页」；次按钮「去检索文档」（`doc:search` 可见时显示） |

---

## 5. 类型与请求约定

### 5.1 `src/types/` 完整定义

`GLOSSARY.md` §7 的示例已按 §0.1 新口径更新（审计字段统一为 `createdAt` / `updatedAt`）后引用；`AuditVo` / `UserStatus` / `PermType` / `UserStatusDtoReq` / `StatVo` / `DocumentVersionVo` / `ImageVo` / `PermissionVo` / `DeptVo` / `RoleVo` / `UserVo` 的字段按 §0.1 与 `PRD.md` §5 的输入输出说明定义，并已在 M1 收口时登记进 `GLOSSARY.md` §3.7（字段级唯一真源）。

**审计字段约定**：所有实体型 VO 继承 `AuditVo`（`createdAt` / `createdBy` / `updatedAt` / `updatedBy`）；聚合 VO（`StatVo`）与上传回执（`ImageVo`）不继承；`deleted` 不进入任何 VO（§0.1 第 8 条）。权限 / 部门 / 分类三类节点**没有启停字段**（§0.1 第 9 条），失效一律由软删除表达，前端只提供「删除」入口；全站只有 `sys_user` 有 `status` 三态。

```ts
// src/types/common.ts
export interface ResponseResult<T> {
  code: number
  message: string
  data: T
}

export interface PageVo<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}

export interface PageDtoReq {
  pageNum: number
  pageSize: number
}

export interface AuditVo {
  createdAt: string
  createdBy: string
  updatedAt: string
  updatedBy: string
}

// deleted 不进入任何 VO；回收站由文档 status = TRASH 表达（口径 §0.1 第 8 条）

export type ErrorCode =
  | 'SUCCESS'
  | 'BAD_REQUEST'
  | 'UNAUTHORIZED'
  | 'NO_PERMISSION'
  | 'USER_DISABLED'
  | 'NOT_FOUND'
  | 'CONFLICT_STATUS'
  | 'SERVER_ERROR'
```

```ts
// src/types/auth.ts
import type { AuditVo } from './common'

export interface LoginDtoReq {
  username: string
  password: string
}

export interface UserInfoVo extends AuditVo {
  id: string
  username: string
  realName: string
  deptId: string
  deptName: string        // 联表展示字段（部门 name 列的扁平化展示）
  roles: string[]         // 角色码数组：用户与角色为纯中间表关系
  avatarUrl: string
  permissions: string[]
}

export interface LoginVo {
  token: string
  userInfo: UserInfoVo
  roles: string[]         // 与 userInfo.roles 同源，供 store 一次写入
  permissions: string[]   // 与 userInfo.permissions 同源
}
```

```ts
// src/types/document.ts
import type { AuditVo, PageDtoReq } from './common'

export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'TRASH'

export interface DocumentVo extends AuditVo {
  id: string              // 主键统一序列化为字符串（后端 64 位自增 → string）
  title: string
  summary: string
  categoryId: string
  categoryName: string    // 联表展示字段（doc_category.name 的扁平化展示）
  authorId: string
  authorName: string      // 联表展示字段（sys_user.real_name）
  status: DocumentStatus
  versionNum: number
  priceCents: number      // 单位：分
  viewCount: number
  favoriteCount: number
  canEdit: boolean
}

export interface DocumentDetailVo extends DocumentVo {
  contentMd: string
  derivedFromId: string | null
  rejectReason: string | null
  favorited: boolean
}

export interface DocumentCreateDtoReq {
  title: string
  summary: string
  contentMd: string
  categoryId: string
  tagIds: string[]
  priceCents: number
}

export interface DocumentUpdateDtoReq extends DocumentCreateDtoReq {
  id: string
}

export type DocumentSort = 'updatedAt_desc' | 'publishAt_desc' | 'viewCount_desc'

export interface DocumentSearchDtoReq extends PageDtoReq {
  keyword?: string
  categoryId?: string
  tagIds?: string[]
  status?: DocumentStatus
  sort?: DocumentSort
}

export interface DocumentVersionVo extends AuditVo {
  id: string
  documentId: string
  versionNum: number
  title: string
  contentMd: string
  changeType: string
  changeRemark: string | null
  operatorId: string
  operatorName: string   // VO 专有展示字段，与 authorName / categoryName 同例
}

export interface CategoryVo extends AuditVo {
  id: string
  name: string           // doc_category.name
  parentId: string
  ancestors: string
  sortOrder: number
  children: CategoryVo[]
}

export interface TagVo extends AuditVo {
  id: string
  name: string           // doc_tag.name
  useCount: number
}

export interface ImageVo {
  url: string            // 相对 URL：uploads/yyyy/MM/{uuid}.{ext}
}
```

```ts
// src/types/admin.ts
import type { AuditVo } from './common'

export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED'
export type PermType = 'DIR' | 'MENU' | 'BUTTON'
export type RoleCode = 'STAFF' | 'DOC_ADMIN' | 'SYS_ADMIN'   // 三个内置角色的 code 取值

export interface UserVo extends AuditVo {
  id: string
  username: string
  realName: string
  deptId: string
  deptName: string        // 联表展示字段（部门 name 列的扁平化展示）
  roles: string[]         // 角色码数组
  phone: string
  email: string
  avatarUrl: string
  status: UserStatus
  lastLoginAt: string
}

export interface UserCreateDtoReq {
  username: string
  realName: string
  deptId: string
  roles: string[]
  phone: string
  email: string
  password: string
}

export interface UserUpdateDtoReq {
  realName: string
  deptId: string
  roles: string[]
  phone: string
  email: string
}

export interface UserStatusDtoReq {
  status: UserStatus
}

export interface RoleVo extends AuditVo {
  id: string
  name: string
  code: string
  description: string
  isBuiltin: number
  sortOrder: number
}

export interface RoleDtoReq {
  name: string
  code: string
  description: string
}

export interface PermissionVo extends AuditVo {
  id: string
  name: string
  code: string
  type: PermType
  parentId: string
  ancestors: string
  path: string
  icon: string
  sortOrder: number
  children: PermissionVo[]
}

export interface PermissionUpdateDtoReq {
  name: string
  type: PermType
  parentId: string
  sortOrder: number
  icon: string
  path: string
}

export interface DeptVo extends AuditVo {
  id: string
  name: string
  parentId: string
  ancestors: string
  sortOrder: number
  children: DeptVo[]
}

export interface StatVo {
  myDocumentCount: number
  myFavoriteCount: number
  publishedCount: number
}
```
```ts
// src/types/document.ts 补充（分页包装直接用 PageVo）
import type { PageVo } from './common'
import type { DocumentVo, DocumentVersionVo, CategoryVo, TagVo } from './document'
import type { UserVo, RoleVo, PermissionVo, DeptVo } from './admin'

export type DocumentPageVo = PageVo<DocumentVo>
export type UserPageVo = PageVo<UserVo>
export type RolePageVo = PageVo<RoleVo>
export type DocumentVersionListVo = DocumentVersionVo[]
export type CategoryTreeVo = CategoryVo[]
export type TagListVo = TagVo[] | TagListVo[]
export type PermissionTreeVo = PermissionVo[]
export type DeptTreeVo = DeptVo[]
```

> 上面最后一段是本文件唯一允许的「类型别名段」：别名只做可读性包装，**不得**引入 `GLOSSARY.md` §6.1 的禁用后缀；别名必须与真实类型同构，不允许新增字段。

### 5.2 `src/api/request.ts` 与拦截器行为

```ts
// src/api/request.ts
import axios from 'axios'
import type { AxiosInstance, AxiosError, InternalAxiosRequestConfig } from 'axios'
import type { ResponseResult } from '../types/common'
import { useUserStore } from '../stores/user'
import { showError } from '../utils/toast'
import router from '../router'

const request: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useUserStore().token
  if (token !== '') {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data as ResponseResult<unknown>
    if (body.code === 200) {
      return body.data
    }
    if (body.code === 401) {
      handleUnauthorized()
      return Promise.reject(new Error(body.message))
    }
    showError(body.code === 403 ? '无权限执行该操作' : body.message)
    return Promise.reject(new Error(body.message))
  },
  (error: AxiosError<ResponseResult<unknown>>) => {
    const status = error.response?.status
    if (status === 401) {
      handleUnauthorized()
    } else if (status === 403) {
      showError('无权限执行该操作')
    } else if (status === undefined) {
      showError('网络异常，请检查网络后重试')
    } else {
      showError(error.response?.data?.message ?? '服务器内部错误，请稍后重试')
    }
    return Promise.reject(error)
  }
)

function handleUnauthorized(): void {
  useUserStore().clear()
  showError('登录已过期，请重新登录')
  void router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
}

export default request
```

| # | 触发 | 行为 |
|---|---|---|
| I1 | 每个请求 | 从 `useUserStore().token` 注入 `Authorization: Bearer <token>`；token 为空则不加该头（仅登录接口会走到这里） |
| I2 | `code === 200` | 只返回 `data`，业务代码拿到的是 VO / `PageVo<T>`，不再判 `code` |
| I3 | `code === 401` 或 HTTP 401 | 清 token / userInfo / permissions、提示「登录已过期，请重新登录」、跳 `/login?redirect=<当前 fullPath>` |
| I4 | `code === 403` 或 HTTP 403 | 提示「无权限执行该操作」（账号 `LOCKED` / `DISABLED` 场景由登录页兜底，见 §8.1） |
| I5 | `code === 400` / `409` | 直接提示服务端 `message`（服务端中文文案优先，前端不重写） |
| I6 | `code === 500` | 提示「服务器内部错误，请稍后重试」，不显示堆栈 |
| I7 | 无响应（网络断开 / DNS 失败） | 提示「网络异常，请检查网络后重试」 |
| I8 | 超过 15000 ms | 提示「请求超时，请稍后重试」（`ECONNABORTED` 走 I7 分支的文案独立处理） |
| I9 | 业务代码 catch | 除需自行处理表单错误的场景外，禁止静默吞掉错误；表单类接口 catch 后把 `message` 交给 `FormField` 展示，**不重复弹提示** |

### 5.3 接口与页面映射总表（路径逐字取自可用清单）

| 页面 | 依赖接口 |
|---|---|
| `/login` | `POST /api/auth/login` |
| `/docs` | `GET /api/documents`、`GET /api/categories/tree`、`GET /api/tags`、`POST /api/documents/{id}/favorite`、`POST /api/documents/{id}/derive` |
| `/docs/:id` | `GET /api/documents/{id}`、`POST /api/documents/{id}/favorite`、`POST /api/documents/{id}/derive`、`GET /api/documents/{id}/versions`、`POST /api/documents/{id}/publish` |
| `/docs/edit/:id?` | `POST /api/documents`、`GET /api/documents/{id}`、`PUT /api/documents/{id}`、`POST /api/documents/{id}/publish`、`GET /api/categories/tree`、`GET /api/tags`、`POST /api/upload/image` |
| `/my` | `GET /api/documents/mine`、`GET /api/documents/trash`、`GET /api/favorites`、`POST /api/documents/{id}/restore`、`POST /api/documents/{id}/destroy`、`POST /api/documents/{id}/publish`、`POST /api/documents/{id}/favorite`、`GET /api/documents/{id}/versions`、`GET /api/stats/overview` |
| `/review` | `GET /api/review/documents`、`GET /api/documents/{id}`、`GET /api/documents/{id}/versions`、`POST /api/documents/{id}/audit`、`POST /api/documents/{id}/reject` |
| `/admin/docs` | `GET /api/documents`、`GET /api/documents/{id}`、`POST /api/documents/{id}/archive`、`POST /api/documents/{id}/republish`、`POST /api/documents/{id}/destroy`、`GET /api/categories/tree`、`GET /api/tags`、`GET /api/stats/overview` |
| `/admin/system` | `GET/POST /api/users`、`GET/PUT /api/users/{id}`、`PUT /api/users/{id}/status`、`PUT /api/users/{id}/password`、`GET/POST /api/roles`、`GET/PUT/DELETE /api/roles/{id}`、`POST /api/roles/{id}/permissions`、`GET /api/permissions/tree`、`GET/PUT/DELETE /api/permissions/{id}`、`GET /api/depts/tree`、`POST /api/depts/{id}/roles` |
| 全局（AppLayout / 刷新恢复） | `GET /api/auth/me`、`POST /api/auth/logout` |

**写接口缺位的确定处理**（本文件按给定路径清单编写，清单未提供的写接口一律**不渲染对应 UI 入口**，此为确定决策，不是待定项）：

| 功能（PRD 编号） | 需要的写接口 | 本文件处理 |
|---|---|---|
| 权限节点新增（F1-13） | 权限集合的 `POST` | 不渲染「新增权限」按钮；权限节点由 `backend/sql/data.sql` 初始化；页面提供 `PUT /api/permissions/{id}`（编辑）与 `DELETE /api/permissions/{id}`（删除） |
| 部门新增 / 改名 / 删除（F1-15） | 部门集合的 `POST`、单节点 `PUT` / `DELETE` | 不渲染；部门树由 `backend/sql/data.sql` 初始化；页面提供 `POST /api/depts/{id}/roles`（部门绑角色） |
| 分类新增 / 改名 / 删除（F2-16） | 分类集合的 `POST`、单节点 `PUT` / `DELETE` | 不渲染；分类树由 `backend/sql/data.sql` 初始化；页面提供 `GET /api/categories/tree` 浏览与筛选 |
| 标签新增 / 改名 / 删除（F2-17） | 标签集合的 `POST`、单节点 `PUT` / `DELETE` | 不渲染；标签由文档编辑时的 `GET /api/tags` 关联与筛选使用 |
| 用户级直接授权（`sys_user_permission` 中间表） | 用户集合下的授权接口 | 不渲染用户级授权入口；本规格的授权入口只有角色级（`POST /api/roles/{id}/permissions`）与部门级（`POST /api/depts/{id}/roles`），`sys_user_permission` 由数据初始化脚本维护 |

上表 5 行中，前 4 行涉及的权限点（`sys:perm:add`、`sys:dept:add`、`doc:category:edit`、`doc:tag:edit`）已在权限树中就位，M3 后端接口补全后按 `docs/03-qa-review/tasks.md` 追加对应按钮；第 5 行（用户级直授权）在权限树中无对应权限点，界面永久不渲染。

---

## 6. 表单校验规则表

规则与文案为**前端第一道校验**；服务端同规则再校验一次（BR-04 双重校验）。文案为 `FormField` 红字与轻提示的统一文本。

| 表单 | 字段（GLOSSARY 名） | 规则 | 中文提示文案 | 依据 |
|---|---|---|---|---|
| 登录 | `username` | 必填；去首尾空格；1–64 字符 | 「请输入工号」 | F1-01 |
| 登录 | `password` | 必填；1–64 字符 | 「请输入密码」 | F1-01 |
| 新增用户 | `username` | 必填；4–32 位字母 / 数字 / 下划线；唯一 | 「工号必填，且为 4–32 位字母、数字或下划线」 | F1-05、BR-01 |
| 新增用户 | `realName` | 必填；1–32 字符 | 「请输入真实姓名（不超过32字）」 | F1-05 |
| 新增用户 | `deptId` | 必填 | 「请选择所属部门」 | BR-01 |
| 新增用户 | `roles` | 必填；至少 1 个角色码（多选，取值来自 `sys_role.code`） | 「请至少选择一个角色」 | BR-01 |
| 新增用户 / 编辑用户 | `status` | 必填；取 `ACTIVE` / `LOCKED` / `DISABLED` 之一 | 「请选择用户状态」 | 口径 §0.1 第 3 条、F1-07 |
| 新增用户 | `phone` | 选填；11 位数字且以 1 开头 | 「手机号格式不正确」 | F1-05 |
| 新增用户 | `email` | 选填；含 `@` 且含 `.`，最长 64 字符 | 「邮箱格式不正确」 | F1-05 |
| 新增用户 | `password` | 必填；≥8 位且同时含字母与数字 | 「初始密码至少8位，且需包含字母和数字」 | F1-05、BR-20 |
| 编辑用户 | `realName` / `deptId` / `roles` | 同上；`username` 只读不可改；无密码字段；`status` 单独走 `PUT /api/users/{id}/status` | 「工号不可修改」 | F1-06、F1-10 |
| 重置密码 | `password` | 必填；≥8 位且同时含字母与数字；与确认框一致 | 「新密码至少8位，且需包含字母和数字」「两次输入的密码不一致」 | F1-08、BR-20 |
| 角色新增 / 编辑 | `name` | 必填；2–32 字符 | 「请输入角色名称（2–32字）」 | F1-10 |
| 角色新增 / 编辑 | `code` | 必填；大写字母与下划线；全局唯一；编辑态只读 | 「角色编码必填，且只能使用大写字母与下划线」「角色编码创建后不可修改」 | BR-02 |
| 角色新增 / 编辑 | `description` | 选填；0–128 字符 | 「角色描述不超过128字」 | F1-10 |
| 权限编辑 | `name` | 必填；1–32 字符 | 「请输入权限名称（不超过32字）」 | F1-13 |
| 权限编辑 | `code` | 只读（不提交修改） | 「权限码不可修改」 | F1-13 |
| 权限编辑 | `type` | 必填；取 `DIR` / `MENU` / `BUTTON` 之一 | 「请选择权限类型」 | 口径 §0.1 第 4 条、GLOSSARY §4.3 |
| 权限编辑 | `parentId` | 必填；不得等于自身或自身子孙节点 | 「不能将节点移动到其子节点下」 | AC-08.3、F1-13 |
| 文档 | `title` | 必填；1–128 字符（按字符计，非字节） | 「文档标题不能为空且不超过128字」 | BR-04、AC-02.2 |
| 文档 | `summary` | 选填；0–255 字符；超出时禁止继续输入 | 「摘要不超过255字」 | BR-04 |
| 文档 | `contentMd` | 选填（保存草稿允许为空，提交发布前必填）；0–100000 字符 | 「正文不超过100000字」「提交发布前请先填写正文」 | BR-04、T2 |
| 文档 | `categoryId` | 必填（分类树最多 3 层，只能选具体节点） | 「请选择文档分类」 | BR-13、F2-01 |
| 文档 | `tagIds` | 选填；去重后最多 5 个；单个标签名 1–16 字符 | 「最多选择5个标签」 | BR-14 |
| 文档 | `priceCents` | 必填；整数；0 ≤ 值 ≤ 999999（即 0–9999.99 元）；输入以元为单位时前端 `Math.round(元 × 100)` 换算 | 「价格标记需为 0–9999.99 之间的金额」 | BR-17、R2 |
| 审核 | `changeRemark`（驳回理由） | 必填；1–255 字符；仅空格视为未填 | 「驳回理由不能为空」 | BR-12、AC-07.3 |
| 审核 | `changeRemark`（通过备注） | 选填；0–255 字符 | 「审核备注不超过255字」 | BR-12、F2-14 |
| 分类名 | `name` | 必填；1–32 字符；同级同名唯一 | 「分类名称必填且不超过32字」 | F2-16、BR-13 |
| 分类名 | 层级 | 新增节点层级 ≤ 3 | 「分类最多支持3层」 | BR-13 |
| 排序（分类 / 权限 / 部门 / 角色） | `sortOrder` | 整数；0 ≤ 值 ≤ 9999；同级升序 | 「排序值需为 0–9999 的整数」 | F1-13、F2-16 |
| 标签名 | `name` | 必填；1–16 字符（去首尾空格）；同名唯一 | 「标签名称必填且不超过16字」 | BR-14、F2-17 |
| 通用 | `pageNum` | 整数；≥1 | 「页码必须从 1 开始」 | BR-05 |
| 通用 | `pageSize` | 整数；1–100；默认 10；下拉项固定 10 / 20 / 50 / 100 | 「每页条数需在 1–100 之间」 | BR-05 |
| 通用 | `keyword` | 选填；0–64 字符；去首尾空格后为空则视为未填 | 「关键词不超过64字」 | F2-06 |
| 图片（上传前） | — | 扩展名 ∈ {jpg, jpeg, png, webp, gif}；单张 ≤ 5MB | 「仅支持 jpg / jpeg / png / webp / gif 格式」「图片不能超过5MB」 | BR-15、NFR-S6 |

### 6.1 通用表单交互规则

| # | 规则 |
|---|---|
| V1 | 提交前校验全部字段；失败时聚焦并滚动到第一个错误字段，错误文案由 `FormField` 渲染 |
| V2 | 输入中不校验；字段 `blur` 时校验该字段；修正后立即清除该字段错误 |
| V3 | 提交中按钮进入 `loading` 且禁用，禁止重复提交 |
| V4 | 服务端返回 400 时，把 `message` 展示在对应字段（字段无法定位时用轻提示），不关闭弹窗 |
| V5 | 弹窗内 `Enter` 提交、`Esc` 关闭（危险操作弹窗禁用 `Esc`） |

---

## 7. Markdown 渲染与图片上传

### 7.1 渲染管线（`src/utils/markdown.ts`）

```ts
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({
  html: false,        // 禁止原始 HTML，从源头切断脚本注入
  linkify: true,
  breaks: true,
  typographer: false
})

const SANITIZE_CONFIG = {
  ALLOWED_TAGS: [
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'br', 'hr', 'strong', 'em', 'del', 'blockquote',
    'ul', 'ol', 'li', 'code', 'pre', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'img'
  ],
  ALLOWED_ATTR: ['href', 'title', 'src', 'alt', 'class'],
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|\/|uploads\/)/i,
  FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick']
}

export function renderMarkdown(source: string): string {
  const raw = md.render(source ?? '')
  return DOMPurify.sanitize(raw, SANITIZE_CONFIG)
}
```

| # | 规则 |
|---|---|
| M1 | 渲染顺序固定：`markdown-it` → `DOMPurify.sanitize()` → `v-html`（NFR-S4）；不得跳过清洗 |
| M2 | `html: false` + `FORBID_ATTR` 含 `style` / `on*`：Markdown 源文里手写的 HTML、脚本、内联样式全部被丢弃 |
| M3 | 链接统一加 `target="_blank"` 与 `rel="noopener noreferrer"`（渲染后在 `MarkdownPreview` 的 `onMounted` 中给 `a` 标签补属性，仍不使用内联 style） |
| M4 | 正文区样式走 `.markdown-body`（定义在 `src/markdown.css` 的 `@layer components`），覆盖标题字号、列表缩进、引用条、代码块底色 `bg-gray-100`、表格边框 `border-gray-200` |
| M5 | 代码块不做语法高亮（不引入高亮库）；统一 `font-mono text-code bg-gray-100 rounded-control p-3 overflow-x-auto` |
| M6 | 图片渲染前把相对 URL 转绝对（见 §7.5），转换在 `renderMarkdown` 之后、`v-html` 之前完成 |

### 7.2 编辑器分栏与滚动同步（`MarkdownEditor.vue` + `MarkdownPreview.vue`）

| # | 规格 |
|---|---|
| E1 | 布局：左栏源文、右栏预览，`grid grid-cols-2 gap-4`；1366 宽下每栏最小 480px；页面滚动条只出现在两栏内部（各自 `overflow-y-auto`） |
| E2 | 滚动同步：按比例同步 `ratio = scrollTop / (scrollHeight - clientHeight)`；用 `isSyncing` 布尔锁防回环（同步触发的那一次 `scroll` 事件直接返回） |
| E3 | 同步频率：`requestAnimationFrame` 节流；关闭同步时（工具栏开关）互不影响 |
| E4 | 左侧 `textarea` 使用 `font-mono text-code leading-6 p-4 resize-none focus:outline-none`，与右栏同字号同行高，避免视觉跳动 |
| E5 | 输入防抖 200ms 后渲染预览（大文档降低重排压力）；保存草稿不等防抖，直接取当前 `contentMd` |
| E6 | 离开页面保护：`contentMd` 有未保存改动时，`onBeforeRouteLeave` 与 `beforeunload` 弹二次确认「有未保存的修改，确定离开吗？」 |
| E7 | 快捷键：`Ctrl/Cmd + S` 保存草稿、`Ctrl/Cmd + B` 加粗、`Ctrl/Cmd + K` 插入链接；快捷键在源文区生效 |

### 7.3 图片上传三种入口（`src/utils/useImageUpload.ts`）

```ts
export interface UploadEntry {
  blob: Blob
  name: string
}

// 入口 1：工具栏按钮 → 触发隐藏的 <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" multiple>
// 入口 2：粘贴 → textarea 的 paste 事件，从 clipboardData.items 中取图片项（items[i].type.startsWith('image/')）
// 入口 3：拖拽 → 编辑区 dragover 阻止默认行为 + drop 事件取 dataTransfer.items
```

| # | 入口 | 触发 | 光标与插入行为 |
|---|---|---|---|
| U1 | 工具栏按钮 | 点击「插入图片」→ 隐藏 `input` 的 `change` | 插入位置 = 点击按钮前的 `selectionStart`（点击时先记录，失焦不清空） |
| U2 | 粘贴 | 源文区 `paste`；剪贴板含图片时 `preventDefault()`，纯文本粘贴不受影响 | 插入位置 = 当前 `selectionStart` |
| U3 | 拖拽 | 编辑区 `dragover` + `drop`；拖入非图片时提示「仅支持 jpg / jpeg / png / webp / gif 格式」 | 插入位置 = 拖放点的最近行（用 `textarea` 的 `selectionStart`，drop 前先把光标定位到拖放处） |
| U4 | 三入口共用 | 都调用同一个 `uploadImage(entry: UploadEntry)`，不做差异化处理 | 上传成功后统一走 U6 的插入逻辑 |

### 7.4 前端拦截与后端一致性（BR-15）

| # | 校验项 | 前端 | 后端（PRD F2-18 / BR-15） | 一致性 |
|---|---|---|---|---|
| C1 | 扩展名白名单 | `jpg` / `jpeg` / `png` / `webp` / `gif` | 同左 | ✅ 逐字一致 |
| C2 | 大小上限 | 单张 ≤ 5MB（5 × 1024 × 1024 字节） | ≤ 5MB | ✅ |
| C3 | 类型判定 | 扩展名 + MIME（`type.startsWith('image/')`）双重判定 | 扩展名 + MIME + 大小（NFR-S6） | ✅ 前端为子集，后端更强 |
| C4 | 存储路径 | 不感知 | `uploads/yyyy/MM/{uuid}.{ext}` | 前端只接收返回的相对 URL |
| C5 | 失败提示 | 逐条列出被拒图片名与原因 | 400 返回中文 `message` | ✅ 文案同源 |
| C6 | 多图提交 | 允许一次多张，按顺序串行上传（避免并发打满） | 单次请求单张（`multipart`） | ✅ |

### 7.5 进度、失败重试、光标插入、URL 解析

| # | 规格 |
|---|---|
| P1 | 上传开始即在光标处插入占位符 `![上传中 0%](uploading)`；进度来自 axios `onUploadProgress` 的 `loaded / total` |
| P2 | 上传成功：把占位符整体替换为 `![图片描述](uploads/yyyy/MM/{uuid}.{ext})`，并把选区移到 `alt` 文本内方便改名 |
| P3 | 上传失败：占位符替换为 `![上传失败，点击重试](retry:{批次序号})`，点击该行触发重试；重试成功后原地替换为最终 Markdown |
| P4 | 光标插入：插入文本前记录 `const start = textarea.selectionStart`，插入后用 `setSelectionRange(start + 2, start + 2 + altLength)` 选中 `alt` 位置，并手动派发 `input` 事件让 `v-model` 同步 |
| P5 | 相对 URL 转绝对：后端只返回相对 URL（BR-15），前端统一用 `resolveAssetUrl()` 拼接，预览、详情、审核抽屉三处共用同一函数 |
| P6 | `resolveAssetUrl` 规则：以 `http://` 或 `https://` 开头 → 原样返回；以 `/` 开头 → 拼接 `VITE_API_BASE_URL` 的 origin；以 `uploads/` 开头 → `${VITE_API_BASE_URL}/${url}`；其余 → 原样返回 |
| P7 | 图片展示：`<img class="max-w-full rounded-control border border-gray-200" loading="lazy" alt="..." />`；不设置固定宽高，不写内联 style |
| P8 | 上传期间允许继续编辑；未完成占位符在保存草稿前必须全部完成或全部回滚（保存前检查占位符数量，存在未完成则提示「有图片正在上传，请稍候」） |

---

## 8. 逐页设计

通用规则（8 个页面都适用，页面内不再重复）：路由级无权限一律由守卫重定向 `/403`（§1.2）；页面内局部无权限用 `NoPermHint` 内联提示或直接不渲染按钮；所有列表页的分页条都受 BR-05 约束（`pageNum ≥ 1`、`1 ≤ pageSize ≤ 100`）。

### 8.1 页面 1：`/login` 登录

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 工号 + 密码登录 | 换取 token 与权限码集合，是平台唯一入口；没有它所有接口恒 401 |
| 登录后写 Pinia + localStorage | 刷新页面不掉线（`fetchMe` 恢复用户信息与权限，§1.2） |
| 错误提示不区分「用户不存在 / 密码错误」 | 防用户名枚举（NFR-S2）；区分了就等于把工号字典交给攻击者 |
| 账号状态提示 | `LOCKED` / `DISABLED` 时明确告知「联系管理员」，避免用户反复试密码（AC-01.3） |
| `?redirect=` 回跳 | 被守卫拦下的深链接登录后直达原目标，省一次手动导航 |

**② 组件树（`src/views/auth/LoginView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/auth/LoginView.vue` | 页面容器：居中卡片布局 + 表单状态机（初始 / 提交中 / 错误） | 公开 |
| `common/FormField.vue` | 用户名、密码两个字段的标签与错误红字 | 始终 |
| `common/AppIcon.vue` | 品牌图形与「显示 / 隐藏密码」眼睛图标 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| POST | `/api/auth/login` | 提交 `username` / `password`，返回 `token` + `userInfo` + `permissions` | 公开 |

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 页面初始态（表单页无列表数据，空态等价为「未输入」态） | 两个输入框为空；登录按钮 `disabled` 且保持 `bg-primary-300` 弱化样式 | 两个字段都非空后按钮变为可用 `bg-primary-600`；按 `Enter` 提交 |
| 加载 | 点击登录后请求未返回 | 按钮文字变「登录中…」+ 左侧旋转图标；两个输入框与按钮全部 `disabled` | 禁止重复提交；请求返回后恢复；无论成功失败都在 `finally` 中恢复按钮 |
| 错误 | 401 / 403 / 400 / 网络异常 | 卡片顶部 `danger` 提示条 + 字段红字：401「用户名或密码错误」；403（`LOCKED` / `DISABLED`）：「账号已冻结，请联系系统管理员」/「账号已停用，请联系系统管理员」；400「请输入工号与密码」；网络异常「网络异常，请检查网络后重试」 | 保留已输入的 `username`，清空 `password` 并聚焦密码框；错误条在用户再次输入时消失 |
| 无权限 | 已登录用户访问 `/login` | 不渲染页面，直接重定向：有 `doc:search` → `/docs`；否则有 `doc:mine` → `/my`；否则 → `/403` | 无提示条（避免打扰） |

---

### 8.2 页面 2：`/docs` 文档列表（检索）

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 关键词检索 | 命中 `title` 与 `summary`，不必逐个分类翻找（AC-04.1） |
| 分类树筛选 | 按单位知识结构定位，缩小范围（BR-13 三层树） |
| 标签多选筛选 | 跨分类横向聚合同类内容（BR-14） |
| 排序切换 | 默认 `updatedAt` 倒序；可切发布时间 / 阅读量倒序，找「最新」或「最热」 |
| 分页 | 避免一次返回海量数据（NFR-P4、BR-05） |
| 派生按钮 | 一键把他人文档变成自己的草稿，免去复制粘贴（US-05、AC-05.1） |
| 收藏按钮 | 建立个人快捷入口，二次访问不用再搜（BR-10） |
| 只返回有权查看的文档 | 后端按数据权限过滤（AC-04.3），前端不承担安全职责 |

**② 组件树（`src/views/document/DocumentListView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/document/DocumentListView.vue` | 页面容器：筛选条件与分页状态同步到 URL query（`usePageSync`），请求编排 | `doc:search` |
| `document/DocumentFilterBar.vue` | 关键词输入（防抖 300ms）、分类树、标签多选、排序下拉、清空筛选 | 始终 |
| `document/CategoryTreeSelect.vue` | 分类树下拉（`GET /api/categories/tree`） | 始终 |
| `document/TagSelect.vue` | 标签多选筛选 | 始终 |
| `document/DocumentTable.vue` | 结果表格：标题（链接到详情）、状态徽标、作者、分类、更新时间、阅读量 | 始终 |
| `common/StatusTag.vue` | 状态徽标（检索结果只出现 `PUBLISHED`） | 始终 |
| `common/PaginationBar.vue` | 分页 | 始终 |
| `common/PermButton.vue` | 行内「派生」按钮，`perm="doc:derive"` | 无 `doc:derive` 不渲染 |
| `common/PermButton.vue` | 行内「收藏 / 取消收藏」按钮，`perm="doc:favorite"`，`favorited` 状态由详情接口结果缓存 | 无 `doc:favorite` 不渲染 |
| `common/EmptyState.vue` / `LoadingState.vue` / `ErrorState.vue` | 四态容器 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/documents` | 分页检索（`keyword` / `categoryId` / `tagIds` / `sort` / `pageNum` / `pageSize`） | `doc:search` |
| GET | `/api/categories/tree` | 分类树下拉数据 | `doc:search` |
| GET | `/api/tags` | 标签多选数据 | `doc:search` |
| POST | `/api/documents/{id}/favorite` | 收藏 / 取消收藏（幂等，BR-10） | `doc:favorite` |
| POST | `/api/documents/{id}/derive` | 基于该文档派生新草稿，成功后跳 `/docs/edit/{新id}` | `doc:derive` |

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 无筛选且可见文档为 0 | `EmptyState`：标题「还没有可查看的文档」；副文案「等同事发布第一篇文档，或自己先写一篇」；有 `doc:create` 时主按钮「新建文档」→ `/docs/edit` | 主按钮按权限显隐；无权限时不显示按钮，只留文案 |
| 空（筛选无命中） | 有筛选条件且 `total = 0`（AC-04.2：接口返回 200 + 空列表，前端必须呈现空态而非错误态） | `EmptyState`：标题「没有匹配的文档」；副文案「试试更换关键词，或清空筛选条件」；主按钮「清空筛选」 | 点击「清空筛选」重置 `keyword` / `categoryId` / `tagIds` / `sort` 并回到第 1 页，同时清掉 URL query |
| 加载 | 首次进入或条件变化后请求未返回 | 首屏 `LoadingState preset="table"` 骨架 6 行；筛选条件变化时保留旧数据并在表格顶部显示 2px 进度条（`bg-primary-600`）。请求 200ms 内返回则不显示骨架（防闪烁） | 加载中筛选控件仍可操作，新请求会取消上一个（同一请求序号只接受最后一次响应） |
| 错误 | HTTP 500 / 网络异常 / 超时 | `ErrorState`：标题「文档列表加载失败」；副文案为服务端 `message`（无则为「请稍后重试」）；按钮「重新加载」 | 保留当前筛选条件与页码；点击重试后重新发起同一请求；提供「清空筛选」次按钮 |
| 无权限 | 路由级：不含 `doc:search` 且不含 `doc:mine` | 由守卫重定向 `/403`，本页不渲染 | 403 页显示「缺少权限：`doc:search`」 |
| 无权限 | 路由级：不含 `doc:search` 但含 `doc:mine` | 自动重定向 `/my` | 无提示条（该用户本就有「我的文档」入口） |
| 无权限 | 页面内：行内按钮权限不足 | 「派生」按钮不渲染（缺 `doc:derive`）；「收藏」按钮不渲染（缺 `doc:favorite`） | 表格列宽保持稳定：按钮列用固定宽度占位，避免因不渲染导致列跳动 |
| 无权限 | 页面内：数据权限（他人 `DRAFT` 文档不可见） | 结果集中不出现该文档（AC-04.3） | 前端不做任何过滤逻辑，完全依赖后端返回 |

---

### 8.3 页面 3：`/docs/:id` 文档详情

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| Markdown 渲染 | 让文档可读且排版稳定（`markdown-it` + `DOMPurify`，NFR-S4） |
| 阅读量展示 | 让作者知道文档被看过多少次（BR-09：仅 `PUBLISHED` 计数、30 分钟去重） |
| 收藏切换 | 把常看的文档收进个人列表 |
| 派生入口 | 站在他人成果上继续写（AC-05.1：新文档 `DRAFT`、正文预填、`derivedFromId` 记录血缘） |
| 版本历史抽屉 | 回答「这篇改过几次、谁改的、改了什么」（BR-06） |
| 驳回理由横幅 | 让属主知道为什么被驳回，形成 US-07 的反馈闭环 |
| 属主快捷操作 | 属主在详情页直接「编辑」「提交发布」「删除」，不用先回「我的文档」 |

**② 组件树（`src/views/document/DocumentDetailView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/document/DocumentDetailView.vue` | 页面容器：加载详情、计算按钮显隐条件、编排操作 | `doc:search` |
| `document/DocumentHeader.vue`（在 `components/document/`） | 标题、`StatusTag`、作者 `authorName`、`categoryName`、`updatedAt`、`viewCount`、`favoriteCount`、版本号 | 始终 |
| `document/RejectReasonAlert.vue` | `rejectReason` 非空时顶部 `warning` 横幅展示驳回理由 | 始终（字段有值才渲染） |
| `document/DocumentActionBar.vue`（在 `components/document/`） | 收藏、派生、编辑、提交发布、删除按钮组 | 逐个按权限 + 状态判断 |
| `markdown/MarkdownPreview.vue` | 正文渲染（清洗后 `v-html`） | 始终 |
| `document/VersionDrawer.vue` | 版本历史抽屉（`versionNum` 倒序 + 变更类型 + 备注 + 操作人） | 始终（数据为空时抽屉内空态） |
| `common/ConfirmDialog.vue` | 提交发布 / 删除的二次确认 | 始终 |
| `common/StatusTag.vue` / `LoadingState.vue` / `ErrorState.vue` / `NoPermHint.vue` | 状态徽标与四态容器 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/documents/{id}` | 详情（含 `contentMd` / `canEdit` / `favorited` / `rejectReason` / `derivedFromId`） | `doc:search` |
| GET | `/api/documents/{id}/versions` | 版本历史 | `doc:mine` |
| POST | `/api/documents/{id}/favorite` | 收藏 / 取消收藏 | `doc:favorite` |
| POST | `/api/documents/{id}/derive` | 派生 | `doc:derive` |
| POST | `/api/documents/{id}/publish` | 属主快捷提交发布（仅 `status = DRAFT` 时显示） | `doc:publish` |

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | `contentMd` 为空 | 预览区 `EmptyState`：标题「这篇文档还没有正文」；副文案「属主还没有填写内容」；属主可见主按钮「去编辑」 | 非属主不显示按钮 |
| 空 | 版本抽屉无记录 | 抽屉内 `EmptyState`：「暂无版本记录」 | 抽屉保持打开，可直接关闭 |
| 空 | `rejectReason` / `derivedFromId` 为空 | 对应区域整体不渲染（不留空白占位） | — |
| 加载 | 首次进入或从检索跳转 | 头部信息条骨架（1 行标题 + 1 行元信息）+ 正文骨架（8 行文本）分两块渲染 | 骨架高度贴近真实内容高度，避免加载完成时页面跳动 |
| 错误 | HTTP 403 | `ErrorState`：标题「你没有查看这篇文档的权限」；副文案「该文档可能尚未发布，或不属于你的可见范围」；按钮「返回文档列表」 | 按钮跳 `/docs`；同时提供「退出登录」次按钮 |
| 错误 | HTTP 404 | `ErrorState`：标题「文档不存在或已被删除」；按钮「返回文档列表」 | 若从检索页进入，返回时保留原筛选条件（用 `router.back()` 优先） |
| 错误 | 操作类错误：409（重复发布 / `ARCHIVED` 只读）、403（非属主操作） | 轻提示（`toast`）：409「该文档当前状态不允许该操作」；403「无权限执行该操作」 | 失败后重新拉取详情，按钮显隐按最新状态刷新 |
| 无权限 | 路由级：不含 `doc:search` | 守卫重定向 `/403` | 403 页展示「缺少权限：`doc:search`」 |
| 无权限 | 数据级：非属主查看 `DRAFT` / `TRASH` | 后端返回 403 → 页面进入 403 错误态（见上） | 前端不预判，直接按响应渲染 |
| 无权限 | 页面内：`canEdit = false` | 「编辑」按钮不渲染；`status = ARCHIVED` 时按钮区显示 `NoPermHint`「归档文档为只读」 | 提示条不阻断正文阅读 |
| 无权限 | 页面内：缺 `doc:derive` / `doc:favorite` / `doc:publish` / `doc:delete` | 对应按钮不渲染 | 按钮区按剩余按钮自适应，居中保持左对齐 |

---

### 8.4 页面 4：`/docs/edit/:id?` Markdown 编辑器

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 左右分栏实时预览 | 写完立刻看到排版，不用「保存 → 详情页 → 回来改」来回跳 |
| 元信息表单（标题 / 摘要 / 分类 / 标签 / 价格标记） | 一次提交即产出可检索、可归档、可授权的完整文档 |
| 图片上传三入口（按钮 / 粘贴 / 拖拽） | 截图直接粘贴进文档，知识沉淀不断在「先存图再上传」 |
| 保存草稿 | 长文档可分段完成（AC-02.1：`status = DRAFT`、`versionNum = 1`） |
| 提交发布 | 一步进入可检索状态（AC-03.1：`versionNum` 自增、写版本记录） |
| 派生进入编辑 | 从详情页派生后正文已预填，只改差异部分（AC-05.1） |
| 未保存离开保护 | 误点返回不会丢内容 |

**② 组件树（`src/views/document/DocumentEditView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/document/DocumentEditView.vue` | 页面容器：新建 / 编辑两种模式、表单校验、保存与发布编排、离开保护 | 新建需 `doc:create`；编辑需 `doc:edit` 且 `canEdit = true` |
| `document/DocumentMetaPanel.vue` | 标题、摘要、分类、标签、价格标记表单区 | 始终 |
| `document/CategoryTreeSelect.vue` | 分类选择（`GET /api/categories/tree`，只允许选具体节点，BR-13） | 始终 |
| `document/TagSelect.vue` | 标签多选（最多 5 个，BR-14） | 始终 |
| `markdown/EditorToolbar.vue` | 加粗 / 标题 / 列表 / 引用 / 代码块 / 链接 / 插入图片 | 「插入图片」需 `doc:upload` |
| `markdown/MarkdownEditor.vue` | 左栏源文输入 + 粘贴 / 拖拽上传入口 | 始终 |
| `markdown/MarkdownPreview.vue` | 右栏渲染（清洗后 `v-html`） | 始终 |
| `common/PermButton.vue` | 「保存草稿」（`doc:create` 或 `doc:edit`）、「提交发布」（`doc:publish`） | 按 `perm` 显隐 |
| `common/ConfirmDialog.vue` | 离开前的未保存确认、发布前确认 | 始终 |
| `common/FormField.vue` / `LoadingState.vue` / `ErrorState.vue` / `NoPermHint.vue` | 字段错误、加载与错误态、只读提示 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| POST | `/api/documents` | 新建草稿（`DocumentCreateDtoReq`，成功返回新 `id`） | `doc:create` |
| GET | `/api/documents/{id}` | 编辑模式回填（含 `contentMd` / `canEdit` / `status` / `versionNum`） | `doc:edit` |
| PUT | `/api/documents/{id}` | 保存修改（`DocumentUpdateDtoReq`，`versionNum` 自增） | `doc:edit` |
| POST | `/api/documents/{id}/publish` | 提交发布 | `doc:publish` |
| GET | `/api/categories/tree` | 分类下拉数据 | `doc:create` |
| GET | `/api/tags` | 标签下拉数据 | `doc:create` |
| POST | `/api/upload/image` | 图片上传（`multipart`，返回 `ImageVo.url` 相对 URL） | `doc:upload` |

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 新建模式（路由无 `:id`） | 页面标题「新建文档」；`title` / `summary` / `contentMd` 为空；`priceCents` 默认 0（界面显示「免费」）；右栏预览区 `EmptyState`：「左侧输入 Markdown，这里实时预览」 | 第一个输入框自动聚焦；标签下拉无数据时显示「暂无标签可选」 |
| 空 | 标签 / 分类接口返回空数组 | 下拉面板内 `EmptyState`：「暂无分类」/「暂无标签可选」 | 不阻断保存（分类为必填时给字段错误「请选择文档分类」） |
| 加载 | 编辑模式进入时回填请求未返回 | 整页骨架：元信息区 3 行 + 分栏区左右两块灰底占位；顶部显示「加载中…」细进度条 | 骨架期间禁用全部输入 |
| 加载 | 图片上传中 | 源文区插入占位符 `![上传中 0%](uploading)`，百分比随 `onUploadProgress` 更新 | 允许继续编辑其它内容；保存前若仍有未完成占位符，提示「有图片正在上传，请稍候」 |
| 错误 | 回填失败 HTTP 403 | 整页 `ErrorState`：标题「你没有编辑这篇文档的权限」；副文案「只有属主或文档管理员可以修改」；按钮「返回文档列表」 | 不渲染表单，避免用户填完才失败 |
| 错误 | 保存 / 发布返回 400 | 字段级红字（文案见 §6）+ 自动滚动并聚焦第一个错误字段；顶部轻提示「请检查表单填写」 | 保留全部已填内容；修正后错误即时消失 |
| 错误 | 保存 / 发布返回 409 | 轻提示：「归档文档为只读」（`ARCHIVED`）或「该文档当前状态不允许该操作」；同时把页面切到只读模式（所有输入 `disabled`，按钮只剩「返回」） | 只读模式下顶部显示 `NoPermHint`「归档文档为只读」 |
| 错误 | 图片上传失败（类型 / 大小 / 网络） | 占位符替换为 `![上传失败，点击重试](retry:{批次序号})`；顶部轻提示给具体原因（§7.4 C5） | 点击该占位符重试；重试成功后原地替换为最终 Markdown |
| 错误 | 无响应网络异常 | 顶部 `danger` 提示条「网络异常，保存失败，请稍后重试」+ 按钮「重试保存」 | 界面内容原样保留，支持手动重试 |
| 无权限 | 路由级：既无 `doc:create` 也无 `doc:edit` | 守卫重定向 `/403` | 403 页展示「缺少权限：`doc:create`」 |
| 无权限 | 页面内：缺 `doc:upload` | 工具栏「插入图片」按钮不渲染；粘贴 / 拖拽图片被拦截并提示「你没有上传图片的权限」 | 纯文本粘贴不受影响 |
| 无权限 | 页面内：缺 `doc:publish` | 只显示「保存草稿」，不渲染「提交发布」 | 草稿保存成功后轻提示「已保存草稿」 |
| 无权限 | 页面内：`canEdit = false`（非属主或 `ARCHIVED`） | 表单与编辑器整体 `disabled`（`bg-gray-50` + `cursor-not-allowed`），顶部 `NoPermHint`「这篇文档当前不可编辑」 | 仅保留「返回详情」按钮 |

---

### 8.5 页面 5：`/my` 我的文档

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 我的文档列表（状态筛选 + 关键词） | 只列自己的文档，草稿不缺、已发布不混（F2-02 强制 `authorId = 当前用户`） |
| 统计概览卡片 | 一屏知道「写了多少、收藏多少、平台已发布多少」（F2-20） |
| 我的收藏 tab | 常看文档的快捷入口（F2-09，仅返回可见文档） |
| 回收站 tab + 恢复 | 误删可逆（BR-07、AC-03.3 前置：`TRASH` 需先恢复才能发布） |
| 彻底删除（二次确认） | 清理真正不要的内容，且必须二次确认（BR-08） |
| 行内提交发布 | 草稿写完就地发布，不用进详情页（US-03） |
| 版本历史入口 | 需要回滚内容时能查历史快照（F2-19） |

> **口径说明（§0.1 第 8 条）**：回收站完全由文档 `status = 'TRASH'` 表达，`deleted` 字段不进入任何 VO，界面上不出现「逻辑删除」概念；「我的文档」列表的状态筛选只提供 `DRAFT` / `PUBLISHED` / `ARCHIVED` 与「全部」，`TRASH` 只出现在回收站 tab。

**② 组件树（`src/views/document/MyDocumentView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/document/MyDocumentView.vue` | 页面容器：tab 切换（`?tab=mine|favorite|trash`）、统计卡加载、各 tab 独立请求 | `doc:mine` |
| `admin/GovernanceOverview.vue`（复用统计卡片，props 控制展示项） | 统计卡片：我的文档数 / 我的收藏数 / 平台已发布数 | 始终 |
| `common/TabNav.vue` | tab 切换条 | 各 tab 按权限显隐 |
| `document/DocumentTable.vue` | 我的文档表格：状态筛选（全部 / `DRAFT` / `PUBLISHED` / `ARCHIVED`）、关键词、更新时间、操作列 | 「我的文档」tab |
| `document/DocumentTable.vue`（`mode="favorite"`） | 我的收藏表格：标题、作者、收藏时间、取消收藏按钮 | 「我的收藏」tab，需 `doc:favorite` |
| `document/DocumentTable.vue`（`mode="trash"`） | 回收站表格：标题、删除时间、恢复 / 彻底删除按钮 | 「回收站」tab，需 `doc:restore` |
| `document/VersionDrawer.vue` | 版本历史抽屉（复用） | 始终 |
| `common/PermButton.vue` | 「提交发布」（`doc:publish`）、「编辑」（`doc:edit` + `canEdit`）、「删除」（`doc:delete`）、「恢复」（`doc:restore`）、「彻底删除」（`doc:delete`） | 逐个按 `perm` 显隐 |
| `common/ConfirmDialog.vue` | 删除、彻底删除（需输入标题确认，BR-08） | 始终 |
| `common/EmptyState.vue` / `LoadingState.vue` / `ErrorState.vue` / `NoPermHint.vue` | 四态容器 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/documents/mine` | 我的文档分页（`status` / `keyword` 筛选） | `doc:mine` |
| GET | `/api/favorites` | 我的收藏分页 | `doc:favorite` |
| GET | `/api/documents/trash` | 回收站分页 | `doc:mine` |
| GET | `/api/stats/overview` | 统计卡片数据（`StatVo`） | `doc:center` |
| GET | `/api/documents/{id}/versions` | 版本历史抽屉 | `doc:mine` |
| POST | `/api/documents/{id}/publish` | 行内提交发布 | `doc:publish` |
| POST | `/api/documents/{id}/favorite` | 取消收藏（切换） | `doc:favorite` |
| POST | `/api/documents/{id}/restore` | 从回收站恢复（`TRASH → DRAFT`，BR-07） | `doc:restore` |
| POST | `/api/documents/{id}/destroy` | 彻底删除（需二次确认标记，BR-08） | `doc:delete` |

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 「我的文档」tab 无数据 | `EmptyState`：标题「你还没有创建过文档」；副文案「写第一篇文档，把经验沉淀下来」；有 `doc:create` 时主按钮「新建文档」→ `/docs/edit` | 主按钮按权限显隐 |
| 空 | 「我的文档」tab 某状态筛选无数据 | `EmptyState`：标题「没有 DRAFT 状态的文档」（按当前筛选值拼接中文状态名）；主按钮「查看全部」 | 点击后清除状态筛选并回到第 1 页 |
| 空 | 「我的收藏」tab 无数据 | `EmptyState`：标题「还没有收藏任何文档」；副文案「在检索结果或详情页点收藏，这里就会出现」；主按钮「去检索文档」→ `/docs` | 主按钮在缺 `doc:search` 时不渲染 |
| 空 | 「回收站」tab 无数据 | `EmptyState`：标题「回收站是空的」；副文案「删除的文档会先放到这里，可随时恢复」 | 无主按钮 |
| 空 | 统计卡片数值为 0 | 正常渲染数字 0（不用空态替代），卡片下方 `text-xs` 说明文字 | 数值不做千分位以外的格式化 |
| 加载 | 首次进入 tab 或切换 tab 未返回 | 统计卡 4 块灰底骨架 + 表格骨架 6 行 | tab 切换时若该 tab 已有缓存数据则直接渲染，不闪骨架；请求在后台静默刷新 |
| 错误 | 列表请求 500 / 网络异常 | `ErrorState`：标题「加载失败」；副文案服务端 `message`；按钮「重试」 | 保留当前 tab 与筛选条件；统计卡失败时只让对应卡片显示「—」与「重试」小链接，不阻断表格 |
| 错误 | 恢复失败 409 / 403 | 轻提示：409「该文档当前状态不允许恢复」；403「无权限执行该操作」 | 失败后刷新当前 tab 数据 |
| 错误 | 彻底删除失败 | 轻提示：服务端 `message`（如「仅回收站中的文档可以彻底删除」） | 弹窗保持打开，便于用户取消；成功后关闭弹窗并刷新列表 |
| 无权限 | 路由级：不含 `doc:mine` | 守卫重定向 `/403` | 403 页展示「缺少权限：`doc:mine`」 |
| 无权限 | 页面内：缺 `doc:favorite` | 「我的收藏」tab 不渲染 | 直接访问 `?tab=favorite` 时回落到「我的文档」tab，并轻提示「你没有收藏权限」 |
| 无权限 | 页面内：缺 `doc:restore` | 「回收站」tab 不渲染 | 直接访问 `?tab=trash` 时回落到「我的文档」tab，并轻提示「你没有回收站权限」 |
| 无权限 | 页面内：缺 `doc:delete` | 「删除」「彻底删除」按钮不渲染 | 回收站表格保留「恢复」按钮，操作列宽度固定 |
| 无权限 | 页面内：缺 `doc:publish` 或 `doc:edit` | 对应行内按钮不渲染 | 操作列按剩余按钮自适应 |

---

### 8.6 页面 6：`/review` 审核队列

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 待治理文档列表 | 一屏看到全部 `PUBLISHED` 文档，不用逐篇打开猜（F2-13） |
| 状态 / 关键词 / 作者筛选 | 大库中快速定位待处理内容 |
| 详情抽屉 + 正文预览 | 就地阅读，不跳页，审核动作与判断在同一处完成 |
| 版本对比 | 看到「这一版改了什么」，判断依据充分（US-07 痛点行） |
| 通过（备注） | 记录审核结论与操作人（AC-07.1） |
| 驳回（理由必填） | 形成留痕与回传属主的闭环（BR-12、AC-07.3） |
| 权限隔离 | 只有 `doc:review` 能进入，`STAFF` 调用审核接口一律 403（AC-07.2） |

**② 组件树（`src/views/review/ReviewView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/review/ReviewView.vue` | 页面容器：队列请求、抽屉开关、审核后刷新 | `doc:review` |
| `review/ReviewFilterBar.vue` | 状态、关键词、作者筛选 | 始终 |
| `review/ReviewTable.vue` | 队列表格：标题、作者、`categoryName`、状态徽标、`updatedAt`、`viewCount`、审核入口 | 始终 |
| `review/AuditDrawer.vue` | 详情抽屉：正文渲染 + 操作区（通过 / 驳回 / 版本对比入口） | 始终 |
| `review/VersionCompare.vue` | 版本并排对比（左旧右新，差异行高亮） | 始终 |
| `review/RejectDialog.vue` | 驳回弹窗：`changeRemark` 必填 1–255 字（BR-12） | 必需 `doc:reject` |
| `common/PermButton.vue` | 「通过」「驳回」按钮 | 分别需 `doc:audit` / `doc:reject` |
| `common/StatusTag.vue` / `PaginationBar.vue` / `EmptyState.vue` / `LoadingState.vue` / `ErrorState.vue` | 状态徽标与四态容器 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/review/documents` | 审核队列分页（`status` / `keyword` / `authorId` / 分页） | `doc:review` |
| GET | `/api/documents/{id}` | 抽屉内详情（含 `contentMd`） | `doc:review` |
| GET | `/api/documents/{id}/versions` | 版本列表与对比数据 | `doc:review` |
| POST | `/api/documents/{id}/audit` | 审核通过（备注写入 `changeRemark`） | `doc:audit` |
| POST | `/api/documents/{id}/reject` | 驳回（理由必填，写入 `rejectReason` 回传属主） | `doc:reject` |

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 队列无数据 | `EmptyState`：标题「当前没有待审核的文档」；副文案「所有已发布文档都已处理完毕」；无主按钮 | 保留筛选条，用户可改筛选条件再看 |
| 空 | 版本对比缺少可对比版本（仅 1 个版本） | 对比区 `EmptyState`：「只有一个版本，暂无可对比的差异」 | 抽屉内其余信息正常展示 |
| 加载 | 首次进入或切换筛选 | 表格骨架 5 行；抽屉打开时详情区骨架（标题 1 行 + 正文 6 行） | 骨架期间「通过 / 驳回」按钮禁用 |
| 错误 | 队列请求 500 / 网络异常 | `ErrorState`：标题「审核队列加载失败」；副文案服务端 `message`；按钮「重试」 | 保留筛选条件 |
| 错误 | 通过 / 驳回返回 409 | 轻提示「该文档状态已变更，请刷新列表」 | 自动重新拉取队列并关闭抽屉 |
| 错误 | 驳回未填理由（前端拦截，BR-12） | `RejectDialog` 内 `FormField` 红字「驳回理由不能为空」；确认按钮 `disabled` | 弹窗不关闭；输入后按钮变为可用 |
| 错误 | 通过 / 驳回返回 400（服务端二次校验） | 弹窗内红字展示服务端 `message` | 弹窗不关闭，内容保留 |
| 无权限 | 路由级：不含 `doc:review` | 守卫重定向 `/403` | 403 页展示「缺少权限：`doc:review`」（对应 AC-07.2：`STAFF` 无法进入审核页） |
| 无权限 | 页面内：不含 `doc:audit` | 「通过」按钮不渲染 | 操作区只剩「驳回」 |
| 无权限 | 页面内：不含 `doc:reject` | 「驳回」按钮不渲染，`RejectDialog` 不可打开 | 操作区只剩「通过」；两者都缺时显示 `NoPermHint`「你没有审核操作权限，请联系系统管理员」 |

---

### 8.7 页面 7：`/admin/docs` 文档管理（治理）

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 治理概览卡片 | 一屏掌握平台已发布量等关键数字（F2-20 聚合查询） |
| 全平台文档列表 + 状态筛选 | 管理员视角看全量，含 `DRAFT` / `ARCHIVED` / `TRASH` |
| 归档 | 内容不合适时下架但保留可检索只读形态（T5：`PUBLISHED → ARCHIVED`）；没有它只能物理删除，历史全丢 |
| 恢复上架 | 归档错了能撤回（T7：`ARCHIVED → PUBLISHED`，同时清空 `rejectReason`） |
| 彻底删除 | 真正清理垃圾数据，且必须二次确认（BR-08） |
| 分类与标签只读浏览 | 让管理员看到分类体系与标签使用热度（`useCount`），为后续治理决策提供依据 |
| 状态机约束前置提示 | 非法转移由后端 409 兜底（PRD §4.3 I5），前端按返回文案提示，不做静默容错 |

**② 组件树（`src/views/admin/DocumentAdminView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/admin/DocumentAdminView.vue` | 页面容器：tab（治理 / 分类与标签）、列表请求、操作编排 | `doc:manage` |
| `admin/GovernanceOverview.vue` | 概览卡片（`GET /api/stats/overview`） | 始终 |
| `admin/GovernanceTable.vue` | 全平台文档表格 + 状态筛选 + 操作列 | 始终 |
| `admin/CategoryTagPanel.vue` | 分类树浏览（含层级与文档数展示位）+ 标签列表（`useCount` 倒序） | 「分类与标签」tab，需 `doc:category` |
| `document/VersionDrawer.vue` | 版本历史抽屉（治理时查看变更留痕） | 始终 |
| `common/PermButton.vue` | 「归档」（`doc:archive`）、「恢复上架」（`doc:archive`）、「彻底删除」（`doc:delete`） | 逐个按 `perm` 显隐 |
| `common/ConfirmDialog.vue` | 归档确认、（彻底删除需输入标题确认，BR-08） | 始终 |
| `common/StatusTag.vue` / `EmptyState.vue` / `LoadingState.vue` / `ErrorState.vue` | 状态徽标与四态容器 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/documents` | 全平台文档分页（含状态筛选） | `doc:manage` |
| GET | `/api/documents/{id}` | 行内查看详情（抽屉） | `doc:manage` |
| GET | `/api/documents/{id}/versions` | 版本历史 | `doc:manage` |
| POST | `/api/documents/{id}/archive` | 归档（`PUBLISHED → ARCHIVED`，意见写入 `changeRemark`；T5） | `doc:archive` |
| POST | `/api/documents/{id}/republish` | 恢复上架（`ARCHIVED → PUBLISHED`；T7） | `doc:archive` |
| POST | `/api/documents/{id}/destroy` | 彻底删除（仅 `TRASH`，需二次确认标记；BR-08） | `doc:delete` |
| GET | `/api/categories/tree` | 分类树浏览 | `doc:category` |
| GET | `/api/tags` | 标签列表浏览 | `doc:category` |
| GET | `/api/stats/overview` | 概览卡片数据 | `doc:manage` |

> 分类与标签的新增 / 改名 / 删除写接口不在本规格接口清单内，对应 UI 入口不渲染（见 §5.3 写接口缺位处理表）；权限点 `doc:category:edit` / `doc:tag:edit` 已在权限树中就位。

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 全平台无文档 | `EmptyState`：标题「平台还没有任何文档」；副文案「等员工发布第一篇文档后，这里会出现治理入口」 | 无主按钮；概览卡片显示 0 |
| 空 | 分类树为空 | 面板内 `EmptyState`：标题「暂无分类」；副文案「分类数据由初始化脚本 `backend/sql/data.sql` 写入」 | 无主按钮（写接口缺位，见 §5.3） |
| 空 | 标签列表为空 | 面板内 `EmptyState`：标题「暂无标签」；副文案「标签在文档编辑时产生」 | 无主按钮 |
| 空 | 状态筛选无命中 | `EmptyState`：标题「没有匹配的文档」；主按钮「清空筛选」 | 清除状态筛选并回第 1 页 |
| 加载 | 首次进入或切换 tab / 筛选 | 概览卡骨架 4 块 + 表格骨架 6 行；分类标签面板骨架（左树 3 层缩进占位 + 右侧标签块） | 骨架期间操作按钮禁用 |
| 错误 | 列表 / 概览请求失败 | `ErrorState`：标题「数据加载失败」；副文案服务端 `message`；按钮「重试」 | 概览与表格相互独立：一个失败不影响另一个渲染 |
| 错误 | 归档 / 恢复上架返回 409 | 轻提示「状态冲突：该文档当前不是 PUBLISHED」 | 自动刷新列表；抽屉内数据同步刷新 |
| 错误 | 彻底删除返回 400 / 403 | 轻提示服务端 `message`（如「仅回收站中的文档可以彻底删除」） | 确认弹窗保持打开，用户可取消 |
| 无权限 | 路由级：不含 `doc:manage` | 守卫重定向 `/403` | 403 页展示「缺少权限：`doc:manage`」 |
| 无权限 | 页面内：不含 `doc:archive` | 「归档」「恢复上架」按钮不渲染 | 操作列只剩「彻底删除」（若也有 `doc:delete`） |
| 无权限 | 页面内：不含 `doc:delete` | 「彻底删除」按钮不渲染 | 操作列只保留归档类按钮 |
| 无权限 | 页面内：不含 `doc:category` | 「分类与标签」tab 不渲染 | 直接访问 `?tab=category` 时回落到「治理」tab，并轻提示「你没有分类与标签权限」 |

---

### 8.8 页面 8：`/admin/system` 用户 / 角色 / 权限 / 部门

**① 用途与价值**

| 功能点 | 多了什么用（没有它会怎样） |
|---|---|
| 用户列表（关键词 / 部门 / `status` 三态筛选） | 人员可查可管（F1-04，响应中永不含 `passwordHash`） |
| 新增用户（工号 / 姓名 / 部门 / 角色 / 初始密码） | 新员工入职即开通，不用改数据库（F1-05） |
| 编辑用户（姓名 / 部门 / 角色） | 组织调整跟得上；工号不可改，避免破坏审计（F1-06、BR-02 同源思想） |
| 用户状态三态 | `ACTIVE` / `LOCKED` / `DISABLED` 改完即生效（F1-07：非 `ACTIVE` 即强制下线并清 token 与权限缓存） |
| 重置密码 | 用户忘记密码时的唯一自助替代路径（F1-08） |
| 角色增删改 | 权限集合可复用、可演进；内置角色 `isBuiltin = 1` 不可删（F1-10） |
| 角色授权（三层权限树勾选） | 授权粒度到按钮级，保存后权限缓存立即失效（AC-08.1、BR-18） |
| 权限树浏览与单节点编辑 | 让「39 个权限点」看得见、排得清（F1-12） |
| 部门树 + 部门绑角色 | 部门一次配置，员工自动继承（F1-14、F1-16） |
| `sys:center` + tab 级权限隔离 | 非系统管理员连页面都进不去（AC-08.2），减少误操作面 |

**② 组件树（`src/views/admin/SystemView.vue`）**

| 组件 | 职责 | 权限 / 显示条件 |
|---|---|---|
| `views/admin/SystemView.vue` | 页面容器：四个 tab（`?tab=user|role|perm|dept`）懒加载与回落逻辑 | `sys:center` |
| `common/TabNav.vue` | tab 切换条（按权限过滤） | 各 tab 按权限显隐 |
| `admin/UserPanel.vue` | 用户列表（关键词 / 部门 / `status` 三态筛选 + 分页） | `sys:user` |
| `admin/UserEditDialog.vue` | 新增 / 编辑用户弹窗（新增含初始密码，编辑隐藏密码字段） | 新增 `sys:user:add`；编辑 `sys:user:edit` |
| `admin/ResetPasswordDialog.vue` | 重置密码弹窗 | `sys:user:reset` |
| `common/StatusTag.vue`（`mode="user-status"`） | 用户状态三态徽标（配色见 §2.3） | 始终 |
| `common/PermButton.vue` | 行内「变更状态」按钮（打开三态选择器，`sys:user:disable`） | 按 `perm` 显隐 |
| `admin/RolePanel.vue` | 角色列表（内置角色不显示删除按钮） | `sys:role` |
| `admin/RoleEditDialog.vue` | 角色新增 / 编辑弹窗（编辑态 `code` 只读） | `sys:role:add` / `sys:role:edit` |
| `admin/PermissionGrantDialog.vue` | 角色授权弹窗：三层权限树勾选（父节点半选状态、全选 / 反选、展开折叠） | `sys:role:grant` |
| `admin/PermissionPanel.vue` | 权限树浏览 + 单节点编辑 / 删除（不提供新增，见 §5.3） | 浏览 `sys:perm`；编辑 `sys:perm:edit`；删除 `sys:perm:delete` |
| `admin/DeptPanel.vue` | 部门树浏览 + 授权入口 | `sys:dept` |
| `admin/DeptRoleDialog.vue` | 部门绑定角色弹窗 | `sys:role:grant` |
| `common/ConfirmDialog.vue` / `EmptyState.vue` / `LoadingState.vue` / `ErrorState.vue` / `NoPermHint.vue` | 四态与确认容器 | 始终 |

**③ 依赖接口清单**

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/users` | 用户分页（关键词 / 部门 / `status` 三态筛选） | `sys:user` |
| POST | `/api/users` | 新增用户（`UserCreateDtoReq`，含初始密码） | `sys:user:add` |
| GET | `/api/users/{id}` | 编辑前回填 | `sys:user:edit` |
| PUT | `/api/users/{id}` | 编辑用户（`UserUpdateDtoReq`，不含密码） | `sys:user:edit` |
| PUT | `/api/users/{id}/status` | 变更用户状态（提交 `UserStatusDtoReq`；非 `ACTIVE` 即强制下线） | `sys:user:disable` |
| PUT | `/api/users/{id}/password` | 重置密码 | `sys:user:reset` |
| GET | `/api/roles` | 角色列表 | `sys:role` |
| POST | `/api/roles` | 新增角色（`RoleDtoReq`） | `sys:role:add` |
| GET | `/api/roles/{id}` | 角色详情 / 编辑回填 | `sys:role:edit` |
| PUT | `/api/roles/{id}` | 编辑角色（`code` 不可改，BR-02） | `sys:role:edit` |
| DELETE | `/api/roles/{id}` | 删除角色（被引用时禁止，PRD F1-10） | `sys:role:delete` |
| POST | `/api/roles/{id}/permissions` | 角色授权（覆盖式保存 `permIds`，保存后清空该角色下用户权限缓存） | `sys:role:grant` |
| GET | `/api/permissions/tree` | 权限树（三层，F1-12） | `sys:perm` |
| GET | `/api/permissions/{id}` | 权限节点回填 | `sys:perm` |
| PUT | `/api/permissions/{id}` | 编辑权限节点（`code` 只读） | `sys:perm:edit` |
| DELETE | `/api/permissions/{id}` | 删除权限节点（有子节点或被引用时禁止，BR-03） | `sys:perm:delete` |
| GET | `/api/depts/tree` | 部门树（F1-14） | `sys:dept` |
| POST | `/api/depts/{id}/roles` | 部门绑定角色（覆盖式保存 `roleIds`） | `sys:role:grant` |

> 权限节点新增、部门新增 / 改名 / 删除的写接口不在本规格接口清单内，对应 UI 入口不渲染（见 §5.3）。

**④ 四态设计**

| 状态 | 触发条件 | 呈现（组件 + 文案） | 交互 |
|---|---|---|---|
| 空 | 用户列表无数据 | `EmptyState`：标题「没有匹配的用户」；主按钮「清空筛选」 | 无筛选却为空时标题改「还没有用户」+ 副文案「用户数据由初始化脚本写入」（无新增入口则不显示按钮时仍显示有 `sys:user:add` 时的「新增用户」） |
| 空 | 角色列表无数据 | `EmptyState`：标题「暂无自定义角色」；副文案「平台内置 STAFF / DOC_ADMIN / SYS_ADMIN 三个角色」；有 `sys:role:add` 时主按钮「新增角色」 | 主按钮按权限显隐 |
| 空 | 权限树为空（异常情形） | `EmptyState`：标题「权限数据未初始化」；副文案「请检查 `backend/sql/data.sql` 中的 39 个权限点」 | 无主按钮；授权弹窗入口置灰 |
| 空 | 部门树为空 | `EmptyState`：标题「暂无部门」；副文案「部门数据由初始化脚本写入」 | 无主按钮 |
| 加载 | tab 首次进入或保存中 | 首次进入才发请求：列表骨架 5 行 / 树骨架 3 层缩进占位；保存授权时树节点全部 `disabled` + 按钮文字「保存中…」 | tab 切换有缓存则不重新请求；保存期间禁止重复提交 |
| 错误 | 列表 / 树请求 500 或网络异常 | `ErrorState`：标题「加载失败」；副文案服务端 `message`；按钮「重试」 | 保留筛选条件；重试只重发当前 tab 的请求 |
| 错误 | 新增用户返回 400（工号重复） | `UserEditDialog` 内 `username` 字段红字「工号已存在」 | 弹窗不关闭，其它输入保留 |
| 错误 | 密码强度不足返回 400 | 字段红字「初始密码至少8位，且需包含字母和数字」（重置密码弹窗同文案） | 弹窗不关闭 |
| 错误 | 权限节点移动成环返回 400 | 字段红字「不能将节点移动到其子节点下」 | 树组件回滚到移动前状态（本地状态不提交） |
| 错误 | 删除角色返回 409 | 轻提示「该角色已被用户使用，无法删除」 | 列表保持，按钮可再次点击 |
| 错误 | 删除权限节点返回 409 | 轻提示「该权限节点存在子节点或已被角色引用，无法删除」 | 树保持展开状态 |
| 无权限 | 路由级：不含 `sys:center` | 守卫重定向 `/403` | 403 页展示「缺少权限：`sys:center`」（对应 AC-08.2） |
| 无权限 | tab 级：缺 `sys:user` / `sys:role` / `sys:perm` / `sys:dept` | 对应 tab 不渲染；直接访问 `?tab=x` 回落到第一个有权限的 tab | 回落时轻提示「你没有该模块的权限」 |
| 无权限 | tab 级：四个权限全缺 | 整页显示 `NoPermHint`：「你没有任何系统管理权限，请联系系统管理员」 | 不发起任何请求 |
| 无权限 | 按钮级：`sys:user:add/edit/disable/reset`、`sys:role:add/edit/delete/grant`、`sys:perm:edit/delete`、`sys:dept:*` 缺失 | 对应按钮不渲染（`PermButton`） | 表格操作列宽度固定，避免列跳动 |
| 无权限 | 授权弹窗内：缺 `sys:role:grant` | 「角色授权」「部门授权」入口不渲染 | 权限树仍可只读浏览（需 `sys:perm`） |

---

## 9. 交付自检

| 自查项 | 结论 | 证据 |
|---|---|---|
| 路由覆盖 | ✅ | §1 表格 8 行，与 `MASTER-PLAN.md` §6.1 逐条对应，一条不多一条不少 |
| 页面四态 | ✅ | §8.1~§8.8 每页均含「空 / 加载 / 错误 / 无权限」四类呈现（文案 + 组件 + 交互） |
| 接口路径合规 | ✅ | 所有路径取自本任务给定清单，无自创路径；清单缺位的写接口按 §5.3 表格确定处理（不渲染入口） |
| 命名合规 | ✅ | 按 §0.1 口径：审计字段 `createdAt` / `createdBy` / `updatedAt` / `updatedBy`，用户状态 `status: UserStatus`，名称 / 编码 / 类型 / 排序统一 `name` / `code` / `type` / `sortOrder`；领域名词禁用别名 0 命中；`deleted` 不出现在任何 VO |
| 类型约定 | ✅ | 主键全为 `string`（后端 64 位自增，序列化为字符串）；`PageVo<T>` / `ResponseResult<T>` 与 §5.1 一致 |
| 样式约束 | ✅ | 只用 Tailwind 令牌（§2）；无内联 style；无任意值写法 |
| 业务规则落位 | ✅ | BR-04/05/07/08/10/11/12/13/14/15/16/17 在前端有对应校验或交互（§6、§7.4、§8） |
| BDD 可验收 | ✅ | US-01→§8.1、US-02/03/06→§8.4、US-04→§8.2、US-05→§8.2/§8.3、US-07→§8.6、US-08→§8.8 |

**待回填项（M1 收口时处理）**：`GLOSSARY.md` §3 需按 §0.1 新口径重写，并回填 `AuditVo`、`UserStatus`、`PermType`、`UserStatusDtoReq`、`StatVo`、`DocumentVersionVo`、`ImageVo`、`PermissionVo`、`DeptVo`、`RoleVo`、`UserVo` 的字段字典（本文件 §5.1 为首次登记处）。以下四项已按裁决确认，**不再作为待对齐项**：① 联表展示字段 `deptName` / `categoryName` / `authorName` / `operatorName` 保留；② 权限 / 部门 / 分类没有启停列，相关启停字段与开关已全部清除，节点失效只由软删除（前端「删除」）表达；③ 角色 `isBuiltin: number` 保留；④ 状态变更接口为 `PUT /api/users/{id}/status` + `UserStatusDtoReq{status}`。

**冻结签署**：本文件自 2026-09-21 起冻结；页面、路由、令牌的变更须在 `docs/03-qa-review/` 留痕后再改代码。
