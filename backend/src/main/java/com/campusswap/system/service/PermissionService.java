package com.campusswap.system.service;

import com.campusswap.entity.Permission;
import com.campusswap.system.dto.PermissionCreateDtoReq;
import com.campusswap.system.dto.PermissionUpdateDtoReq;
import com.campusswap.system.vo.PermissionVo;
import java.util.List;

/**
 * 权限服务（US-08 权限树与授权）。
 *
 * @author Zyaire
 */
public interface PermissionService {

    /**
     * 全量权限树（三层：DIR → MENU → BUTTON），<b>一条 SQL</b>查全后在内存组树。
     *
     * @return 根节点数组
     */
    List<PermissionVo> tree();

    /**
     * 新增权限节点（{@code ancestors} 由后端计算，层级越级拒绝）。
     *
     * @param req 新增入参
     * @return 新增后的节点
     */
    PermissionVo create(PermissionCreateDtoReq req);

    /**
     * 编辑权限节点（支持子树整体移动，禁止移到自身子孙下）。
     *
     * @param id  权限 ID
     * @param req 编辑入参
     * @return 编辑后的节点
     */
    PermissionVo update(Long id, PermissionUpdateDtoReq req);

    /**
     * 删除权限节点（有子节点或被引用时拒绝）。
     *
     * @param id 权限 ID
     */
    void delete(Long id);

    /**
     * 按 ID 取权限，不存在则 404。
     *
     * @param id 权限 ID
     * @return 权限实体
     */
    Permission getPermissionOrThrow(Long id);
}
