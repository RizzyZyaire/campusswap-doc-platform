# M5 前置 · 三项后端变更收口记录（M5PREP-CLOSURE）

| 项 | 值 |
|---|---|
| 范围 | ① 治理用全状态列表接口 ② 登录用户自助改密接口 ③ 文档全文检索（ngram）分支与出参 |
| 日期 | 2026-09-23 |
| 状态 | **实现已完成，验收进行中**（本文档在验收过程中逐节回填，未完成的格子写"待回填"，不写估计值） |
| 上游契约 | `API_SPECIFICATION.md §9.1/§9.2/§9.3`、`UI_UX_SPECIFICATION.md §10.1/§10.2/§10.4` |
| 机检 | 覆盖分散在既有里程碑检查器里（不新开第 10 个脚本，避免同一契约两处维护）：全文检索与出参 → `verify-m4-http.ps1` §US-04b；治理全状态列表 → 同脚本 §US-07b；自助改密 → `verify-m3-http.ps1` §8b；索引与种子形状 → `verify-m2.ps1` C12/C13b、`verify-db-deep.ps1` D6b |

---

## 1 三项变更的实现落点（供答辩时定位）

| 变更 | 关键文件 |
|---|---|
| 治理全状态列表（端点编号 **57**） | `DocumentManageDtoReq`（入参，状态/关键词/分类/拟稿人/时间/排序）｜`DocumentManageQuery`（仓储查询记录）｜`DocumentQueryRepositoryImpl#searchManage`（原生 SQL，`status` 空 = 全状态、`TRASH` = `(status='TRASH' OR deleted=1)`）｜`DocumentService#manage` / `DocumentServiceImpl#manage`｜`DocumentController#manage`（`@RequiresPermission("doc:manage")`） |
| 自助改密（端点编号 **56**） | `PasswordChangeDtoReq`（`oldPassword` + `newPassword`，8–32 位含字母数字）｜`AuthController#changePassword`（`PUT /api/auth/password`，无权限点）｜`AuthServiceImpl#changePassword`（BCrypt 校验 → 改哈希 → `TxUtil.afterCommit` 撤全部 token） |
| 全文检索分支 | `DocumentFullTextQuery`（`supports` / `terms` / `expression`：剥离布尔符号、`+词*`、1 字词丢弃）｜`DocumentSearchRow`（投影行 + highlight + matchedIn）｜`DocumentQueryRepositoryImpl#searchFullText` / `#dataSql` / `#highlight` / `#orderBy`｜`DocumentServiceImpl#search`（≥2 字走全文，否则原 Criteria 分支不变）｜`DocumentSort#RELEVANCE`｜`DocumentVo.highlight` / `.matchedIn`｜`schema.sql` 加 `FULLTEXT KEY ft_doc_search (title, summary, content_md) WITH PARSER ngram` |

## 2 独立验证证据（我自己发请求复现，不采信实现者的自述）

只读探针（不改变任何数据），服务 = `mvnw spring-boot:run`，端口 10087，账号 = 库内演示账号：

| # | 探针 | 实测结果 |
|---|---|---|
| 1 | `GET /api/documents/manage?pageSize=100`（admin） | **200**，`total=14`，状态集合含 `DRAFT/PUBLISHED/ARCHIVED/TRASH` |
| 2 | 同上 | 出参含 `highlight` / `matchedIn` 两个键；全部行 `canEdit=false` |
| 3 | `?status=TRASH`（admin） | **200**，`total=1`（回收站行 `deleted=1`，证明绕过了 `@SQLRestriction`） |
| 4 | `?status=BOGUS` | **400**，message = 「文档状态取值非法」 |
| 5 | `manage`（staff / 无 token） | **403** / **401** |
| 6 | `GET /api/documents?keyword=递归` | **200**，`total=1`，`matchedIn=content`，`highlight` 含 `<em>递归</em>`（片段 68 字，30 字上下文） |
| 7 | `keyword=分页` | **200**，`total=1`，`matchedIn=summary` |
| 8 | `keyword=JPA` | **200**，`total=1`，`matchedIn=title` |
| 9 | `keyword=页`（1 字） | **200**，`total=1` 且 `matchedIn` 全为 `null` → 走的是 LIKE 回落分支，不是全文分支 |
| 10 | `?sort=relevance`（无关键词） | **400**，message = 「排序方式 relevance 仅在关键词检索（2个字及以上）时可用」 |
| 11 | `?keyword=分页&sort=relevance` | **200**，`total=1` |
| 12 | `PUT /api/auth/password` 旧密码错 / 新密码弱 / 新密码空 / 无 token | **400**（原密码不正确）/ **400**（新密码需8到32位且同时包含字母和数字）/ **400** / **401**；且失败后原密码仍可登录（无副作用） |

写路径（改密成功后全部会话失效）与 SQL 预算由 `verify-m5prep-http.ps1` 覆盖，结果见 §5 回填。

## 3 本轮发现并修掉的文档-实现偏差（"文档说 A、代码做 B"清单）

| # | 偏差 | 处置 |
|---|---|---|
| 1 | `GLOSSARY.md` 未登记新出参 `highlight` / `matchedIn`，也没有 `DocumentManageDtoReq` / `PasswordChangeDtoReq` / `sort=relevance` —— 违反红线 R1「新字段先登记再写代码」 | GLOSSARY 升 **v2.2**（§3.6 关键词口径 + `relevance`、§3.7 四个登记项、§7 TS 类型、§8 自检行 + 变更记录） |
| 2 | API 文档 §9.2 写「`staff`/`docadmin` 调用 403」，但 `doc:manage` 由 **DOC_ADMIN 与 SYS_ADMIN 两个角色**持有（查 `sys_role_permission` 实测） | 改为「`staff` 403；`docadmin`/`admin` 均可调用」，并写清持有角色 |
| 3 | API 文档 §4.7.6 写 `keyword` 是「标题 / 摘要模糊匹配」、`startTime/endTime` 按「更新时间」过滤 | 关键词口径改成全文检索（≥2 字含正文 + 高亮）；时间列纠正为 **`created_at`（创建时间）**，与 GLOSSARY §3.6 及实现一致 |
| 4 | 检索接口 §4.6.1 的 `keyword` / `sort` 两行、§2 公共查询字段表、§2.7.3 `DocumentVo` 字段表仍是旧口径 | 三处全部同步（含 `relevance` 与出参 `highlight`/`matchedIn`） |
| 5 | OpenAPI 里检索与治理两个端点的 `keyword` 描述写「标题/摘要模糊匹配」、`sort` 描述缺 `relevance` | `patch-openapi-fts.ps1` 定点替换（各 2 处，带 count 断言），重解析 OK（paths=42 / schemas=54），桌面 Apifox 副本 SHA256 同步一致 |
| 6 | 预览稿三处版本号不一致（标题 v3 / 文件头 v4 / 预览条 v2），且两处「接口缺口 / 待办」标注在接口落地后变成假信息 | 预览稿升 **v5**：缺口标注改「已就位（编号 57 / 56）」、检索页体现正文命中高亮与「相关度」排序、筛选「更新时间」纠正为「创建时间」、评审抽屉删掉「提交单位」行（下一行已有「拟稿人」）、数据层删掉 `unit` 字段；版本号统一并加自检断言（161 项全绿） |
| 7 | 检查器自身的盲区：`verify-m1.ps1` C14 的登记清单是**硬编码 41 项**，新增的 5 个类型 + `MatchedIn` 没被纳入 → 红线 R1 实际处于无人看守状态 | C14 清单扩到 47 项（`registered=47/47`），属**加强**而非放宽 |

> 检查器口径修正累计：8（M4 结束时的计数）→ 本轮 0 个"口径 bug"、1 个"盲区"（#7，已加强）。另外 `verify-m4-http.ps1` 原先有一处**旧种子依赖**（`tag.update.duplicate` 拿 `SpringBoot` 当重名探针），随 `data.sql` 校园口径重种子化一并修掉：改成自己新建第二个标签 `m4tagB{时间戳}` 做重名探针（不依赖任何种子文案）。同轮还给 `Count-Sql` 加了"日志读数稳定后才计数"的等待（实测曾把上一批 SQL 重复计入，出现 `sql.manage-trash = 12` 而非 3 的假象）。

## 4 `data.sql` 校园口径重种子化

形状（表数 / 行数）与旧种子逐项一致，只换口径，所以靠行数做的断言不需要改：

| 维度 | 新口径 |
|---|---|
| 单位（3，两级树） | 信息化中心 ← 网络运行科；软件学院（网络教育学院） |
| 分类（4，含 1 子类） | 教务教学 / 党政公文 / 科研学术；实习支教（子类，挂在教务教学下） |
| 标签（5） | 国家自然科学基金 / 实习支教 / 课程思政 / 实验室安全 / 雄安新区（`use_count` = 1/1/1/2/0） |
| 账号（3） | 赵慕辰（SYS_ADMIN，信息化中心）/ 王砚秋（DOC_ADMIN，信息化中心）/ 李承霖（STAFF，软件学院）—— 用户名与 BCrypt 哈希不变，邮箱改 `@hebtu.edu.cn` |
| 文档（5，覆盖四状态） | 支教办法（PUBLISHED）/ 本科毕业论文规定（PUBLISHED）/ 实验室安全检查通知（DRAFT）/ 国基金结题通知（ARCHIVED）/ 实验室安全专项检查（TRASH） |
| 版本留痕（9） | 标题随文档改写，类型集合不变 |

**校验方式**：先导入 scratch 库 `campusswap_seedcheck`（`schema.sql` + `data.sql` 全量执行）零报错，再核对 10 组行数；实库重灌后另跑 `verify-m2.ps1` / `verify-db-deep.ps1` 复绿（结果见 §5）。

## 5 验收状态（2026-09-23 收口，全部为我自己跑出来的输出）

服务以 `mvnw spring-boot:run "-Dspring-boot.run.arguments=--spring.jpa.hibernate.ddl-auto=validate"` 起在 10087
（进程 pid 33784，`Started CampusSwapApplication in 5.123 seconds`），**`validate` 通过 = 实体↔重种子后的实库逐列对齐**。

| 检查器 | 项数 | 结果 |
|---|---|---|
| `verify-m0.ps1` | 13 | ALL GREEN |
| `verify-m1.ps1` | 15 | ALL GREEN |
| `verify-api-spec.ps1` | 24 | ALL GREEN（总表 57 行逐条对账） |
| `verify-m2.ps1` | 17 | ALL GREEN（含新增 C13b：全文索引列序 + ngram） |
| `verify-db-deep.ps1` | 9 | ALL GREEN（D6b 含 `ft_doc_search`） |
| `verify-m3.ps1` | 36 | PASS=36 FAIL=0 |
| `verify-m3-http.ps1` | 142 | **PASS=142 FAIL=0**（含 §8b 自助改密） |
| `verify-m4.ps1` | 30 | PASS=30 FAIL=0 |
| `verify-m4-http.ps1 -AppLog` | 218 | **PASS=218 FAIL=0**（含 17 项 SQL 预算、§US-04b、§US-07b） |
| `ui-preview.smoke.mjs` | 161 | TOTAL pass=161 fail=0 |
| **合计** | **产品 504 + 预览 161 = 665** | **0 失败** |

重种子后的实库实测（`reseed-campus.ps1` + 复核 SQL，`ANALYZE TABLE doc_document` 之后）：

```
rows   dept=3 cat=4 tag=5 usr=3 doc=5 ver=9 perm=39 role=3 deptRole=4 fav=3 tagRel=5
trash  status_trash=1 deleted1=1 both=1          <-- 种子缺陷已修：status 与 deleted 同时置位
fts    title_summary_like=0 content_like=1 boolean_match=1 published_match=1 jianbao_published=1
index  ft=1 ft_cols=title,summary,content_md
EXPLAIN  type=fulltext  key=ft_doc_search  Extra=Using where; Ft_hints: no_ranking
```

提交与推送：**已提交并推送（`8e3c34f`，2026-09-23 16:45，38 文件 / +1655 −144；远端 `origin/main` 与本机 HEAD 逐字一致，工作区干净）**。

### 5.1 收口期修掉的两个真问题（都有实测依据）

1. **种子 TRASH 行只写了 `status='TRASH'` 没写 `deleted=1`**（实现者报告 → 我核对 `data.sql` 的 INSERT 列表确认没有 `deleted` 列 → 修：列清单补 `deleted`，五行分别给 0/0/0/0/1）。
   后果链：回收站接口按 `deleted = 1` 查 → 回收站恒空、该行无法 restore/destroy；治理接口用的是 `(status='TRASH' OR deleted=1)` 宽松口径才看得见它。
   **连带发现（更值钱）**：回收站为空会让 `DocumentServiceImpl#buildPage` 早返回，于是 `GET /api/documents/trash` 的 SQL 条数实测是 **1**；
   修完种子、回收站有行后同一请求变成 **3**（+作者名批量 +分类名批量）。原先登记在 `ARCHITECTURE §10.5` 与 `M4-CLOSURE` 的「回收站 = 1」是**假象**，
   已改成 **1~3**（空 1 / 有行 3 / 满页 +1），`verify-m4-http.ps1` 的 `Count-Sql 'trash'` 期望值同步改为 3 并写明原因。
2. **我自己那份临时检查器 `verify-m5prep-http.ps1` 有两条错误期望**（写完后我判断与既有检查器重复而删除，但删除前被实现者执行过一次，暴露了它们）：
   ① `A.login-with-old-password` 期望 400，实际 **401**（登录失败按防用户名枚举契约恒为 401，`verify-m3-http.ps1` 早有 401 断言）；
   ② `C2.draft-list.contains-own-draft` 断言草稿在 DRAFT 列表里，但脚本前面已经把它发布了 —— **是我脚本的步骤顺序写错**，实现者独立复现证明接口行为正确。
   两条都是脚本 bug、不是实现缺陷；这也是我删掉那份脚本的另一个理由（同一契约不该有两处口径）。

### 5.2 实现期踩到的两个坑（实现者报告 + 我在日志里看到过同源现象）

1. **Hibernate 原生查询按语句校验命名参数**：count 语句里没有 `LOCATE(:tN)`，若把 `:tN` 一并绑到 count 上会抛
   `UnknownParameterException: No parameter named ':t0'` → 接口 500。修法：数据查询与 count 查询**各用一个绑定器**
   （`executeNativePage(dataBinder, countBinder)`）。这是"一条 SQL 拆两条、参数集不同"时最容易漏的点。
2. **SQL 条数点数不能靠固定 sleep**：日志经 PowerShell 重定向写出时可能半刷新，固定 600ms 会读到**上一批**语句
   （实测出现过 `sql.manage-trash = 12` 而非 3 的假象，正是 §5.1 第 1 条的同一个回收站预算项）。
   修法：`Count-Sql` 改成"连续两次读数一致才计数"（最多等 15×400ms）。
