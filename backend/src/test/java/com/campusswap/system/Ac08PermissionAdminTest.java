package com.campusswap.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.PermissionAspect;
import com.campusswap.common.security.RedisKeys;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.entity.DeptRole;
import com.campusswap.entity.Permission;
import com.campusswap.entity.Role;
import com.campusswap.entity.RolePermission;
import com.campusswap.entity.UserRole;
import com.campusswap.entity.enums.PermType;
import com.campusswap.support.UnitTestSupport;
import com.campusswap.system.controller.RoleController;
import com.campusswap.system.dto.PermissionUpdateDtoReq;
import com.campusswap.system.dto.RolePermissionDtoReq;
import com.campusswap.system.repository.DeptRoleRepository;
import com.campusswap.system.repository.PermissionRepository;
import com.campusswap.system.repository.RolePermissionRepository;
import com.campusswap.system.repository.RoleRepository;
import com.campusswap.system.repository.UserPermissionRepository;
import com.campusswap.system.repository.UserRepository;
import com.campusswap.system.repository.UserRoleRepository;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.impl.PermissionCacheServiceImpl;
import com.campusswap.system.service.impl.PermissionServiceImpl;
import com.campusswap.system.service.impl.RoleServiceImpl;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * US-08 系统管理员配置权限并授权（AC-08.1 / AC-08.2 / AC-08.3）。
 *
 * <p><b>三条断言分别落在哪一层</b>：</p>
 * <ul>
 *   <li><b>AC-08.1 → Service 层（授权 + 缓存失效）</b>：这里把
 *       {@link PermissionCacheServiceImpl} 以<b>真实对象</b>（Redis 只是 mock 的
 *       {@code StringRedisTemplate}）注入 {@link RoleServiceImpl}，因此
 *       "该角色下所有用户的 {@code perm:user:{userId}} 立即失效"断言的是<b>真实的键名与删除动作</b>
 *       （用 {@code ArgumentCaptor<Collection<String>>} 抓住 {@code DEL} 的键列表），
 *       而不是"调了某个方法"。随后再走一次鉴权，断言缓存被删除后回源数据库、按新权限放行。
 *       <br>口径说明：生产环境删缓存由 {@code TxUtil.afterCommit} 放在<b>事务提交后</b>执行；
 *       纯单元测试没有活动事务，{@code afterCommit} 会立即执行 —— 断言的是"失效动作与键名正确"。</li>
 *   <li><b>AC-08.2 → AOP 权限切面层</b>：非 {@code SYS_ADMIN} 调授权接口被
 *       {@code @RequiresPermission("sys:role:grant")} 拦下，拦截点是
 *       {@link PermissionAspect#check(RequiresPermission)}；这里用生产端点上的真实注解喂给切面，
 *       断言 {@code NO_PERMISSION}（HTTP 403），并做正向对照。</li>
 *   <li><b>AC-08.3 → Service 层（树形环检测）</b>：{@code PermissionServiceImpl.update} 的
 *       {@code isSelfOrDescendant} 是这条规则的唯一落点，断言 400 +「不能将节点移动到其子节点下」，
 *       并断言环<b>没有被写进库</b>（节点与子孙一行都没动）。</li>
 * </ul>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m3-http.ps1}
 * 步骤 <i>[8] Authorization takes effect immediately (US-08 AC-08.1)</i> ——
 * {@code grant-effect.before.http}(403) → 授权 200 → {@code grant-effect.after.permission-count}(11→12)
 * 与 {@code grant-effect.after.http}(200，旧 token 立即可用) → 还原后 {@code grant-effect.reverted.http}(403)
 * （AC-08.1 的 HTTP 证据）；步骤 <i>[6] Permission CRUD: hierarchy / ancestors / cycle / delete guards</i> 的
 * {@code perm.move-node1-under-own-child.http} / {@code perm.move-menu-under-itself.http}(400)（AC-08.3）；
 * 以及 {@code docs/03-qa-review/verify-m4-http.ps1} 的
 * <i>US-08 AC-08.2: non-admin cannot grant permissions</i>（{@code US08.staff-grant.http} 403）。
 * 本类补的是脚本断言不到的：{@code DEL} 的键名逐字校验（{@code perm:user:{userId}} 一个都不漏）、
 * 切面在"有/无权限"两种输入下的分支、环被拒绝后库中一行未动。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac08PermissionAdminTest {

    /** 角色 DOC_ADMIN 的 ID（对应 backend/sql/data.sql 种子：`(2,'文档管理员','DOC_ADMIN',...)`）。 */
    private static final Long DOC_ADMIN_ROLE_ID = 2L;

    /** 权限 doc:review 的 ID（对应种子：`(12,'审核队列','doc:review','MENU',1,'0,1','/review',...)`）。 */
    private static final Long DOC_REVIEW_PERMISSION_ID = 12L;

    /** 权限码：审核。 */
    private static final String DOC_REVIEW = "doc:review";

    /** 权限树：根节点（目录）ID。 */
    private static final Long ROOT_DIR_ID = 1L;

    /** 权限树：根节点下的菜单 ID（= 根节点的直接子孙）。 */
    private static final Long CHILD_MENU_ID = 11L;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private DeptRoleRepository deptRoleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPermissionRepository userPermissionRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    /** 真实的权限缓存实现（Redis 由 mock 的 StringRedisTemplate 顶替）。 */
    private PermissionCacheService permissionCacheService;

    /** 被测：角色授权服务。 */
    private RoleServiceImpl roleService;

    /** 被测：权限服务。 */
    private PermissionServiceImpl permissionService;

    /** 被测：权限切面。 */
    private PermissionAspect permissionAspect;

    /**
     * 手工装配被测对象（AC-08.1 要真缓存实现，AC-08.2 要真切面）。
     */
    @BeforeEach
    void setUp() {
        permissionCacheService = new PermissionCacheServiceImpl(stringRedisTemplate, permissionRepository,
                userRepository, userRoleRepository, deptRoleRepository);
        roleService = new RoleServiceImpl(roleRepository, permissionRepository, rolePermissionRepository,
                userRoleRepository, deptRoleRepository, permissionCacheService);
        permissionService = new PermissionServiceImpl(permissionRepository, rolePermissionRepository,
                userPermissionRepository);
        permissionAspect = new PermissionAspect(permissionCacheService);
    }

    /**
     * 清 ThreadLocal 登录上下文。
     */
    @AfterEach
    void tearDown() {
        UnitTestSupport.clearSecurityContext();
    }

    /**
     * AC-08.1（正常流）：给 DOC_ADMIN 勾选 doc:review → 覆盖式保存 + 该角色下全部用户权限缓存立即失效。
     */
    @Test
    @DisplayName("AC-08.1 给 DOC_ADMIN 勾选 doc:review 并保存：覆盖式写入 sys_role_permission，该角色下全部用户 perm:user:{userId} 立即失效，后续鉴权按新权限生效")
    @SuppressWarnings("unchecked")
    void ac0801_grantPermissionEvictsEveryUserCacheOfThatRole() {
        Role docAdmin = Role.builder().name("文档管理员").code("DOC_ADMIN").isBuiltin(1).sortOrder(2).build();
        docAdmin.setId(DOC_ADMIN_ROLE_ID);
        Permission docReview = Permission.builder().name("审核队列").code(DOC_REVIEW).type(PermType.MENU)
                .parentId(ROOT_DIR_ID).ancestors("0,1").path("/review").icon("audit").sortOrder(3).build();
        docReview.setId(DOC_REVIEW_PERMISSION_ID);

        when(roleRepository.findById(DOC_ADMIN_ROLE_ID)).thenReturn(Optional.of(docAdmin));
        when(permissionRepository.findAllByIdIn(List.of(DOC_REVIEW_PERMISSION_ID))).thenReturn(List.of(docReview));
        // 该角色下的用户：2 个直授该角色 + 1 个通过部门（10）继承该角色
        when(userRoleRepository.findByRoleId(DOC_ADMIN_ROLE_ID))
                .thenReturn(List.of(new UserRole(1001L, DOC_ADMIN_ROLE_ID), new UserRole(1002L, DOC_ADMIN_ROLE_ID)));
        when(deptRoleRepository.findByRoleId(DOC_ADMIN_ROLE_ID))
                .thenReturn(List.of(new DeptRole(10L, DOC_ADMIN_ROLE_ID)));
        when(userRepository.findIdsByDeptIdIn(List.of(10L))).thenReturn(List.of(1003L));
        // 授权前：缓存里的旧集合不含 doc:review；授权（清缓存）之后回源数据库拿到新集合
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(RedisKeys.userPerm(1001L)))
                .thenReturn(Set.of("doc:create"), Set.<String>of());
        when(permissionRepository.findEffectivePermCodes(1001L))
                .thenReturn(List.of("doc:create", DOC_REVIEW));

        assertThat(docAdmin.getCode()).as("AC-08.1 Given 角色 DOC_ADMIN 存在").isEqualTo("DOC_ADMIN");
        assertThat(docReview.getCode()).as("AC-08.1 Given 权限 doc:review 存在").isEqualTo(DOC_REVIEW);
        assertThat(permissionCacheService.hasPermission(1001L, DOC_REVIEW))
                .as("AC-08.1 授权前：缓存里没有 doc:review").isFalse();

        roleService.grantPermissions(DOC_ADMIN_ROLE_ID, new RolePermissionDtoReq(List.of("12")));

        // 保存成功：覆盖式写入 sys_role_permission（先按 roleId 清空，再批量插入）
        verify(rolePermissionRepository).deleteByRoleId(DOC_ADMIN_ROLE_ID);
        ArgumentCaptor<List<RolePermission>> relationCaptor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(relationCaptor.capture());
        assertThat(relationCaptor.getValue())
                .as("AC-08.1 该角色最终拥有的权限关系")
                .containsExactly(new RolePermission(DOC_ADMIN_ROLE_ID, DOC_REVIEW_PERMISSION_ID));

        // 该角色下所有用户的权限缓存立即失效：DEL perm:user:{userId}，一个都不能漏
        ArgumentCaptor<Collection<String>> keyCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(stringRedisTemplate).delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue())
                .as("AC-08.1 perm:user:{userId} 键（直授 + 部门继承的用户都要清）")
                .containsExactlyInAnyOrder(RedisKeys.userPerm(1001L), RedisKeys.userPerm(1002L),
                        RedisKeys.userPerm(1003L));

        // 后续鉴权按新权限生效：缓存已失效 → 回源数据库 → doc:review 通过，并把新集合回写缓存
        assertThat(permissionCacheService.hasPermission(1001L, DOC_REVIEW))
                .as("AC-08.1 清缓存后鉴权读到新权限").isTrue();
        verify(setOperations).add(eq(RedisKeys.userPerm(1001L)), any(String[].class));
        verify(stringRedisTemplate).expire(eq(RedisKeys.userPerm(1001L)), eq(RedisKeys.PERM_TTL));
    }

    /**
     * AC-08.2（异常流）：非 SYS_ADMIN 调授权接口 → 403 NO_PERMISSION。
     */
    @Test
    @DisplayName("AC-08.2 非 SYS_ADMIN 调用授权接口：切面拦截抛 BusinessException NO_PERMISSION（HTTP 403），授予后同一调用放行")
    void ac0802_grantEndpointBlockedForNonSysAdmin() {
        Long staffUserId = 4001L;
        SecurityContext.set(staffUserId, "tok-staff");
        RequiresPermission annotation = UnitTestSupport.requiresPermissionOn(
                RoleController.class, "grant", Long.class, RolePermissionDtoReq.class);
        assertThat(annotation.value()).as("AC-08.2 授权接口的权限码").isEqualTo("sys:role:grant");

        // 该用户的权限缓存内容 = STAFF 角色（没有 sys:role:grant）
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(RedisKeys.userPerm(staffUserId)))
                .thenReturn(Set.of("doc:create", "doc:search", "doc:favorite", "doc:mine"));

        assertThat(permissionCacheService.permissionsOf(staffUserId))
                .as("AC-08.2 Given 当前用户不是 SYS_ADMIN（权限集合里没有 sys:role:grant）")
                .doesNotContain("sys:role:grant");

        BusinessException failure = UnitTestSupport.businessFailure(() -> permissionAspect.check(annotation));

        assertThat(failure.getErrorCode()).as("AC-08.2 错误码 NO_PERMISSION").isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(failure.getErrorCode().getCode()).as("AC-08.2 HTTP 403").isEqualTo(403);

        // 正向对照：把权限集合换成 SYS_ADMIN 的（含 sys:role:grant）后，同一调用放行
        when(setOperations.members(RedisKeys.userPerm(staffUserId))).thenReturn(Set.of("sys:role:grant"));
        assertThatCode(() -> permissionAspect.check(annotation)).doesNotThrowAnyException();
    }

    /**
     * AC-08.3（异常流）：把权限节点移动到自己的子孙节点下 → 400 +「不能将节点移动到其子节点下」。
     */
    @Test
    @DisplayName("AC-08.3 把权限节点移到自己子孙下（成环）：返回 400 与提示「不能将节点移动到其子节点下」，节点与子孙一行未改")
    void ac0803_moveNodeUnderItsOwnDescendantRejected() {
        Permission root = Permission.builder().name("文档中心").code("doc:center").type(PermType.DIR).parentId(0L)
                .ancestors("0").sortOrder(1).build();
        root.setId(ROOT_DIR_ID);
        Permission childMenu = Permission.builder().name("文档检索").code("doc:search").type(PermType.MENU)
                .parentId(ROOT_DIR_ID).ancestors("0,1").path("/docs").sortOrder(2).build();
        childMenu.setId(CHILD_MENU_ID);
        when(permissionRepository.findById(ROOT_DIR_ID)).thenReturn(Optional.of(root));
        when(permissionRepository.findById(CHILD_MENU_ID)).thenReturn(Optional.of(childMenu));

        // 把 1 号节点（根目录）挂到自己的子孙 11 号菜单下；类型改成 BUTTON 以通过层级前置校验，
        // 从而真正走到环检测分支（否则会先撞"层级不合法"那条更早的提示）
        PermissionUpdateDtoReq req = new PermissionUpdateDtoReq(
                "文档中心", PermType.BUTTON, "11", null, null, 1);

        BusinessException failure = UnitTestSupport.businessFailure(
                () -> permissionService.update(ROOT_DIR_ID, req));

        assertThat(failure.getErrorCode()).as("AC-08.3 错误码 BAD_REQUEST").isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(failure.getErrorCode().getCode()).as("AC-08.3 HTTP 400").isEqualTo(400);
        assertThat(failure.getMessage()).as("AC-08.3 提示文案").isEqualTo("不能将节点移动到其子节点下");

        // 环没有被写进库：节点自身与子孙的 parent_id / ancestors 一律未变，且没有任何写操作
        assertThat(root.getParentId()).isZero();
        assertThat(root.getAncestors()).isEqualTo("0");
        assertThat(root.getType()).isEqualTo(PermType.DIR);
        assertThat(childMenu.getParentId()).isEqualTo(ROOT_DIR_ID);
        assertThat(childMenu.getAncestors()).isEqualTo("0,1");
        verify(permissionRepository, never()).saveAndFlush(any(Permission.class));
        verify(permissionRepository, never()).saveAll(any());
        verify(permissionRepository, never()).findByAncestorsPath(any());
    }
}
