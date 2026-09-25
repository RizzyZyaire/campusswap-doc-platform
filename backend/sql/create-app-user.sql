-- =============================================================================
--  CampusSwap 应用数据库账号（最小权限）
--  依据: docs/MASTER-PLAN.md §9.3（仓库为 Public，真实密码只走环境变量；
--        这里的口令与 backend/src/main/resources/application-dev.yml 的
--        ${DB_PASSWORD:CampusSwap@2026} 默认值一致，属**本地开发演示口令**，
--        生产/交付环境必须用 -DbPassword 之外的方式另行设置，禁止沿用本文件的值）
--
--  为什么需要这个文件: backend/sql/schema.sql 只建库建表，**不建账号**；
--  换一台机器部署时如果只跑 schema.sql + data.sql，应用会以 campusswap_dev
--  连接失败（ERROR 1045）。本文件补上建号与授权这两步，可重复执行。
--
--  用法（root 身份执行一次即可）:
--    mysql -uroot -p < backend/sql/create-app-user.sql
--
--  权限口径（与 M2/M3 的验收一致）: 只给 CRUD，不给 DDL —— 应用永远不建表，
--  表结构只来自 schema.sql；这样即使应用被攻破也改不了表结构、删不了库。
-- =============================================================================

CREATE USER IF NOT EXISTS 'campusswap_dev'@'localhost' IDENTIFIED BY 'CampusSwap@2026';

-- 最小权限：仅 campusswap_db 的增删改查（无 CREATE / DROP / ALTER / GRANT）
GRANT SELECT, INSERT, UPDATE, DELETE ON `campusswap_db`.* TO 'campusswap_dev'@'localhost';

FLUSH PRIVILEGES;

-- 自检：应输出 USAGE + 4 个 CRUD 权限两行
SHOW GRANTS FOR 'campusswap_dev'@'localhost';
