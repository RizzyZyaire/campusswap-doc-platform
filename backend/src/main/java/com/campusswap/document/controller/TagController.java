package com.campusswap.document.controller;

import com.campusswap.common.api.PageVo;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.document.dto.TagCreateDtoReq;
import com.campusswap.document.dto.TagPageDtoReq;
import com.campusswap.document.dto.TagUpdateDtoReq;
import com.campusswap.document.service.TagService;
import com.campusswap.document.vo.TagVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签接口（API_SPECIFICATION §4.8.5 ~ §4.8.8）。
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /**
     * 标签列表（热度优先）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping
    @RequiresPermission("doc:search")
    public ResponseResult<PageVo<TagVo>> page(@Valid @ModelAttribute TagPageDtoReq req) {
        return ResponseResult.ok(tagService.page(req));
    }

    /**
     * 新增标签。
     *
     * @param req 新增入参
     * @return 新增后的标签
     */
    @PostMapping
    @RequiresPermission("doc:tag:edit")
    public ResponseResult<TagVo> create(@Valid @RequestBody TagCreateDtoReq req) {
        return ResponseResult.ok(tagService.create(req));
    }

    /**
     * 编辑标签。
     *
     * @param id  标签 ID
     * @param req 编辑入参
     * @return 编辑后的标签
     */
    @PutMapping("/{id}")
    @RequiresPermission("doc:tag:edit")
    public ResponseResult<TagVo> update(@PathVariable("id") Long id,
                                        @Valid @RequestBody TagUpdateDtoReq req) {
        return ResponseResult.ok(tagService.update(id, req));
    }

    /**
     * 删除标签。
     *
     * @param id 标签 ID
     * @return 无数据
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("doc:tag:edit")
    public ResponseResult<Void> delete(@PathVariable("id") Long id) {
        tagService.delete(id);
        return ResponseResult.ok();
    }
}
