package com.campusswap.system.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.Dept;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 部门节点出参（API_SPECIFICATION §4.5.1，GLOSSARY §3.7 的 {@code DeptVo}）。
 *
 * <p>部门树同样一次查全 + 内存组树（SQL 预算 1 条）；
 * 部门人数<b>不</b>在本 VO 返回（删除校验在服务端做）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DeptVo extends AuditVo {

    /** 部门 ID（字符串）。 */
    private String id;

    /** 部门名称。 */
    private String name;

    /** 父部门 ID（根为 "0"）。 */
    private String parentId;

    /** 祖级路径。 */
    private String ancestors;

    /** 同级排序号。 */
    private Integer sortOrder;

    /** 子部门（叶子为空数组）。 */
    private List<DeptVo> children = new ArrayList<>();

    /**
     * 由实体组装（不含 {@code children}，由 Service 组树时填）。
     *
     * @param dept 部门实体
     * @return 部门节点出参
     */
    public static DeptVo of(Dept dept) {
        DeptVo vo = new DeptVo();
        vo.fillAudit(dept);
        vo.id = IdUtil.toStr(dept.getId());
        vo.name = dept.getName();
        vo.parentId = IdUtil.toStr(dept.getParentId());
        vo.ancestors = dept.getAncestors();
        vo.sortOrder = dept.getSortOrder();
        return vo;
    }
}
