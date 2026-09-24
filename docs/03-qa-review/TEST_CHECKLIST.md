# M4 验收清单 —— US-02 ~ US-08 逐条 BDD 断言对账

> 里程碑：M4 文档业务　｜　日期：2026-09-22　｜　**21/21 断言全部有通过记录**
>
> **2026-09-23 复跑（M5 前置三项变更 + 校园口径重种子化之后）**：本表结论不变，脚本项数已增长，
> 新覆盖见下方「汇总」表脚注与 `M5PREP-CLOSURE.md`。
>
> 执行方式（可重跑）：
> ```powershell
> # 起服务（dev 10087；重种子后建议带 validate 起一次，验证实体↔实库逐列对齐）
> cd backend; $env:JAVA_HOME='D:\DevEnv\02_JDK\jdk-17.0.5'
> .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.jpa.hibernate.ddl-auto=validate"
> # 接口验收（221 项，含 17 项 SQL 条数预算 + 3 项陈旧表单断言）
> powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m4-http.ps1 -AppLog D:\DevEnv\logs\campusswap-app.log
> # 静态自检
> powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m4.ps1
> # 白盒测试（4 类 6 用例；需 MySQL + Redis 在跑，且**不要**在环境里留 DB_PASSWORD，见 COURSEWARE-CLOSURE §3.6）
> cd backend; .\mvnw.cmd test
> ```
> 表中「证据」列写的是 `脚本.检查名`，全部可在脚本里检索到。

| 断言 | 断言要点（摘自 USER_STORIES.md） | 证据（脚本.检查名） | 结果 |
|---|---|---|---|
| AC-02.1 | 建草稿：`DRAFT`、`versionNum=1`、`createdBy=当前用户`、`priceCents=0` | `m4.US02.create.http/status/versionNum/authorIsCreator/priceCents/tags.count` | 通过 |
| AC-02.2 | 标题空/超 128 → 400 中文提示，且**库中无新增** | `m4.US02.empty-title.http`、`US02.title-129.http`、`US02.no-insert-after-400.total=0` | 通过 |
| AC-02.3 | 无有效 token → 401，不产生数据 | `m4.US02.no-token.http=401` | 通过 |
| AC-03.1 | 属主发布：`PUBLISHED`、版本 1→2、`doc_version` 新增、`updatedAt` 刷新 | `m4.US03.publish.http/status/versionNum`、`US03.versions.total=2`、`US03.versions.newest-first=PUBLISH` | 通过 |
| AC-03.2 | 已发布再发布 → 409 | `m4.US03.republish.http=409` | 通过 |
| AC-03.3 | `TRASH` 文档发布 → 409 | `m4.US03.publish-trashed.http=409` | 通过 |
| AC-04.1 | 关键词检索：200 + 分页数据 + `authorName`，默认 `updated_at` 倒序 | `m4.US04.search.http/total/has-authorName`、`US04.search.no-contentMd` | 通过 |
| AC-04.2 | 无命中 → 200 + 空列表 + `total=0`（不得 404/500） | `m4.US04.search-no-hit.http=200`、`US04.search-no-hit.total=0` | 通过 |
| AC-04.3 | 不包含他人 `DRAFT` | `m4.US04.search-excludes-others-draft.total=0`、`US04.detail-others-draft.http=403` | 通过 |
| AC-05.1 | 派生：新 `DRAFT`、`derivedFromId=D`、正文预填、标题「原标题（副本）」、`versionNum=1`、**源文档不变** | `m4.US05.derive.http/status/derivedFromId/title-copy/versionNum/content-prefilled`、`US05.derive.source-version-unchanged` | 通过 |
| AC-05.2 | 他人 `DRAFT` 派生 → 403 | `m4.US05.derive-invisible-source.http=403` | 通过 |
| AC-05.3 | `TRASH` 文档派生 → 409 | `m4.US03.derive-trashed.http=409` | 通过 |
| AC-06.1 | 属主编辑：版本 2→3、`updatedBy/updatedAt` 刷新、新增版本记录 | `m4.US06.edit.http/versionNum=3/priceCents/tags.count`、`US06.version-record-added=3`、`US06.version.newest-is-edit=EDIT` | 通过 |
| AC-06.2 | 非属主编辑 → 403 且**内容完全不变** | `m4.US06.edit-others-doc.http=403`、`US06.content-unchanged-after-403` | 通过 |
| AC-06.3 | `ARCHIVED` 编辑 → 409 | `m4.US06.archive.status=ARCHIVED`、`US06.edit-archived.http=409` | 通过 |
| AC-07.1 | 归档写意见与操作人（`changeType=ARCHIVE`），属主可见意见 | `m4.US07.archive-remark-in-version`、`US07.versions.operator-name` | 通过 |
| AC-07.2 | `STAFF` 调审核 → 403 | `m4.US07.review-list.staff.http=403`、`US07.audit.staff.http=403` | 通过 |
| AC-07.3 | 驳回无理由 → 400「驳回理由不能为空」 | `m4.US07.reject-reason-blank.http=400`、`US07.audit.remark-blank.http=400` | 通过 |
| AC-08.1 | 角色授权后权限缓存立即失效、按新权限生效 | `m3-http.grant-effect.*`（**verify-m3-http.ps1**：加权限后旧 token 立即 403→200，撤销后 200→403） | 通过 |
| AC-08.2 | 非 `SYS_ADMIN` 调授权 → 403 | `m4.US08.staff-grant.http=403` | 通过 |
| AC-08.3 | 权限节点移到自身子孙 → 400 | `m3-http.perm.move-menu-under-itself.http=400`（**verify-m3-http.ps1**） | 通过 |

## 汇总

| 脚本 | 项数 | 结果 |
|---|---|---|
| `verify-m4-http.ps1`（接口验收，含 17 项 SQL 预算 + 3 项陈旧表单断言） | **221** | 全绿（2026-09-23 晚实测 PASS=221 / FAIL=0） |
| `verify-m4.ps1`（静态自检） | **30** | 全绿 |
| `verify-m3-http.ps1`（复跑，AC-08.1/08.3 证据 + §8b 自助改密） | **142** | 全绿（2026-09-23 实测 PASS=142 / FAIL=0） |
| `verify-m0/m1/api-spec/m2/db-deep`（回归复跑） | 13 / 15 / 24 / **17** / 9 | 全绿 |
| `mvnw test`（JUnit 白盒，课件 4.1/5.1 的 DoD 项，非产品机检） | **4 类 / 6 用例** | 全绿（2026-09-23 实测 Tests run: 6, Failures: 0, Errors: 0） |
| `ui-preview.smoke.mjs`（预览稿自检，非产品机检） | **211** | 全绿（v8.2 契约登记后 +2） |

> **2026-09-23 新增覆盖（M5 前置三项变更）**：`verify-m4-http.ps1` 的 **§US-04b**（全文检索：正文only 关键词、
> `matchedIn=content/title/summary`、`highlight` 的 `<em>` 包裹、布尔符号注入不报错、`sort=relevance` 行为、
> 1 字关键词回落 LIKE）与 **§US-07b**（治理全状态列表：全状态/回收站软删行可见、检索接口看不见该行、
> 关键词与拟稿人/分类/时间筛选、staff 403、docadmin 200）；`verify-m3-http.ps1` 的 **§8b**（自助改密：
> 旧密码错 400「原密码不正确」、强度四档 400 中文提示、成功后**两路旧 token 全 401**、旧密码登录 401、
> 新密码登录 200、跑完自动还原种子密码）；`verify-m2.ps1` **C13b**（`ft_doc_search` 存在且列序
> = `title,summary,content_md` 且带 ngram 解析器）。
>
> 全量合计：**产品机检 507 项**（504 → +3）+ **JUnit 白盒 6 用例** + 预览稿自检 **211** 项（200 → +2 后为 211）= **724 项，全部 0 失败**。

> **2026-09-23 晚间新增覆盖（课件 4.1 / 5.1 对齐，详见 `COURSEWARE-CLOSURE.md`）**：
>
> ① `verify-m4-http.ps1` 新增 **3 条陈旧表单断言**：过期 `versionNum` → 409、冲突后标题分毫不动、缺 `versionNum` → 400
> （6 处编辑调用同步补 `versionNum`，故脚本项数 218 → **221**）；
>
> ② 新增 **JUnit 白盒通道**（`mvnw test`，4 类 6 用例）：
> `ViewCountConcurrencyTest`（100 线程并发自增 = 恰好 +100，课件 4.1 DoD④）、
> `BulkUpdateStalenessTest`（同一事务"读→批量自增→再读"的对照实验，证明 `clearAutomatically` 不是装饰）、
> `TagBindingConsistencyTest`（证明 `flushAutomatically` 不是装饰，锁死本轮被机检抓到的"关联行被丢掉"缺陷）、
> `PasswordHashCompatTest`（官方 `BCryptPasswordEncoder` 能校验库里既有的 `$2b$10$` 哈希，且双向兼容）。
> 运行需要 MySQL 与 Redis 在跑；`src/test/resources/application-test.yml` 把连接池放大到 120 以保证真并发；
> **口令走专属变量** `CAMPUSSWAP_TEST_DB_USER` / `CAMPUSSWAP_TEST_DB_PASSWORD`（默认 `campusswap_dev` / 见配置），
> **不要**在同一个终端里 export `DB_PASSWORD` 再跑测试 —— 那个名字是**机检脚本要的 root 口令**，
> Spring 会把同名变量插进测试数据源，导致 6 个用例全报 `Access denied ... (using password: YES)`（2026-09-23 实测踩到，见 `COURSEWARE-CLOSURE.md §3.6`）；
>
> ③ 预览稿自检 200 → **211**：v8.2 增加"409 版本冲突提示与 toast"、"落地接口对照登记必填 `versionNum`"两条断言。

## 状态机覆盖（PRD §4.2 的 T1~T11 与本文件的 T11 登记）

| 边 | 触发接口 | 验证检查 |
|---|---|---|
| T2 `DRAFT→PUBLISHED` | `POST /documents/{id}/publish` | `US03.publish.status` |
| T3/T6/T8 `*→TRASH` | `DELETE /documents/{id}` | `US07.delete.http` |
| T5 `PUBLISHED→ARCHIVED` | `POST /documents/{id}/archive` | `US06.archive.status` |
| T7 `ARCHIVED→PUBLISHED` | `POST /documents/{id}/republish` | `US06.republish.status` |
| T9 `TRASH→DRAFT` | `POST /documents/{id}/restore` | `US07.restore.status` |
| T10 `TRASH→物理删除` | `DELETE /documents/{id}/destroy` | `US07.destroy.real.http` + 级联清理检查 |
| T11 `PUBLISHED→DRAFT`（驳回） | `POST /documents/{id}/reject` | `US07.reject.status` |
| 审核不改状态 | `POST /documents/{id}/audit` | `US07.audit.status-unchanged` |

---

# M5 验收清单 —— 前端 15 条路由逐页对账

> ⚠️ **跑检查器的顺序（2026-09-24 实测踩到）**：`verify-m2.ps1` / `verify-db-deep.ps1` 断言的是**种子数据的精确计数**
> （45 篇文档 / 99 条版本 / 20 个标签 / 109 条关联 / 39 条收藏），而两个 HTTP 检查器**会改库**
> （建/删文档、改用户状态与密码、增删标签）。所以正确顺序是：
> ① 重灌库（`schema.sql` + `data.sql`）→ ② 跑静态与 DB 检查器 → ③ 跑 HTTP 检查器 → ④ **要给人看就再重灌一次**。
> 顺序颠倒时 m2 少 1 项、db-deep 少 2 项、m3-http 少 3 项（不是预算破了，是数据被动过），会白白误导人。
> 另外：HTTP 检查器跑的时候 `staff` 的 token 会被重置密码/改状态踢掉 —— **别同时开浏览器截图**。

> 里程碑：M5 前端实现　｜　日期：2026-09-23　｜　机检入口 `verify-m5.ps1`（**51/51**）
>
> 前端没有单元测试框架（范围外），验收证据 = **①静态机检 ②产物类名体检 ③端到端脚本 ④截图人眼**。
> 截图全部在 `D:\DevEnv\logs\shots\m5-*.png`，重跑方式见 `M5-CLOSURE.md §2`。
> 「四态」列的 ✅ 表示该页实现了空 / 加载 / 错误 / 无权限四种态（§8.x 的文案已在代码里逐字落地）。

| # | 路由（§6.1） | 视图文件 | 权限 | 四态 | 关键交互验收点 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `/login` | `auth/LoginView.vue` | 公开 | ✅ | 校徽 + 校训 + 塔图背景 + 6 主题；错误密码给服务端中文文案 | `m5-login2.png` |
| 2 | `/workbench` | `workbench/WorkbenchView.vue` | 登录 | ✅ | 4 张统计（真接口 `GET /api/stats/overview`）、我的草稿、待我审核、常用分类、通知（静态，已注明无接口） | `m5-workbench.png` |
| 3 | `/docs` | `document/DocumentListView.vue` | `doc:search` | ✅ | 筛选同步 URL、关键词 300ms 防抖、`highlight` 高亮 + 命中位置、分页 | `m5-search.png`、`m5-search-hit.png` |
| 4 | `/docs/:id` | `document/DocumentDetailView.vue` | `doc:search` | ✅ | 面包屑、正文 Markdown 渲染（markdown-it + DOMPurify）、本文目录（H1 与标题重复时不上目录）、相关文档（同分类）、版本历史（**仅作者/`doc:manage`**，否则显示"仅作者可见"）、收藏/派生/归档/恢复上架/回收站操作 | `m5-detail.png`、`m5-detail3.png` |
| 5 | `/docs/edit/:id?` | `document/DocumentEditView.vue` | `doc:create` / `doc:edit`+`canEdit` | ✅ | 三态编辑器（编辑/双栏/预览）、图片三入口（工具栏/粘贴/拖拽）+ 失败"点击重试"+ 未完成上传禁止保存、未保存离开二次确认、**409 分两种处置**（状态冲突切只读 / 版本冲突横幅 +「刷新内容」不切只读）、400 字段级红字 + 聚焦 | `m5-edit3.png`、`m5-conflict-1-banner.png`、`m5-conflict-2-recovered.png`；脚本 `verify-edit-conflict.mjs` **10/10** |
| 6 | `/my` | `document/MyDocumentView.vue` | `doc:mine` | ✅ | 6 个 tab（按权限渲染收藏/回收站）、发布/恢复/删除、彻底删除需**输入标题**确认（BR-08） | `m5-my.png` |
| 7 | `/review` | `review/ReviewView.vue` | `doc:review` | ✅ | 三张统计（口径写明）、左列表 + 右抽屉、**版本并排逐行 diff**（自研 LCS，无第三方依赖）、通过意见必填、驳回理由必填 1–255（BR-12）、409 关抽屉重拉 | `m5-review.png` |
| 8 | `/governance` | `admin/GovernanceView.vue` | `doc:manage` | ✅ | 概览卡（各状态 count 查询）、状态/拟稿人/关键词/排序筛选、宽表 + 版本抽屉、移入回收站 → 彻底删除两步、**无「下架」入口**（§10.6） | `m5-governance2.png`、`m5-governance3.png` |
| 9 | `/taxonomy` | `admin/TaxonomyView.vue` | `doc:category` | ✅ | 左分类树（可折叠/缩进/选中）+ 右标签表（分页/查询）、分类与标签各自 CRUD、服务端 400/409 原文回显（"不能移动到子节点下"/"该分类下仍有文档"） | `m5-taxonomy2.png` |
| 10 | `/admin/users` | `admin/UserAdminView.vue` | `sys:user` | ✅ | 关键词/单位/状态筛选（**按角色筛选后端无入参，页面明写原因**）、新增/编辑（编辑不带 `username`/`password`）、状态切换提示"立即下线"、重置密码 | `m5-users.png` |
| 11 | `/admin/roles` | `admin/RoleAdminView.vue` | `sys:role` | ✅ | 左角色列表（内置不可删）+ 右三层权限树（**父节点半选**、全选/反选/撤销、覆盖式保存、保存时树置灰）+「权限点清单」tab（`sys:perm` 可增删改） | `m5-roles.png` |
| 12 | `/admin/org` | `admin/OrgAdminView.vue` | `sys:dept` | ✅ | 左机构树（折叠/缩进）+ 单位详情 + 绑定角色（覆盖式、`sys:role:grant`）+ 单位成员分页表；缺 `sys:role`/`sys:user` 时降级并说明 | `m5-org.png` |
| 13 | `/me` | `me/ProfileView.vue` | 登录 | ✅ | 账号信息、39 个权限点墙（持有高亮 + 权限码）、角色两个来源说明、自助改密 → 清会话回登录页 | `m5-me.png` |
| 14 | `/403` | `error/ForbiddenView.vue` | 公开 | 不适用 | 写明缺哪个权限点（`?perm=`）、三个出口（工作台/检索/我的资料） | 路由守卫日志 + 页面截图 |
| 15 | `/404` | `error/NotFoundView.vue` | 公开 | 不适用 | 空白布局 + 回工作台 | `m5-404.png` |

## 跨页面硬约束（机检项）

| 约束 | 机检项 | 结果 |
|---|---|---|
| 无 `any`、无内联 `style=` / `v-bind:style` | `verify-m5.ps1` D4a/D4b | 通过 |
| 15 个视图都真的实现（无占位桩） | D1a/D1b/D1c | 通过 |
| 路由 path/name/视图文件与 §6.1 逐字一致；`/docs/edit/:id?` 在 `/docs/:id` 之前 | D2a~D2d | 通过 |
| 6 套主题三处（theme.css / theme store / index.html）一致 | D3a~D3c | 通过 |
| 保存回传 `versionNum` + 冲突横幅 + 两种 409 分开 | D5a~D5e | 通过 |
| `doc:offline` 在前端**任何地方**都没有端点与入口 | D6a~D6c | 通过 |
| 39 个权限码与 `data.sql` 逐字一致、`PERM_LABEL` 全覆盖 | D7a~D7d | 通过 |
| token 键单一出处（`TOKEN_KEY`） | D8a/D8b | 通过 |
| 57 个后端端点全部被 `src/api` 覆盖 | D9a/D9b | 通过 |
| 生成物带"请勿手改"横幅、素材清单逐项存在、`main.css` 未被生成脚本覆盖 | D10a~D10f、D11a~D11d | 通过 |
| 模板里的类名都在产物 CSS 里存在（219 个） | `pnpm run check-classes` | 通过 |
| 顶栏就是预览稿那套可视化色卡选择器；无预览条遗留的 35px 偏移 | D3b5/D3b6（+ 几何探针 `sidebarTop=0`） | 通过 |
| `src/types` 无自造字段名（逐个回查 GLOSSARY） | D13 | 通过 |
