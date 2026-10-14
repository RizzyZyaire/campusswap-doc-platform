# M13 收口记录：RabbitMQ 异步索引重建（课件 13.1）

> 收口日期：2026-10-14　|　机检：`verify-m13.ps1`（静态 23 + 中间件 4 + 接口 5）
> 关联：老师《作业说明》功能模块第 8 行「异步索引重建：提交任务 → **RabbitMQ 后台消费执行**」，扣分表「未集成 RabbitMQ **-10**」
> 设计：`ES-SEARCH-DESIGN.md` §14；接口：`API_SPECIFICATION` §3.13 / §4.9

---

## 1 里程碑范围与验收对照

| 课件 13.1 验收标准（DoD） | 状态 | 实测证据 |
|---|---|---|
| RabbitMQ 原生服务与管理控制台正常运行，能在 Web 页面看到交换机与队列拓扑 | ✅ | `http://localhost:15672` HTTP 200；`rabbitmqctl list_queues` 显示 `campusswap.search.rebuild` + `…rebuild.dlq`，`list_exchanges` 显示 `campusswap.search.exchange` + `…dlx` |
| 异步重建接口 **20ms 内**返回 `SUBMITTED` 与全局唯一 `taskId` | ✅ | 服务端 `submitCostMs` = **3 ms / 17 ms**（两次实测）；客户端总耗时含鉴权约 98 ms |
| 后台消费者异步拉取消息，重建后执行 `channel.basicAck` 手动确认 | ✅ | `@RabbitListener(ackMode = "MANUAL")`；实测 `SUBMITTED → RUNNING → SUCCEEDED`（28 篇 / 785~1767 ms）；单测断言 ack 参数 |
| 异常场景下消息进死信队列且主队列不堵塞 | ✅ | 停掉 ES 后提交：任务 `FAILED` + **DLQ 收到 1 条** + 主队列 `messages=0`（无原地重投死循环） |
| `mvn test` 全部通过 | ✅ | **40/40**（原 30 + 消费者 6 + 任务仓库 4），BUILD SUCCESS |

---

## 2 中间件安装（原生，Windows）

| 项 | 值 |
|---|---|
| Erlang/OTP | **27.3.4**（课件红线：26.2.x / 27.3.x；**严禁 OTP 29**）→ `D:\DevEnv\12_Erlang` |
| RabbitMQ | **4.0.9**（课件推荐 4.0.x / 3.13.x）→ `D:\DevEnv\13_RabbitMQ\rabbitmq_server-4.0.9` |
| 数据目录 | `RABBITMQ_BASE=D:\DevEnv\13_RabbitMQ\data`（**不落 C 盘**：默认会写 `%APPDATA%\RabbitMQ`） |
| 端口 | 5672（AMQP）/ 15672（管理控制台）/ 25672（节点间） |
| 账号 | `campusswap / CampusSwap@2026`（administrator，vhost `/`）；`guest/guest` 保留但仅本机可用 |
| 管理插件 | `rabbitmq-plugins.bat enable rabbitmq_management`（离线生效，需重启 broker） |
| 安装包归档 | `D:\DevEnv\06_Tools\安装包归档\{otp_win64_27.3.4.exe, rabbitmq-server-windows-4.0.9.zip}` |

**启动方式**：`sbin\rabbitmq-server.bat`（需 `ERLANG_HOME` 指向 Erlang 安装目录；zip 版不注册 Windows 服务）。
**冷启动实测**：`5672 + 15672` 全部就绪 **10.4 秒**。

---

## 3 交付物

| 层 | 文件 |
|---|---|
| 拓扑配置 | `config/MqConfig.java`（direct 交换机 + 业务队列 + 死信交换机/队列 + JSON 转换器 + RabbitTemplate） |
| 消息与任务 | `document/search/mq/{SearchRebuildMessage,RebuildTaskStatus,SearchRebuildTask,SearchRebuildTaskStore,SearchRebuildProducer,SearchRebuildConsumer}.java` |
| 服务与接口 | `DocumentSearchService#submitRebuildTask/getRebuildTask`；`SearchController` 的 `POST /api/search/rebuild`、`GET /api/search/rebuild/{taskId}`、`GET /api/search/rebuild/recent` |
| 配置 | `pom.xml`（`spring-boot-starter-amqp`）；`application-{dev,demo}.yml` + `application-test.yml`（rabbitmq + `acknowledge-mode: manual` + `missing-queues-fatal: false` + `prefetch: 1`） |
| 前端 | `api/search.ts`（`submitRebuild/getRebuildTask/getRecentRebuildTasks`）、`types/search.ts`（`SearchRebuildTask` / `RebuildTaskStatus`）、检索页「重建检索索引」改为提交 + 800ms 轮询并显示 `重建任务：执行中/已完成` |
| 测试 | `SearchRebuildMqTest`（ack / DLQ-nack / 异常 / 幂等 / 未知任务 / 锁占用 6 例）、`SearchRebuildTaskStoreTest`（真实 Redis 4 例） |
| 机检 | `verify-m13.ps1`（A 静态 23 / B 中间件 4 / C 接口 5） |

---

## 4 关键设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 消息体 | **只放 taskId**，状态以 Redis 为准 | 消息小、易投递；重复投递不会让状态错乱（幂等） |
| 任务状态存放 | **Redis**（JSON 字符串 + 2h TTL + 最近 20 条列表） | 短期运行痕迹，不需要事务；与项目既有口径一致（token/权限缓存/阅读去重都在 Redis）；不新增库表 |
| 失败处理 | 业务失败 → `basicNack(requeue=false)` → **DLQ** | 索引重建失败通常是"ES 挂了/映射冲突"这类需人工介入的问题，原地 requeue 会堵死队列并刷爆日志（课件 §6/§7 三道防线） |
| 并发控制 | Redis 串行锁（TTL 10 分钟）；拿不到锁 → `requeue=true` + 3 秒退避 | 全量重建会删索引重建，并发跑会互相打架；锁有 TTL 兜底不会死锁 |
| broker 不可用 | 提交接口返回 `status=FAILED` + 中文原因（**不抛 500**） | 老师机器上没起 MQ 时，界面能看到"消息队列不可用（5672 连接被拒）"，而不是一个无信息的错误页 |
| 应用启动 | 不依赖 broker（`missing-queues-fatal: false`） | 与 ES 同理：没装中间件也要能启动、能演示其余功能 |

---

## 5 实测缺陷与修法（先失败后修）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `MqConfig`/`SearchRebuildTaskStore` 编译失败：`程序包 com.fasterxml.jackson.databind 不存在` | **Spring Boot 4 用的是 Jackson 3**（`tools.jackson`）；Jackson 2 的 `jackson-databind:2.21.5` 在本工程只是 **runtime** 依赖，编译期不可见 | 改用 `tools.jackson.databind.json.JsonMapper`（Boot 4 暴露的正是该 bean），AMQP 转换器改 `JacksonJsonMessageConverter` |
| 2 | `JacksonJsonMessageConverter(ObjectMapper)` 编译失败 | 该转换器要求 `JsonMapper`（Jackson 3 的具体实现），不接受基类 `ObjectMapper` | 工厂方法参数改为 `JsonMapper` |
| 3 | `enable rabbitmq_management` 退出码 1、路径不对 | 我用了 PowerShell **只读自动变量 `$home`** 存 RabbitMQ 目录（`AGENTS.local.md` 明令禁止），赋值静默失败 → 命令指向了 `C:\Users\44922` | 变量改名为 `$mqHome`（这是本项目第三次踩 `$home`） |
| 4 | ES 刚恢复后立刻提交任务 → `FAILED`（ES 未连接） | 检索引擎可用性探测有 **10 秒缓存**，ES 启动中的那次探测把状态记成了"不可用" | 属预期行为（探针缓存 + 快速降级），已在设计文档写明；录屏时先确认 `GET /api/search/status` 为 `elasticsearch` 再提交 |
| 5 | 失败任务的消息进 DLQ 后，DLQ 长期留 1 条 | 设计如此（保留现场待人工处理） | 提供 `rabbitmqctl purge_queue campusswap.search.rebuild.dlq` 清理；控制台也可点 Purge |

---

## 6 与课件口径的偏差

| 项 | 课件写法 | 实际 | 理由 |
|---|---|---|---|
| 队列/交换机命名 | `dochub.document.*` | `campusswap.search.*` | 本项目叫 CampusSwap（课件是 DocHub 案例）；语义一致 |
| 任务状态存储 | 课件未指定 | Redis（JSON + TTL） | 见 §4；若要审计可另接入 M12 的 `sys_operation_log` |
| `doc_file` 表 | 12.1 章（对象存储）要求 | **不做** | 老师《作业说明》未要求对象存储（MinIO 不做），12.1 与本次交付无关 |
| 死信队列名 | `dochub.document.dlq` | `campusswap.search.rebuild.dlq` | 同命名偏差；机检 A4/B2 断言真实队名 |

---

## 7 给老师手册的反馈

1. **Windows 原生安装要设 `RABBITMQ_BASE`**：否则 mnesia 数据与日志落在 `%APPDATA%\RabbitMQ`（C 盘），与"环境装在 D 盘"的约定冲突。
2. **zip 版不注册服务、不改注册表**：手册已提到 Erlang 要用 exe 安装（zip 版 RabbitMQ 会找不到），实测**Erlang 必须用 exe 安装器**（静默参数 `/S /D=目标目录`，`/D` 必须最后且不加引号），装完注册表出现 `HKLM\SOFTWARE\WOW6432Node\Ericsson\Erlang\<ERTS版本>`。
3. **管理插件是"离线生效"**：`enable` 后必须重启 broker（手册已写，实测确认输出 `Offline change; changes will take effect at broker restart.`）。
4. **Boot 4 + Spring AMQP 4 用 Jackson 3**：示例代码里 `Jackson2JsonMessageConverter` + `com.fasterxml` 的 ObjectMapper 在 Boot 4 工程里编译不过，应写 `JacksonJsonMessageConverter` + `tools.jackson`。
