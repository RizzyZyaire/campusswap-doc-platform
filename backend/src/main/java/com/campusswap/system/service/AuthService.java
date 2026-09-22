package com.campusswap.system.service;

import com.campusswap.system.dto.LoginDtoReq;
import com.campusswap.system.vo.LoginVo;
import com.campusswap.system.vo.UserInfoVo;

/**
 * 认证服务（US-01 登录/登出/获取当前用户）。
 *
 * @author Zyaire
 */
public interface AuthService {

    /**
     * 登录：校验密码与状态 → 签发 token → 预写权限缓存 → 返回用户信息。
     *
     * @param req 登录入参
     * @return token + 用户信息 + 角色编码 + 权限码
     */
    LoginVo login(LoginDtoReq req);

    /**
     * 登出：让当前 token 立即失效（幂等，重复登出也返回成功）。
     *
     * @param token 当前请求携带的 token
     */
    void logout(String token);

    /**
     * 获取当前登录用户（页面刷新后重建用户态与权限码）。
     *
     * @return 当前用户信息
     */
    UserInfoVo currentUser();
}
