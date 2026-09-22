package com.campusswap.document.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.util.IdUtil;
import com.campusswap.document.dto.CategoryCreateDtoReq;
import com.campusswap.document.dto.CategoryUpdateDtoReq;
import com.campusswap.document.repository.CategoryRepository;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.service.CategoryService;
import com.campusswap.document.vo.CategoryVo;
import com.campusswap.entity.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分类服务实现。
 *
 * <p>层级口径（BR-13）：{@code ancestors} 的段数即层级 —— 根分类 {@code "0"} 为第 1 层，
 * 因此「父层级 + 1 ≤ 3」。移动子树时还要算上子树自身高度，避免整体搬过去后超过 3 层。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /** 分类最大层级（BR-13）。 */
    private static final int MAX_DEPTH = 3;

    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;

    /**
     * 分类树：一次查全 + 内存组树（1 条 SQL）。
     *
     * @return 根节点数组
     */
    @Override
    @Transactional(readOnly = true)
    public List<CategoryVo> tree() {
        List<Category> all = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, CategoryVo> voById = new LinkedHashMap<>();
        for (Category category : all) {
            voById.put(category.getId(), CategoryVo.of(category));
        }
        List<CategoryVo> roots = new ArrayList<>();
        for (Category category : all) {
            CategoryVo vo = voById.get(category.getId());
            CategoryVo parent = category.getParentId() == null ? null : voById.get(category.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    /**
     * 新增分类。
     *
     * @param req 新增入参
     * @return 新增后的分类
     */
    @Override
    @Transactional
    public CategoryVo create(CategoryCreateDtoReq req) {
        Long parentId = IdUtil.toLong(req.parentId(), "上级分类ID");
        Category parent = requireParent(parentId);
        String name = req.name().trim();
        if (categoryRepository.existsByParentIdAndName(parentId, name)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同级分类下已存在同名分类");
        }
        int level = levelOf(parent) + 1;
        if (level > MAX_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类最多支持3层");
        }
        Category category = Category.builder()
                .name(name)
                .parentId(parentId)
                .ancestors(childAncestors(parent))
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        categoryRepository.saveAndFlush(category);
        return CategoryVo.of(category);
    }

    /**
     * 编辑分类（支持子树移动）。
     *
     * @param id  分类 ID
     * @param req 编辑入参
     * @return 编辑后的分类
     */
    @Override
    @Transactional
    public CategoryVo update(Long id, CategoryUpdateDtoReq req) {
        Category category = getCategoryOrThrow(id);
        Long parentId = IdUtil.toLong(req.parentId(), "上级分类ID");
        Category parent = requireParent(parentId);
        String name = req.name().trim();
        if (categoryRepository.existsByParentIdAndName(parentId, name)
                && !(parentId.equals(category.getParentId()) && name.equals(category.getName()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同级分类下已存在同名分类");
        }

        String oldPath = category.getAncestors() + "," + category.getId();
        if (parent != null && isSelfOrDescendant(parent, oldPath)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能将分类移动到其子分类下");
        }
        List<Category> descendants = categoryRepository.findByAncestorsPath(oldPath);
        int newLevel = levelOf(parent) + 1;
        if (newLevel + subtreeExtraDepth(descendants, oldPath) > MAX_DEPTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类最多支持3层");
        }

        String newPath = childAncestors(parent) + "," + category.getId();
        category.setName(name);
        category.setParentId(parentId);
        category.setAncestors(childAncestors(parent));
        category.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        categoryRepository.saveAndFlush(category);

        if (!newPath.equals(oldPath)) {
            for (Category descendant : descendants) {
                descendant.setAncestors(newPath + descendant.getAncestors().substring(oldPath.length()));
            }
            categoryRepository.saveAll(descendants);
            log.info("分类子树移动完成: id={}, 受影响={} 个", id, descendants.size());
        }
        return CategoryVo.of(category);
    }

    /**
     * 删除分类（有子分类或仍有文档时拒绝）。
     *
     * @param id 分类 ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Category category = getCategoryOrThrow(id);
        if (categoryRepository.existsByParentId(id) || documentRepository.countByCategoryId(id) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "该分类下仍有子分类或文档，请先迁移文档");
        }
        categoryRepository.delete(category);
    }

    /**
     * 取分类，不存在则 404。
     *
     * @param id 分类 ID
     * @return 分类实体
     */
    private Category getCategoryOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在或已被删除");
        }
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在或已被删除"));
    }

    /**
     * 取父分类（0 = 根）。
     *
     * @param parentId 父分类 ID
     * @return 父分类（根为 null）
     */
    private Category requireParent(Long parentId) {
        if (parentId == 0L) {
            return null;
        }
        return categoryRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "上级分类不存在，请重新选择"));
    }

    /**
     * 计算子节点的 ancestors。
     *
     * @param parent 父分类
     * @return 祖级路径
     */
    private String childAncestors(Category parent) {
        return parent == null ? "0" : parent.getAncestors() + "," + parent.getId();
    }

    /**
     * 层级：ancestors 段数（根 = 1 层）。
     *
     * @param category 分类（null = 根之上，返回 0）
     * @return 层级
     */
    private int levelOf(Category category) {
        return category == null ? 0 : category.getAncestors().split(",").length;
    }

    /**
     * 子树相对高度（自身之下还有几层）。
     *
     * @param descendants 子孙列表
     * @param oldPath     当前节点完整路径
     * @return 额外层数（无子孙为 0）
     */
    private int subtreeExtraDepth(List<Category> descendants, String oldPath) {
        int extra = 0;
        for (Category descendant : descendants) {
            int depth = descendant.getAncestors().split(",").length - oldPath.split(",").length;
            extra = Math.max(extra, depth);
        }
        return extra;
    }

    /**
     * 环检测。
     *
     * @param parent  目标父分类
     * @param oldPath 当前分类完整路径
     * @return true = 会形成环
     */
    private boolean isSelfOrDescendant(Category parent, String oldPath) {
        String parentPath = parent.getAncestors() + "," + parent.getId();
        return oldPath.equals(parentPath) || parentPath.startsWith(oldPath + ",");
    }
}
