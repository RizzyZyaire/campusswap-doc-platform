package com.campusswap.system.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.BearerToken;
import com.campusswap.system.dto.LoginDtoReq;
import com.campusswap.system.service.AuthService;
import com.campusswap.system.vo.LoginVo;
import com.campusswap.system.vo.UserInfoVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（API_SPECIFICATION §4.1，共 3 条）。
 *
 * <p>Controller 只做：路由、{@code @Valid}、取当前用户、权限点标注、包 {@code ResponseResult}。</p>
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录（公开接口，无需 token）。
     *
     * @param req 登录入参
     * @return token + 用户信息 + 权限码
     */
    @PostMapping("/login")
    public ResponseResult<LoginVo> login(@Valid @RequestBody LoginDtoReq req) {
        return ResponseResult.ok(authService.login(req));
    }

    /**
     * 登出（幂等：token 已失效时再调也返回成功）。
     *
     * <p>本接口被登录拦截器<b>排除</b>（见 {@code WebMvcConfig}），因此这里直接从请求头取 token，
     * 而不是从 {@code SecurityContext} 取——否则"重复登出返回 200"会被拦截器的 401 挡掉。</p>
     *
     * @param authorization 请求头 {@code Authorization: Bearer <token>}
     * @return 无数据
     */
    @PostMapping("/logout")
    public ResponseResult<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(BearerToken.parse(authorization));
        return ResponseResult.ok();
    }

    /**
     * 获取当前登录用户（页面刷新后重建用户态与权限码）。
     *
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public ResponseResult<UserInfoVo> me() {
        return ResponseResult.ok(authService.currentUser());
    }
}
