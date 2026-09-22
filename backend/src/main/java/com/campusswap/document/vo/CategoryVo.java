package com.campusswap.document.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.Category;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 分类节点出参（API_SPECIFICATION §4.8.1，GLOSSARY §3.7 的 {@code CategoryVo}）。
 *
 * <p>分类树一次查全 + 内存组树（SQL 预算 1 条）；最多 3 层（BR-13）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class CategoryVo extends AuditVo {

    /** 分类 ID（字符串）。 */
    private String id;

    /** 分类名称。 */
    private String name;

    /** 父分类 ID（根为 "0"）。 */
    private String parentId;

    /** 祖级路径。 */
    private String ancestors;

    /** 同级排序号。 */
    private Integer sortOrder;

    /** 子分类（叶子为空数组）。 */
    private List<CategoryVo> children = new ArrayList<>();

    /**
     * 由实体组装（不含 children）。
     *
     * @param category 分类实体
     * @return 分类节点出参
     */
    public static CategoryVo of(Category category) {
        CategoryVo vo = new CategoryVo();
        vo.fillAudit(category);
        vo.id = IdUtil.toStr(category.getId());
        vo.name = category.getName();
        vo.parentId = IdUtil.toStr(category.getParentId());
        vo.ancestors = category.getAncestors();
        vo.sortOrder = category.getSortOrder();
        return vo;
    }
}
