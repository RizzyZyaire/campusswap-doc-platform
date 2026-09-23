package com.campusswap.document.controller;

import com.campusswap.common.api.PageDtoReq;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.document.dto.DocumentCreateDtoReq;
import com.campusswap.document.dto.DocumentDeriveDtoReq;
import com.campusswap.document.dto.DocumentDestroyDtoReq;
import com.campusswap.document.dto.DocumentManageDtoReq;
import com.campusswap.document.dto.DocumentMineDtoReq;
import com.campusswap.document.dto.DocumentSearchDtoReq;
import com.campusswap.document.dto.DocumentTrashDtoReq;
import com.campusswap.document.dto.DocumentUpdateDtoReq;
import com.campusswap.document.dto.FavoritePageDtoReq;
import com.campusswap.document.service.DocumentService;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVersionVo;
import com.campusswap.document.vo.DocumentVo;
import com.campusswap.document.vo.FavoriteVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档接口（API_SPECIFICATION §4.6，共 15 条）。
 *
 * <p>本类<b>不用类级 {@code @RequestMapping}</b>：因为「我的收藏」在同一模块里却是 {@code /api/favorites}
 * 前缀，写全路径比拆成两个 Controller 更贴 API 文档的模块划分（§3.6 文档业务 15 条）。</p>
 *
 * @author Zyaire
 */
@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 检索已发布文档。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping("/api/documents")
    @RequiresPermission("doc:search")
    public ResponseResult<PageVo<DocumentVo>> search(@Valid @ModelAttribute DocumentSearchDtoReq req) {
        return ResponseResult.ok(documentService.search(req));
    }

    /**
     * 我的文档。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping("/api/documents/mine")
    @RequiresPermission("doc:mine")
    public ResponseResult<PageVo<DocumentVo>> mine(@Valid @ModelAttribute DocumentMineDtoReq req) {
        return ResponseResult.ok(documentService.mine(req));
    }

    /**
     * 治理用全状态列表（含回收站行，权限 {@code doc:manage}，API_SPECIFICATION §9.2）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping("/api/documents/manage")
    @RequiresPermission("doc:manage")
    public ResponseResult<PageVo<DocumentVo>> manage(@Valid @ModelAttribute DocumentManageDtoReq req) {
        return ResponseResult.ok(documentService.manage(req));
    }

    /**
     * 回收站列表。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping("/api/documents/trash")
    @RequiresPermission("doc:mine")
    public ResponseResult<PageVo<DocumentVo>> trash(@Valid @ModelAttribute DocumentTrashDtoReq req) {
        return ResponseResult.ok(documentService.trash(req));
    }

    /**
     * 新建文档草稿。
     *
     * @param req 新建入参
     * @return 文档详情
     */
    @PostMapping("/api/documents")
    @RequiresPermission("doc:create")
    public ResponseResult<DocumentDetailVo> create(@Valid @RequestBody DocumentCreateDtoReq req) {
        return ResponseResult.ok(documentService.create(req));
    }

    /**
     * 文档详情（已发布文档在此累计阅读量）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @GetMapping("/api/documents/{id}")
    @RequiresPermission("doc:search")
    public ResponseResult<DocumentDetailVo> detail(@PathVariable("id") Long id) {
        return ResponseResult.ok(documentService.detail(id));
    }

    /**
     * 编辑文档。
     *
     * @param id  文档 ID
     * @param req 编辑入参
     * @return 文档详情
     */
    @PutMapping("/api/documents/{id}")
    @RequiresPermission("doc:edit")
    public ResponseResult<DocumentDetailVo> update(@PathVariable("id") Long id,
                                                   @Valid @RequestBody DocumentUpdateDtoReq req) {
        return ResponseResult.ok(documentService.update(id, req));
    }

    /**
     * 提交发布。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @PostMapping("/api/documents/{id}/publish")
    @RequiresPermission("doc:publish")
    public ResponseResult<DocumentDetailVo> publish(@PathVariable("id") Long id) {
        return ResponseResult.ok(documentService.publish(id));
    }

    /**
     * 派生文档。
     *
     * @param id  源文档 ID
     * @param req 派生入参
     * @return 新文档详情
     */
    @PostMapping("/api/documents/{id}/derive")
    @RequiresPermission("doc:derive")
    public ResponseResult<DocumentDetailVo> derive(@PathVariable("id") Long id,
                                                   @RequestBody(required = false) @Valid DocumentDeriveDtoReq req) {
        return ResponseResult.ok(documentService.derive(id, req));
    }

    /**
     * 删除文档（进回收站）。
     *
     * @param id 文档 ID
     * @return 无数据
     */
    @DeleteMapping("/api/documents/{id}")
    @RequiresPermission("doc:delete")
    public ResponseResult<Void> delete(@PathVariable("id") Long id) {
        documentService.delete(id);
        return ResponseResult.ok();
    }

    /**
     * 从回收站恢复。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @PostMapping("/api/documents/{id}/restore")
    @RequiresPermission("doc:restore")
    public ResponseResult<DocumentDetailVo> restore(@PathVariable("id") Long id) {
        return ResponseResult.ok(documentService.restore(id));
    }

    /**
     * 彻底删除（需二次确认）。
     *
     * @param id  文档 ID
     * @param req 二次确认入参
     * @return 无数据
     */
    @DeleteMapping("/api/documents/{id}/destroy")
    @RequiresPermission("doc:delete")
    public ResponseResult<Void> destroy(@PathVariable("id") Long id,
                                        @Valid @RequestBody DocumentDestroyDtoReq req) {
        documentService.destroy(id, req);
        return ResponseResult.ok();
    }

    /**
     * 版本历史。
     *
     * @param id   文档 ID
     * @param page 分页
     * @return 分页结果
     */
    @GetMapping("/api/documents/{id}/versions")
    @RequiresPermission("doc:mine")
    public ResponseResult<PageVo<DocumentVersionVo>> versions(@PathVariable("id") Long id,
                                                              @Valid @ModelAttribute PageDtoReq page) {
        return ResponseResult.ok(documentService.versions(id, page));
    }

    /**
     * 收藏文档（幂等）。
     *
     * @param id 文档 ID
     * @return 收藏结果
     */
    @PostMapping("/api/documents/{id}/favorite")
    @RequiresPermission("doc:favorite")
    public ResponseResult<FavoriteVo> favorite(@PathVariable("id") Long id) {
        return ResponseResult.ok(documentService.favorite(id));
    }

    /**
     * 取消收藏（幂等）。
     *
     * @param id 文档 ID
     * @return 收藏结果
     */
    @DeleteMapping("/api/documents/{id}/favorite")
    @RequiresPermission("doc:favorite")
    public ResponseResult<FavoriteVo> unfavorite(@PathVariable("id") Long id) {
        return ResponseResult.ok(documentService.unfavorite(id));
    }

    /**
     * 我的收藏。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @GetMapping("/api/favorites")
    @RequiresPermission("doc:favorite")
    public ResponseResult<PageVo<DocumentVo>> favorites(@Valid @ModelAttribute FavoritePageDtoReq req) {
        return ResponseResult.ok(documentService.favorites(req));
    }
}
