package com.campusswap.document.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.document.dto.CategoryCreateDtoReq;
import com.campusswap.document.dto.CategoryUpdateDtoReq;
import com.campusswap.document.service.CategoryService;
import com.campusswap.document.vo.CategoryVo;
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
 * 分类接口（API_SPECIFICATION §4.8.1 ~ §4.8.4）。
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 分类树（树形接口返回数组，不分页）。
     *
     * @return 根节点数组
     */
    @GetMapping("/tree")
    @RequiresPermission("doc:search")
    public ResponseResult<List<CategoryVo>> tree() {
        return ResponseResult.ok(categoryService.tree());
    }

    /**
     * 新增分类。
     *
     * @param req 新增入参
     * @return 新增后的分类
     */
    @PostMapping
    @RequiresPermission("doc:category:edit")
    public ResponseResult<CategoryVo> create(@Valid @RequestBody CategoryCreateDtoReq req) {
        return ResponseResult.ok(categoryService.create(req));
    }

    /**
     * 编辑分类。
     *
     * @param id  分类 ID
     * @param req 编辑入参
     * @return 编辑后的分类
     */
    @PutMapping("/{id}")
    @RequiresPermission("doc:category:edit")
    public ResponseResult<CategoryVo> update(@PathVariable("id") Long id,
                                             @Valid @RequestBody CategoryUpdateDtoReq req) {
        return ResponseResult.ok(categoryService.update(id, req));
    }

    /**
     * 删除分类。
     *
     * @param id 分类 ID
     * @return 无数据
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("doc:category:edit")
    public ResponseResult<Void> delete(@PathVariable("id") Long id) {
        categoryService.delete(id);
        return ResponseResult.ok();
    }
}
