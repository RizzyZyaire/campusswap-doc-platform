package com.campusswap.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.entity.User;
import com.campusswap.entity.enums.UserStatus;
import com.campusswap.support.UnitTestSupport;
import com.campusswap.system.dto.LoginDtoReq;
import com.campusswap.system.repository.UserRepository;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.TokenService;
import com.campusswap.system.service.UserService;
import com.campusswap.system.service.impl.AuthServiceImpl;
import com.campusswap.system.vo.LoginVo;
import com.campusswap.system.vo.UserInfoVo;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * US-01 登录认证（AC-01.1 / AC-01.2 / AC-01.3）。
 *
 * <p><b>为什么在这一层测</b>：三条断言的判定全部发生在 {@code AuthServiceImpl.login} ——
 * 「查用户 → 比密码 → 查状态 → 发 token → 组装用户信息 VO」这条链没有 Controller 逻辑，
 * 控制器只是把 Service 的返回值包进 {@code ResponseResult}；错误码 → HTTP 状态码的映射由
 * {@code GlobalExceptionHandler#handleBusiness}（{@code ErrorCode.code} 即 HTTP 码）统一承担。
 * 因此这里直接 mock 仓储与协作者，断言 Service 的返回值、抛出的 {@code ErrorCode}
 * 以及"未产生副作用"（不发 token / 不刷新最后登录时间）。</p>
 *
 * <p><b>接口层由哪条机检脚本兜底</b>：{@code docs/03-qa-review/verify-m3-http.ps1} 步骤
 * <i>[1] Auth: login / me / logout</i> —— {@code login.http/login.token.32chars/login.roles[0]/
 * login.permission.count(39)/login.no.passwordHash}（AC-01.1 的 HTTP 证据）、
 * {@code wrong-password.http} + {@code wrong-password.same-message-as-unknown-user} +
 * {@code unknown-user.same-message}（AC-01.2 的 HTTP 证据）、
 * {@code user.status.disabled-login.http/.code}（步骤 [3]，AC-01.3 的 HTTP 证据）。
 * 本类补的是脚本看不到的 Service 内部口径：错误码枚举本身、失败路径"不发 token / 不刷新 last_login_at /
 * 不组装 VO"这三条副作用的缺席，以及"登录出参整条继承链上没有 passwordHash 字段"的结构性断言。</p>
 *
 * @author Zyaire
 */
@ExtendWith(MockitoExtension.class)
class Ac01LoginTest {

    /** 用例里的正常账号。 */
    private static final Long USER_ID = 1001L;

    /** 用例里的停用账号。 */
    private static final Long DISABLED_USER_ID = 1002L;

    /** 明文密码。 */
    private static final String PASSWORD = "Staff@123";

    /** 伪造的 BCrypt 哈希（本测试不比真实哈希，比对由 mock 的 PasswordEncoder 负责）。 */
    private static final String HASH = "$2a$10$Z2x0Y2FtcHVzc3dhcC1mYWtlLWhhc2g";

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private TokenService tokenService;

    @Mock
    private PermissionCacheService permissionCacheService;

    @InjectMocks
    private AuthServiceImpl authService;

    /**
     * AC-01.1（正常流）：正确用户名 + 密码 → token + 用户信息 VO，且响应体不含 passwordHash。
     */
    @Test
    @DisplayName("AC-01.1 正确用户名密码：返回 200 语义的 token + 用户信息 VO（roles/permissions/deptName），响应体不含 passwordHash 字段")
    void ac0101_loginWithRightPasswordReturnsTokenAndUserInfo() {
        User user = user(USER_ID, "u1001", UserStatus.ACTIVE);
        List<String> roles = List.of("STAFF");
        List<String> permissions = List.of("doc:create", "doc:search", "doc:favorite");
        UserInfoVo userInfo = UserInfoVo.of(user, "计算机学院", roles, permissions);

        when(userRepository.findByUsername("u1001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(tokenService.issue(USER_ID)).thenReturn("8f14e45fceea167a5a36dedd4bea2543");
        when(userService.buildUserInfo(user)).thenReturn(userInfo);

        LoginVo vo = authService.login(new LoginDtoReq("u1001", PASSWORD));

        assertThat(vo.token()).as("AC-01.1 返回 token").isEqualTo("8f14e45fceea167a5a36dedd4bea2543");
        assertThat(vo.roles()).as("AC-01.1 顶层 roles 角色码数组").containsExactly("STAFF");
        assertThat(vo.permissions()).as("AC-01.1 顶层 permissions 权限码数组")
                .containsExactly("doc:create", "doc:search", "doc:favorite");
        assertThat(vo.userInfo().getDeptName()).as("AC-01.1 用户信息含 deptName").isEqualTo("计算机学院");
        assertThat(vo.userInfo().getRoles()).isEqualTo(vo.roles());
        assertThat(vo.userInfo().getPermissions()).isEqualTo(vo.permissions());
        assertThat(vo.userInfo().getUsername()).isEqualTo("u1001");

        // 副作用：刷新最后登录时间 + 把 token 写进 Redis（TokenService 内部两把键）
        verify(userService).touchLastLogin(USER_ID);
        verify(tokenService).issue(USER_ID);

        // AC-01.1 的"响应体中不含 passwordHash"：登录出参整条继承链上不存在该字段，也没有对应 getter。
        // 先用 User 实体做对照，证明这次扫描不是空扫。
        assertThat(UnitTestSupport.fieldNames(User.class)).as("对照：实体上确实有 passwordHash").contains("passwordHash");
        assertThat(UnitTestSupport.fieldNames(LoginVo.class, UserInfoVo.class, AuditVo.class))
                .as("登录响应体（LoginVo → UserInfoVo → AuditVo）不得含 passwordHash")
                .doesNotContain("passwordHash");
        assertThat(Arrays.stream(UserInfoVo.class.getMethods()).map(java.lang.reflect.Method::getName))
                .as("UserInfoVo 不得暴露任何 password 相关 getter")
                .noneMatch(name -> name.toLowerCase().contains("password"));
    }

    /**
     * AC-01.2（异常流）：错误密码 → 401 UNAUTHORIZED，且文案与"用户不存在"完全一致。
     */
    @Test
    @DisplayName("AC-01.2 错误密码：返回 401 UNAUTHORIZED，且提示文案与「用户不存在」逐字相同（防用户名枚举），不签发 token")
    void ac0102_wrongPasswordUnauthorizedWithIndistinguishableMessage() {
        User user = user(USER_ID, "u1001", UserStatus.ACTIVE);
        when(userRepository.findByUsername("u1001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Wrong@999", HASH)).thenReturn(false);

        BusinessException wrongPassword =
                UnitTestSupport.businessFailure(() -> authService.login(new LoginDtoReq("u1001", "Wrong@999")));
        assertThat(wrongPassword.getErrorCode()).as("AC-01.2 错误码 UNAUTHORIZED").isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(wrongPassword.getErrorCode().getCode()).as("AC-01.2 HTTP 401").isEqualTo(401);

        // 另一半：用户名不存在时必须是同一条文案（否则攻击者可枚举工号）
        when(userRepository.findByUsername("u9999")).thenReturn(Optional.empty());
        BusinessException noSuchUser =
                UnitTestSupport.businessFailure(() -> authService.login(new LoginDtoReq("u9999", "Wrong@999")));
        assertThat(noSuchUser.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(wrongPassword.getMessage())
                .as("AC-01.2 「密码错误」与「用户不存在」的提示必须逐字相同")
                .isEqualTo("用户名或密码错误")
                .isEqualTo(noSuchUser.getMessage());

        // 副作用：两条失败路径都不得签发 token、不得刷新登录时间、不得回填用户信息
        verify(tokenService, never()).issue(any());
        verify(userService, never()).touchLastLogin(any());
        verify(userService, never()).buildUserInfo(any());
    }

    /**
     * AC-01.3（异常流）：status = DISABLED 且密码正确 → 403 USER_DISABLED。
     */
    @Test
    @DisplayName("AC-01.3 DISABLED 账号用正确密码登录：返回 403 USER_DISABLED，且不签发 token、不刷新最后登录时间")
    void ac0103_disabledUserForbiddenWithUserDisabledCode() {
        User disabled = user(DISABLED_USER_ID, "u1002", UserStatus.DISABLED);
        when(userRepository.findByUsername("u1002")).thenReturn(Optional.of(disabled));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

        BusinessException failure =
                UnitTestSupport.businessFailure(() -> authService.login(new LoginDtoReq("u1002", PASSWORD)));

        assertThat(failure.getErrorCode()).as("AC-01.3 错误码 USER_DISABLED").isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(failure.getErrorCode().getCode()).as("AC-01.3 HTTP 403").isEqualTo(403);
        assertThat(failure.getMessage()).contains("账号已停用");

        // 停用账号连"登录成功"的副作用都不能产生
        verify(tokenService, never()).issue(any());
        verify(userService, never()).touchLastLogin(any());
    }

    /**
     * 构造一个用户实体（审计列与哈希按用例需要填）。
     *
     * @param id       用户 ID
     * @param username 登录名
     * @param status   账号状态
     * @return 用户实体
     */
    private static User user(Long id, String username, UserStatus status) {
        User user = User.builder()
                .username(username)
                .passwordHash(HASH)
                .realName("张三")
                .deptId(10L)
                .status(status)
                .build();
        user.setId(id);
        user.setCreatedAt(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
        user.setCreatedBy(0L);
        user.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
        user.setUpdatedBy(0L);
        return user;
    }
}
