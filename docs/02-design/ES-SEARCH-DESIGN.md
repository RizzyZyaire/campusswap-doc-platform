# ES 全文检索设计（M8）

> 对应课件：`10.1 企业知识搜索：Elasticsearch 全文检索基础.md` + `10.1-安装手册-Elasticsearch 原生安装与 IK 分词器配置指南.md`
> 落地版本：**Elasticsearch 9.4.5 + analysis-ik 9.4.5**（版本选择理由见第 7 节，与课件的 8.11.3 不同，原因写在文档里）
> 机检脚本：`docs/03-qa-review/verify-m8.ps1`（静态 27 项 + ES 运行时 4 项 + 接口 7 项）

---

## 1 为什么引入 ES（课件 §1 的痛点，用本项目的口径复述）

现有检索有两条链路：关键词为空走 Criteria 动态条件；关键词 ≥2 字走 MySQL **ngram 全文索引** `ft_doc_search`（`MATCH ... AGAINST ... IN BOOLEAN MODE`）。它能用，但有三个迈不过去的坎：

| 痛点 | MySQL 现状 | ES 方案 |
|---|---|---|
| 词法切分 | ngram 按 **2 字滑窗**硬切，"高并发架构" 与 "并发架构" 无法语义对齐 | IK 词典分词（`ik_max_word` 入库 / `ik_smart` 查询） |
| 相关度 | 只能按更新时间/阅读量硬排 | Lucene 打分（TF-IDF/BM25）+ 字段权重 `title^3 / summary^2 / content` |
| 高亮与片段 | 后端要在 SQL 结果里手工拼片段 | 倒排索引能定位命中位置，片段截取更准（本项目仍在 Java 侧统一生成，见第 4 节） |

**ES 不是主库**：`campusswap_db` 仍是唯一真实数据源，ES 只是"可检索副本"。索引丢了可以随时全量重建（第 5 节），这也是双引擎降级成立的前提。

---

## 2 索引与映射（对应课件 §2、§4）

索引名 `campusswap_document`，由 `@Document(indexName = "campusswap_document")` 声明；映射由 Spring Data ES 按注解生成（**已实测**，见第 8 节映射原文）。

| 字段 | 类型 | 分词器 | 用途 |
|---|---|---|---|
| `id` | long（`@Id`） | — | 与 `doc_document.id` 强一致 |
| `title` | text | `analyzer=ik_max_word` / `search_analyzer=ik_smart` | 检索主字段，权重 **3** |
| `summary` | text | 同上 | 权重 **2** |
| `content` | text | 同上 | 权重 **1**（正文 Markdown 原文） |
| `categoryId` | long | — | 分类过滤 |
| `categoryName` | keyword | — | 展示/聚合（"未分类"占位） |
| `status` | keyword | — | **只索引 PUBLISHED**，同时作为 filter |
| `tags` | keyword（数组） | — | 标签展示与后续过滤 |
| `authorId` / `authorName` / `deptName` | long / keyword / keyword | — | 作者与部门展示 |
| `publishAt` / `updatedAt` | keyword | — | ISO-8601 字符串，字典序=时间序，可直接排序 |
| `viewCount` | integer | — | 热度排序 |

**为什么时间用 keyword 存 ISO 字符串**：Spring Data ES 6.1.1（Boot 4.1.1 管辖）配的 Java 客户端是 9.4.5，而日期字段的格式转换在"客户端大版本 ≠ 服务端大版本"时最容易出坑；ISO 字符串既能排序又不依赖日期序列化，属于刻意换取稳定性的工程取舍。

**为什么过滤字段一定要 Keyword**：实测教训——若 `status` 落成 `text`，`term` 查询拿 `PUBLISHED` 去比会被小写化后的词项 `published` 挡掉，结果**恒为 0 命中**（本项目在调试期差点误判成"索引没数据"）。

---

## 3 三种检索范围（用户明确要求：搜索前先选"在哪儿找"）

`SearchScope` 枚举 + 仓储里三条独立 JSON DSL（`@Query`），范围由前端分段按钮切换：

| 范围 | DSL | 语义 | 实测命中（关键词"支教"） |
|---|---|---|---|
| `TITLE` | `match: { title }` | 只查标题——找"标题就是这个主题"的文档 | 7 |
| `CONTENT` | `match: { content }` | 只查正文——召回最广 | 8 |
| `ALL`（默认） | `multi_match: [title^3, summary^2, content]` | 联合检索，标题命中排前 | 8 |

三条查询的 `filter` 上下文都固定为 `{ "term": { "status": "PUBLISHED" } }`：不参与打分、可走 Bitset 缓存，同时保证**草稿/归档/回收站文档永远搜不到**（课件的 DoD 之一）。

选中分类时走第四条 `searchAllFieldsInCategory`（`filter` 里多一个 `term: { categoryId }`，数值不加引号）。

---

## 4 关键词高亮（用户明确要求）

**实现位置：Java 侧统一做**（`SearchTextHighlighter`），不依赖 ES 的 `highlight` 参数。三条理由：

1. ES 返回的片段是"原文 + `<em>`"，直接交给前端就是 XSS 通道，转义逻辑一样要自己写；
2. 双引擎降级时（第 6 节）MySQL 链路也要高亮，两套引擎共用一份实现才不会出现"ES 下有高亮、降级后没有"；
3. 可以单元测试，不依赖 ES 是否在跑。

规则：
- **先转义再插标记**：`& < > " '` 全部转义，然后才插入 `<mark>`（前端 `v-html` 的白名单里只有这一个标签）；
- 关键词大小写不敏感；中文天然命中；
- 片段截取：命中位置前后各 48 字符，压掉换行，两端加省略号；正文里没有关键词时退回开头一段（说明命中的是标题或摘要）；
- 出参同时给 `matchedFields`（`title`/`summary`/`content`）与 `score`，界面显示"命中位置：标题、正文 · 相关度 4.83"。
- **命中时间字段**：`publishAt` 与 `updatedAt` 都由索引里的 ISO 字符串转成 `yyyy-MM-dd HH:mm:ss` 回给出参（只做 `T`→空格，**不新增查询**）；结果行的时间列显示 `updatedAt`，与"最近更新 ↓"排序同源。**2026-10-14 修复显示错位**：此前 `SearchHitVo` 没有 `updatedAt`，前端只能拿 `publishAt` 当时间列渲染，按更新时间排序时看起来像"乱序"（排序本身一直是对的，错的是时间列取错了字段）。

---

## 5 数据同步：全量重建 + 增量镜像

### 5.1 全量重建（课件 §5.2 的 `rebuildAllPublishedDocuments()`）

`POST /api/search/rebuild`（权限 `doc:manage`）→ `DocumentSearchServiceImpl#rebuildAll()`：

1. `deleteAll()` 清空索引（保证不残留已下架的历史文档）；
2. **游标分页**从主库读 PUBLISHED 文档（`WHERE id > :afterId ORDER BY id LIMIT 500`）——用一条原生 JOIN 一次拿齐标题/摘要/正文/分类名/作者名/部门名/时间/浏览量，避开 N+1（课件 3.1 红线二）与深分页（红线五）；
3. 标签一次性全量加载后在内存分组（避免逐篇查库）；
4. `saveAll()` 批量写入，最后 `count()` 自检。

**实测**：28 篇已发布文档 → 索引 28 篇，耗时 **550 ms**。

### 5.2 增量镜像

状态变更点全部通过 `DocumentServiceImpl#writeVersion(...)` 汇聚（发布/编辑/归档/驳回/重审/恢复/派生/新建）——**实测确认**：`ReviewServiceImpl` 的 `audit/reject/archive/republish` 也调用同一个方法，因此只需一处钩子；另外在"移入回收站"与"彻底删除"两处补钩子。

- 时机：`TxUtil.afterCommit(...)` —— 索引写入不属于事务；若在提交前同步，同步用的原生查询会读到尚未提交的旧状态（刚发布的文档仍是 DRAFT），出现"发布成功但搜不到"；
- 语义：`syncOne(id)` 是**镜像**而不是"写入"——查得到 PUBLISHED 就 upsert，查不到（草稿/归档/回收站/已删除）就 `deleteById`，一个方法覆盖所有状态；
- **失败绝不影响业务**：整段 try/catch，只打 WARN 日志；
- 循环依赖处理：`DocumentSearchService` 的降级引擎反向依赖 `DocumentService`，故 `DocumentServiceImpl` 用 `ObjectProvider<DocumentSearchService>` 延迟解析，避免构造注入成环。

---

## 6 双引擎降级（用户拍板的方案 A）

```
GET /api/search/documents
        │
        ├─ esAvailable()?（带 10 秒缓存的 count() 探测）
        │      ├─ 是 → 走 ES 三条 @Query → 命中带 score / 高亮
        │      └─ 否 / 查询抛异常 → markEsDown() → mysqlFallback()
        └─ 返回统一结构 SearchResultVo{ engine, engineMessage, scope, list[], total, pageNum, pageSize }
```

- `engine=elasticsearch`：`"Elasticsearch 引擎（IK 中文分词，相关度排序）"`；
- `engine=mysql`：`"Elasticsearch 未连接，已降级为 MySQL 引擎（无相关度评分；高亮基于标题与摘要）"`，复用既有 `DocumentService.search`，`score=null`；
- ES 地址与**短超时**（`connection-timeout: 2s` / `socket-timeout: 3s`）写在 `application-dev.yml` 与 `application-demo.yml`：ES 没启动时要在 2 秒内降级，而不是卡在默认 30 秒；
- 前端徽标如实显示"这次是谁在检索"，不做静默降级。

**为什么必须降级**：提交包里**不带 ES**（解压后 950 MB），老师/同学解压后没有 ES 也要能正常演示检索；装了 ES 的机器自动升级为 ES 引擎。

---

## 7 版本选择：为什么不是课件写的 8.11.3

| 环节 | 课件/手册 | 本项目实际 | 原因 |
|---|---|---|---|
| ES 服务端 | 8.11.3（手册）/ 学生下载 8.18.0 | **9.4.5** | 见下 |
| IK 分词器 | `elasticsearch-analysis-ik-8.11.3.zip`（GitHub releases） | **`analysis-ik 9.4.5`**（`get.infini.cloud`） | 官方已停止在 GitHub 发安装包（其 release 页写明 Breaking Change，旧链接 404）；改为 `bin\elasticsearch-plugin.bat install -b https://get.infini.cloud/elasticsearch/analysis-ik/<版本>` |
| Java 客户端 | `spring-boot-starter-data-elasticsearch`（Boot 3.x → 5.x 客户端） | Spring Data ES **6.1.1** + Java 客户端 **9.4.5** | Spring Boot **4.1.1** 的依赖管理锁定的版本，不可选 |

**决定性实测**（这也是 8.x 服务端被换掉的唯一原因）：

```
POST http://127.0.0.1:9200/_search       Accept: application/vnd.elasticsearch+json;compatible-with=9  →  HTTP 400
GET  http://127.0.0.1:9200/_cluster/health  Accept: ...;compatible-with=8                                  →  HTTP 200
```

9.x 客户端默认带 `compatible-with=9` 的版本头，**8.18 服务端直接 400 拒绝**（启动时的 `indices.exists` 就炸，报 `Expecting a response body, but none was sent`）。所以服务端必须 ≥9.x；本项目选与客户端**完全同号**的 9.4.5，IK 也取同号。

**⚠️ 给老师的反馈点**：手册里"下载 IK 的 GitHub 链接"已失效、"插件 CLI 必须加 `-b`"（否则非交互环境卡在权限确认上直接退出，exit 70）、"若用 Spring Boot 4.x 则服务端需 9.x"这三条，建议补进下一版手册。

其他安装配置（与手册一致）：纯英文路径 `D:\DevEnv\11_Elasticsearch\elasticsearch-9.4.5`、`cluster.name=campusswap-es-cluster`、`node.name=node-1`、`network.host=127.0.0.1`、`http.port=9200`、`discovery.type=single-node`、`xpack.security.enabled=false`（教学用，生产必须开启）、堆 `-Xms1g -Xmx1g`；启动时**必须钉 `ES_JAVA_HOME`** 指向 ES 自带 JDK（自带 26，系统 `JAVA_HOME` 是 21，不钉会用错）。

---

## 8 实测证据

| 项 | 结果 |
|---|---|
| `GET /` | `node-1` / `campusswap-es-cluster` / **9.4.5** / lucene 10.4.0，`status=green` |
| `GET /_cat/plugins?v` | `node-1  analysis-ik  9.4.5` |
| `_analyze` `ik_smart`："高并发系统架构实战" | 高 / 并发 / 系统 / 架构 / 实战 |
| `_analyze` `ik_max_word`："CampusSwap企业级高并发知识库" | campusswap / 企业级 / 企业 / 级 / 高 / 并发 / 知识库 / 知识 / 库 |
| `GET /campusswap_document/_mapping` | `title/summary/content` = `text` + `analyzer: ik_max_word` + `search_analyzer: ik_smart`；`status` = `keyword`（已核原文） |
| `POST /api/search/rebuild` | success=true，indexed=**28**，indexCount=**28**，耗时 **550 ms** |
| `GET /api/search/status` | engine=elasticsearch，available=true，indexCount=28，ikAnalyzerReady=true |
| 范围差异（关键词"支教"） | TITLE=7 / CONTENT=8 / ALL=8（三种范围确实是三条不同查询） |
| 关键词"教师" | TITLE=2 / CONTENT=17 / ALL=17 |
| 高亮 | 标题与片段均由 `<mark>` 包裹（例：实习`<mark>`支教`</mark>`安全管理与应急处置预案） |
| 相关度排序 | 标题+摘要+正文全命中 4.834 > 标题+正文 4.662 > 仅正文命中 3.956（标题权重生效） |
| 降级 | 停掉 ES 后同一接口返回 `engine=mysql`、`score=null`、高亮仍可用（见 M8-CLOSURE 记录） |
| 机检 | `verify-m8.ps1` 全绿（静态 + ES 运行时 + 接口三组） |

---

## 9 接口与权限

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/search/documents` | `doc:search` | 全文检索：`keyword`（必填）、`scope`（TITLE/CONTENT/ALL）、`categoryId`、`sort`（`relevance` 默认 / updatedAt_desc / publishAt_desc / viewCount_desc）、`pageNum`、`pageSize` |
| GET | `/api/search/status` | `doc:search` | 引擎状态：`elasticsearchAvailable` / `engine` / `indexCount` / `ikAnalyzerReady` / `endpoint` |
| POST | `/api/search/rebuild` | `doc:manage` | 全量重建索引，返回 `success/indexed/indexCount/elapsedMs/message` |

非法 `scope` 由 DTO 的 `@Pattern` 拦成 **400**（实测 `scope=NOPE` → 400）。

---

## 10 前端（`DocumentListView.vue`）

- 工具栏下方新增**检索范围分段按钮**（标题 / 正文 / 标题+摘要+正文），切换即重查；
- 有关键词且**未选标签**时走 ES 链路；无关键词或选了标签时走既有链路（标签是 AND 命中，由 MySQL 引擎处理），徽标如实说明；
- 标题用 `titleHighlight`、片段用 `snippet` 渲染（`v-html`，只白名单 `<mark>`）；同时显示"命中位置 + 相关度"；
- 页头给 `doc:manage` 角色一个**"重建检索索引"**按钮，一键把主库同步进 ES 并刷新状态；
- 引擎徽标：`检索引擎：Elasticsearch（IK 分词）` / `MySQL`，旁边显示索引文档数。

---

## 11 与既有 MySQL 检索的关系（非目标声明）

- **不删除** MySQL ngram 全文索引与 `MATCH ... AGAINST` 分支：它是降级通道，也是 `ft_doc_search` 索引存在的意义；
- 标签筛选、回收站、我的文档等**治理类**列表继续走 Criteria/原生 SQL，不进 ES；
- 权限体系不因 ES 变化：`/api/search/*` 依然是"拦截器 → ThreadLocal → `@RequiresPermission`"四道关卡，ES 索引里不含任何草稿/归档文档，天然不越权。

---

## 12 关键词为空时

`keyword` 为空返回空列表 + 提示（`请输入关键词后再检索`），`engine` 字段仍如实回填——避免"空关键词把 28 篇全捞出来"这种反直觉行为。

---

## 13 没有 ES 的机器也必须能跑（2026-10-11 补测后加固）

初版有个致命假设错误：以为"Spring Data ES 只是惰性连接"。**实测推翻了它**——把 ES 目录改名藏起来后启动应用，直接崩：

```
Error creating bean with name 'documentSearchRepository'
  → BeanInstantiationException: Failed to instantiate [SimpleElasticsearchRepository]
  → DataAccessResourceFailureException: Connect to http://127.0.0.1:9200 failed: Connection refused
```

**根因**：Spring Data ES 的仓储在**应用启动时就被实例化**，而 `SimpleElasticsearchRepository` 的构造器会立刻连集群检查/创建索引。也就是说"没装 ES"= 应用起不来，双引擎降级形同虚设——而提交包**不带 ES**，这等于把风险直接甩给老师。

**加固措施（三处）**：

| 措施 | 说明 |
|---|---|
| 仓储改为普通 `@Repository` 类 | `DocumentSearchRepository` 不再 `extends ElasticsearchRepository`，改为注入 `ElasticsearchOperations` 的普通 Bean（构造期零网络请求）。这也是**本项目唯一一处刻意偏离课件写法**的地方：`@EnableElasticsearchRepositories` 在 Spring Data ES 6.1.1 里没有 `bootstrapMode` 属性，无法声明惰性引导；且注解式 `@Query` 是静态 DSL，装不下"范围 × 可选分类过滤"的动态组合 |
| 索引改为**首次用到 ES 时显式创建** | 实体上是 `@Document(..., createIndex = false)`，建索引在 `ensureIndex()` 里用 `IndexOperations.createWithMapping()` 完成（映射仍来自 `@Field` 注解，含 IK 分词器），并用 `indexReady` 短路避免每次检索都往返一次 `exists` |
| 写入后**显式 refresh** | ES 默认 1 秒才刷成可搜索段，而 `ElasticsearchOperations#save` 不带刷新策略 → 实测"重建报 indexed=28 但 `_count=0`、紧接着检索 0 命中"。`refresh()` 后读写立即一致（本项目量级下开销可忽略） |

**补测结果**：

| 场景 | 结果 |
|---|---|
| ES 目录完全不存在（改名模拟）+ 9200 空闲 | 应用 **12.2 秒正常启动**；`/api/search/status` → `engine=mysql`；三种范围检索照常返回结果并高亮；`POST /api/search/rebuild` 返回 `success=false` + 中文说明（不是 500） |
| ES 恢复后 | 首次检索/重建即自动建索引（映射含 IK），重建 28 篇 / 922 ms，`_count=28` 立即一致；范围 7 / 8 / 8 与标题权重 4.83 全部复现 |
| 启动脚本（包内） | 先探 9200 → 再找本机安装（`ES_HOME` 优先，其次 `C:`/包所在盘 的 `Environment\` / `elasticsearch-*` / `DevEnv\11_Elasticsearch\` / `Program Files\Elasticsearch`），都找不到就打印"search will use MySQL"并继续；**端口已被占用时一律不重复启动、也不误判为"没有 ES"** |
## 15. 组合查询条件（M16 增补，2026-10-14）

老师《作业说明》要求"关键词搜索 + **组合查询条件筛选** + 结果高亮"，本节补齐筛选维度：

| 条件 | 实现 | 说明 |
|---|---|---|
| 关键词 | `match` / `multi_match`（`title^3, summary^2, content`） | 已有（M8） |
| 检索范围 | `TITLE` / `CONTENT` / `ALL` | 已有（M8） |
| 分类 | `term` on `categoryId`（filter 上下文） | 已有（M8） |
| **时间范围** | `range` on `publishAt`（term-range 变体） | 新增：支持 `yyyy-MM-dd`（自动补 00:00:00 / 23:59:59）或 `yyyy-MM-dd HH:mm:ss` |
| **标签** | `term` on `tags`（keyword 多值） | 新增：精确命中任一标签 |

**踩到的真缺陷（实测）**：`publishAt` 以 ISO 字符串（`2026-09-15T15:40:00`）存在 keyword 字段里，字典序即时间序 —— 但查询边界若写成 `2030-01-01 00:00:00`（空格分隔），字典序比较会得出 `"2026-…T…" > "2030-… …"`（第 4 个字符 `6 > 0`），**过滤形同虚设**（反证断言"不可能区间必须 0 命中"当场抓出）。
修法：查询边界统一规范化为与索引相同的 ISO 格式（`replace(' ', 'T')`）。
更彻底的做法是把字段改成 `date` 类型，但那要重建映射（本项目重建索引会重建映射，后续可平滑切换）。

**验证**（`verify-m8.ps1` C6~C10 + 人工实测）：仅关键词 8 条 → 2026 全年 8 → 2026-09 单月 **6** → 2030 不可能区间 **0** → 未来精确到秒 **0** → 分类 1 **1** → 不存在标签 **0**；降级链路（MySQL）同样支持时间范围。