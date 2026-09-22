package com.campusswap.system.service;

import java.util.Collection;
import java.util.List;

/**
 * 权限缓存服务（Redis {@code perm:user:{userId}} Set，TTL 30 分钟）。
 *
 * <p>对应 ARCHITECTURE §6.3 的缓存与失效矩阵：授权变更后必须主动删除，否则用户要等 30 分钟才生效（US-08 AC-08.1 要求即时生效）。</p>
 *
 * @author Zyaire
 */
public interface PermissionCacheService {

    /**
     * 取用户的权限码集合：优先读 Redis，未命中回源数据库并回写。
     *
     * <p>Redis 不可用时降级为直查数据库（打 WARN，功能不中断）。</p>
     *
     * @param userId 用户 ID
     * @return 权限码集合（已按 code 升序去重）
     */
    List<String> permissionsOf(Long userId);

    /**
     * 判断用户是否拥有某权限码（鉴权关卡③用）。
     *
     * @param userId 用户 ID
     * @param code   权限码
     * @return true = 拥有
     */
    boolean hasPermission(Long userId, String code);

    /**
     * 删除单个用户的权限缓存。
     *
     * @param userId 用户 ID
     */
    void evict(Long userId);

    /**
     * 批量删除权限缓存。
     *
     * @param userIds 用户 ID 集合
     */
    void evictAll(Collection<Long> userIds);

    /**
     * 角色授权变更：删除该角色下<b>全部用户</b>的权限缓存。
     *
     * @param roleId 角色 ID
     */
    void evictByRoleId(Long roleId);

    /**
     * 部门绑定角色变更：删除该部门下<b>全部用户</b>的权限缓存。
     *
     * @param deptId 部门 ID
     */
    void evictByDeptId(Long deptId);
}
