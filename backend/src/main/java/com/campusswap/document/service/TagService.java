package com.campusswap.document.service;

import com.campusswap.common.api.PageVo;
import com.campusswap.document.dto.TagCreateDtoReq;
import com.campusswap.document.dto.TagPageDtoReq;
import com.campusswap.document.dto.TagUpdateDtoReq;
import com.campusswap.document.vo.TagVo;

/**
 * 标签服务（US-04 检索维度）：字典分页 + 增删改。
 *
 * @author Zyaire
 */
public interface TagService {

    /**
     * 标签分页（use_count 倒序 → id 升序）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<TagVo> page(TagPageDtoReq req);

    /**
     * 新增标签（名称全局唯一）。
     *
     * @param req 新增入参
     * @return 新增后的标签
     */
    TagVo create(TagCreateDtoReq req);

    /**
     * 编辑标签（改名等价于合并同义标签）。
     *
     * @param id  标签 ID
     * @param req 编辑入参
     * @return 编辑后的标签
     */
    TagVo update(Long id, TagUpdateDtoReq req);

    /**
     * 删除标签（同事务清理文档关联关系）。
     *
     * @param id 标签 ID
     */
    void delete(Long id);
}
