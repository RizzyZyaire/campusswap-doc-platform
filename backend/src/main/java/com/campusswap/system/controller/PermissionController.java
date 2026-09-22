package com.campusswap.system.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.system.dto.PermissionCreateDtoReq;
import com.campusswap.system.dto.PermissionUpdateDtoReq;
import com.campusswap.system.service.PermissionService;
import com.campusswap.system.vo.PermissionVo;
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
 * 权限管理接口（API_SPECIFICATION §4.4，共 4 条）。
 *
 * <p>树形接口返回数组（不分页），一次查全后在 Service 内存组树。</p>
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 权限树。
     *
     * @return 根节点数组
     */
    @GetMapping("/tree")
    @RequiresPermission("sys:perm")
    public ResponseResult<List<PermissionVo>> tree() {
        return ResponseResult.ok(permissionService.tree());
    }

    /**
     * 新增权限节点。
     *
     * @param req 新增入参
     * @return 新增后的节点
     */
    @PostMapping
    @RequiresPermission("sys:perm:add")
    public ResponseResult<PermissionVo> create(@Valid @RequestBody PermissionCreateDtoReq req) {
        return ResponseResult.ok(permissionService.create(req));
    }

    /**
     * 编辑权限节点。
     *
     * @param id  权限 ID
     * @param req 编辑入参
     * @return 编辑后的节点
     */
    @PutMapping("/{id}")
    @RequiresPermission("sys:perm:edit")
    public ResponseResult<PermissionVo> update(@PathVariable("id") Long id,
                                               @Valid @RequestBody PermissionUpdateDtoReq req) {
        return ResponseResult.ok(permissionService.update(id, req));
    }

    /**
     * 删除权限节点。
     *
     * @param id 权限 ID
     * @return 无数据
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("sys:perm:delete")
    public ResponseResult<Void> delete(@PathVariable("id") Long id) {
        permissionService.delete(id);
        return ResponseResult.ok();
    }
}
