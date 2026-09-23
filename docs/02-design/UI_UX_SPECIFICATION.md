# CampusSwap 文档管理平台 · 前端 UI/UX 设计规格说明书

| 项 | 值 |
|---|---|
| 文件 | `docs/02-design/UI_UX_SPECIFICATION.md` |
| 版本 | **v2.2**（v8 视觉定稿：顶栏弹层、行级悬停、A 版式与时光塔首页、主题三字名；v2.1 的 §2.5 与主题体系在此基础上细化） |
| 日期 | 2026-09-23 |
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

**前后端一致性提示**：`deleted` 只用于后端查询条件（`deleted = 0`），前端既不展示也不提交；`sys_user_permission`（用户级直授权中间表）在本规格接口清单内没有写接口，界面对应入口不渲染（处置决定见 §10）。

## 0.2 变更记录（v1 → v2，2026-09-23 用户反馈驱动）

| # | 变更 | 原因 | 落位 |
|---|---|---|---|
| 1 | **信息架构重做**：删掉「系统管理」聚合板块，用户管理 / 角色与权限 / 组织机构拆成三个独立菜单 | 把用户/角色/权限/部门堆一页，不符合真实站点的命名与职责划分 | §1.1 #10~#12、§4.4 |
| 2 | **新增「我的资料」**（所有身份可见） | v1 只有管理员能找到用户/角色/权限/部门；普通教职工看不到自己的单位、角色与权限来源 | §1.1 #13、§8.13 |
| 3 | **新增「工作台」首页** | v1 没有首页，登录后直接落在检索页；平台需要一屏看清"我的草稿 / 待我审核 / 常用分类" | §1.1 #2、§8.2 |
| 4 | **主题体系 6 套 + 可视化切换**：默认师大蓝（校徽采样藏青 #303064 + 亮蓝主色），新增银杏暖、青瓷；删除松烟黛、宣纸暖 | v1 只有暗色调、无暖色；页面纯白底刺眼；主题选择器是文字下拉且夹在工具栏中间 | §2、§2.4 |
| 5 | **视觉资源规范化**：内联真实校徽 / 校训题字 / 校园风景，弃用「师」字假 Logo | v1 用文字充当品牌标识 | §3 |
| 6 | **分类与标签恢复写入口** | 后端 6 个写接口与权限点早已就位；校内分类体系会随院系与业务条线调整 | §8.9、§10.3 |
| 7 | **检索支持全文**（正文可搜） | 现状只搜标题与摘要，实测漏文档（见 §10.4 对照实验） | §8.3、§10.4 |
| 8 | **两处后端缺口定案**：治理页全状态列表接口、自助改密接口 | v1 把它们留作"待拍板"，本次直接给出契约 | §10.1、§10.2 |
| — | 保留不变 | §0 阅读约定与口径基线、§5.1 类型定义、§5.2 请求拦截器、§6 表单规则、§7 Markdown 与图片上传 | 原位 |

## 0.3 变更记录（v2.0 → v2.1，2026-09-23 第四轮反馈 · 全部来自截图复核）

| # | 变更 | 原因（用户原话/我的复核） | 落位 |
|---|---|---|---|
| 1 | 预览条（预览稿专用条）改成**跟随主题的浅色常驻条**，并把那个白底白字的胶囊换成可读的 chips | 原先是黑条 + 白字下拉；胶囊根因是 `.pvbar .tag` 只声明文字色没声明背景，被通用 `.tag` 的浅底盖住 → 白字白底看不见 | 预览稿 `UI-PREVIEW.html`（预览条不是产品 UI，故不进本规格的实现清单） |
| 2 | **主题选择器位置定案**：正式前端放**产品顶栏右上角**（用户菜单左侧）；预览稿为方便随时换肤，把它放在**常驻预览条最右边**，并取消登录页右下角的浮动按钮 | 用户："放在黑色顶栏的下面、具体页面的右上角，导致在首页选不了主题"；同一控件不该出现两处 | §2.4 |
| 3 | **工作台横幅给三版式**（A 左文右图 / B 通栏大图 + 左侧文字浮层 / C 细照片带 + 浅色信息卡），M5 默认实现 **B**，切换器本身只属于预览稿 | 用户："你可以再改几版我看看什么样首页好看" | §8.2 |
| 4 | **可读性**：卡片描边 1px → **2px 且用主题色**，悬停整块上浮 2px + 阴影加深 + 描边转主题色；按钮/链接悬停改主题色 | 用户："边框虽然是主题色，但太细了还是看得眼花；光标停留在哪块就放大，悬停在按钮上会变色" | §2.5（新增，全局视觉反馈规范） |
| 5 | **退出登录常驻侧栏底部**（回登录页），登录按钮点完真的进工作台 | 用户："登录后再也找不见退登键能让我回首页了" | §8.1、§4.4 |
| 6 | **换图 + 重裁**：师大蓝→时光塔高清版、青瓷绿→华砚湖畔、墨玉青→天下石牌坊；横幅输出宽 1000~1200px（原 760）；登录页背景按**显示框 1:1** 重裁（原先 0.9 比例被 cover 放大 1.15 倍 = 用户说的"蒙"），并给出两版候选 | 用户："原图挺好看的，裁剪质地后感觉又蒙又糊"；青瓷的花图看不出师大；墨玉青与师大蓝都是时光塔；师大蓝那张石碑"越看越丑"→ 改回时光塔高清版 | §3 |
| 7 | **顶栏两个按钮必须有弹出**：通知铃 → 未读通知面板（可全部已读）；身份框 → 身份介绍 + 我的资料 + 切换账号 + 退出登录 | 用户："带红点的通知 logo 和身份框按钮无任何弹出" | §8.15（新增） |
| 8 | **悬停反馈下沉到行**：整块上浮只留给没有行的统计卡；表格行/列表行悬停 = 本行高亮 + 左侧主题色竖条；板块描边与行分隔线 1px → **2px `--border-strong`**；列表行补左右 16px 内边距 | 用户："悬停至待我审核、检索结果这类多行板块时，放大的是整个板块而非具体行"；"字都贴到最左边边框了"；"边界线以及多行的分界线还是不显眼" | §2.5 V1/V2 修订 |
| 9 | **版式与主题名定稿**：工作台横幅固定 A 左文右图（B/C 移除）；登录页背景固定时光塔；主题名统一三字（青瓷→**青瓷绿**、墨夜→**墨夜黑**） | 用户："工作台页面固定成左文右图"；"登录页固定为时光塔"；"改名以求都是三字主题" | §2.1、§3、§8.2 |


---

## 1. 路由总表（v2：13 条功能路由 + 2 条异常路由）

> v1 的 8 条路由全部作废重排。**分级导航**是本次重做的核心：不再有「系统管理」这类把用户/角色/权限/部门堆一页的聚合板块，管理类功能按业务语义拆成三个独立菜单；同时**任何身份都能看到「我的资料」**，不再出现"普通教职工找不到自己在哪个单位、有什么角色与权限"的情况。
> 视觉与文案以 `docs/02-design/UI-PREVIEW.html`（**v6 预览稿**，配套自检 `ui-preview.smoke.mjs` **200** 项）为准，本表定的是路由、权限与行为。

### 1.1 功能路由

| # | 路由 | 路由名 | 页面 | 视图文件 | 权限点（守卫） | 用途 |
|---|---|---|---|---|---|---|
| 1 | `/login` | `login` | 登录 | `views/auth/LoginView.vue` | 公开 | 统一身份认证入口；换 token 与权限码 |
| 2 | `/workbench` | `workbench` | 工作台 | `views/workbench/WorkbenchView.vue` | 登录即可（统计需 `doc:center`） | 首页：问候 + 4 张统计卡 + 我的草稿 + 待我审核 + 常用分类 + 通知 |
| 3 | `/docs` | `docs` | 文档检索 | `views/document/DocumentListView.vue` | `doc:search` | 关键词/**全文**检索 + 分类 + 标签 + 单位 + 时间 + 排序 |
| 4 | `/docs/:id` | `docs-detail` | 文档详情 | `views/document/DocumentDetailView.vue` | `doc:search`（属主可看自己的非发布稿） | 正文渲染 + 收藏/派生/编辑/发布 + 版本历史 + 文档信息侧栏 |
| 5 | `/docs/edit/:id?` | `docs-edit` | 新建 / 编辑 | `views/document/DocumentEditView.vue` | 新建 `doc:create`；编辑 `doc:edit` 且 `canEdit=true` | 左写作右预览 + 发布设置侧栏 + 图片三入口 |
| 6 | `/my` | `my` | 我的文档 | `views/document/MyDocumentView.vue` | `doc:mine` | 六个 tab：全部 / 草稿 / 已发布 / 已归档 / 收藏（`doc:favorite`）/ 回收站（`doc:restore`） |
| 7 | `/review` | `review` | 待我审核 | `views/review/ReviewView.vue` | `doc:review` | 待审列表 + 就地详情抽屉 + 版本对比 + 通过/驳回（`doc:audit` / `doc:reject`） |
| 8 | `/governance` | `governance` | 内容治理 | `views/admin/GovernanceView.vue` | `doc:manage` | 全平台文档归档 / 恢复上架 / 彻底删除（`doc:archive` / `doc:delete`）；权限点 `doc:offline` 为预留位，无接口也无入口（见 §10.6） |
| 9 | `/taxonomy` | `taxonomy` | 分类与标签 | `views/admin/TaxonomyView.vue` | `doc:category` | 分类树与标签的**浏览 + 维护**（写操作分别需 `doc:category:edit` / `doc:tag:edit`） |
| 10 | `/admin/users` | `admin-users` | 用户管理 | `views/admin/UserAdminView.vue` | `sys:user` | 用户查询/开通/编辑/状态/重置密码（`sys:user:*`） |
| 11 | `/admin/roles` | `admin-roles` | 角色与权限 | `views/admin/RoleAdminView.vue` | `sys:role` | 角色增删改 + 三层权限树授权（`sys:role:grant`）；「权限点清单」tab 需 `sys:perm` |
| 12 | `/admin/org` | `admin-org` | 组织机构 | `views/admin/OrgAdminView.vue` | `sys:dept` | 机构树（党政管理机构 / 教学单位 / 直属单位 / 附属单位）+ 单位绑角色（`sys:role:grant`） |
| 13 | `/me` | `me` | 我的资料 | `views/me/ProfileView.vue` | 登录即可 | 我的账号 / 单位 / **角色的两个来源** / **39 个权限点的自有情况** / 账号安全 |

### 1.2 异常与默认路由

| 路由 | 路由名 | 视图 | 用途 |
|---|---|---|---|
| `/403` | `forbidden` | `views/error/ForbiddenView.vue` | 已登录但权限不足：写明缺哪个权限点，给「返回工作台 / 去检索 / 看我的权限」三个出口 |
| `/404` | `not-found` | `views/error/NotFoundView.vue` | 路由不存在 |
| `/` | — | — | 按权限重定向：有 `doc:search` → `/docs`；否则有 `doc:mine` → `/my`；否则 → `/403` |

### 1.3 守卫判定表

| 情形 | 判定条件 | 动作 |
|---|---|---|
| 公开路由 | `meta.public === true`（仅 `/login`） | 放行；已登录则跳 `/workbench` |
| 未登录 | `token` 为空 | 跳 `/login?redirect=<encodeURIComponent(to.fullPath)>` |
| 有 token 无用户信息 | 刷新页面（`userInfo === null`） | 先 `await fetchMe()` 再判定；失败（401）→ 清 token 跳 `/login` |
| 无该路由权限 | `meta.perm` 存在且 `hasPerm` 为假、`meta.permAny` 全不命中 | 跳 `/403?perm=<meta.perm>`，403 页写明缺失权限点 |
| 路由不存在 | 未匹配 | 跳 `/404` |

---

## 2. 主题与设计令牌（v2：6 套主题 · 可视化切换）

### 2.1 主题清单

| # | id | 名称 | 侧栏 | 页面底色 | 主色 | 点缀 | 适用场景 |
|---|---|---|---|---|---|---|---|
| 1 | `hebtu` | **师大蓝（默认）** | 浅色 `#FAFCFF` | `#EDF3FB` 蓝调 | `#1E56D6` 亮蓝 | `#C79A2E` | 日常办公；品牌藏青 `#303064` 取校徽采样值 |
| 2 | `gingko` | 银杏暖 | 浅暖 `#FFFBF3` | `#FAF3E7` 米白 | `#C2740E` 琥珀 | `#E0A32E` | 暖色亮调；取校园银杏之意 |
| 3 | `celadon` | 青瓷绿 | 浅青 `#F8FCFB` | `#EDF7F4` 青白 | `#0E8074` 青绿 | `#B98A2E` | 冷调清爽；科研与制度类长文 |
| 4 | `ink` | 墨玉青 | 深墨 `#11201C` | `#EEF4F2` | `#1F6F63` 青玉 | `#B58A2B` | 沉稳；深色侧栏 |
| 5 | `jiang` | 师大绛 | 深绛 `#2A1215` | `#FAF6F3` | `#9E2B25` 绛红 | `#A9772A` | 庄重；党政公文与通知 |
| 6 | `night` | 墨夜黑 | 深色 `#0A1013` | `#0E1417` | `#4FA8A0` | `#D3AE6A` | 深色主题；夜间值班与投影 |

**v1 → v2 的主题变更**：删除「松烟黛」（偏暗的靛蓝）与「宣纸暖」（米褐底发糊，用户反馈"像糊了泥巴"）；新增「师大蓝（默认）」「银杏暖」「青瓷绿」三套亮调；**默认主题从墨玉青改为师大蓝**（学校本身以亮蓝白为主形象）。

### 2.2 硬约束（四条，不可协商）

| # | 约束 |
|---|---|
| T1 | **页面底色不得为纯白**：每套主题的 `--bg` 必须是带色调的浅色（或深色主题的深色），卡片才用 `--surface`；避免大面积纯白刺眼 |
| T2 | 正文与背景对比度 ≥ 4.5:1；按钮文字与按钮底色 ≥ 4.5:1；深色主题禁用"亮底 + 白字"组合 |
| T3 | 语义色四组（`--success` / `--warning` / `--danger` / `--info`）**每套主题单独定义**，不允许只改主色就复用别套的语义色 |
| T4 | 模板中禁止内联色值（红线 R6）：只用令牌类名与 CSS 变量；动态色值（主题缩略图）走 `data-bg` 属性 + JS 注入 |

### 2.3 令牌清单（24 个变量，六套主题逐一定义）

| 分组 | 变量 |
|---|---|
| 底色与层级 | `--bg` `--surface` `--surface-2` `--surface-3` |
| 描边 | `--border` `--border-strong` |
| 文字 | `--text` `--text-2` `--text-3` |
| 主色 | `--primary` `--primary-hover` `--primary-soft` `--primary-soft-text` `--on-primary` |
| 点缀与品牌 | `--accent` `--accent-soft` `--brand-ink` |
| 侧栏 | `--sidebar-bg` `--sidebar-text` `--sidebar-muted` `--sidebar-title` `--sidebar-hover` `--sidebar-active` `--sidebar-active-text` `--sidebar-border` `--sidebar-chip` |
| 状态 | `--success(-soft/-text)` `--warning(-soft/-text)` `--danger(-soft/-text)` `--info(-soft/-text)` |
| 阴影与焦点 | `--shadow-sm` `--shadow` `--shadow-lg` `--ring` |

> 实现方式：`html[data-theme="<id>"]` 下定义上述变量；切换主题只改这一个属性。深色侧栏的三套主题可只补 `--sidebar-*` 差异变量（CSS 自定义属性按属性级联，不会互相覆盖）。

### 2.4 主题选择器（规格）

| 项 | 规格 |
|---|---|
| 位置 | **正式前端：产品顶栏右上角**（通知铃左侧、用户菜单右侧一组）。预览稿把同一个控件放在**常驻预览条的最右边**，以便在登录页与任何页面都能随时换肤；登录页右下角的浮动按钮**已取消**（同一控件不出现两处） |
| 形态 | **不是文字下拉**：点开是 6 张「迷你界面」缩略图色卡（侧栏条 + 三行内容块 + 三色点 + 名称 + 风格标签），当前主题带 ✓ 与主色描边 |
| 交互 | 点击缩略图立即生效（不刷新、不丢当前页面）；点击面板外部关闭；ESC 关闭 |
| 持久化 | `localStorage['campusswap.theme']`，默认 `hebtu`；首次进入按持久化值渲染，避免闪白 |
| 可访问性 | 面板为 `role="dialog"`，缩略图 `aria-pressed` 标记当前项；键盘可 Tab 遍历、Enter 选中 |

### 2.5 视觉反馈规范（v2.1 新增：可读性）

| # | 规则 | 为什么 |
|---|---|---|
| V1 | **卡片描边 2px 且用主题色**（`--border-strong`，悬停转 `color-mix(--accent 46%, --border-strong)`） | 用户实测 1px 描边"太细了还是看得眼花"，板块之间的边界必须一眼分得清 |
| V2 | **悬停反馈要落在"用户真正指向的东西"上**：统计卡这类**没有行的整块**才上浮（`translateY(-2px)` + `--shadow-lg`）；**含表格/列表的板块不上浮**，改为表格行 / 列表行悬停时「本行底色 `--primary-soft` + 左侧 3px 主题色竖条」，行分隔线用 **2px `--border-strong`** | 用户："悬停至待我审核、检索结果这类多行板块时，鼠标悬停放大的是整个板块而非具体行，这很没有"；"多行的分界线还是不显眼" |
| V3 | **按钮/链接悬停变色**：`.btn:hover` → `--primary-soft` 底 + 主题色文字与描边；`.link:hover` → 主题色 + 下划线 | 用户："悬停在按钮上会变色"；让"可点"这件事无需思考 |
| V4 | 过渡统一 `--t-fast`（0.15s ease），禁止更慢的动画 | 悬停反馈要跟手，慢动画会显得卡 |
| V5 | **列表/表格内容必须离边框 ≥14px**（`.list-item` 左右 16px、`.tbl td` 左右 14px） | 用户："待我审核板块、待审列表里字都贴到最左边边框了" |
| V6 | 顶栏常驻控件的"点了要动"：通知铃与身份框必须有面板，点击别处或 ESC 关闭 | 用户："带红点的通知 logo 和身份框按钮无任何弹出" |
| V7 | **禁止给 `<td>` / `<th>` 设 `display:flex`**（含 `.acts` 这类单元格级工具类）：单元格会退出表格布局、不再撑满整行，行底边线在该列断开并与相邻列错行。单元格内要横排请用「保持 table-cell + 相邻兄弟 margin」，或在内层加一个 `div.row` | v8.1 实测：`.tbl .acts{display:flex}` 让「操作」列比「文档」列矮，2px 分隔线上线后一眼看出"选项和文档错行" |

> 实现落点：`src/main.css` 的 `@layer components`（`.card` / `.btn` / `.link`）；`UI-PREVIEW.html` 已按此实现，M5 照抄令牌与规则。

---

## 3. 视觉资源（v2 新增）

| 资源 | 文件 | 来源 | 规格 | 用途 |
|---|---|---|---|---|
| 校徽 | `emblem-128.png` | 桌面「河师相关UI/学校LOGO.jpg」（官网学校标识页同款） | 128×128 PNG | 侧栏品牌位、工作台横幅右下角、登录页、设计系统页 |
| 站点图标 | `emblem-64.png` | 同上 | 64×64 PNG | `<link rel="icon">` |
| 校训题字 | `motto-320.png` | 桌面「河师相关UI/Title.png」 | 320×320 PNG（透明） | 登录页左栏「怀天下 求真知」题字 |
| 登录页背景 | `login-900.jpg` | 桌面「河师相关UI/校园风光/多花.jpg」（2000×1333） | 竖裁 900×1000 JPEG q82 | 登录页左栏铺底（缩放比 < 1，永不放大） |
| 画廊小图 | `gal-flower.jpg` | 同上目录「花.jpg」 | 520×347 JPEG | 设计系统「视觉资源」画廊 |
| 主题横幅 ×6 | `hero-*.jpg` | 同上目录 6 张（见下表，v6 映射） | 各 **1000~1200 × 380~456** JPEG q88（≈2.63:1，与显示槽位同比例） | 工作台横幅照片，**一套主题一张，全站不复用** |
| 品牌色 | `#303064` | **实测采样自校徽图像**（徽记与题字同为该藏青） | — | 品牌色，用于 `--brand-ink`、横幅渐变基调 |

**主题 ↔ 照片对应（v7 定稿，2026-09-23 用户两轮反馈后）**：

| 主题 | 照片 | 源图（分辨率） | 裁剪 → 输出 | 说明 |
|---|---|---|---|---|
| `hebtu` 师大蓝 | **时光塔高清版** | 时光塔高清版.jpg（5471×2914） | 4200×1597 → **1200×456** | 时光塔 + 广场，缩放 0.29 极清晰（用户看过校训石碑后改用它） |
| `gingko` 银杏暖 | 校内行道 | 校内行道.jpg（1000×666） | 1000×380 → 1000×380 | 林荫道 + 暖色渐变 |
| `celadon` 青瓷 | **华砚湖畔** | 华砚湖畔.jpg（2000×1333） | 1520×578 → **1000×380** | 湖面 + 垂柳 + 连翘（用户从时光塔改回此图） |
| `ink` 墨玉青 | **天下石牌坊** | 天下.jpg（6000×4000） | 3600×1368 → **1200×456** | 石牌坊 + 深绿树；替换原「时光塔下」（与师大蓝重复） |
| `jiang` 师大绛 | 东门黎明 | 东门黎明.jpg（1000×465） | 1000×380 → 1000×380 | 黎明金红，呼应绛红 |
| `night` 墨夜 | 教学楼夜景 | 教学楼夜景.jpg（1000×563） | 1000×380 → 1000×380 | 夜景，呼应深色 |

**登录页背景（v8 定稿）**：**固定为时光塔**（时光塔高清版.jpg，5471×2914）—— 取景框必须**装得下整座塔**（塔尖到广场），按登录左栏显示框的比例（≈1:1）裁成 **1200×1200**，缩放比 0.41，永不放大。v7 曾给两版候选（多花 / 时光塔），用户选定时光塔。

> **裁剪纪律（v4 教训 → v6 加固 → v7 补上"按显示框比例"这一条）**
> 1. **输出宽度 ≤ 裁剪宽度**（生成脚本直接抛错拒绝放大）：源图不足 1200px 的主题就输出 1000px。
> 2. **按显示框的实际宽高比裁剪**，不要只按"看起来像横幅"的比例：v6 的登录页背景裁成 0.9（900×1000），
>    而登录左栏实际是 ≈1:1（约 1050×1065），`background-size:cover` 把它**放大了 1.15 倍 → 糊**；
>    这就是用户说"登录页背景蒙"的真正原因。v7 改成 1:1 的 1200×1200（缩放比 0.55~0.90）后清晰。
> 3. 显示槽位实测：A 版式右侧卡片约 506px、B 版式右侧照片板约 663px、C 版式整条 1150×138、登录左栏约 1050×1065。
> 4. 所有照片在文档里只内联一次（自检断言：jpeg data URI 恰好 9 处 = 6 张主题横幅 + 登录页 2 版候选 + 画廊小图）。


**内联方式**：预览稿把上述资源全部转成 base64 data URI 内联，保证"单文件、离线、零请求"；正式工程（M5）改为 `frontend/src/assets/` 下的静态文件 + Vite 资源处理，不内联。
**再生成方式**：`docs/02-design/ui-preview.assets.ps1`（缩放 / 压缩 / 取色 / 生成 base64；脚本含中文路径，须以 `[IO.File]::ReadAllText(..., UTF8)` 读取后执行，勿用 `-File`）。
**使用边界**：校徽、校名、校训题字属学校标识，仅用于本校课程作业演示；正式上线前须向学校宣传部门确认标识使用规范（已在预览稿设计系统页注明）。

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

### 4.4 侧栏导航按权限码过滤（v2：五组结构）

导航固定为五组，组内条目按权限码过滤，**该组一条都不剩时整组标题一起隐藏**：

| 组 | 条目（权限码） |
|---|---|
| （无标题） | 工作台 |
| **文档** | 文档检索（`doc:search`）· 我的文档（`doc:mine`）· 新建文档（`doc:create`） |
| **审核与治理** | 待我审核（`doc:review`，带待办数角标）· 内容治理（`doc:manage`）· 分类与标签（`doc:category`） |
| **组织与权限** | 用户管理（`sys:user`）· 角色与权限（`sys:role`）· 组织机构（`sys:dept`） |
| **个人** | 我的资料（无权限要求，所有登录用户可见） |

- 三个身份的实际效果（机检断言覆盖，见 `ui-preview.smoke.mjs`）：教职工 5 条 / 文档管理员 8 条 / 系统管理员 11 条。
- 侧栏底部固定显示当前身份：姓名 · 单位 · 有效权限数 / 39（数据来自 `GET /api/auth/me` 的 `permissions.length`）。
- v1 的「系统管理」聚合板块（把用户/角色/权限/部门堆一页）已作废，禁止在任何入口复现该命名。

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

### 5.3 接口与页面映射总表（v2）

> 路径逐字取自 `API_SPECIFICATION.md`；标 ★ 的是 §10 决定新增、待回写 API 文档的接口。

| 页面（路由） | 主要接口 |
|---|---|
| `/login` | `POST /api/auth/login`；`POST /api/auth/logout` |
| `/workbench` | `GET /api/stats/overview`、`GET /api/documents/mine`、`GET /api/review/documents`（`doc:review`） |
| `/docs` | `GET /api/documents`（全文检索）、`GET /api/categories/tree`、`GET /api/tags`、`POST/DELETE /api/documents/{id}/favorite`、`POST /api/documents/{id}/derive` |
| `/docs/:id` | `GET /api/documents/{id}`、`GET /api/documents/{id}/versions`、`POST /api/documents/{id}/publish`、`POST/DELETE .../favorite`、`POST .../derive`、`DELETE /api/documents/{id}` |
| `/docs/edit/:id?` | `POST /api/documents`、`GET/PUT /api/documents/{id}`、`POST /api/documents/{id}/publish`、`GET /api/categories/tree`、`GET /api/tags`、`POST /api/upload/image` |
| `/my` | `GET /api/documents/mine`、`GET /api/favorites`、`GET /api/documents/trash`、`POST /api/documents/{id}/restore`、`DELETE /api/documents/{id}/destroy`、`GET /api/documents/{id}/versions` |
| `/review` | `GET /api/review/documents`、`GET /api/documents/{id}`、`GET /api/documents/{id}/versions`、`POST /api/documents/{id}/audit`、`POST /api/documents/{id}/reject` |
| `/governance` | `POST /api/documents/{id}/archive`、`POST /api/documents/{id}/republish`、`DELETE /api/documents/{id}/destroy`；**全状态列表接口待新增（契约见 §10.1）** |
| `/taxonomy` | `GET /api/categories/tree`、`POST/PUT/DELETE /api/categories`、`GET /api/tags`、`POST/PUT/DELETE /api/tags` |
| `/admin/users` | `GET/POST /api/users`、`GET/PUT /api/users/{id}`、`PUT /api/users/{id}/status`、`PUT /api/users/{id}/password` |
| `/admin/roles` | `GET/POST /api/roles`、`GET/PUT/DELETE /api/roles/{id}`、`POST /api/roles/{id}/permissions`、`GET /api/permissions/tree`、`GET/PUT/DELETE /api/permissions/{id}` |
| `/admin/org` | `GET /api/depts/tree`、`POST /api/depts/{id}/roles` |
| `/me` | `GET /api/auth/me`、`GET /api/documents/mine`、`GET /api/favorites`；**自助改密接口待新增（契约见 §10.2）** |

> **本轮决定新增、尚未回写 API 文档的两个接口**（`doc:manage` 的全状态列表、登录用户自助改密）已在 §10.1 / §10.2 给出完整契约；落地时同步写进 `API_SPECIFICATION.md` 与 OpenAPI 后再并入本表 —— 本表始终只收录"可用清单"内的路径，避免出现前端调不到的死链。

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

## 8. 逐页设计（v2：13 个功能页 + 2 个异常页）

> 通用规则：路由级无权限一律由守卫跳 `/403?perm=`；页内局部无权限用 `NoPermHint` 内联提示或直接不渲染按钮；列表页分页受 BR-05 约束（`pageNum ≥ 1`、`1 ≤ pageSize ≤ 100`）；所有页面四态文案见下表，实现时以 `docs/02-design/UI-PREVIEW.html` 的观感为准。

### 8.1 `/login` 登录

- **权限**：公开 ｜ **接口**：`POST /api/auth/login`
- **布局**：左栏校园风景铺底 + 藏青渐变遮罩 + 校徽 + 校训题字 + 三条平台说明；右栏登录卡（工号/学号 + 密码 + 显示密码）
- **四态**：空＝两项未填时按钮弱化禁用；加载＝按钮「登录中…」+ 双输入禁用，`finally` 恢复；错误＝401「工号或密码错误」（**故意不区分工号不存在/密码错误**，防工号枚举）、403「账号已冻结/已停用，请联系信息化中心（0311-8078xxxx）」、400「请输入工号与密码」、网络异常；无权限＝已登录访问本页 → 跳 `/workbench`
- **关键交互**：`?redirect=` 回跳；错误后保留工号、清空密码并聚焦；再次输入时错误条消失；登录成功进 `/workbench`

> **v2.1（退出闭环）**：**侧栏底部常驻「退出登录」**（`logout` → 清 token → 回 `/login`），不只在 `/me` 页里；
> 原因：用户实测"登录之后再也找不见退登键能让我回首页"。侧栏底部同时显示当前身份与单位，退出即"换个人登录"。

### 8.2 `/workbench` 工作台

- **权限**：登录即可（统计卡需 `doc:center`）｜ **接口**：`GET /api/stats/overview`、`GET /api/documents/mine`、`GET /api/review/documents`（仅 `doc:review`）
- **布局**：校园风景横幅（问候 + 统计摘要 + 快捷操作「新建文档 / 检索全校文档 / 我的资料」+ 右侧校徽）→ 4 张统计卡（全校已发布 / 我的文档 / 待我审核[无审核权限时改为我的收藏] / 收藏或本月阅读）→ 左「我的草稿」右「常用分类 + 通知」
- **横幅版式（v8 定稿）**：**固定为「A 左文右图」** —— 左侧主题色渐变承载问候 / 统计摘要 / 快捷操作，右侧是主题照片卡片（约 44% 宽）。
  v6/v7 曾提供 B 通栏大图与 C 细照片带两版供挑选，用户拍板用 A（理由：B 的深色渐变区与主题不协调）；预览稿的切换器与 B/C 两版样式已全部移除。
  **照片一律按显示比例裁剪，缩放比恒 < 1**（不放大 = 不糊）。
- **四态**：空＝无草稿时给「新建文档」引导（**v2.1 修正**：空列表必须走空状态，不许画一个只有表头和一个孤零零页码的空表格）；加载＝统计卡 4 块骨架 + 表格 6 行；错误＝对应卡片显示「—」+ 重试，不阻断其它区块；无权限＝统计接口 403 时卡片降级为「—」，页面其余部分正常
- **关键交互**：横幅操作按权限显隐；草稿行「继续编辑」直达编辑器

### 8.3 `/docs` 文档检索

- **权限**：`doc:search` ｜ **接口**：`GET /api/documents`（**v2 起支持全文检索**，见 §10.4）、`GET /api/categories/tree`、`GET /api/tags`、`POST /api/documents/{id}/favorite`、`POST /api/documents/{id}/derive`
- **布局**：筛选区（关键词 / 分类树 / 标签多选 / 更新时间 / 排序 + 重置/检索）→ 结果列表（标题 + 摘要 + **拟稿人** + 分类 + 状态 + 阅读 + 更新时间 + 行操作「收藏/派生/查看」）→ 分页条
- **四态**：空＝「没有匹配的文档」+ 清空筛选；加载＝首屏骨架 6 行，筛选变化时保留旧数据 + 顶部 2px 进度条（200ms 内返回不显示骨架）；错误＝「文档列表加载失败」+ 服务端 message + 重新加载（保留筛选）；无权限＝无 `doc:search` 但含 `doc:mine` → 自动跳 `/my`，两者皆无 → 守卫跳 403
- **关键交互**：关键词防抖 300ms；**筛选条件同步 URL query**（刷新与分享不丢）；**命中词在标题与摘要中高亮**（全文检索返回的 `highlight` 字段）；结果只包含「已发布」文档 —— 草稿与他人未发布内容不在此页出现

### 8.4 `/docs/:id` 文档详情

- **权限**：`doc:search`（属主可看自己的非发布稿）｜ **接口**：`GET /api/documents/{id}`、`GET /api/documents/{id}/versions`、`POST .../favorite`、`POST .../derive`、`POST .../publish`
- **布局**：面包屑 → 标题 + 状态徽标 + 版本 + 元信息（单位/分类/拟稿/更新/阅读/收藏）+ 标签 → 操作条（收藏·派生·编辑·提交发布·删除·版本历史）→ 左正文（含驳回理由横幅）右粘性侧栏（本文目录 / 文档信息 / 相关文档）→ 底部版本历史表
- **四态**：空＝正文为空时提示「这篇文档还没有正文」+ 属主可见「去编辑」；版本为空时抽屉内「暂无版本记录」；加载＝头骨架 1 行 + 正文骨架 8 行；错误＝403「你没有查看这篇文档的权限」/ 404「文档不存在或已被删除」+ 返回列表；无权限＝`canEdit=false` 不渲染编辑按钮，`ARCHIVED` 显示「归档文档为只读」
- **关键交互**：操作类 409 用轻提示并刷新详情；从检索进入时优先 `router.back()` 保留原筛选

### 8.5 `/docs/edit/:id?` 新建 / 编辑

- **权限**：新建 `doc:create`；编辑 `doc:edit` 且 `canEdit=true` ｜ **接口**：`POST /api/documents`、`GET/PUT /api/documents/{id}`、`POST .../publish`、`GET /api/categories/tree`、`GET /api/tags`、`POST /api/upload/image`
- **布局**：标题（大号无边框输入）→ 左栏编辑器（工具栏 + 源文，可切「编辑 / 预览 / 双栏」）右栏发布设置（分类·发文单位·标签·摘要·可见范围）
- **四态**：空＝新建模式右栏预览「左侧输入 Markdown，这里实时预览」；加载＝编辑模式整页骨架 + 顶部细进度条，骨架期禁用输入；错误＝403 整页「你没有编辑这篇文档的权限」、400 字段级红字 + 自动滚动聚焦、409 切只读模式「该文档当前状态不允许编辑」、图片上传失败在源文原地留「点击重试」占位符；无权限＝缺 `doc:upload` 时工具栏插图按钮不渲染且粘贴/拖拽被拦截（纯文本不受影响）、缺 `doc:publish` 只保留「保存草稿」
- **关键交互**：未保存离开二次确认；有未完成的上传占位符时阻止保存并提示

### 8.6 `/my` 我的文档

- **权限**：`doc:mine`（收藏 tab 另需 `doc:favorite`，回收站 tab 另需 `doc:restore`）｜ **接口**：`GET /api/documents/mine`、`GET /api/favorites`、`GET /api/documents/trash`、`POST .../publish`、`POST .../restore`、`DELETE .../destroy`、`GET /api/documents/{id}/versions`
- **布局**：页面头 + tab（全部/草稿/已发布/已归档/收藏/回收站，各带计数）+ 筛选（搜索 + 单位 + 排序）+ 表格（文档 / 状态 / 版本 / 阅读 / 更新时间 / 操作）
- **四态**：空＝「你还没有创建过文档」+ 新建；某状态筛选为空＝「没有 DRAFT 状态的文档」+「查看全部」；收藏为空＝「还没有收藏任何文档」+「去检索文档」；回收站为空＝「回收站是空的」；加载＝表格骨架 6 行；错误＝ErrorState + 重试（保留 tab 与筛选）；无权限＝缺 `doc:favorite` 不渲染收藏 tab，直接访问 `?tab=favorite` 回落并轻提示
- **关键交互**：删除/彻底删除二次确认（彻底删除需**输入标题**确认，BR-08）；恢复失败 409 提示后刷新

### 8.7 `/review` 待我审核

- **权限**：`doc:review`（通过需 `doc:audit`，驳回需 `doc:reject`）｜ **接口**：`GET /api/review/documents`、`GET /api/documents/{id}`、`GET /api/documents/{id}/versions`、`POST .../audit`、`POST .../reject`
- **布局**：三张统计（待审核 / 本周已通过 / 本周已驳回）+ 左待审列表 + 右抽屉（详情 + 本次变更摘要 + 版本并排对比 + 通过/驳回）
- **四态**：空＝「当前没有待审核的文档」；只有 1 个版本时对比区「只有一个版本，暂无可对比的差异」；加载＝表格骨架 5 行、抽屉内骨架；错误＝409「该文档状态已变更，请刷新列表」自动重拉并关抽屉、驳回未填理由时弹窗内红字且确认禁用；无权限＝缺 `doc:audit` 不渲染通过按钮，两个都缺时显示「你没有审核操作权限，请联系系统管理员」
- **关键交互**：驳回理由必填 1–255 字（BR-12），提交后写入 `rejectReason` 回传属主详情页

### 8.8 `/governance` 内容治理

- **权限**：`doc:manage`（归档/恢复上架需 `doc:archive`，彻底删除需 `doc:delete`）｜ **接口**：`GET /api/documents/manage`（**v2 新增，见 §10.1**）、`GET /api/documents/{id}`、`GET /api/documents/{id}/versions`、`POST .../archive`、`POST .../republish`、`DELETE .../destroy`
- **布局**：概览卡 + 状态筛选（含回收站）+ **拟稿人筛选** + 全平台文档表 + 版本历史抽屉
- **四态**：空＝「平台还没有任何文档」；筛选无命中＝「没有匹配的文档」+ 清空筛选；加载＝概览卡 4 块 + 表格 6 行骨架；错误＝409「状态冲突：该文档当前不是 PUBLISHED」自动刷新、彻底删除 400「仅回收站中的文档可以彻底删除」；无权限＝缺 `doc:archive` 只保留删除按钮
- **关键交互**：归档写入审核意见；恢复上架同时清空 `rejectReason`（T7）；彻底删除需输入标题确认

### 8.9 `/taxonomy` 分类与标签

- **权限**：浏览 `doc:category`；维护分类 `doc:category:edit`；维护标签 `doc:tag:edit` ｜ **接口**：`GET /api/categories/tree`、`POST/PUT/DELETE /api/categories`、`GET /api/tags`、`POST/PUT/DELETE /api/tags`
- **布局**：左分类树（可展开、显示每类文档数、选中节点后可改名/调序/删除）右标签表（标签 / 使用次数 / 最近使用 / 改名·合并·删除）
- **四态**：空＝分类树「暂无分类」+ 副文案指向 `data.sql`；标签「暂无标签」；加载＝树 3 层骨架 + 标签骨架；错误＝400「不能将节点移动到其子节点下」树组件本地回滚、409「该分类下仍有文档，无法删除」；无权限＝缺 `doc:category:edit` 时不渲染「新增/改名/删除」，只读浏览
- **v2 决定**：**恢复写入口**（v1 曾判"清单缺位 → 不渲染"）。理由：后端 6 个写接口与两个权限点早已就位，而"分类体系由初始化脚本维护"在校内场景不成立 —— 院系与业务条线会变，管理员必须能自助维护。

### 8.10 `/admin/users` 用户管理

- **权限**：`sys:user`（新增 `sys:user:add`、编辑 `sys:user:edit`、状态 `sys:user:disable`、重置密码 `sys:user:reset`）｜ **接口**：`GET /api/users`、`POST /api/users`、`GET/PUT /api/users/{id}`、`PUT /api/users/{id}/status`、`PUT /api/users/{id}/password`
- **布局**：筛选（工号/姓名/手机 + 单位 + 角色 + 状态）+ 用户表（工号 / 姓名 / 所属单位 / 角色 / 状态 / 最近登录 / 操作）
- **四态**：空＝「没有匹配的用户」+ 清空筛选；加载＝表格骨架 5 行；错误＝400「工号已存在」字段红字、400 密码强度「初始密码至少 8 位，且需包含字母和数字」；无权限＝缺 `sys:user:add` 不渲染新增按钮
- **关键交互**：编辑用户时工号只读（BR-02 同源思想）；状态改非 `ACTIVE` **立即强制下线并清 token 与权限缓存**（F1-07）；重置密码与自助改密（§10.2）区分文案

### 8.11 `/admin/roles` 角色与权限

- **权限**：`sys:role`（授权 `sys:role:grant`；「权限点清单」tab 需 `sys:perm`）｜ **接口**：`GET /api/roles`、`POST /api/roles`、`GET/PUT/DELETE /api/roles/{id}`、`POST /api/roles/{id}/permissions`、`GET /api/permissions/tree`、`GET/PUT/DELETE /api/permissions/{id}`
- **布局**：左角色列表（内置角色标注不可删）右权限树（三层勾选，父节点半选、全选/反选、保存授权）+「权限点清单」tab（39 个权限点，可编辑/删除）
- **四态**：空＝「暂无自定义角色」+ 新增；权限树为空＝「权限数据未初始化」+ 指向 `data.sql`；加载＝树骨架 + 保存时节点全禁用 +「保存中…」；错误＝409「该角色已被用户使用，无法删除」、权限节点 400「不能将节点移动到其子节点下」；无权限＝缺 `sys:role:grant` 时树只读
- **关键交互**：授权保存后**清空该角色下所有用户的权限缓存**（BR-18），旧 token 下次请求即生效

### 8.12 `/admin/org` 组织机构

- **权限**：`sys:dept`（绑角色 `sys:role:grant`）｜ **接口**：`GET /api/depts/tree`、`POST /api/depts/{id}/roles`
- **布局**：左机构树（党政管理机构 / 教学单位 / 直属单位 / 附属单位）右单位详情（全称 / 上级 / 负责人 / 在编人数 / 文档量 / 绑定角色 / 成员列表）+ 单位绑定角色一览表
- **四态**：空＝「暂无部门」；加载＝树骨架 3 层；错误＝409「该部门下仍有成员或子部门」；无权限＝缺 `sys:role:grant` 不渲染「绑定角色」
- **关键交互**：单位绑角色后该单位成员自动继承（技术部 → STAFF + DOC_ADMIN 是内置演示数据）

### 8.13 `/me` 我的资料

- **权限**：登录即可（**这是 v1 缺失、v2 新增的页面**）｜ **接口**：`GET /api/auth/me`、`PUT /api/auth/password`（**v2 新增，见 §10.2**）、`GET /api/documents/mine`、`GET /api/favorites`
- **布局**：账号卡（校徽头像 / 姓名 / 单位 / 岗位 / 工号 / 邮箱 / 电话 / 最近登录）→ 「我的角色」两个来源（直接授予 + 单位继承）→ 「我的权限」（39 个权限点标签墙，有的打勾、没有的置灰，可 hover 看权限码）→ 右栏「账号安全」（修改密码 / 登录记录 / 设备管理 / 退出登录）+「我的内容」入口卡
- **四态**：空＝无内容时各卡显示 0 与引导；加载＝卡片骨架；错误＝账号信息加载失败给重试，权限墙单独失败只让该块显示「—」；无权限＝不适用（人人可看自己）
- **关键交互**：修改密码成功后提示「密码已修改，请重新登录」并跳 `/login`（后端清空 `user:tokens:`，**全部会话含当前设备一并失效**）；权限墙用于回答"我为什么看得到/看不到某个功能"

### 8.14 `/403`、`/404`

- **403**：写明缺失权限点（`?perm=`），三个出口：返回工作台 / 去文档检索 / 看我的权限（跳 `/me`）
- **404**：路由不存在，给返回工作台入口

### 8.15 顶栏常驻控件（通知面板与身份菜单 · v8 新增）

| 控件 | 位置 | 点击后 |
|---|---|---|
| 通知铃（带未读红点） | 产品顶栏右侧、身份框左侧 | 弹出 **320px 通知面板**：标题栏「通知 · N 条未读」+「全部标为已读」；正文逐条 = 类别徽标（维护/制度/流程/…）+ 标题 + 时间，**每条未读都带红点**（红点数 = 未读数）；「全部标为已读」后圆点一起消失、按钮隐藏、空态显示「已全部读完」 |
| 身份框（头像 + 姓名 + 单位） | 产品顶栏最右 | 弹出 **264px 身份菜单**：`身份介绍`（姓名 / 职称 / 角色 / 单位 / 有效权限 X/39）→「我的资料」（跳 `/me`）→「切换账号」→「退出登录」（回 `/login`）。 |

- 交互：点击控件开合；**点击面板外部或按 ESC 关闭**；同一时刻只开一个（通知与身份菜单互斥）。
- 身份菜单里的「切换账号」＝**退出当前登录 → 回 `/login` 重新登录**（与正式版一致；预览稿不做"即时切换身份"，那是预览工具的职责，由预览条上的身份下拉承担）。
- 四态：通知为空 → 「暂时没有通知」；加载 → 骨架 3 行；错误 → 「通知加载失败 + 重试」；无权限 → 通知对所有登录身份可见（不挂权限点）。


## 9. 交付自检（v2）

| 自查项 | 结论 | 证据 |
|---|---|---|
| 路由覆盖 | ✅ | §1 共 13 条功能路由 + 2 条异常路由；每条都有权限点、视图文件与用途；v1 的 8 条路由与「系统管理」聚合板块已作废 |
| 管理功能拆分 | ✅ | 用户管理 / 角色与权限 / 组织机构 三个独立菜单（§1.1 #10~#12），命名与职责对应真实站点惯例；非管理员不可见 |
| 人人可见「我的资料」 | ✅ | §1.1 #13 `/me` 权限为"登录即可"，含角色两个来源与 39 个权限点自有情况 |
| 页面四态 | ✅ | §8.1~§8.14 每页均含「空 / 加载 / 错误 / 无权限」（文案 + 组件 + 交互） |
| 主题与配色 | ✅ | §2 六套主题（三套亮调，默认师大蓝）；T1~T4 四条硬约束；选择器规格见 §2.4 |
| 视觉资源 | ✅ | §3 五项资源（校徽 / 图标 / 校训题字 / 校园风景 / 品牌色）来源、规格、用途、内联方式与使用边界齐备 |
| 接口路径合规 | ✅ | 所有路径取自 `API_SPECIFICATION.md`，新增的 3 个接口在 §10 明确给出契约并同步回写 API 文档 |
| 命名合规 | ✅ | 字段取自 `GLOSSARY.md`；`deleted` 不出现在任何 VO；主键一律 `string` |
| 样式约束 | ✅ | 只用令牌与语义类；无内联 style（预览稿经机检 139 项断言验证：`style=` 0 处、`style-xxx` 0 处） |
| 业务规则落位 | ✅ | BR-05/07/08/10/11/12/13/14/15/17/18 在前端有对应校验或交互（§8 各页「关键交互」） |
| BDD 可验收 | ✅ | US-01→§8.1、US-02/03/06→§8.5、US-04→§8.3、US-05→§8.3/§8.4、US-07→§8.7、US-08→§8.11/§8.12 |

---

## 10. 后端缺口处置决定（v2 新增，作者已拍板）

> 本节是**决定**，不是待办清单：三处 v1 遗留缺口与一项能力增强，处置方式已定，落地后需同步回写 `API_SPECIFICATION.md`、`ARCHITECTURE.md`、`openapi-campusswap.json` 与机检脚本。

### 10.1 「内容治理」缺全状态列表接口 → **新增 `GET /api/documents/manage`**

| 项 | 契约 |
|---|---|
| 路径 | `GET /api/documents/manage` |
| 权限 | `doc:manage` |
| 入参 | `status`（可空，支持 `DRAFT`/`PUBLISHED`/`ARCHIVED`/`TRASH`，空=全部）、`keyword`（标题/摘要）、`categoryId`、`authorId`（拟稿人；**数据模型无「发文单位」列**，故按拟稿人筛选）、`startTime`/`endTime`、`sort`、`pageNum`/`pageSize` |
| 出参 | `PageVo<DocumentVo>`（含 `status`，回收站行 `deleted=1` 需原生 SQL 绕过 `@SQLRestriction`，列序与类型转换复用 `DocumentColumns`） |
| 与既有接口的边界 | `GET /api/documents` **语义不变**（只返回 `PUBLISHED`，权限 `doc:search`，供检索页）；治理页**只调新接口** |
| 机检增补 | ① 含 `TRASH` 状态返回 200 且行数 ≥1；② `staff`/`docadmin` 调用返回 403；③ SQL 条数 ≤3（`ARCHITECTURE §10.5` 预算表同步登记） |

### 10.2 「我的资料 → 修改密码」缺自助入口 → **新增 `PUT /api/auth/password`**

| 项 | 契约 |
|---|---|
| 路径 | `PUT /api/auth/password` |
| 权限 | 登录即可（本人操作） |
| 入参 | `oldPassword`（必填）、`newPassword`（必填，8–32 位且含字母与数字，与 F1-08 同规则） |
| 行为 | 校验旧密码 → 更新 `password_hash` → **清空 `user:tokens:{userId}`**（全部会话失效，含当前设备）→ 返回 200；旧密码错误返回 400「原密码不正确」 |
| 与既有接口的边界 | `PUT /api/users/{id}/password`（管理员重置，`sys:user:reset`）保留不动 |
| 机检增补 | ① 改密成功后再用旧 token 请求返回 401；② 旧密码错误返回 400；③ 新密码不合规返回 400 且提示为中文 |

### 10.3 「分类与标签写入口是否渲染」 → **渲染（可管理）**

- **决定**：`/taxonomy` 由 v1 的"只读浏览"改为"浏览 + 维护"，按权限显隐：新增/改名/调序/删除分类（`doc:category:edit`）、新增/改名/合并/删除标签（`doc:tag:edit`）。
- **理由**：后端 6 个写接口与两个权限点早已实现且有机检覆盖；校内场景下院系与业务条线会调整，分类体系不可能只靠 `data.sql` 维护。
- **连带改动**：`UI_UX_SPECIFICATION §5.3` 的"写接口缺位处理表"删去分类/标签两行；`API_SPECIFICATION` 的页面↔接口映射补入这 6 个写接口。

### 10.4 文档全文检索 → **落地 MySQL InnoDB FULLTEXT + ngram 解析器**（已实测）

**现状（实测，非推断）**：检索走 `DocumentQueryRepositoryImpl` 第 103–105 行的 `LIKE '%kw%'`，**只匹配 `title` 与 `summary`，不搜正文**。真实种子数据上的对照实验：

| 探测 | 语句 | 命中 |
|---|---|---|
| A 现状做法 | `title LIKE '%递归%' OR summary LIKE '%递归%'` | **0** |
| C 正文 LIKE | `content_md LIKE '%递归%'` | 1（证明正文确有此词） |
| B ngram 全文 | `MATCH(title,summary,content_md) AGAINST('递归' IN NATURAL LANGUAGE MODE)` | **1** |
| D ngram 布尔与 | `AGAINST('+接口 +分页' IN BOOLEAN MODE)` | 1 |
| E 英文词 | `AGAINST('Vite' IN BOOLEAN MODE)` | 1 |
| G 两者不一致的行数 | 全文命中 且 LIKE 未命中 | **1** ← 缺口证据 |

环境：MySQL 8.0.46，`ngram_token_size=2`（中文按 2-gram 切分，**2 字及以上中文查询可用**）。
（实验在临时表 `ft_probe` 上完成，跑完已 `DROP`，未改动真实 schema。）

**索引变更**：

```sql
ALTER TABLE `doc_document`
  ADD FULLTEXT KEY `ft_doc_search` (`title`,`summary`,`content_md`) WITH PARSER ngram;
```

- `backend/sql/schema.sql` 同步；索引数 **20 → 21**；`verify-m2.ps1` 的索引清单与 `verify-db-deep.ps1` 的列/索引对账随之更新。
- `perf-fixture.sql` 造数后需 `ANALYZE TABLE doc_document`（InnoDB 全文索引的可见性与统计）。

**接口契约变更**（`GET /api/documents`）：

| 项 | 规则 |
|---|---|
| 触发条件 | `keyword` 非空且去空格后长度 ≥ 2 → 走全文检索分支；为空 → 走原 Criteria 分支（不触发索引，保持既有 SQL 预算） |
| 匹配方式 | 关键词按空白切词，逐词 `+词*` 拼成布尔表达式：`AGAINST('+国家 +自然科学*' IN BOOLEAN MODE)`；**不做分词器外挂**，依靠 ngram |
| 排序 | 命中相关度 `MATCH(...)` 作为可选排序键（`sort=relevance`），默认仍为 `updatedAt` 倒序 |
| 出参新增 | `highlight`：命中的正文片段（命中词两侧各 30 字，命中词以 `<em>` 包裹），前端在摘要下方以高亮行展示；`matchedIn`：`title` / `summary` / `content`（用于列表标注"命中正文"） |
| 兜底 | 全文检索 0 命中且关键词长度 < 2 时回落 `LIKE title/summary`；检索接口仍只返回 `PUBLISHED` |
| 安全 | 关键词里的布尔符号（`+ - * " ( ) ~ < >`）在拼接前剥离，避免布尔注入导致语法错误或结果异常 |

**受影响机检（2026-09-23 已全部执行完毕，结果见 `M5PREP-CLOSURE.md §5`）**：`verify-m2.ps1`（C12 索引 21 + **新增 C13b** 断言全文索引列序与 ngram 解析器）、`verify-db-deep.ps1`（D6b 索引对账含 `ft_doc_search`）、`verify-m3.ps1` / `verify-m4.ps1`（端点计数 26 / 31）、`verify-m3-http.ps1`（**新增 §8b** 自助改密）、`verify-m4-http.ps1`（**新增 §US-04b / §US-07b**：全文检索出参与治理全状态列表 + 11 项 SQL 预算）、`ARCHITECTURE §10.5`（检索预算 3~5、全文分支与治理列表均**按实测登记**：3 末页 / 4 满页 / 5 带分类）、`openapi-campusswap.json`（`highlight` / `matchedIn` / `sort=relevance` / 新接口 2 个）、`EXPLAIN-NOTES.md`（新增 §2b Q5：`type=fulltext` 实证）。

### 10.5 落地顺序（M5 开工前）

1. **后端契约**：§10.1 新接口 + §10.2 改密接口 + §10.4 全文索引与检索分支（schema → repository → service → 机检增补 → 全量重跑 420 项）。
2. **文档回写**：`API_SPECIFICATION.md`（§9 三项并入 §3/§4）、`ARCHITECTURE.md`（§10.5 预算表、索引清单）、`openapi-campusswap.json`、`GLOSSARY.md`（新增字段 `highlight` / `matchedIn`）。
3. **数据重种子**（校园口径，见 `CODE-TOUR §9` 第 4 项）：`data.sql` + 受影响断言。
4. **然后**才开 M5 前端编码；前端按本规格 §1~§9 施工，视觉以 `UI-PREVIEW.html`（**v6**）为准。

### 10.6 审查发现：权限点 `doc:offline` 无接口也无入口

评审 §8.8 时发现：`sys_permission` 里的 `doc:offline`（下架文档）在 DOC_ADMIN / SYS_ADMIN 的权限集合中，但**后端没有任何 offline 端点**，v1 的界面也未渲染入口 —— 即"权限点存在、能力不存在"。

**决定**：本轮**不新增下架接口**。理由：状态机里的 `ARCHIVED`（归档＝下架但可检索只读）已经覆盖该语义，再加一个 offline 会出现两个含义重叠的"下架"。该权限点作为预留位保留，界面上不出现任何入口，也不会出现在 §5.3 的接口映射表里。

**遗留**：是否在 M6 清理权限树（删除 `doc:offline`）需连带处理 `PRD §3.2`（39 个权限点）、`data.sql`（DOC_ADMIN 20 → 19、SYS_ADMIN 39 → 38）、`verify-m2.ps1` 的权限计数断言与 `verify-m1.ps1` 的 C11 断言，影响面明确但需单独一次变更。

### 10.7 用户级直授权（`sys_user_permission`）→ **不渲染入口**

`sys_user_permission`（用户级直授权中间表）与权限合并逻辑（角色权限 ∪ 直授权）在 M3 已实现并实测"直授权立即生效"，但**接口清单里没有对应的写接口** —— 即该能力只能通过 SQL 维护。

**决定**：界面**不渲染**用户级直授权入口（维持 M3 的口径，§2 一致性提示里已写明）。理由：① 校内 RBAC 的授权单元是"岗位/角色"，给单个账号挂例外权限会让权限来源无法解释（同一个按钮有人因为角色亮、有人因为例外亮，审计时说不清）；② 真正需要"例外"的场景（如临时答辩秘书）用"新建自定义角色 + 绑到该用户"表达更清楚，且 M3 已有完整接口与界面（§8.11）；③ 少一个界面就少一处越权面。

**若将来要开**：需要新增一个「用户级授权」写接口（`PUT` 方法、挂在用户资源下并以 `permissions` 结尾，本规格刻意不写出完整路径 —— 未实现的接口不得出现在本文档的路径清单里），权限点建议复用 `sys:role:grant`，并同步 `API_SPECIFICATION.md` 总表编号、OpenAPI 与 §5.3 映射表。

---

## 11. 落地检查清单（M5 开工前必须全绿）

| # | 事项 | 验收方式 |
|---|---|---|
| 1 | `API_SPECIFICATION.md` 的 §9 三项变更已实现并并入 §3/§4 | `verify-api-spec.ps1` 全绿 + 新接口的 HTTP 机检 |
| 2 | `openapi-campusswap.json` 补齐新接口与 `highlight`/`matchedIn` 字段 | 重新生成 + 与 API 文档逐条对账 |
| 3 | `schema.sql` 加 `ft_doc_search` 全文索引（20 → 21 个索引） | `verify-m2.ps1`、`verify-db-deep.ps1` 更新后全绿 |
| 4 | `data.sql` 按校园口径重新种子化 | `verify-m2.ps1` 计数断言 + `verify-m4-http.ps1` 全绿 |
| 5 | 全量回归：9 个产品机检 **504** 项（m0 13 / m1 15 / api-spec 24 / m2 17 / db-deep 9 / m3 36 / m3-http 142 / m4 30 / m4-http 218）+ 预览稿自检 **200** 项 = **704 项** | 全部 0 失败（2026-09-23 实测） |
| 6 | 本规格 §1~§9 与 `UI-PREVIEW.html`（**v6**）逐页对账 | 人工核对：路由、权限、四态、主题令牌 |


