-- =============================================================================
-- CampusSwap（单位内部文档管理平台）数据库初始化 DDL 脚本
-- 目标数据库: MySQL 8.0+        字符集: utf8mb4 / utf8mb4_unicode_ci      引擎: InnoDB
-- 依据: docs/01-requirements/GLOSSARY.md v2.1（字段级唯一真源）
--       docs/MASTER-PLAN.md §4（数据模型权威字段表）
--       老师课件《1.2-示例-数据库物理建表脚本(MySQL版)》（本文件逐字沿用其规范）
--
-- 规范约束:
--   1. 统一小写下划线命名；表名带 sys_ / doc_ 前缀，纯关联中间表以 _rel 结尾
--   2. 主键 BIGINT NOT NULL AUTO_INCREMENT；纯关联中间表用复合主键、无 id 列
--   3. 公共审计列（所有业务表）: created_at, created_by, updated_at, updated_by, deleted
--      · created_at  DEFAULT CURRENT_TIMESTAMP
--      · updated_at  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
--      · deleted     TINYINT NOT NULL DEFAULT 0（0 正常 / 1 已软删除，由 @SQLDelete 维护）
--   4. 金额/积分列统一 INT UNSIGNED，单位「分」（price_cents）
--   5. 严禁外键约束（关联靠逻辑外键 + 代码校验），删除与迁移不被约束阻塞
--   6. 每张表、每个字段都有 COMMENT
--   7. 复合索引结合 deleted 设计，并遵守最左前缀原则（见各表索引注释）
--   8. 表清单（14 张）= 系统域 8 + 文档域 6
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `campusswap_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `campusswap_db`;

-- -----------------------------------------------------------------------------
-- 1. 部门表: sys_dept（parent_id + ancestors 祖先链，支持多层组织树）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE `sys_dept` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '部门唯一自增主键',
    `name`       VARCHAR(64)  NOT NULL COMMENT '部门名称（如：技术部、产品部、后端组）',
    `parent_id`  BIGINT       NOT NULL DEFAULT 0 COMMENT '上级部门ID（0 表示顶级部门）',
    `ancestors`  VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖级路径（逗号分隔，如 0,1），用于一次查整棵子树',
    `sort_order` INT          NOT NULL DEFAULT 0 COMMENT '同级展示排序号（升序）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    `created_by` BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人用户ID（0 为系统初始化）',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    `updated_by` BIGINT       NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_sys_dept_parent` (`parent_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门组织层级表';

-- -----------------------------------------------------------------------------
-- 2. 用户表: sys_user（一人一部门；角色一律走 sys_user_role 中间表，本表不存角色列）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户唯一自增主键',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录账号（工号，全局唯一）',
    `password_hash` VARCHAR(128) NOT NULL COMMENT 'BCrypt 加盐哈希后的密码密文（严禁明文/可逆加密）',
    `real_name`     VARCHAR(64)  NOT NULL COMMENT '真实姓名',
    `dept_id`       BIGINT       NOT NULL COMMENT '归属部门ID（关联 sys_dept.id，一人一部门）',
    `email`         VARCHAR(128) DEFAULT NULL COMMENT '工作邮箱（可空）',
    `phone`         VARCHAR(20)  DEFAULT NULL COMMENT '手机号（可空）',
    `avatar_url`    VARCHAR(255) DEFAULT NULL COMMENT '头像相对 URL（可空）',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态: ACTIVE-正常, LOCKED-冻结, DISABLED-停用（仅 ACTIVE 可登录）',
    `last_login_at` DATETIME     DEFAULT NULL COMMENT '最后一次登录成功时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    `created_by`    BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人用户ID（0 为系统初始化）',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    `updated_by`    BIGINT       NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`),
    INDEX `idx_sys_user_dept` (`dept_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- -----------------------------------------------------------------------------
-- 3. 角色表: sys_role（STAFF / DOC_ADMIN / SYS_ADMIN 为内置角色，is_builtin=1 禁止删除）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色唯一自增主键',
    `name`        VARCHAR(64)  NOT NULL COMMENT '角色名称（如：普通员工、文档管理员、系统管理员）',
    `code`        VARCHAR(64)  NOT NULL COMMENT '角色编码（全局唯一，如 STAFF / DOC_ADMIN / SYS_ADMIN）',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '角色职责描述',
    `is_builtin`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否内置角色: 1-内置（禁止删除/改编码）, 0-自定义',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '展示排序号（升序）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `created_by`  BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人用户ID',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `updated_by`  BIGINT       NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

-- -----------------------------------------------------------------------------
-- 4. 权限表: sys_permission（三层树：DIR 目录 / MENU 菜单 / BUTTON 按钮）
--    查「某节点下全部权限」按【完整路径段】匹配，禁止裸前缀 LIKE：
--      SELECT * FROM sys_permission
--       WHERE deleted = 0 AND (ancestors = '0,1' OR ancestors LIKE CONCAT('0,1', ',%'));
--    原因：裸 LIKE '0,1%' 会把 '0,10'（根级 10 号节点的子树）误判成 '0,1' 的后代；
--          本项目权限点 id 正是 1 / 10 / 100 段位复用，误判风险真实存在（M3 实测）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '权限唯一自增主键',
    `name`       VARCHAR(64)  NOT NULL COMMENT '权限名称（如：新建文档、角色授权）',
    `code`       VARCHAR(64)  NOT NULL COMMENT '权限编码（全局唯一，形如 doc:create / sys:role:grant）',
    `type`       VARCHAR(32)  NOT NULL COMMENT '权限类型: DIR-目录层, MENU-菜单层, BUTTON-按钮/操作点层',
    `parent_id`  BIGINT       NOT NULL DEFAULT 0 COMMENT '父节点ID（0 表示根节点）',
    `ancestors`  VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖级路径（逗号分隔，如 0,1,10）',
    `path`       VARCHAR(255) DEFAULT NULL COMMENT '前端路由（目录/菜单层使用，按钮层为空）',
    `icon`       VARCHAR(64)  DEFAULT NULL COMMENT '前端图标名（可空）',
    `sort_order` INT          NOT NULL DEFAULT 0 COMMENT '同级展示排序号（升序）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `created_by` BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人用户ID',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `updated_by` BIGINT       NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_permission_code` (`code`),
    INDEX `idx_sys_perm_parent` (`parent_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统功能权限表（三层树）';

-- -----------------------------------------------------------------------------
-- 5. 用户-角色关联表: sys_user_role（复合主键，无 id 列）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `user_id`    BIGINT   NOT NULL COMMENT '用户ID（关联 sys_user.id）',
    `role_id`    BIGINT   NOT NULL COMMENT '角色ID（关联 sys_role.id）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (`user_id`, `role_id`),
    INDEX `idx_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户与角色关联表（多对多，复合主键）';

-- -----------------------------------------------------------------------------
-- 6. 用户-权限直授表: sys_user_permission（个别补权，与角色权限合并取并集）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_permission`;
CREATE TABLE `sys_user_permission` (
    `user_id`       BIGINT   NOT NULL COMMENT '用户ID（关联 sys_user.id）',
    `permission_id` BIGINT   NOT NULL COMMENT '权限点ID（关联 sys_permission.id）',
    `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '直授时间',
    PRIMARY KEY (`user_id`, `permission_id`),
    INDEX `idx_user_perm_perm` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户直授权限表（多对多，复合主键）';

-- -----------------------------------------------------------------------------
-- 7. 角色-权限关联表: sys_role_permission（复合主键，无 id 列）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
    `role_id`       BIGINT   NOT NULL COMMENT '角色ID（关联 sys_role.id）',
    `permission_id` BIGINT   NOT NULL COMMENT '权限点ID（关联 sys_permission.id）',
    `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配置时间',
    PRIMARY KEY (`role_id`, `permission_id`),
    INDEX `idx_role_perm_perm` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色与权限关联表（多对多，复合主键）';

-- -----------------------------------------------------------------------------
-- 8. 部门-角色关联表: sys_dept_role（部门绑定角色后，部门成员自动继承权限）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_dept_role`;
CREATE TABLE `sys_dept_role` (
    `dept_id`    BIGINT   NOT NULL COMMENT '部门ID（关联 sys_dept.id）',
    `role_id`    BIGINT   NOT NULL COMMENT '角色ID（关联 sys_role.id）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    PRIMARY KEY (`dept_id`, `role_id`),
    INDEX `idx_dept_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门与角色关联表（多对多，复合主键）';

-- -----------------------------------------------------------------------------
-- 9. 文档主表: doc_document
--    作者 = created_by（不再另设 author_id）；正文 content_md 为大文本，列表查询禁读（课件 3.1 红线三）
--    索引最左前缀说明:
--      · idx_doc_cat_status_updated(category_id, status, updated_at, deleted) → 命中「分类+状态+时间倒序」
--      · idx_doc_status_updated(status, updated_at, deleted)                  → 命中「仅状态+时间倒序」（审核队列/我的文档）
--        两者不可互相替代：仅按 status 查询无法使用前者（跳过最左列 category_id）
--    全文检索索引（UI_UX_SPECIFICATION §10.4，实测 MySQL 8.0.46 / ngram_token_size=2）:
--      · ft_doc_search(title, summary, content_md) WITH PARSER ngram → 中文按 2-gram 切分，2 字及以上关键词可用
--      · 只允许 MATCH(title, summary, content_md) 这一种列组合（子集 MATCH 会报 ERROR 1191）
--      · perf-fixture.sql 造数后必须 ANALYZE TABLE doc_document（全文索引统计可见性）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `doc_document`;
CREATE TABLE `doc_document` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档唯一自增主键',
    `category_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '所属分类ID（关联 doc_category.id，0 表示未分类）',
    `title`            VARCHAR(128) NOT NULL COMMENT '文档标题（1-128 字符）',
    `summary`          VARCHAR(255) DEFAULT NULL COMMENT '纯文本摘要（可空）',
    `content_md`       MEDIUMTEXT   DEFAULT NULL COMMENT 'Markdown 正文（最大 16MB；列表查询严禁读取本列）',
    `status`           VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '生命周期状态: DRAFT-草稿, PUBLISHED-已发布, ARCHIVED-已归档, TRASH-回收站',
    `version_num`      INT          NOT NULL DEFAULT 1 COMMENT '业务版本号（从 1 起单调递增，每次正文写入或状态流转 +1）',
    `price_cents`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '价格标记（单位：分，0 表示免费；仅作免费/积分标记，不涉及真实支付）',
    `view_count`       INT          NOT NULL DEFAULT 0 COMMENT '阅读量（仅 PUBLISHED 累加，Redis 去重后原子自增）',
    `favorite_count`   INT          NOT NULL DEFAULT 0 COMMENT '收藏数（冗余计数，原子自增/自减）',
    `derived_from_id`  BIGINT       DEFAULT NULL COMMENT '派生来源文档ID（为空表示原创，用于血缘追溯）',
    `reject_reason`    VARCHAR(255) DEFAULT NULL COMMENT '审核驳回理由（回传作者，通过后清空）',
    `publish_at`       DATETIME     DEFAULT NULL COMMENT '首次发布时间',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `created_by`       BIGINT       NOT NULL DEFAULT 0 COMMENT '作者ID（= 创建人，关联 sys_user.id）',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后编辑时间',
    `updated_by`       BIGINT       NOT NULL DEFAULT 0 COMMENT '最后编辑人用户ID',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_doc_cat_status_updated` (`category_id`, `status`, `updated_at`, `deleted`),
    INDEX `idx_doc_status_updated` (`status`, `updated_at`, `deleted`),
    -- updated_at 必须进索引且紧随等值列之后，否则 ORDER BY updated_at DESC 会退化成内存排序
    -- （实测：旧写法 (created_by, deleted) 需读 10003 行 + filesort = 28.4ms；改为本写法后无排序）
    INDEX `idx_doc_created_by_updated` (`created_by`, `updated_at`, `deleted`),
    INDEX `idx_doc_derived_from` (`derived_from_id`),
    -- 全文检索（ngram 解析器）：中文按 2-gram 切分，供 MATCH(...) AGAINST(? IN BOOLEAN MODE) 使用
    FULLTEXT KEY `ft_doc_search` (`title`, `summary`, `content_md`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档核心业务主表';

-- -----------------------------------------------------------------------------
-- 10. 文档版本快照表: doc_version（追加型日志；随文档彻底删除时物理清理）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `doc_version`;
CREATE TABLE `doc_version` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本记录唯一自增主键',
    `document_id`   BIGINT       NOT NULL COMMENT '文档ID（关联 doc_document.id）',
    `version_num`   INT          NOT NULL COMMENT '该次操作后的文档版本号（与 doc_document.version_num 对应）',
    `title`         VARCHAR(128) NOT NULL COMMENT '标题快照',
    `content_md`    MEDIUMTEXT   DEFAULT NULL COMMENT '正文快照（Markdown）',
    `change_type`   VARCHAR(32)  NOT NULL COMMENT '变更类型: CREATE/EDIT/PUBLISH/AUDIT/REJECT/ARCHIVE/RESTORE/DELETE/DERIVE',
    `change_remark` VARCHAR(255) DEFAULT NULL COMMENT '变更备注 / 审核意见（驳回时必填）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `created_by`    BIGINT       NOT NULL DEFAULT 0 COMMENT '操作人用户ID',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（日志表固定等于 created_at）',
    `updated_by`    BIGINT       NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_version` (`document_id`, `version_num`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档版本快照表（不可变留痕）';

-- -----------------------------------------------------------------------------
-- 11. 文档分类表: doc_category（最多 3 层，parent_id + ancestors）
--     查「某分类及全部子孙」同样按完整路径段匹配：
--       WHERE deleted = 0 AND (ancestors = '0,1' OR ancestors LIKE CONCAT('0,1', ',%'))
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `doc_category`;
CREATE TABLE `doc_category` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类唯一自增主键',
    `name`       VARCHAR(64)  NOT NULL COMMENT '分类名称（如：后端开发、接口规范）',
    `parent_id`  BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID（0 表示顶级分类）',
    `ancestors`  VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖级路径（逗号分隔，如 0,1）；传父分类 = 含全部子孙',
    `sort_order` INT          NOT NULL DEFAULT 0 COMMENT '同级展示排序号（升序）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `created_by` BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人用户ID',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `updated_by` BIGINT       NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_doc_category_parent` (`parent_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分类表（多级树）';

-- -----------------------------------------------------------------------------
-- 12. 标签字典表: doc_tag（全局唯一，扁平结构）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `doc_tag`;
CREATE TABLE `doc_tag` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '标签唯一自增主键',
    `name`       VARCHAR(64) NOT NULL COMMENT '标签名称（全局唯一，如：SpringBoot、线上排障）',
    `use_count`  INT         NOT NULL DEFAULT 0 COMMENT '被引用次数（冗余计数，便于清理废弃标签）',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `created_by` BIGINT      NOT NULL DEFAULT 0 COMMENT '创建人用户ID',
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `updated_by` BIGINT      NOT NULL DEFAULT 0 COMMENT '最后修改人用户ID',
    `deleted`    TINYINT     NOT NULL DEFAULT 0 COMMENT '软删除标记: 0-正常存活, 1-已逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_tag_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档标签字典表';

-- -----------------------------------------------------------------------------
-- 13. 文档-标签关联表: doc_document_tag_rel（复合主键，无 id 列）
--     写入纪律: 标签增删一律走本表的显式实体（清空重插），禁走 @ManyToMany 的 add/remove
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `doc_document_tag_rel`;
CREATE TABLE `doc_document_tag_rel` (
    `document_id` BIGINT   NOT NULL COMMENT '文档ID（关联 doc_document.id）',
    `tag_id`      BIGINT   NOT NULL COMMENT '标签ID（关联 doc_tag.id）',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打标签时间',
    PRIMARY KEY (`document_id`, `tag_id`),
    INDEX `idx_rel_tag_doc` (`tag_id`, `document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档与标签关联表（多对多，复合主键）';

-- -----------------------------------------------------------------------------
-- 14. 文档收藏表: doc_favorite（复合主键天然去重，重复收藏幂等）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `doc_favorite`;
CREATE TABLE `doc_favorite` (
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID（关联 sys_user.id）',
    `document_id` BIGINT   NOT NULL COMMENT '文档ID（关联 doc_document.id）',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`user_id`, `document_id`),
    INDEX `idx_fav_doc` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档收藏表（复合主键）';

-- =============================================================================
-- 附：三层权限树落库示例（完整 39 条权限点见 sql/data.sql 的种子数据）
--   ① 目录层 type=DIR     id=1  文档中心 doc:center   ancestors='0'
--   ② 菜单层 type=MENU    id=10 我的文档 doc:mine     ancestors='0,1'      parent_id=1
--   ③ 按钮层 type=BUTTON  id=100 新建文档 doc:create  ancestors='0,1,10'   parent_id=10
--   查「文档中心下全部权限」（完整路径段匹配，勿用裸 LIKE '0,1%'）：
--     SELECT * FROM sys_permission
--      WHERE deleted = 0 AND (ancestors = '0,1' OR ancestors LIKE CONCAT('0,1', ',%'));
-- =============================================================================
