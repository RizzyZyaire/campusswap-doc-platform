package com.campusswap.system.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.system.dto.DeptCreateDtoReq;
import com.campusswap.system.dto.DeptRoleDtoReq;
import com.campusswap.system.dto.DeptUpdateDtoReq;
import com.campusswap.system.service.DeptService;
import com.campusswap.system.vo.DeptRoleVo;
import com.campusswap.system.vo.DeptVo;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门管理接口（API_SPECIFICATION §4.5，共 6 条）。
 *
 * <p>命名分工：本条「部门绑定角色」用 {@code roleIds}（角色 <b>ID</b> 数组），
 * 与用户接口的 {@code roles}（角色 <b>编码</b> 数组）不可互换。</p>
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    /**
     * 部门树。
     *
     * @return 根节点数组
     */
    @GetMapping("/tree")
    @RequiresPermission("sys:dept")
    public ResponseResult<List<DeptVo>> tree() {
        return ResponseResult.ok(deptService.tree());
    }

    /**
     * 新增部门。
     *
     * @param req 新增入参
     * @return 新增后的部门
     */
    @PostMapping
    @RequiresPermission("sys:dept:add")
    public ResponseResult<DeptVo> create(@Valid @RequestBody DeptCreateDtoReq req) {
        return ResponseResult.ok(deptService.create(req));
    }

    /**
     * 编辑部门。
     *
     * @param id  部门 ID
     * @param req 编辑入参
     * @return 编辑后的部门
     */
    @PutMapping("/{id}")
    @RequiresPermission("sys:dept:edit")
    public ResponseResult<DeptVo> update(@PathVariable("id") Long id,
                                         @Valid @RequestBody DeptUpdateDtoReq req) {
        return ResponseResult.ok(deptService.update(id, req));
    }

    /**
     * 删除部门。
     *
     * @param id 部门 ID
     * @return 无数据
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("sys:dept:delete")
    public ResponseResult<Void> delete(@PathVariable("id") Long id) {
        deptService.delete(id);
        return ResponseResult.ok();
    }

    /**
     * 查询部门已绑定角色。
     *
     * @param id 部门 ID
     * @return 部门 → 角色 ID 集合
     */
    @GetMapping("/{id}/roles")
    @RequiresPermission("sys:dept")
    public ResponseResult<DeptRoleVo> roles(@PathVariable("id") Long id) {
        return ResponseResult.ok(deptService.rolesOf(id));
    }

    /**
     * 部门绑定角色（覆盖式保存，保存后该部门用户权限即时生效）。
     *
     * @param id  部门 ID
     * @param req 角色 ID 集合
     * @return 无数据
     */
    @PutMapping("/{id}/roles")
    @RequiresPermission("sys:role:grant")
    public ResponseResult<Void> bindRoles(@PathVariable("id") Long id,
                                          @Valid @RequestBody DeptRoleDtoReq req) {
        deptService.bindRoles(id, req);
        return ResponseResult.ok();
    }
}
