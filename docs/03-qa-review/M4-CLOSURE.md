# M4 收口记录 —— 文档业务（US-02 ~ US-08）

> 日期：2026-09-22　｜　里程碑：M4（`MASTER-PLAN.md` §7）　｜　状态：**已完成并验收**
> 本文只写"实际跑出来的东西"：每条结论都有可重跑的机检脚本或原始 SQL/日志支撑。

---

## 1. 交付物清单

| 类别 | 内容 | 数量 |
|---|---|---|
| DTO | `document/dto/`：DocumentCreate/Update/Search/Mine/Trash/Audit/Reject/Derive/Destroy DtoReq、ReviewPage、FavoritePage、CategoryCreate/Update、TagCreate/Update、TagPage、DocumentSort | 17 |
| VO | `document/vo/`：DocumentVo、DocumentDetailVo、DocumentVersionVo、CategoryVo、TagVo、FavoriteVo、ImageVo、StatVo | 8 |
| 仓储 | `document/repository/` 新增：`DocumentColumns`（列清单 + 类型容错）、`DocumentListRow`（构造器投影行）、`DocumentListQuery`、`DocumentQueryRepository(+Impl)`（Criteria 定制片段）、`TagSpecifications`；扩展 `DocumentRepository`（回收站/收藏原生查询 + 恢复/彻底删除）、`DocumentVersionRepository`、`FavoriteRepository`、`TagRepository`、`CategoryRepository` | 11 |
| 服务 | `DocumentService(+Impl)`、`ReviewService(+Impl)`、`CategoryService(+Impl)`、`TagService(+Impl)`、`FileStorageService` | 9 |
| 接口 | `document/controller/`：Document(15)、Review(5)、Category(4)、Tag(4)、File(1)、Stat(1) | 6 类 / **30 端点** |
| 机检 | `docs/03-qa-review/verify-m4.ps1`（静态 30 项）、`verify-m4-http.ps1`（接口 152 项 + SQL 预算 10 项） | 2 |
| 清单 | `docs/03-qa-review/TEST_CHECKLIST.md`（US-02~US-08 共 21 条 BDD 断言逐条对账） | 1 |

---

## 2. 验收证据（全部可重跑）

| # | 检查 | 结果 |
|---|---|---|
| 1 | `mvnw -B clean compile` | `BUILD SUCCESS` |
| 2 | `verify-m4-http.ps1`（接口验收 + 状态机 + 越权） | **PASS=152 / FAIL=0** |
| 3 | `verify-m4.ps1`（静态自检） | **PASS=30 / FAIL=0** |
| 4 | `verify-m3-http.ps1`（回归，含 AC-08.1/08.3 证据） | **PASS=125 / FAIL=0** |
| 5 | `verify-m3.ps1` / `verify-m2.ps1` / `verify-db-deep.ps1` / `verify-m1.ps1` / `verify-m0.ps1`（回归） | 36 / 16 / 9 / 15 / 13，全绿 |
| 6 | `TEST_CHECKLIST.md` | 21/21 断言有通过记录 |

覆盖到的状态机边：`T2`（发布）、`T3/T6/T8`（进回收站）、`T5`（归档）、`T7`（恢复上架）、`T9`（回收站恢复）、`T10`（彻底删除 + 级联清理）、`T11`（驳回），以及"审核通过不改状态只留痕"。

---

## 3. SQL 条数实测（T4.11 零 N+1 验收）

方法：dev 开 `show-sql`，请求前记录日志行数，请求后统计窗口内 `Hibernate:` 语句条数（脚本 `verify-m4-http.ps1 -AppLog <路径>` 自动跑这 10 项）。

| 接口 | 实测 | 说明 |
|---|---|---|
| `GET /api/documents`（末页） | **3** | 主查询 + 作者名批量 + 分类名批量 |
| `GET /api/documents`（满页，`pageSize=1`） | **4** | 多一条分页 count（`PageableExecutionUtils` 只在满页时发） |
| `GET /api/documents?categoryId=1` | **5** | 再多一条「分类 + 子孙」查询 |
| `GET /api/documents/mine` | **3** | 同上三件套 |
| `GET /api/documents/trash` | **1** | 原生分页查询自带 count（末页跳过） |
| `GET /api/favorites` | **3** | 原生 JOIN 分页 + 作者名 + 分类名 |
| `GET /api/review/documents` | **3** | 与检索同一套 Criteria 投影 |
| `GET /api/categories/tree` | **1** | 一次查全 + 内存组树 |
| `GET /api/tags` | **1** | 分页 |
| `GET /api/stats/overview` | **1** | 三个计数在一条 SQL 里（子查询） |

**关键证据（课堂红线三）**：列表投影的实际 SQL 是

```sql
select d1_0.id, d1_0.title, d1_0.summary, d1_0.category_id, d1_0.created_by, d1_0.status,
       d1_0.version_num, d1_0.price_cents, d1_0.view_count, d1_0.favorite_count, ...
```

—— **没有 `content_md`**；条数只随"是否满页 / 是否带分类筛选"变化，**与 `pageSize`、数据量、标签数无关**，全是常数。

> 与文档口径的差异：`ARCHITECTURE §10.5` 原先写"检索列表 3 条"，实测为 **3~5 条**（含 count 与分类子孙查询）。
> 已按实测更新该表，并说明每一条的用途。

---

## 4. 本轮实测发现的缺陷（先失败 → 定位 → 修 → 复验）

### 缺陷 1：统计接口 500（`Object[]` 被 Spring Data 又包了一层）
- **现象**：`GET /api/stats/overview` → 500，日志 `NumberFormatException: For input string: "[Ljava.lang.Object;@..."`。
- **根因**：方法声明返回 `Object[]`，Spring Data 把"结果集"转成数组，于是 `row[0]` 是行数组本身而不是第一列。
- **修复**：返回 `List<Object[]>` 并取 `get(0)`；同时加注释说明这个坑。
- **复验**：`stats.http=200`、`stats.fields` 通过、SQL=1。

### 缺陷 2：回收站恢复 409（版本号撞唯一键）
- **现象**：删除 → 恢复，恢复返回 409（`DataIntegrityViolationException`）。
- **根因**：删除时写了 `versionNum = 当前+1` 的 `DELETE` 快照，但 `moveToTrash` 没有把主表 `version_num` 加一；恢复时 `version_num+1` 得到同一个数字，写 `RESTORE` 快照撞 `uk_doc_version(document_id, version_num)`。
- **修复**：`moveToTrash` 的 SQL 一并 `version_num = version_num + 1`，让"每次动作都递增版本"这条不变式真正成立。
- **复验**：删除→409 幂等→恢复 200（`versionNum=3`），版本历史 `1:CREATE, 2:DELETE, 3:RESTORE`。

### 缺陷 3：重复删除返回 404 而不是约定的 409
- **根因**：回收站文档 `deleted = 1`，实体查询看不到 → 走了"不存在"分支。
- **修复**：`delete()` 在实体查不到时回查原生回收站行，命中即 409「文档已在回收站中」。
- **复验**：`US07.delete-again.http=409`。

### 缺陷 4：恢复/彻底删除对"不在回收站"的文档返回 404 而不是 409
- **修复**：抽出 `requireTrashRow(id, conflictMsg)`：回收站里有 → 返回；库里存在但不在回收站 → 409；彻底没有 → 404。
- **复验**：`US07.restore-not-in-trash.http=409`、`US07.destroy-not-in-trash.http=409`。

### 缺陷 5：删除/归档/发布接口对回收站文档的语义不对
- **问题**：`publish` / `derive` / `update` 对回收站文档都给 404，而约定分别是「回收站文档需先恢复为草稿」409（AC-03.3）、「回收站文档不可派生」409（AC-05.3）、「文档已在回收站中」409。
- **修复**：三处都改成"实体查不到 → 回查回收站行 → 409"；顺带把 `detail()` 改成：回收站文档对**作者/管理员**仍可见（§4.6.5 的 TRASH 可见性），对其他人 403。
- **复验**：`US03.publish-trashed.http=409`、`US03.derive-trashed.http=409`、`US07.detail-trashed-owner.http=200`。

### 缺陷 6：一次瞬时 401（未能稳定复现，已加固）
- **现象**：同一个 token 前后请求都是 200，中间一次请求返回 401「登录状态已失效」。
- **排查**：Redis 里该 token 键随后仍存在（`login:token:<token> → userId`），说明不是登出/踢人逻辑所致；怀疑 Redis 客户端瞬时读失败（Lettuce 连接抖动）被当成"token 不存在"。
- **加固**：拦截器把「Redis 抛异常」与「token 真的不存在」分开处理 —— 前者 500「登录状态校验失败，请稍后重试」，后者才 401；并把 null 命中的日志从 DEBUG 提到 WARN（只记 token 前 8 位，便于下次定位）。
- **复验**：正常路径全部 200；无 token → 401；异常路径无法人为复现，已在本文登记为"观察到的现象 + 未证实根因"。

### 附带：检查器自身 3 个口径 bug（M4 新增代码暴露的）
1. `verify-m3.ps1` C10 把新增的 `DocumentQueryRepository`（Spring Data **片段接口**，不继承 `JpaRepository`）也算作仓储 → 计数 15≠14；改为只统计真 JPA 仓储，并把片段单独列出来。
2. `verify-m3.ps1` / `verify-m4.ps1` 的"禁 `JOIN FETCH`"规则太粗：`Page<` 与**单条** to-one `join fetch d.category`（文档明确推荐）共存也报错；改为只禁**集合型** `JOIN FETCH`（逐条解析 `join fetch <路径>`，允许 `.category`）。
3. `verify-m4.ps1` 的端点正则漏掉**裸注解** `@GetMapping` / `@PostMapping`（Category / Tag 控制器各一处）→ 计数 27≠30；改为路径可选、缺省用类级前缀。

> 累计：M0~M4 检查器自身 bug/口径问题 **8 个**（M0~M2 期间 5 个 + M4 期间 3 个）。每次都是"检查器报错 → 先怀疑代码 → 定位到检查器 → 修检查器并写注释说明为什么"。

---

## 5. 文档同步

| 文档 | 位置 | 修正 |
|---|---|---|
| `GLOSSARY.md` | §3.7 分页查询入参 | 补登记 `DocumentMineDtoReq` / `DocumentTrashDtoReq`（原表只有 5 个 `*PageDtoReq`，实际用到 8 个），并说明 `ReviewPageDtoReq.status` 取值域 |
| `ARCHITECTURE.md` | §10.5 | 检索列表预算 3 → **3~5**（按实测，逐条说明 count / 分类子孙 / 批量补名）；其余接口补实测值 |
| `MASTER-PLAN.md` | §7 M4 | 11 个任务全部打勾 + 补实做细节；M4 标题标记"已完成"；新增验证命令 |
| `MASTER-PLAN.md` | 附录 D | 新增 v2.4 变更行 |
| `TEST_CHECKLIST.md` | 新建 | US-02~US-08 的 21 条断言逐条对账 + 状态机覆盖表 |

---

## 6. 与冻结文档的偏差（诚实清单）

| 偏差 | 文档口径 | 实际实现 | 影响 |
|---|---|---|---|
| 检索列表 SQL 预算 | §10.5 写 3 条 | 3~5 条（含分页 count、分类子孙查询） | 已按实测更新文档；仍是"常数"，不随数据量增长 |
| 回收站文档的 `canEdit` | 文档未定义 | 回收站详情返回 `canEdit=true`（作者确实可恢复后编辑），`favorited=false` | 前端以 `status=TRASH` 控制按钮即可 |
| 派生时的标签 | 文档只说"继承分类 + 预填正文" | 不复制源文档标签 | 与文档一致，登记以防误解 |
| 标签 `use_count` 的并发 | 文档未定义 | 增减走 `@Modifying` 原子 SQL（批量 IN），并发下不丢更新 | 无 |
| 图片上传类型 | BR-15 允许 gif | 白名单含 gif；MIME 必须与扩展名一致 | 比文档更严 |
| 审核/归档对回收站文档 | 错误表只列 404 | 回收站文档 → 404（不在回收站 → 404 也符合"不存在"语义） | 无 |

---

## 7. 顺延 / 未做项

| 项 | 原因 | 去向 |
|---|---|---|
| 前端 8 个页面 | 属 M5 | M5 |
| 导出 / 全文检索 / 协同编辑 | PRD §9 显式非目标 | 不做 |
| 阅读量定时回写 | 采用 Redis SETNX + 即时原子自增（ARCHITECTURE §7），无需回写 | 已收敛 |
| `doc_offline`（强制下线）接口 | 权限点已存在但 PRD 未列接口 | 与文档一致，不实现 |

---

## 8. 复现步骤（从零到验收）

```powershell
# 0. MySQL80 / Redis 服务已启动（D:\DevEnv\scripts\start-all.cmd）
cd D:\DevEnv\projects\campusswap\backend
$env:JAVA_HOME='D:\DevEnv\02_JDK\jdk-17.0.5'

# 1. 编译 + 静态自检
.\mvnw.cmd -B clean compile
powershell -NoProfile -ExecutionPolicy Bypass -File ..\docs\03-qa-review\verify-m4.ps1

# 2. 起服务（另开窗口；日志写 %TEMP% 避免占用 target）
.\mvnw.cmd spring-boot:run

# 3. 接口验收（152 项 + SQL 预算 10 项）
powershell -NoProfile -ExecutionPolicy Bypass -File ..\docs\03-qa-review\verify-m4-http.ps1 -AppLog "$env:TEMP\campusswap-app.log"
```

> 验收会写入测试数据（文档 / 分类 / 标签 / 上传的图片）。收口后已用 `schema.sql` + `data.sql` 重建 `campusswap_db`，
> `verify-m2.ps1`（16）与 `verify-db-deep.ps1`（9）复绿；`backend/uploads/` 下的验收图片已清理。
