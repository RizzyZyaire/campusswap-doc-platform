# CampusSwap · 文档管理平台

单位内部 **Markdown 文档管理平台**（课程大作业）。核心闭环：撰写文档 → 分类与标签 → 快速检索 → **基于已有文档派生新文档** → 版本管理 → 文档管理员审核/归档 → 全程 RBAC 控权。

> 📘 **先读这份**：**[docs/MASTER-PLAN.md](docs/MASTER-PLAN.md)** —— 大作业执行手册（已冻结）：技术栈、硬约束、14 张表数据模型、M0~M7 执行计划（**61 个可勾选任务**）、Prompt 库、交付与 Git 规范。
> 🔍 **质量证据**：`docs/03-qa-review/` 下有 4 个可重跑的机检脚本（`verify-m0/m1/m2/api-spec.ps1`）+ `EXPLAIN-NOTES.md`（执行计划实测）+ `AUDIT-M0-M2.md`（复核与变异测试记录）。

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3（`<script setup lang="ts">`）+ TypeScript(strict) + Vite + Tailwind CSS + Pinia + Axios |
| 后端 | Java 17 + Spring Boot 4.1.1 + Spring Data JPA（Hibernate，Boot 托管）+ Jakarta Validation + Lombok + Hutool |
| 数据 | MySQL 8（InnoDB / `utf8mb4_unicode_ci`，库 `campusswap_db`）+ Redis 5 |

## 目录结构

```text
campusswap/
├── docs/                          # 全部文档
│   ├── MASTER-PLAN.md             # ★ 执行手册（总纲）
│   ├── 01-requirements/           # USER_STORIES.md · PRD.md · GLOSSARY.md（字段级唯一真源）
│   ├── 02-design/                 # ARCHITECTURE.md · API_SPECIFICATION.md · UI_UX_SPECIFICATION.md
│   ├── 03-qa-review/              # 机检脚本 · EXPLAIN-NOTES.md · AUDIT-M0-M2.md · GIT-CHEATSHEET.md
│   └── 04-prompts/                # 阶段指令卡片（留档）
├── backend/                       # Spring Boot 工程（com.campusswap）
│   ├── sql/                       # schema.sql（14 张表）· data.sql（种子）· perf-fixture.sql（压测夹具）
│   └── src/main/java/com/campusswap/
│       ├── common/                # api（ResponseResult/PageVo/ErrorCode）· exception · security · util
│       ├── config/                # JpaAuditConfig · WebMvcConfig · RedisConfig · CorsConfig
│       ├── entity/                # BaseEntity + 14 个实体 + enums/（顶层集中，对齐课件）
│       ├── system/                # 模块一：系统与权限（controller/service/repository/dto/vo）
│       └── document/              # 模块二：文档业务（controller/service/repository/dto/vo）
└── frontend/                      # Vue 3 工程
    └── src/{api,types,components,views,stores,router,utils}
```

## 项目进度

- [x] 项目初始化：目录骨架、`.gitignore`、执行手册
- [x] **M0** 需求冻结：`docs/01-requirements/` 三剑客（8 故事 / 24 条 BDD / 39 权限点 / 14 表 / 24 条业务规则）
- [x] **M1** 设计定稿：架构、接口规格（55 条接口）、UI/UX 规范（8 页 + 四态）
- [x] **M2** 数据库：`backend/sql/schema.sql`（14 张表 + 20 索引）+ `data.sql` 种子数据
- [ ] **M3** 后端骨架 + RBAC（用户/角色/权限/部门）
- [ ] **M4** 文档业务（CRUD/版本/检索/派生/审核/回收站）
- [ ] **M5** 前端（8 个页面）
- [ ] **M6** 测试与代码审查（含零 N+1 与索引回归）
- [ ] **M7** 交付与上传

## 本地开发环境

| 项 | 版本/说明 |
|---|---|
| JDK | 17（编译目标；本机另有 21） |
| 构建 | Maven Wrapper（`./mvnw`），依赖走阿里云镜像，本地仓库在 `D:\DevEnv\05_Maven\repository` |
| 数据库 | MySQL 8.0.46，库 **`campusswap_db`**，端口 3306；应用账号 **`campusswap_dev`**（最小权限，不用 root） |
| 缓存 | Redis 5.0.14，端口 6379 |
| 后端 | dev 端口 **10087**（prod 10180），context-path `/`（接口形如 `http://localhost:10087/api/...`） |
| 前端 | Vite dev 端口 **5173**（`/api` 与 `/uploads` 代理到 10087），包管理 pnpm |

```bash
# 数据库初始化（本机已执行完毕；换机器时按序执行）
mysql -uroot -p < backend/sql/schema.sql      # 建库 + 14 张表 + 20 个索引
mysql -uroot -p < backend/sql/data.sql        # 种子数据（39 权限点 / 3 角色 / 3 账号）

# 后端（M3 完成后可用）
cd backend && ./mvnw clean compile test
./mvnw spring-boot:run

# 前端（M5 完成后可用）
cd frontend && pnpm install && pnpm dev
pnpm run typecheck && pnpm run lint

# 质量机检（可随时重跑）
powershell -File docs/03-qa-review/verify-m0.ps1            # 需求冻结 13 项
powershell -File docs/03-qa-review/verify-m1.ps1            # 设计冻结 15 项
powershell -File docs/03-qa-review/verify-api-spec.ps1      # 接口契约 24 项
$env:MYSQL_ROOT_PASSWORD='<密码>'; powershell -File docs/03-qa-review/verify-m2.ps1   # 数据库 17 项
#   注意：这个变量是 **root** 的口令（旧名 DB_PASSWORD 仍兼容，但别混用 —— 它同时被
#   application-dev/prod.yml 当作 campusswap_dev 的口令读取，混用会让应用/测试连不上库）
```

**内置演示账号**（`data.sql` 灌入，bcrypt 真哈希）：`admin/Admin@123`（SYS_ADMIN）、`docadmin/Doc@123456`（DOC_ADMIN）、`staff/Staff@123`（STAFF）。

## 规范要点（摘要，完整见执行手册 §2）

1. 字段命名只认 `docs/01-requirements/GLOSSARY.md` 与 `backend/sql/schema.sql`，禁止自造字段
2. 金额一律**整数分**（`priceCents` / `price_cents`），禁止 `float`/`double`
3. Controller 入参必须 `@Valid` + 中文错误提示；**出参必须是 VO**，实体不穿透前端
4. 写操作必须在 Service 层做**属主校验**（防越权/IDOR）
5. **写 ID、读关联**：写入只用 `xxxId` / 显式中间表；查询导航用**只读** `@ManyToOne(fetch = LAZY)` / 只读 `@ManyToMany`，禁 `EAGER`；不建数据库外键；树形用 `parentId + ancestors`
6. 列表接口**零 N+1**：DTO 投影 / `@EntityGraph` / `findAllById` 批量补名，SQL 条数预算见 `ARCHITECTURE.md` §10.5；列表禁读 `content_md`
7. 所有主键（`Long`）统一序列化为 `String`（防前端精度丢失）
8. 每个类写类注释（含 `@author`），每个 public 方法写 `@param` / `@return`

## 仓库说明

- 本仓库为**公开仓库**：不包含任何真实生产密钥。本地开发库口令通过环境变量注入（`${DB_USERNAME:campusswap_dev}` / `${DB_PASSWORD:...}`），且 `campusswap_dev` 仅对 `campusswap_db` 有 CRUD 权限。
- 以下运行期/产物目录**不纳入版本控制**：`backend/target/`、`backend/logs/`、`backend/uploads/`、`frontend/node_modules/`、`frontend/dist/`、`.idea/`。
