package com.campusswap.system.controller;

import com.campusswap.common.api.PageVo;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.system.dto.RoleDtoReq;
import com.campusswap.system.dto.RolePageDtoReq;
import com.campusswap.system.dto.RolePermissionDtoReq;
import com.campusswap.system.service.RoleService;
import com.campusswap.system.vo.RolePermissionVo;
import com.campusswap.system.vo.RoleVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口（API_SPECIFICATION §4.3，共 6 条）。
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /**
     * 角色列表（内置角色在前）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping
    @RequiresPermission("sys:role")
    public ResponseResult<PageVo<RoleVo>> page(@Valid @ModelAttribute RolePageDtoReq req) {
        return ResponseResult.ok(roleService.page(req));
    }

    /**
     * 新增角色。
     *
     * @param req 角色入参
     * @return 新增后的角色
     */
    @PostMapping
    @RequiresPermission("sys:role:add")
    public ResponseResult<RoleVo> create(@Valid @RequestBody RoleDtoReq req) {
        return ResponseResult.ok(roleService.create(req));
    }

    /**
     * 编辑角色（编码不可改）。
     *
     * @param id  角色 ID
     * @param req 角色入参
     * @return 编辑后的角色
     */
    @PutMapping("/{id}")
    @RequiresPermission("sys:role:edit")
    public ResponseResult<RoleVo> update(@PathVariable("id") Long id, @Valid @RequestBody RoleDtoReq req) {
        return ResponseResult.ok(roleService.update(id, req));
    }

    /**
     * 删除角色。
     *
     * @param id 角色 ID
     * @return 无数据
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("sys:role:delete")
    public ResponseResult<Void> delete(@PathVariable("id") Long id) {
        roleService.delete(id);
        return ResponseResult.ok();
    }

    /**
     * 查询角色已授权限（权限树回显）。
     *
     * @param id 角色 ID
     * @return 角色 → 权限 ID 集合
     */
    @GetMapping("/{id}/permissions")
    @RequiresPermission("sys:role")
    public ResponseResult<RolePermissionVo> permissions(@PathVariable("id") Long id) {
        return ResponseResult.ok(roleService.permissionsOf(id));
    }

    /**
     * 角色授权（覆盖式保存，保存后即时生效）。
     *
     * @param id  角色 ID
     * @param req 权限 ID 集合
     * @return 无数据
     */
    @PutMapping("/{id}/permissions")
    @RequiresPermission("sys:role:grant")
    public ResponseResult<Void> grant(@PathVariable("id") Long id,
                                      @Valid @RequestBody RolePermissionDtoReq req) {
        roleService.grantPermissions(id, req);
        return ResponseResult.ok();
    }
}
