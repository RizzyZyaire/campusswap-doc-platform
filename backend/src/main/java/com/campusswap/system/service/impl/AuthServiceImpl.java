package com.campusswap.system.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.common.util.TxUtil;
import com.campusswap.entity.User;
import com.campusswap.entity.enums.UserStatus;
import com.campusswap.system.dto.LoginDtoReq;
import com.campusswap.system.dto.PasswordChangeDtoReq;
import com.campusswap.system.repository.UserRepository;
import com.campusswap.system.service.AuthService;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.TokenService;
import com.campusswap.system.service.UserService;
import com.campusswap.system.vo.LoginVo;
import com.campusswap.system.vo.UserInfoVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现。
 *
 * <p>安全口径：用户名不存在与密码错误返回<b>完全一致</b>的 401 文案（防用户名枚举，NFR-S2）；
 * 账号状态校验放在密码校验<b>之后</b>，避免未通过密码的人探知账号状态。</p>
 *
 * <p>事务口径：本类<b>不</b>加类级事务 —— 登录流程里既有 DB 写（{@code last_login_at}，由
 * {@link UserService#touchLastLogin(Long)} 的独立事务负责）又有 Redis 写（token、权限缓存），
 * 按 ARCHITECTURE §8「事务内不写 Redis」把它们拆开；自助改密是本类唯一<b>方法级</b>事务
 * （DB 写 + 提交后清 token，见 {@link #changePassword(PasswordChangeDtoReq)}）。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final UserService userService;
    private final TokenService tokenService;
    private final PermissionCacheService permissionCacheService;

    /**
     * 登录。
     *
     * @param req 登录入参
     * @return token + 用户信息
     */
    @Override
    public LoginVo login(LoginDtoReq req) {
        String username = req.username().trim();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.debug("登录失败（密码错误）: username={}", username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "账号已停用或冻结，请联系系统管理员");
        }

        userService.touchLastLogin(user.getId());
        String token = tokenService.issue(user.getId());
        UserInfoVo userInfo = userService.buildUserInfo(user);
        log.info("用户登录成功: userId={}, username={}, roles={}", user.getId(), username, userInfo.getRoles());
        return new LoginVo(token, userInfo, userInfo.getRoles(), userInfo.getPermissions());
    }

    /**
     * 登出（幂等）。
     *
     * @param token 当前请求携带的 token
     */
    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        tokenService.revoke(token);
    }

    /**
     * 获取当前登录用户。
     *
     * @return 当前用户信息
     */
    @Override
    public UserInfoVo currentUser() {
        Long userId = SecurityContext.requireUserId();
        User user = userService.getUserOrThrow(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            permissionCacheService.evict(userId);
            throw new BusinessException(ErrorCode.USER_DISABLED, "账号已停用或冻结，请联系系统管理员");
        }
        return userService.buildUserInfo(user);
    }

    /**
     * 自助修改密码（API_SPECIFICATION §9.1，登录即可，仅本人）。
     *
     * <p>顺序：取当前登录用户 → BCrypt 校验旧密码（不通过 400「原密码不正确」）→ 写入新哈希 →
     * <b>事务提交后</b>清空 {@code user:tokens:{userId}}（{@link TxUtil#afterCommit}，ARCHITECTURE §8：
     * 事务内不写 Redis）。清空的是该用户的<b>全部</b> token，因此改密后旧 token 立即 401，
     * 必须用新密码重新登录（与管理员重置密码同一条安全口径）。</p>
     *
     * @param req 改密入参（旧密码 + 新密码）
     */
    @Override
    @Transactional
    public void changePassword(PasswordChangeDtoReq req) {
        Long userId = SecurityContext.requireUserId();
        User user = userService.getUserOrThrow(userId);
        if (!passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            log.warn("自助改密失败（原密码不正确）: userId={}", userId);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.saveAndFlush(user);
        TxUtil.afterCommit(() -> tokenService.revokeAll(userId));
        log.info("自助改密成功: userId={}", userId);
    }
}
