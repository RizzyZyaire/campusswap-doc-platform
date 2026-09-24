# M5 前端实现 · 收口记录

> 里程碑：**M5 前端实现**（Vue 3 + TypeScript + Pinia + Tailwind + markdown-it）
> 日期：2026-09-23
> 机检入口：`docs/03-qa-review/verify-m5.ps1`（**43 项，全绿，可重跑，纯静态不需要后端**）
> 上游依据：`UI_UX_SPECIFICATION.md` **v2.4**（§6.1 路由表 / §8.1~§8.14 逐页 / §10.6 下架口径）、`GLOSSARY.md` §3.7（字段名唯一真源）、`MASTER-PLAN.md` T5.1~T5.9

---

## 1. 交付物

| 层 | 文件 | 说明 |
|---|---|---|
| 工程 | `frontend/{package.json,vite.config.ts,tsconfig.json,env.d.ts,index.html,eslint.config.js,postcss.config.js,tailwind.config.js}` | Vite 6.4.3 / Vue 3.5.43 / TS 5.7.3（strict + `noUnusedLocals/Parameters`）/ Tailwind **3.4.19**（v3 是为了 `tailwind.config.js` + `@layer components`，与规格 §3 一致） |
| 类型 | `src/types/{api,system,document,index}.ts` | 字段名逐字取自 GLOSSARY §3.7；ID 一律 `string`；空 DTO 用 `type` 别名（避开 `no-empty-object-type`） |
| 接口 | `src/api/{request,auth,documents,review,taxonomy,system,files}.ts` | 拦截器拆信封（只回 `data`）、401 广播 `app:unauthorized`；**57 个后端端点全部被前端 api 层覆盖**（`verify-m5.ps1` D9b 对账） |
| 状态 | `src/stores/{user,theme,ui}.ts` | token / 用户信息 / `hasPerm`；6 套主题写 `document.documentElement.dataset.theme`；轻提示 |
| 路由 | `src/router/index.ts` | 13 条功能路由 + 2 条异常路由，路径 / name / 视图文件与 §6.1 逐字一致；`/docs/edit/:id?` 在 `/docs/:id` **之前**；守卫按 §1.3 |
| 组件 | `src/components/{StateBlock,Pager,StatusBadge,ToastHost}.vue` | 四态块 / 分页条 / 状态徽标 / 轻提示宿主 |
| 页面 | `src/views/**`（**15 个 .vue**） | 登录、工作台、检索、详情、编辑器、我的文档、审核、治理、分类标签、用户、角色、组织、我的资料、403、404 |
| 样式 | `src/styles/{theme.css,components.css,main.css}` | 前两个由 `pnpm run sync-preview` 从预览稿**生成**（6 套主题令牌 + 361 条组件规则 + 13 项素材）；`main.css` 手写，只放预览稿里没有的类 |
| 工具 | `frontend/scripts/{extract-preview.mjs,shot.mjs,check-classes.mjs,verify-edit-conflict.mjs}` | 预览稿同步 / 带登录态无头截图 / 类名体检 / 409 端到端复现 |

**范围说明**：正式版把预览稿的「8 个页面」落成 **15 条路由**——预览稿的 8 个是"板块"，§6.1 把它们拆成 13 条功能路由 + 2 条异常路由（依据 `UI_UX_SPECIFICATION §1`，非本次新增范围）。

---

## 2. 验证证据（全部可重跑）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m5.ps1` | **PASS=48 FAIL=0**（视图 / 路由 / 主题 / 红线 / `versionNum` 契约 / 下架无入口 / 39 权限码与库对齐 / token 单一出处 / 57 端点覆盖 / 生成物与素材清单 / M5 任务勾选） |
| 2 | `cd frontend && pnpm run typecheck` | exit 0 |
| 3 | `cd frontend && pnpm run lint` | exit 0（`--max-warnings 0`，禁 `any`、禁内联 `style`） |
| 4 | `cd frontend && pnpm run build` | exit 0（`vue-tsc --noEmit && vite build`） |
| 5 | `cd frontend && pnpm run check-classes` | exit 0：**206 个模板类名全部存在于产物 CSS**（294 个定义类名） |
| 6 | `cd frontend && node scripts/verify-edit-conflict.mjs 3 staff` | **10/10**：真接口改一版 → 保存 → 409 横幅 +「刷新内容」+ 轻提示 + **未切只读** → 刷新内容 → 再保存成功（v1 → v3） |
| 7 | `node docs/02-design/ui-preview.smoke.mjs` | **TOTAL pass=211 fail=0**（预览稿 v8.3） |
| 8 | 后端回归（重建演示库后） | m0 **13** / m1 **15** / api-spec **24** / m2 **17** / db-deep **9** / m3 **36** / m3-http **142** / m4 **30** / m4-http **221** 全绿；`mvnw test` **6/6** |
| 9 | 截图自查（`D:\DevEnv\logs\shots\m5-*.png`） | 登录 / 工作台 / 检索（含命中高亮）/ 详情（发布态 + 草稿态）/ 编辑器（双栏 + **冲突横幅** + 恢复后）/ 我的文档 / 审核抽屉 / 治理 / 分类标签 / 用户 / 角色（权限树）/ 组织 / 我的资料 / 404 |

> 截图是"人眼那一道"的补充：本轮的 UI 缺陷（§3 D5~D8，以及交付后用户反馈带出的 D11/D12）**几乎都是靠截图/裁剪/几何探针发现的**，
> 静态检查当时都是绿的 —— 这条经验值得写进 M6 的走查清单。

---

## 3. 本轮实测发现并修掉的缺陷

| # | 缺陷 | 复现 / 根因 | 修法 | 复验 |
|---|---|---|---|---|
| D1 | **生成的 `components.css` 第一条"规则"是非法选择器**（HTML 注释尾巴 + 字面量 `<style>` 混进选择器） | 预览稿开头的说明注释里**写了 `<style>` 这个词**，`/<style>([\s\S]*?)<\/style>/` 从注释里那一处起算，把注释尾巴与一小段 HTML 一起吞进来 | `extract-preview.mjs` 先摘掉 HTML 注释再取 `<style>`，并断言取到的样式块开头必须是 CSS | 重新生成后首行是真实规则；`verify-m5.ps1` D10 系列 |
| D2 | **`@keyframes sk` 头部丢失**，产物里只剩 `0%,100%{opacity:1}` / `50%{opacity:.5}` 两行孤立声明（非法 CSS，骨架动画失效） | 平铺规则正则 `[^{}]+\{[^{}]*\}` 不支持花括号嵌套，at-rule 被切碎 | 先把带嵌套的 at-rule 整块摘出（注释先遮罩，避免把注释里的 `@layer components` 当 at-rule），再解析平铺区，并断言平铺区不得出现 `@` 或孤立帧选择器 | 产物含 `@keyframes sk{…}`；D10e/D10f |
| D3 | **原检查器的"未解析内容"判断恒为假**（`re.exec` 返回 `null` 会把 `lastIndex` 归零，循环外读它等于整段文本） | 该判断从未真正生效，掩盖了 D1/D2 一年多（自本轮之前的版本） | 在循环内记录 `lastIndex` | 修完后立刻真的报出问题（D1/D2 就是这么被揪出来的） |
| D4 | 模板里写了 **不存在的类名** `.ml8`、`.cat-list` | 手写类名靠记忆，写错不报错、页面只是"少块样式" | 新增 `frontend/scripts/check-classes.mjs`：拿**产物 CSS**（Tailwind 扫源码 + components.css 全量进产物）核对模板里每个类名；接进 `pnpm run check-classes` 与 M5 机检 | 当场揪出 `cat-list`（工作台常用分类块的包裹层）；`.ml8` 在实现详情页时就地改成普通 span |
| D5 | 分类与标签页左卡标题**被按钮挤成一列单字**（"分/类/树"竖排） | `.card-hd` 是 flex，标题在 300px 窄列里被压缩 | 在 `main.css` 覆盖：`.card-hd{flex-wrap:wrap;row-gap:6px}` + `.card-hd h3{white-space:nowrap}`（components.css 是生成物，只能覆盖） | 截图 `m5-taxonomy2.png` + 裁剪复核 |
| D6 | 内容治理页把**宽表格塞进 300px 的列表列**，抽屉反而占满右侧 | 复用了"窄列表 + 宽抽屉"的 `.g-list`，而治理页是"宽表 + 窄侧栏" | 新增 `.g-drawer-right{grid-template-columns:minmax(0,1fr) 420px}` 并精简表格列（版本并入标题副行） | 截图 `m5-governance2/3.png` |
| D7 | 登录页左侧塔图不显示（M5 早期） | `extract-preview.mjs` 早期把含 `var(--photo-*)` 的规则**整条丢弃**，`.login-aside .bg` 的定位一起没了 | 改为**改名保留**（`--photo-login-a → --login-photo`、`--photo-* → --hero-photo`） | 截图 `m5-login2.png` |
| D8 | 编辑器"双栏"模式名不副实（上下堆叠）且右侧预览重复 | 布局按 `.editor` 单列堆叠 | 新增 `.md-wrap(.split)`，双栏时左右各半、并隐藏右栏预览 | 截图 `m5-edit3.png` |
| D9 | `CHANGE_TYPE_TEXT` 在审核页模板里用到但**没 import** | `pnpm run build`（含 `vue-tsc`）拦住 | 补 import | build exit 0 |
| **D11** | **页面顶部一条 35px 的空白带**（交付后用户实测反馈：「顶栏上方莫名一根白条」，侧栏与顶栏整体下沉 35px） | 预览稿顶部有一条 35px 的**预览条**（`.pvbar`：换身份 / 直达 / 主题），它的骨架里到处写着 `calc(100vh - 35px)` 与 `top:35px`；产品没有这条预览条，这些预留偏移就变成了空白带（实测 `.sidebar`/`.topbar` 的 `rect.top` 都是 35） | 在 `main.css` 覆盖成真实值：`.app{min-height:100vh}`、`.sidebar{top:0;height:100vh}`、`.topbar{top:0}`、`.login-wrap{min-height:100vh}`、`.side-sticky{top:76px}`（生成物不能手改）；机检加 D3b6 | 几何探针：`sidebarTop=0 / topbarTop=0`；截图复核 |
| **D12** | **顶栏的主题选择器只剩一个没有样式的文字按钮**（用户：「主题都换不了」） | `extract-preview.mjs` 的 `PREVIEW_ONLY` 把 `.themepick/.theme-btn/.dots/.theme-pop/.theme-grid/.tp` 也当成"预览稿专用"丢掉了 —— 而预览稿里这组样式的注释写着「**通用**：主题选择器（右上角，可视化色卡）」，它只是演示时被摆在预览条上 | ① 提取器只剔 `.pvbar`；② `AppShell` 顶栏照预览稿重建该控件（按钮 = 当前主题三色点 + 中文名 + ▾，弹层 = 6 张「迷你界面」色卡 + 气质标签 + 五色点 + ✓）；③ 主题名/色卡改由 `sync-preview` 从预览稿 `var THEMES` 生成（`src/styles/theme-meta.ts`），产品侧不再手抄 |
| **D12b** | 修完 D12 后**色卡全部透明** | 色卡变量最初生成在 `components.css` 的 `@layer` 里，而卡上的类名是 `t-${id}` 动态拼的 → Tailwind 在源码里找不到 `t-ink` 这种字面量，把 `.t-ink{--c1:…}` 整条**摇掉** | 把 `.t-<主题id>{--c1..--c5}` 生成到 **theme.css**（不在 `@layer` 里、Tailwind 不摇树），上色规则留在组件层；机检加 D3b3/D3b4 | 探针：色卡取到真实色值（`rgb(48,48,100)` 等），点「墨夜黑」后 `data-theme=night`、刷新仍记住 |
| **D13** | 工作台横幅「新建文档 / 检索全校文档 / 我的资料」三个按钮**既无悬停效果、点了也不跳转** | **装饰层挡住了指针**：`.hero .lead:after`（那层淡光晕）是 `.lead` 的最后一个子元素，而 `.hero .lead>*{position:relative}` 没给 `z-index` → 光晕盖在按钮上方。实测三个按钮的 `elementFromPoint` 全部返回 `DIV.lead`，所以 hover 不触发、真实鼠标点击也落不到链接上（合成 `dispatchEvent` 能跳、真指针不能，很有迷惑性） | `main.css` 覆盖：`.hero .lead>*{z-index:1}` + `.hero .lead:after{pointer-events:none}`；机检加 D3b7 | 复验：`elementFromPoint` 返回按钮自身、悬停底色 `rgba(255,255,255,.28)`、点击后路由跳 `/docs` |
| **D14** | 顶栏**与页面底几乎同色、没有辨识度**；主题选择器夹在通知与身份之间（用户要它独立占右上角）；登录页"密码行贴着登录按钮 / 右栏纯白太空 / 塔图看不见塔尖 / 换不了主题" | ① 预览稿 `--topbar-bg:rgba(255,255,255,.88)` 在浅色底上等于白色；② 主题选择器位置是我按"搜索→主题→通知→身份"排的，不是用户要的"最右独立"；③ 登录页原本没有主题选择器（预览稿把它放在预览条上，产品没有预览条）；④ `.login-aside .bg{background-size:cover}` 在"宽而扁"的窗口下把塔尖裁掉；⑤ `.field` 之间没有外边距 | ① 顶栏改主题色淡染 + 2px 分隔线 + 轻投影；② 主题选择器移到**顶栏最右**并加 `.topbar-sep` 细分隔线，同时抽成共享组件 `ThemePicker.vue`；③ 登录页右上角复用该组件；④ 塔图 `background-size:auto 138%` + `background-position:50% 14%`；⑤ 登录页加主题色底 + 校徽抬头 + **演示账号一键填充** + 字段间距 | 截图：`m5-workbench4.png`、`m5-login4.png`、`m5-login-wide.png`（1280×720 也能看见塔尖）、`ev-login-night.png`（登录页切深色）；机检加 D3b5~D3b6 |
| **D15** | 顶栏右侧控件**没贴到右边**（用户：「主题切换虽然在其它功能右边，但还是居于页面中间」）；**顶栏底色只有个别主题变、其余仍是白的**；左上角品牌区**单击出现文本插入符**（"打字时的竖虚线"）、点了也没有任何反馈 | ① 顶栏右侧那行 flex **没加 `margin-left:auto`**（预览稿组件层里本来就有 `.top-right`，我的模板没用上）→ 整组控件紧跟在面包屑后面；② 顶栏底色第一版只混主色 9%，浅色主题下几乎看不出；③ 品牌区是普通 `div`（文本可选中、无交互） | ① 右侧组加 `top-right`，实测控件右边缘距顶栏右边缘 24px（= 顶栏内边距）；② 改成 `--primary` 18%→7% 渐变 + 主色 46% 分隔线，实测六套主题 `backgroundImage`/`borderColor` 各不相同；③ 品牌区改成 `<button>` + `user-select:none`，点开「关于平台」（84px 校徽原图 + 7 条平台事实 + 素材来源说明） | 探针 `round2-check.mjs`；截图 `m5-v4-workbench.png`、`m5-v4-topbar-gingko.png`、`m5-v4-about.png`；机检 D3b8/D3b9/D3b10 |
| **D16** | **没有「上传文档」入口** —— 用户："文档只有新建、在网站上编写的方式" | 规划时把"文档"理解成只能站内撰写（PRD 非目标 O7 只排除了 Office 预览/转换，没排除"导入 Markdown"），于是漏了这个入口 | 新增 `ImportMarkdownButton.vue`：只收 `.md/.markdown/.txt`（≤512 KB），类型不对/文件过大/文件为空**都给中文原因**；编辑器「导入文件」填进表单，「我的文档」页「上传 Markdown」直接建草稿再跳编辑器 | 探针确认两个页面的按钮都在（`/my` → 上传 Markdown + 新建文档；`/docs/edit` → 取消 / 导入文件 / 保存草稿 / 提交审核）；机检 D3b12/D3b13 |
| D10 | 环境/工具链三类报错 | ① `ERR_PNPM_IGNORED_BUILDS`（pnpm 11 不读 `package.json` 的 `pnpm` 字段）→ 设置搬到 `pnpm-workspace.yaml` 的 `allowBuilds`；② pnpm 供应链策略拒绝 24h 内新发布的传递依赖 → 用 `overrides` 钉版本，**没有关策略**；③ Vite 监听临时目录 `EBUSY` → `server.watch.ignored` | 见左 | `pnpm install` / `pnpm dev` 均正常 |

**检查器自身 bug 累计 8 个（M4 前）+ 本轮 1 个（D3）**，另有 `verify-m5.ps1` 首轮自测暴露的 5 处写法问题（`-like` 的 `[` 通配、`.NET` 相对路径按进程目录解析、`Sort-Object -Unique` 把同路径不同方法误并、素材清单少拼一层目录、非 ASCII 锚点）——**均已修**，这也是 `verify-m5.ps1` 首轮 35/43 → 43/43 的过程记录。

---

## 4. 与冻结文档的偏差（都是"预览稿有、后端不认"，已按后端实现并回头改文档）

| # | 偏差 | 决策 | 文档去向 |
|---|---|---|---|
| 1 | 内容治理页的**「下架」按钮** | `doc:offline` 无端点 ⇒ **不渲染入口**（归档已覆盖"下架但可检索只读"） | 预览稿 **v8.3** 删除该按钮 + 说明；规格 §8.8 订正；§10.6 原decision不变 |
| 2 | 编辑页**「可见范围」三选一下拉** | 库里没有分级可见字段 ⇒ 改**只读文本**展示唯一行为（草稿仅本人 / 发布后全校），不做一个点了没反应的控件 | 预览稿 v8.3；规格 §8.5 订正 |
| 3 | 编辑页**「发文单位」** | 数据模型无此列（PRD §2.3），预览稿 v2 起已用「拟稿人（只读，取登录态）」 | 规格 §8.5 订正（此前只有预览稿改了） |
| 4 | 审核页**「审核意见」** | 后端 `@NotBlank` ⇒ **必填**（不是"可选意见"） | 规格 §8.7 订正 |
| 5 | 审核页**「本周已通过 / 已驳回」** | 后端无聚合接口 ⇒ 口径 = **本页待审文档近 7 天的 AUDIT/REJECT 留痕计数**，卡片副文案写明 | 规格 §8.7 订正 |
| 6 | 用户管理**「按角色筛选」** | `UserPageDtoReq` 只有 `keyword/deptId/status` ⇒ 不做该控件，页面明写原因 | 规格 §8.10 保留（页面内说明） |
| 7 | 分类树**「每类文档数」**、标签**「最近使用」「合并」** | `CategoryVo` 无文档数、`TagVo` 无最近使用；改名即等价合并 ⇒ 展示真实字段（子分类数 / `updatedAt`）+ 文案说明 | 页面内说明；规格 §8.9 保留 |
| 8 | 组织机构**「负责人 / 在编人数 / 文档量」** | `DeptVo` 无这些字段 ⇒ 不编造，改展示真实字段 + 成员数（仅直接挂在该单位的用户，文案写明不含下级） | 页面内说明；规格 §8.12 保留 |
| 9 | 治理页**「拟稿人筛选」候选** | 用户目录接口要 `sys:user`，文档管理员没有 ⇒ 候选来自**全量文档里出现过的作者**，界面写明 | 规格 §8.8 订正说明 |

> 处理原则：**一律照后端**（规格 I5：「文案用服务端 message，前端只按 code 决定怎么呈现」），
> 然后回头把预览稿与规格改到与实现一致 —— 不留"文档说 A、产品是 B"的暗坑。

---

## 5. 数据卫生

- `verify-edit-conflict.mjs` 会**真的修改**一篇文档（本轮用的是 `staff` 的草稿 #3：v1 → v3，并写 2 条版本留痕）与真实状态流转 —— 这是它的设计目的（假数据测不出 409）。
- 跑完已用 `backend/sql/schema.sql` + `data.sql` **重建演示库**，并复跑全部后端检查器（m0/m1/api-spec/m2/db-deep/m3/m3-http/m4/m4-http）确认无残留影响：
  权限 39 / 角色 3 / 部门 3 / 用户 3 / 分类 4 / 标签 5 / 文档 5 / 版本 9 / 收藏 3 / 标签关联 5。
- 该脚本的副作用写在脚本头部注释里，跑之前请知悉。

---

## 6. 遗留（M6 及以后）

1. **前端没有自动化测试**：目前是"静态机检 + 端到端脚本 + 截图人眼"三件套；没有引入 Vitest / Playwright（属于范围外，若要补建议放在 M6 走查之后）。
2. **`doc:upload` 的粘贴/拖拽**：实现了入口与"未完成上传禁止保存"，但**没有对超大文件/非图片的后端拒绝路径做专门视觉**（后端 400 中文文案会显示在上传占位符与重试按钮上）。
3. **`sync-preview` 与 `check-classes` 需要手动跑**：没有接进 CI（本项目 PRD §8 O8 明确不做 CI）。
4. **通知面板是静态示意**（后端没有通知接口，页面已写明）；身份菜单「切换账号」= 退出后重登（与预览稿 v8.1 一致）。
5. **`frontend/dist/` 不入库**（`.gitignore` 已挡）；桌面镜像只放文档与操作卡，不放构建产物。

---

## 7. 语料扩充：5 篇 → 45 篇（2026-09-24，用户要求"多爬点文档好校验搜索"）

**做了什么**：`backend/sql/data.sql` 的示例文档从 **5 篇扩到 45 篇**（新增 40 篇，id 6~45），配套 20 个标签、109 条标签关联、99 条版本留痕、39 条收藏关系；仍是**一个脚本**（`schema.sql` + `data.sql` 两步重灌，不新增执行步骤）。

**口径（与项目既有约定一致，必须写清）**：
- **机构名取自河北师范大学真实机构**（教务处 / 科技处 / 人事处 / 研究生院 / 国有资产管理委员会办公室 / 安全工作处 / 信息化中心 / 图书馆 / 档案馆 / 各学院……），**题材取自官网通知类型**（实验室安全、实习支教、国家自然科学基金申报、岗前培训、学位论文送审、招标采购、消防应急演练、课程思政、档案归档……）；
- **人名、工号、联系电话全部虚构**，不对应任何真实人员；正文为**教学用撰文**，不是官网原文的复制。
- ⚠️ 原本想直接抓官网通知正文，**没有做成**：官网通知列表页是 JS 渲染 + 反爬（拉到的 HTML 里没有任何通知标题），且当时加速器开着导致 DNS 是 fake-IP（`www.hebtu.edu.cn → 198.18.1.33`），官方 `web_fetch` 的 SSRF 守卫直接拒绝；单篇详情页能用 PowerShell 抓（HTTP 200），但没有可靠入口枚举全部链接。**所以语料是按题材人工写的，不是爬下来的** —— 别在答辩里说"爬了官网"。

**为什么值得做**：原来的 5 篇里"关键词≥2 字才走全文检索"这条分支几乎无从验证（命中数不是 0 就是 1）。现在语料按关键词分布设计，能真验检索能力：

| 关键词 | 正文命中 | 标题命中 | 关键词 | 正文命中 | 标题命中 |
|---|---|---|---|---|---|
| 实验室安全 | 14 | 11 | 招标 / 采购 | 8 | 3 |
| 实习支教 | 9 | 8 | 毕业设计 / 学位论文 | 7 | 5 |
| 国家自然科学基金 | 7 | 6 | 奖学金 / 助学金 | 6 | 2 |
| 新入职教师 / 岗前培训 | 7 | 4 | 消防 / 应急演练 | 5 | 3 |
| 档案 / 归档 | 19 | 2 | 课程思政 | 5 | 2 |

另含 20 篇"关键词只在正文"（验高亮窗口）与 3 篇"关键词只在标题"（验 `matchedIn=title`）的刻意样本，2 篇 `content_md` 为空（验空正文四态）。

**实测（接口级，脚本 `D:\dsh-workspace\out\search-check.mjs`，可重跑）**：

```
实验室安全   total=7   matchedIn={content:1,title:3,summary:1}  高亮片段 5/5
实习支教     total=8   matchedIn={title:5}
课程思政     total=5   matchedIn={content:2,title:2,summary:1}
一个不存在的词 total=0
分页 pageNum=3&pageSize=10 → total=28 page=3 rows=8 ；pageNum=99 → rows=0（不报错）
分类筛选 categoryId=1 → 18 条（含子类） ；categoryId=3 → 7 条
标签筛选 tagIds=4 → 6 条 ；tagIds=4,2（AND）→ 0 条
单字关键词「安」→ 200（按设计回落到标题/摘要 LIKE，不是 400）
```

**回归结果（重灌库后全量）**：m0 13 / m1 15 / api-spec 24 / **m2 17**（新增 3 项计数一致性断言）/ db-deep 9 / m3 36 / **m3-http 142** / m4 30 / **m4-http 222** / **m5 51** / JUnit 6 / 预览稿 211 / 前端四命令 —— **全绿**。
关键结论：**45 篇下 §10.5 的 SQL 条数预算一条没变**（检索仍是 3~5 条、我的 3 条、回收站 1 条、标签/分类树/统计各 1 条）—— 说明那套预算是"按查询形状"而不是"按数据量"定的，这正是当初的设计意图。

**顺带修掉的两个工具缺陷**：
1. `verify-m4-http.ps1` 的「重名标签可重建」用例会把 `m4tag2-<时间戳>` **留在演示库里**（用户会在标签筛选里看到这枚垃圾标签）→ 补一句清理断言 `cleanup.tag.delete.http`；
2. 后端若用 `mvnw spring-boot:run` 直接起（不重定向输出），`D:\DevEnv\logs\campusswap-app.log` 不会更新 → `verify-m4-http.ps1 -AppLog` 的 17 项 SQL 计数断言**全部读成 actual [0]**（一次全红，但其实是"没日志"而不是"预算破了"）→ 桌面启动器的后端窗口改成 `campusswap-backend.cmd`：用 `Tee-Object` 同时写窗口与日志文件。