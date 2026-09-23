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
> # 接口验收（218 项，含 17 项 SQL 条数预算）
> powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m4-http.ps1 -AppLog "$env:TEMP\campusswap-app.log"
> # 静态自检
> powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/verify-m4.ps1
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
| `verify-m4-http.ps1`（接口验收，含 17 项 SQL 预算） | **218** | 全绿（2026-09-23 实测 PASS=218 / FAIL=0） |
| `verify-m4.ps1`（静态自检） | **30** | 全绿 |
| `verify-m3-http.ps1`（复跑，AC-08.1/08.3 证据 + §8b 自助改密） | **142** | 全绿（2026-09-23 实测 PASS=142 / FAIL=0） |
| `verify-m0/m1/api-spec/m2/db-deep`（回归复跑） | 13 / 15 / 24 / **17** / 9 | 全绿 |
| `ui-preview.smoke.mjs`（预览稿自检，非产品机检） | **161** | 全绿 |

> **2026-09-23 新增覆盖（M5 前置三项变更）**：`verify-m4-http.ps1` 的 **§US-04b**（全文检索：正文only 关键词、
> `matchedIn=content/title/summary`、`highlight` 的 `<em>` 包裹、布尔符号注入不报错、`sort=relevance` 行为、
> 1 字关键词回落 LIKE）与 **§US-07b**（治理全状态列表：全状态/回收站软删行可见、检索接口看不见该行、
> 关键词与拟稿人/分类/时间筛选、staff 403、docadmin 200）；`verify-m3-http.ps1` 的 **§8b**（自助改密：
> 旧密码错 400「原密码不正确」、强度四档 400 中文提示、成功后**两路旧 token 全 401**、旧密码登录 401、
> 新密码登录 200、跑完自动还原种子密码）；`verify-m2.ps1` **C13b**（`ft_doc_search` 存在且列序
> = `title,summary,content_md` 且带 ngram 解析器）。
>
> 全量合计：**产品机检 504 项**（原 420 → +84：m2 +1、m3-http +17、m4-http +66）+ 预览稿自检 161 项 = **665 项，全部 0 失败**。

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
