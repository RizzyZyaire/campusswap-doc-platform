package com.campusswap.system.service;

import com.campusswap.common.api.PageVo;
import com.campusswap.entity.Role;
import com.campusswap.system.dto.RoleDtoReq;
import com.campusswap.system.dto.RolePageDtoReq;
import com.campusswap.system.dto.RolePermissionDtoReq;
import com.campusswap.system.vo.RolePermissionVo;
import com.campusswap.system.vo.RoleVo;

/**
 * 角色服务（US-08 角色与权限授予）。
 *
 * @author Zyaire
 */
public interface RoleService {

    /**
     * 角色分页列表（内置角色在前，再按排序号升序）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<RoleVo> page(RolePageDtoReq req);

    /**
     * 新增角色（{@code is_builtin = 0}，初始无权限）。
     *
     * @param req 角色入参
     * @return 新增后的角色
     */
    RoleVo create(RoleDtoReq req);

    /**
     * 编辑角色（编码不可改；内置角色不可改名）。
     *
     * @param id  角色 ID
     * @param req 角色入参
     * @return 编辑后的角色
     */
    RoleVo update(Long id, RoleDtoReq req);

    /**
     * 删除角色（内置角色或被引用时拒绝）。
     *
     * @param id 角色 ID
     */
    void delete(Long id);

    /**
     * 查询角色已授权限 ID 集合（权限树回显用）。
     *
     * @param id 角色 ID
     * @return 角色 → 权限 ID 集合
     */
    RolePermissionVo permissionsOf(Long id);

    /**
     * 角色授权（覆盖式保存，保存后即时清缓存 → 授权立即生效）。
     *
     * @param id  角色 ID
     * @param req 权限 ID 集合
     */
    void grantPermissions(Long id, RolePermissionDtoReq req);

    /**
     * 按 ID 取角色，不存在则 404。
     *
     * @param id 角色 ID
     * @return 角色实体
     */
    Role getRoleOrThrow(Long id);
}
