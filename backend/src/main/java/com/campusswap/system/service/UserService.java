package com.campusswap.system.service;

import com.campusswap.common.api.PageVo;
import com.campusswap.entity.User;
import com.campusswap.system.dto.UserCreateDtoReq;
import com.campusswap.system.dto.UserPageDtoReq;
import com.campusswap.system.dto.UserPasswordDtoReq;
import com.campusswap.system.dto.UserStatusDtoReq;
import com.campusswap.system.dto.UserUpdateDtoReq;
import com.campusswap.system.vo.UserInfoVo;
import com.campusswap.system.vo.UserVo;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户服务（US-02 账号与组织维护）。
 *
 * @author Zyaire
 */
public interface UserService {

    /**
     * 用户分页列表（关键词 / 状态 / 部门筛选）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<UserVo> page(UserPageDtoReq req);

    /**
     * 新增用户（含角色绑定，BCrypt 落库）。
     *
     * @param req 新增入参
     * @return 新增后的用户（不含密码）
     */
    UserVo create(UserCreateDtoReq req);

    /**
     * 用户详情。
     *
     * @param id 用户 ID
     * @return 用户出参
     */
    UserVo detail(Long id);

    /**
     * 编辑用户（姓名/部门/角色；登录名与密码不可改）。
     *
     * @param id  用户 ID
     * @param req 编辑入参
     * @return 编辑后的用户
     */
    UserVo update(Long id, UserUpdateDtoReq req);

    /**
     * 停用 / 启用用户（非 ACTIVE 时强制下线）。
     *
     * @param id  用户 ID
     * @param req 状态入参
     * @return 变更后的用户
     */
    UserVo updateStatus(Long id, UserStatusDtoReq req);

    /**
     * 重置密码（重置后该用户全部 token 失效）。
     *
     * @param id  用户 ID
     * @param req 新密码入参
     */
    void resetPassword(Long id, UserPasswordDtoReq req);

    /**
     * 按 ID 取用户，不存在则 404。
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    User getUserOrThrow(Long id);

    /**
     * 更新最后登录时间（独立事务，供登录流程调用）。
     *
     * @param userId 用户 ID
     */
    void touchLastLogin(Long userId);

    /**
     * 组装 {@code UserInfoVo}（部门名 / 角色编码 / 权限码一次补齐，权限码顺带写入缓存）。
     *
     * @param user 用户实体
     * @return 登录用户信息
     */
    UserInfoVo buildUserInfo(User user);

    /**
     * 取用户的角色编码集合（一条 SQL）。
     *
     * @param userId 用户 ID
     * @return 角色编码集合
     */
    List<String> roleCodesOf(Long userId);

    /**
     * 批量取用户姓名（一条 {@code IN} 查询 + 内存 Map，供文档列表补作者名）。
     *
     * @param userIds 用户 ID 集合
     * @return 用户 ID → 姓名
     */
    Map<Long, String> realNamesOf(Collection<Long> userIds);

    /**
     * 取单个用户姓名（文档详情补作者名）。
     *
     * @param userId 用户 ID
     * @return 姓名；用户不存在时返回 null
     */
    String realNameOf(Long userId);
}
