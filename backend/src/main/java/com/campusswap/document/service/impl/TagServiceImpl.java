package com.campusswap.document.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.document.dto.TagCreateDtoReq;
import com.campusswap.document.dto.TagPageDtoReq;
import com.campusswap.document.dto.TagUpdateDtoReq;
import com.campusswap.document.repository.DocumentTagRelRepository;
import com.campusswap.document.repository.TagRepository;
import com.campusswap.document.repository.TagSpecifications;
import com.campusswap.document.service.TagService;
import com.campusswap.document.vo.TagVo;
import com.campusswap.entity.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签服务实现。
 *
 * <p>删除标签是<b>物理意义</b>的：{@code @SQLDelete} 会置 {@code deleted = 1} 并改写 name 释放唯一键，
 * 同时同一事务里清掉 {@code doc_document_tag_rel} 的关联，保证「标签列表」与文档标签展示一致
 * （API_SPECIFICATION §4.8.8）。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;
    private final DocumentTagRelRepository documentTagRelRepository;

    /**
     * 标签分页（热度优先）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<TagVo> page(TagPageDtoReq req) {
        Page<Tag> page = tagRepository.findAll(
                TagSpecifications.of(req.getKeyword()),
                req.toPageable(Sort.by(Sort.Order.desc("useCount"), Sort.Order.asc("id"))));
        return PageVo.of(page.getContent().stream().map(TagVo::of).toList(),
                page.getTotalElements(), page.getNumber() + 1, page.getSize());
    }

    /**
     * 新增标签。
     *
     * @param req 新增入参
     * @return 新增后的标签
     */
    @Override
    @Transactional
    public TagVo create(TagCreateDtoReq req) {
        String name = req.name().trim();
        if (tagRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "标签已存在，请直接选择");
        }
        Tag tag = Tag.builder().name(name).useCount(0).build();
        tagRepository.saveAndFlush(tag);
        return TagVo.of(tag);
    }

    /**
     * 编辑标签。
     *
     * @param id  标签 ID
     * @param req 编辑入参
     * @return 编辑后的标签
     */
    @Override
    @Transactional
    public TagVo update(Long id, TagUpdateDtoReq req) {
        Tag tag = getTagOrThrow(id);
        String name = req.name().trim();
        if (!name.equals(tag.getName()) && tagRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "标签已存在，请直接选择");
        }
        tag.setName(name);
        tagRepository.saveAndFlush(tag);
        return TagVo.of(tag);
    }

    /**
     * 删除标签（软删除 + 清理文档关联）。
     *
     * @param id 标签 ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Tag tag = getTagOrThrow(id);
        int removed = documentTagRelRepository.deleteByTagId(id);
        tagRepository.delete(tag);
        log.info("删除标签: id={}, name={}, 清理关联={}", id, tag.getName(), removed);
    }

    /**
     * 取标签，不存在则 404。
     *
     * @param id 标签 ID
     * @return 标签实体
     */
    private Tag getTagOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "标签不存在或已被删除");
        }
        return tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "标签不存在或已被删除"));
    }
}
