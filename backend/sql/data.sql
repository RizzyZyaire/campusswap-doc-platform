-- =============================================================================
-- CampusSwap 种子数据（docs_db → campusswap_db）
-- 依据: docs/01-requirements/PRD.md §3.2（39 个权限点）/ §3.3（角色默认授权）
--       docs/01-requirements/GLOSSARY.md v2.1（字段与枚举取值）
-- 幂等: 先 TRUNCATE 再按显式 ID 插入，可反复执行
--
-- 内置账号（密码为 BCrypt strength 10 真实哈希，可直接登录）:
--   admin     / Admin@123      → SYS_ADMIN 系统管理员（技术部）
--   docadmin  / Doc@123456     → DOC_ADMIN 文档管理员（技术部）
--   staff     / Staff@123      → STAFF      普通员工（产品部）
-- 注意: 生产环境务必删除演示账号；真实密码一律来自环境变量 DB_PASSWORD。
-- =============================================================================

USE `campusswap_db`;
SET NAMES utf8mb4;

TRUNCATE TABLE `sys_permission`;
TRUNCATE TABLE `sys_role`;
TRUNCATE TABLE `sys_role_permission`;
TRUNCATE TABLE `sys_user_permission`;
TRUNCATE TABLE `sys_dept`;
TRUNCATE TABLE `sys_dept_role`;
TRUNCATE TABLE `sys_user`;
TRUNCATE TABLE `sys_user_role`;
TRUNCATE TABLE `doc_document`;
TRUNCATE TABLE `doc_version`;
TRUNCATE TABLE `doc_category`;
TRUNCATE TABLE `doc_tag`;
TRUNCATE TABLE `doc_document_tag_rel`;
TRUNCATE TABLE `doc_favorite`;

-- -----------------------------------------------------------------------------
-- 1. 权限点：39 条三层树（ID 区间约定：目录 1~9 / 菜单 10~29 / 按钮 100+）
--    ancestors 为祖级路径，用于一次查询整棵子树（WHERE ancestors LIKE '0,1%'）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`,`name`,`code`,`type`,`parent_id`,`ancestors`,`path`,`icon`,`sort_order`,`created_by`) VALUES
-- ① 目录层（2）
(1,  '文档中心',      'doc:center',       'DIR', 0, '0',          NULL,         'folder',  1, 1),
(2,  '系统管理',      'sys:center',       'DIR', 0, '0',          NULL,         'setting', 2, 1),
-- ② 菜单层（9）
(10, '我的文档',      'doc:mine',         'MENU', 1, '0,1',       '/my',        'file',    1, 1),
(11, '文档检索',      'doc:search',       'MENU', 1, '0,1',       '/docs',      'search',  2, 1),
(12, '审核队列',      'doc:review',       'MENU', 1, '0,1',       '/review',    'audit',   3, 1),
(13, '文档治理',      'doc:manage',       'MENU', 1, '0,1',       '/admin/docs','shield',  4, 1),
(14, '分类与标签',    'doc:category',     'MENU', 1, '0,1',       NULL,         'tag',     5, 1),
(20, '用户管理',      'sys:user',         'MENU', 2, '0,2',       NULL,         'user',    1, 1),
(21, '角色管理',      'sys:role',         'MENU', 2, '0,2',       NULL,         'team',    2, 1),
(22, '权限管理',      'sys:perm',         'MENU', 2, '0,2',       NULL,         'lock',    3, 1),
(23, '部门管理',      'sys:dept',         'MENU', 2, '0,2',       NULL,         'org',     4, 1),
-- ③ 按钮层（28）
(100,'新建文档',      'doc:create',       'BUTTON', 10, '0,1,10', NULL, NULL, 1, 1),
(101,'编辑文档',      'doc:edit',         'BUTTON', 10, '0,1,10', NULL, NULL, 2, 1),
(102,'提交发布',      'doc:publish',      'BUTTON', 10, '0,1,10', NULL, NULL, 3, 1),
(103,'删除文档',      'doc:delete',       'BUTTON', 10, '0,1,10', NULL, NULL, 4, 1),
(104,'恢复文档',      'doc:restore',      'BUTTON', 10, '0,1,10', NULL, NULL, 5, 1),
(110,'派生文档',      'doc:derive',       'BUTTON', 11, '0,1,11', NULL, NULL, 1, 1),
(111,'收藏文档',      'doc:favorite',     'BUTTON', 11, '0,1,11', NULL, NULL, 2, 1),
(112,'上传图片',      'doc:upload',       'BUTTON', 11, '0,1,11', NULL, NULL, 3, 1),
(120,'审核通过',      'doc:audit',        'BUTTON', 12, '0,1,12', NULL, NULL, 1, 1),
(121,'驳回文档',      'doc:reject',       'BUTTON', 12, '0,1,12', NULL, NULL, 2, 1),
(130,'归档文档',      'doc:archive',      'BUTTON', 13, '0,1,13', NULL, NULL, 1, 1),
(131,'下架文档',      'doc:offline',      'BUTTON', 13, '0,1,13', NULL, NULL, 2, 1),
(140,'维护分类',      'doc:category:edit','BUTTON', 14, '0,1,14', NULL, NULL, 1, 1),
(141,'维护标签',      'doc:tag:edit',     'BUTTON', 14, '0,1,14', NULL, NULL, 2, 1),
(200,'新增用户',      'sys:user:add',     'BUTTON', 20, '0,2,20', NULL, NULL, 1, 1),
(201,'编辑用户',      'sys:user:edit',    'BUTTON', 20, '0,2,20', NULL, NULL, 2, 1),
(202,'启停用户',      'sys:user:disable', 'BUTTON', 20, '0,2,20', NULL, NULL, 3, 1),
(203,'重置密码',      'sys:user:reset',   'BUTTON', 20, '0,2,20', NULL, NULL, 4, 1),
(210,'新增角色',      'sys:role:add',     'BUTTON', 21, '0,2,21', NULL, NULL, 1, 1),
(211,'编辑角色',      'sys:role:edit',    'BUTTON', 21, '0,2,21', NULL, NULL, 2, 1),
(212,'删除角色',      'sys:role:delete',  'BUTTON', 21, '0,2,21', NULL, NULL, 3, 1),
(213,'角色授权',      'sys:role:grant',   'BUTTON', 21, '0,2,21', NULL, NULL, 4, 1),
(220,'新增权限',      'sys:perm:add',     'BUTTON', 22, '0,2,22', NULL, NULL, 1, 1),
(221,'编辑权限',      'sys:perm:edit',    'BUTTON', 22, '0,2,22', NULL, NULL, 2, 1),
(222,'删除权限',      'sys:perm:delete',  'BUTTON', 22, '0,2,22', NULL, NULL, 3, 1),
(230,'新增部门',      'sys:dept:add',     'BUTTON', 23, '0,2,23', NULL, NULL, 1, 1),
(231,'编辑部门',      'sys:dept:edit',    'BUTTON', 23, '0,2,23', NULL, NULL, 2, 1),
(232,'删除部门',      'sys:dept:delete',  'BUTTON', 23, '0,2,23', NULL, NULL, 3, 1);

-- -----------------------------------------------------------------------------
-- 2. 内置角色（is_builtin=1 禁止删除、禁止改 code）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_role` (`id`,`name`,`code`,`description`,`is_builtin`,`sort_order`,`created_by`) VALUES
(1,'普通员工',   'STAFF',    '平台主要使用者：拥有自己的文档增删改查、检索、派生、收藏权限', 1, 1, 1),
(2,'文档管理员', 'DOC_ADMIN','内容治理者：在员工权限之上，拥有审核、归档、分类与标签维护权限', 1, 2, 1),
(3,'系统管理员', 'SYS_ADMIN','平台管理者：拥有全部 39 个权限点（用户/角色/权限/部门管理）',     1, 3, 1);

-- -----------------------------------------------------------------------------
-- 3. 角色-权限（PRD §3.3 默认授权）
--    STAFF 11 条 / DOC_ADMIN 20 条 / SYS_ADMIN 全部 39 条
-- -----------------------------------------------------------------------------
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`) VALUES
-- STAFF（11）
(1,1),(1,10),(1,11),(1,100),(1,101),(1,102),(1,103),(1,104),(1,110),(1,111),(1,112),
-- DOC_ADMIN（= STAFF 11 条 + 治理 9 条 = 20）
(2,1),(2,10),(2,11),(2,100),(2,101),(2,102),(2,103),(2,104),(2,110),(2,111),(2,112),
(2,12),(2,13),(2,14),(2,120),(2,121),(2,130),(2,131),(2,140),(2,141);
-- SYS_ADMIN = 全部权限点（含后续新增，故用 SELECT 生成）
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT 3, `id` FROM `sys_permission`;

-- 用户级直授权限（sys_user_permission）: 本演示数据留空。
-- 原因: M1 契约未提供用户直授权的写接口（见 API_SPECIFICATION），故仅保留表结构与合并算法支持；
--       如需演示，可手工插入，例如: INSERT INTO sys_user_permission VALUES (3, 120, NOW());

-- -----------------------------------------------------------------------------
-- 4. 部门（3 个，演示两级组织树：技术部 → 后端组）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_dept` (`id`,`name`,`parent_id`,`ancestors`,`sort_order`,`created_by`) VALUES
(1,'技术部', 0,'0',   1,1),
(2,'产品部', 0,'0',   2,1),
(3,'后端组', 1,'0,1', 1,1);

-- 部门绑定角色：部门成员自动继承角色权限（技术部额外继承文档管理员）
INSERT INTO `sys_dept_role` (`dept_id`,`role_id`) VALUES
(1,1),(1,2),
(2,1),
(3,1);

-- -----------------------------------------------------------------------------
-- 5. 用户（3 个，覆盖三种角色；密码为 BCrypt strength 10 真实哈希）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_user` (`id`,`username`,`password_hash`,`real_name`,`dept_id`,`email`,`phone`,`status`,`created_by`) VALUES
(1,'admin',   '$2b$10$3VJSaNI6aunhY5nwmzzUNeXR9EJZiUCOplWtdxtdiE8jN3Byf7t0W','系统管理员',1,'admin@campusswap.local',   '13800000001','ACTIVE',1),
(2,'docadmin','$2b$10$9c8RG.p0pQqV3mFC4izLneUhpXcMf/fVsdodtxW3kOCKtbiINvANW','文档管理员',1,'docadmin@campusswap.local','13800000002','ACTIVE',1),
(3,'staff',   '$2b$10$9c7a0U2IKzlowsK3FoXv8uIHQrNkuRz8UCMDriwPDa0X.ZOOBDM7.','普通员工',  2,'staff@campusswap.local',   '13800000003','ACTIVE',1);

INSERT INTO `sys_user_role` (`user_id`,`role_id`) VALUES
(1,3),   -- admin    → SYS_ADMIN
(2,2),   -- docadmin → DOC_ADMIN
(3,1);   -- staff    → STAFF

-- -----------------------------------------------------------------------------
-- 6. 分类（4 个，含一个二级分类用于验证「传父分类 = 含全部子孙」）
-- -----------------------------------------------------------------------------
INSERT INTO `doc_category` (`id`,`name`,`parent_id`,`ancestors`,`sort_order`,`created_by`) VALUES
(1,'后端开发', 0,'0',   1,2),
(2,'前端开发', 0,'0',   2,2),
(3,'接口规范', 1,'0,1', 1,2),
(4,'员工制度', 0,'0',   3,2);

-- -----------------------------------------------------------------------------
-- 7. 标签（5 个）
-- -----------------------------------------------------------------------------
INSERT INTO `doc_tag` (`id`,`name`,`use_count`,`created_by`) VALUES
(1,'SpringBoot',2,2),
(2,'JPA',       1,2),
(3,'Vue',       1,3),
(4,'线上排障',  0,2),
(5,'安全合规',  0,2);

-- -----------------------------------------------------------------------------
-- 8. 示例文档（5 篇，覆盖 DRAFT / PUBLISHED / ARCHIVED / TRASH 四种状态，
--    便于前端四态与状态机分支都能立刻演示；作者 = created_by）
-- -----------------------------------------------------------------------------
INSERT INTO `doc_document`
(`id`,`category_id`,`title`,`summary`,`content_md`,`status`,`version_num`,`price_cents`,`view_count`,`favorite_count`,`derived_from_id`,`reject_reason`,`publish_at`,`created_by`) VALUES
(1,3,'CampusSwap 接口规范（v1）','统一响应体、错误码与分页约定，前后端联调的唯一依据',
 '# 接口规范\n\n## 统一响应体\n\n```json\n{ "code": 200, "message": "成功", "data": {} }\n```\n\n- `code` 与 HTTP 状态码保持一致\n- 分页统一 `PageVo<T>`：`list/total/pageNum/pageSize`\n- 所有 ID 以字符串返回（防 JS 精度丢失）\n',
 'PUBLISHED',2,0,12,2,NULL,NULL,'2026-09-21 10:00:00',2),
(2,1,'JPA 实体建模规范','七条戒律、审计基类与软删除的正确写法',
 '# JPA 实体建模规范\n\n1. 禁 `@Data`（递归 `toString` 会栈溢出）\n2. 主键用包装类 `Long`\n3. `ddl-auto: none`，表结构走 `schema.sql`\n4. 关联一律 `FetchType.LAZY`\n5. 树形用 `parentId + ancestors`\n6. 中间表建显式实体\n7. ID 序列化为字符串\n',
 'PUBLISHED',2,0,8,0,NULL,NULL,'2026-09-21 11:30:00',2),
(3,2,'前端工程搭建说明','Vite + Vue3 + TS 严格模式 + Tailwind 初始化步骤',
 '# 前端工程搭建\n\n```bash\npnpm create vite frontend --template vue-ts\npnpm add vue-router pinia axios markdown-it dompurify\npnpm add -D tailwindcss postcss autoprefixer\n```\n\n（草稿：待补充请求拦截器与四态组件写法）\n',
 'DRAFT',1,0,0,0,NULL,NULL,NULL,3),
(4,4,'季度知识分享制度（2026）','分享会组织方式与激励办法',
 '# 季度知识分享制度\n\n每季度一次，由各部门轮流主讲。\n',
 'ARCHIVED',2,0,35,0,NULL,NULL,'2026-08-01 09:00:00',2),
(5,4,'关于开展季度分享的通知（旧版）','已被新版取代，删除进回收站',
 '# 旧版通知\n\n本通知已作废。\n',
 'TRASH',2,0,3,0,NULL,NULL,'2026-07-01 09:00:00',3);

INSERT INTO `doc_document_tag_rel` (`document_id`,`tag_id`) VALUES
(1,1),(1,5),
(2,2),
(3,3),
(4,5);

INSERT INTO `doc_favorite` (`user_id`,`document_id`) VALUES
(3,1),(1,1),(3,2);

-- -----------------------------------------------------------------------------
-- 9. 版本留痕（与上面 status/version_num 对应，验证状态机与审计）
-- -----------------------------------------------------------------------------
INSERT INTO `doc_version` (`document_id`,`version_num`,`title`,`content_md`,`change_type`,`change_remark`,`created_by`) VALUES
(1,1,'CampusSwap 接口规范（v1）','（首次创建）','CREATE',NULL,2),
(1,2,'CampusSwap 接口规范（v1）','（提交发布）','PUBLISH',NULL,2),
(2,1,'JPA 实体建模规范','（首次创建）','CREATE',NULL,2),
(2,2,'JPA 实体建模规范','（提交发布）','PUBLISH',NULL,2),
(3,1,'前端工程搭建说明','（首次创建）','CREATE',NULL,3),
(4,1,'季度知识分享制度（2026）','（首次创建）','CREATE',NULL,2),
(4,2,'季度知识分享制度（2026）','（管理员归档）','ARCHIVE','内容已过期，转为归档只读',2),
(5,1,'关于开展季度分享的通知（旧版）','（首次创建）','CREATE',NULL,3),
(5,2,'关于开展季度分享的通知（旧版）','（删除进回收站）','DELETE',NULL,3);

-- -----------------------------------------------------------------------------
-- 10. 种子数据自检（期望值见注释）
-- -----------------------------------------------------------------------------
SELECT '权限点总数（期望 39）' AS item, COUNT(*) AS cnt FROM `sys_permission`
UNION ALL SELECT '角色数（期望 3）',        COUNT(*) FROM `sys_role`
UNION ALL SELECT 'STAFF 权限数（期望 11）', (SELECT COUNT(*) FROM `sys_role_permission` WHERE role_id=1)
UNION ALL SELECT 'DOC_ADMIN 权限数（期望 20）', (SELECT COUNT(*) FROM `sys_role_permission` WHERE role_id=2)
UNION ALL SELECT 'SYS_ADMIN 权限数（期望 39）', (SELECT COUNT(*) FROM `sys_role_permission` WHERE role_id=3)
UNION ALL SELECT '部门数（期望 3）',        COUNT(*) FROM `sys_dept`
UNION ALL SELECT '用户数（期望 3）',        COUNT(*) FROM `sys_user`
UNION ALL SELECT '分类数（期望 4）',        COUNT(*) FROM `doc_category`
UNION ALL SELECT '标签数（期望 5）',        COUNT(*) FROM `doc_tag`
UNION ALL SELECT '文档数（期望 5）',        COUNT(*) FROM `doc_document`
UNION ALL SELECT '已发布文档（期望 2）',    (SELECT COUNT(*) FROM `doc_document` WHERE status='PUBLISHED')
UNION ALL SELECT '版本留痕（期望 9）',      COUNT(*) FROM `doc_version`
UNION ALL SELECT '收藏关系（期望 3）',      COUNT(*) FROM `doc_favorite`;
