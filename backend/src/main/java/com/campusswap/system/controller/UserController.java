package com.campusswap.system.controller;

import com.campusswap.common.api.PageVo;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.system.dto.UserCreateDtoReq;
import com.campusswap.system.dto.UserPageDtoReq;
import com.campusswap.system.dto.UserPasswordDtoReq;
import com.campusswap.system.dto.UserStatusDtoReq;
import com.campusswap.system.dto.UserUpdateDtoReq;
import com.campusswap.system.service.UserService;
import com.campusswap.system.vo.UserVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（API_SPECIFICATION §4.2，共 6 条）。
 *
 * <p>所有接口都标了权限点，由 {@code PermissionAspect} 统一校验（鉴权关卡③）；
 * 归属校验（关卡④）在本模块体现为「不能改自己的账号状态」等业务规则。</p>
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户列表。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping
    @RequiresPermission("sys:user")
    public ResponseResult<PageVo<UserVo>> page(@Valid @ModelAttribute UserPageDtoReq req) {
        return ResponseResult.ok(userService.page(req));
    }

    /**
     * 新增用户。
     *
     * @param req 新增入参
     * @return 新增后的用户
     */
    @PostMapping
    @RequiresPermission("sys:user:add")
    public ResponseResult<UserVo> create(@Valid @RequestBody UserCreateDtoReq req) {
        return ResponseResult.ok(userService.create(req));
    }

    /**
     * 用户详情。
     *
     * @param id 用户 ID（字符串形式的数字）
     * @return 用户出参
     */
    @GetMapping("/{id}")
    @RequiresPermission("sys:user")
    public ResponseResult<UserVo> detail(@PathVariable("id") Long id) {
        return ResponseResult.ok(userService.detail(id));
    }

    /**
     * 编辑用户（登录名与密码不可改）。
     *
     * @param id  用户 ID
     * @param req 编辑入参
     * @return 编辑后的用户
     */
    @PutMapping("/{id}")
    @RequiresPermission("sys:user:edit")
    public ResponseResult<UserVo> update(@PathVariable("id") Long id,
                                         @Valid @RequestBody UserUpdateDtoReq req) {
        return ResponseResult.ok(userService.update(id, req));
    }

    /**
     * 停用/启用用户。
     *
     * @param id  用户 ID
     * @param req 状态入参
     * @return 变更后的用户
     */
    @PutMapping("/{id}/status")
    @RequiresPermission("sys:user:disable")
    public ResponseResult<UserVo> updateStatus(@PathVariable("id") Long id,
                                               @Valid @RequestBody UserStatusDtoReq req) {
        return ResponseResult.ok(userService.updateStatus(id, req));
    }

    /**
     * 重置密码（重置后该用户全部 token 失效）。
     *
     * @param id  用户 ID
     * @param req 新密码入参
     * @return 无数据
     */
    @PutMapping("/{id}/password")
    @RequiresPermission("sys:user:reset")
    public ResponseResult<Void> resetPassword(@PathVariable("id") Long id,
                                              @Valid @RequestBody UserPasswordDtoReq req) {
        userService.resetPassword(id, req);
        return ResponseResult.ok();
    }
}
