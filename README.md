# CampusSwap · 文档管理平台

单位内部 **Markdown 文档管理平台**（课程大作业）。核心闭环：撰写文档 → 分类与标签 → 快速检索 → **基于已有文档派生新文档** → 版本管理 → 文档管理员审核/归档 → 全程 RBAC 控权。

> 📘 **先读这份**：**[docs/MASTER-PLAN.md](docs/MASTER-PLAN.md)** —— 大作业执行手册（已冻结版）：技术栈、硬约束、14 张表数据模型、M0~M7 执行计划（55 个可勾选任务）、Prompt 库、交付与 Git 规范。

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3（`<script setup lang="ts">`）+ TypeScript(strict) + Vite + Tailwind CSS + Pinia + Axios |
| 后端 | Java 17 + Spring Boot 4.1 + Spring Data JPA(Hibernate 6) + Jakarta Validation + Lombok + Hutool |
| 数据 | MySQL 8（InnoDB / `utf8mb4_unicode_ci`）+ Redis |

## 目录结构

```text
campusswap/
├── docs/                          # 全部文档
│   ├── MASTER-PLAN.md             # ★ 执行手册（总纲）
│   ├── 01-requirements/           # USER_STORIES.md · PRD.md · GLOSSARY.md
│   ├── 02-design/                 # ARCHITECTURE.md · API_SPECIFICATION.md · UI_UX_SPECIFICATION.md
│   ├── 03-qa-review/              # tasks.md · 测试清单 · 代码审查记录
│   └── 04-prompts/                # 阶段指令卡片（留档）
├── backend/                       # Spring Boot 工程（com.campusswap）
│   ├── sql/schema.sql             # 物理建表脚本（14 张表）
│   └── src/main/java/com/campusswap/{common,config,controller,service,repository,entity,dto,vo,util}
└── frontend/                      # Vue 3 工程
    └── src/{api,types,components,views,stores,router,utils}
```

## 项目进度

- [x] 项目初始化：目录骨架、`.gitignore`、执行手册
- [ ] **M0** 需求冻结：`docs/01-requirements/` 三剑客（USER_STORIES / PRD / GLOSSARY）
- [ ] **M1** 设计定稿：架构、接口规格、UI/UX 规范
- [ ] **M2** 数据库：`backend/sql/schema.sql`（14 张表）+ 种子数据
- [ ] **M3** 后端骨架 + RBAC（用户/角色/权限/部门）
- [ ] **M4** 文档业务（CRUD/版本/检索/派生/审核/回收站）
- [ ] **M5** 前端（8 个页面）
- [ ] **M6** 测试与代码审查
- [ ] **M7** 交付与上传

## 本地开发环境

| 项 | 版本/说明 |
|---|---|
| JDK | 17（编译目标；本机另有 21 可运行） |
| 构建 | Maven Wrapper（`./mvnw`） |
| 数据库 | MySQL 8，库名 `docs_db`，端口 3306 |
| 缓存 | Redis，端口 6379 |
| 后端端口 | dev `10086`，context-path `/backend` |
| 前端包管理 | pnpm |

```bash
# 后端（M3 完成后可用）
cd backend && ./mvnw clean compile test
./mvnw spring-boot:run

# 前端（M5 完成后可用）
cd frontend && pnpm install && pnpm dev
pnpm run typecheck && pnpm run lint
```

## 规范要点（摘要，完整见执行手册 §2）

1. 字段命名只认 `docs/01-requirements/GLOSSARY.md` 与 `backend/sql/schema.sql`，禁止自造字段
2. 金额一律**整数分**（`priceCents` / `price_cents`），禁止 `float`/`double`
3. Controller 入参必须 `@Valid` + 中文错误提示；**出参必须是 VO**，实体不穿透前端
4. 写操作必须在 Service 层做**属主校验**（防越权/IDOR）
5. 关联用**逻辑外键**（`Long xxxId`），**不建数据库外键**；树形用 `parentId + ancestors`
6. 所有雪花 ID 序列化为 `String`（防前端精度丢失）
7. 每个类写类注释（含 `@author`），每个 public 方法写 `@param` / `@return`

## 仓库说明

- 本仓库为**公开仓库**：**不包含任何密钥或真实密码**。数据库密码通过环境变量注入（`${DB_PASSWORD:}`）。
- 以下运行期/产物目录**不纳入版本控制**：`backend/target/`、`backend/logs/`、`backend/uploads/`、`frontend/node_modules/`、`frontend/dist/`、`.idea/`。
