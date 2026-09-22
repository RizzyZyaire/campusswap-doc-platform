package com.campusswap.system.service;

import com.campusswap.entity.Dept;
import com.campusswap.system.dto.DeptCreateDtoReq;
import com.campusswap.system.dto.DeptRoleDtoReq;
import com.campusswap.system.dto.DeptUpdateDtoReq;
import com.campusswap.system.vo.DeptRoleVo;
import com.campusswap.system.vo.DeptVo;
import java.util.List;

/**
 * 部门服务（US-02 组织维护 + US-08 部门授权）。
 *
 * @author Zyaire
 */
public interface DeptService {

    /**
     * 部门树，<b>一条 SQL</b>查全后在内存组树。
     *
     * @return 根节点数组
     */
    List<DeptVo> tree();

    /**
     * 新增部门（同级不可重名，{@code ancestors} 自动计算）。
     *
     * @param req 新增入参
     * @return 新增后的部门
     */
    DeptVo create(DeptCreateDtoReq req);

    /**
     * 编辑部门（支持移动，禁止移到自身子孙下，事务内级联重写子孙 {@code ancestors}）。
     *
     * @param id  部门 ID
     * @param req 编辑入参
     * @return 编辑后的部门
     */
    DeptVo update(Long id, DeptUpdateDtoReq req);

    /**
     * 删除部门（有子部门或在职用户时拒绝）。
     *
     * @param id 部门 ID
     */
    void delete(Long id);

    /**
     * 查询部门已绑定角色。
     *
     * @param id 部门 ID
     * @return 部门 → 角色 ID 集合
     */
    DeptRoleVo rolesOf(Long id);

    /**
     * 部门绑定角色（覆盖式保存，提交后清该部门用户权限缓存）。
     *
     * @param id  部门 ID
     * @param req 角色 ID 集合
     */
    void bindRoles(Long id, DeptRoleDtoReq req);

    /**
     * 按 ID 取部门，不存在则 404。
     *
     * @param id 部门 ID
     * @return 部门实体
     */
    Dept getDeptOrThrow(Long id);
}
