package com.campusswap.document.service;

import com.campusswap.document.dto.CategoryCreateDtoReq;
import com.campusswap.document.dto.CategoryUpdateDtoReq;
import com.campusswap.document.vo.CategoryVo;
import java.util.List;

/**
 * 分类服务（US-04 检索维度）：树查询 + 增删改（最多 3 层，BR-13）。
 *
 * @author Zyaire
 */
public interface CategoryService {

    /**
     * 分类树（一次查全 + 内存组树，SQL 预算 1 条）。
     *
     * @return 根节点数组
     */
    List<CategoryVo> tree();

    /**
     * 新增分类（同级不重名，层级 ≤ 3）。
     *
     * @param req 新增入参
     * @return 新增后的分类
     */
    CategoryVo create(CategoryCreateDtoReq req);

    /**
     * 编辑分类（支持移动，禁止移到自身子孙下，级联重写子孙 ancestors）。
     *
     * @param id  分类 ID
     * @param req 编辑入参
     * @return 编辑后的分类
     */
    CategoryVo update(Long id, CategoryUpdateDtoReq req);

    /**
     * 删除分类（有子分类或该分类下仍有未删除文档时拒绝）。
     *
     * @param id 分类 ID
     */
    void delete(Long id);
}
