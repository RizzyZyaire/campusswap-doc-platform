package com.campusswap.system.service.impl;

import com.campusswap.common.security.RedisKeys;
import com.campusswap.entity.DeptRole;
import com.campusswap.entity.UserRole;
import com.campusswap.system.repository.DeptRoleRepository;
import com.campusswap.system.repository.PermissionRepository;
import com.campusswap.system.repository.UserRepository;
import com.campusswap.system.repository.UserRoleRepository;
import com.campusswap.system.service.PermissionCacheService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 权限缓存实现（ARCHITECTURE §6.2 / §6.3）。
 *
 * <p>合并算法用一条 SQL 完成（{@link PermissionRepository#findEffectivePermCodes(Long)}），
 * 避免"查角色 → 查角色权限 → 查直授权限"多轮往返。</p>
 *
 * <p>降级策略：Redis 不可用时只打 WARN 并直查数据库，功能不中断（只变慢）。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionCacheServiceImpl implements PermissionCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final DeptRoleRepository deptRoleRepository;

    /**
     * 取用户权限码：Redis 优先，未命中回源并回写。
     *
     * @param userId 用户 ID
     * @return 权限码集合（按 code 升序）
     */
    @Override
    public List<String> permissionsOf(Long userId) {
        if (userId == null) {
            return List.of();
        }
        String key = RedisKeys.userPerm(userId);
        try {
            Set<String> cached = stringRedisTemplate.opsForSet().members(key);
            if (cached != null && !cached.isEmpty()) {
                return cached.stream().sorted().toList();
            }
        } catch (Exception ex) {
            log.warn("读取权限缓存失败，降级直查数据库: userId={}, cause={}", userId, ex.getMessage());
            return loadFromDb(userId);
        }

        List<String> codes = loadFromDb(userId);
        if (!codes.isEmpty()) {
            try {
                stringRedisTemplate.opsForSet().add(key, codes.toArray(String[]::new));
                stringRedisTemplate.expire(key, RedisKeys.PERM_TTL);
            } catch (Exception ex) {
                log.warn("回写权限缓存失败: userId={}, cause={}", userId, ex.getMessage());
            }
        }
        return codes;
    }

    /**
     * 鉴权关卡③：是否拥有某权限码。
     *
     * @param userId 用户 ID
     * @param code   权限码
     * @return true = 拥有
     */
    @Override
    public boolean hasPermission(Long userId, String code) {
        if (userId == null || code == null || code.isBlank()) {
            return false;
        }
        return permissionsOf(userId).contains(code);
    }

    /**
     * 删除单个用户的权限缓存。
     *
     * @param userId 用户 ID
     */
    @Override
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(RedisKeys.userPerm(userId));
        } catch (Exception ex) {
            log.warn("删除权限缓存失败: userId={}, cause={}", userId, ex.getMessage());
        }
    }

    /**
     * 批量删除权限缓存（一次 DEL 多键）。
     *
     * @param userIds 用户 ID 集合
     */
    @Override
    public void evictAll(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<String> keys = userIds.stream().filter(Objects::nonNull)
                .distinct().map(RedisKeys::userPerm).toList();
        if (keys.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.delete(keys);
            log.debug("已清理 {} 个用户的权限缓存", keys.size());
        } catch (Exception ex) {
            log.warn("批量删除权限缓存失败: count={}, cause={}", keys.size(), ex.getMessage());
        }
    }

    /**
     * 角色授权变更：清该角色下全部用户（直授该角色的 + 部门绑定该角色的）。
     *
     * @param roleId 角色 ID
     */
    @Override
    public void evictByRoleId(Long roleId) {
        if (roleId == null) {
            return;
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (UserRole userRole : userRoleRepository.findByRoleId(roleId)) {
            userIds.add(userRole.getUserId());
        }
        List<Long> deptIds = deptRoleRepository.findByRoleId(roleId).stream()
                .map(DeptRole::getDeptId).toList();
        if (!deptIds.isEmpty()) {
            userIds.addAll(userRepository.findIdsByDeptIdIn(deptIds));
        }
        evictAll(userIds);
    }

    /**
     * 部门绑定角色变更：清该部门下全部用户。
     *
     * @param deptId 部门 ID
     */
    @Override
    public void evictByDeptId(Long deptId) {
        if (deptId == null) {
            return;
        }
        evictAll(userRepository.findIdsByDeptId(deptId));
    }

    /**
     * 回源数据库计算权限码（一条 SQL）。
     *
     * @param userId 用户 ID
     * @return 权限码集合
     */
    private List<String> loadFromDb(Long userId) {
        return permissionRepository.findEffectivePermCodes(userId);
    }
}
