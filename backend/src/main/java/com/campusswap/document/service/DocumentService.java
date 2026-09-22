package com.campusswap.document.service;

import com.campusswap.common.api.PageVo;
import com.campusswap.document.dto.DocumentCreateDtoReq;
import com.campusswap.document.dto.DocumentDeriveDtoReq;
import com.campusswap.document.dto.DocumentDestroyDtoReq;
import com.campusswap.document.dto.DocumentMineDtoReq;
import com.campusswap.document.dto.DocumentSearchDtoReq;
import com.campusswap.document.dto.DocumentTrashDtoReq;
import com.campusswap.document.dto.DocumentUpdateDtoReq;
import com.campusswap.document.dto.FavoritePageDtoReq;
import com.campusswap.document.vo.DocumentDetailVo;
import com.campusswap.document.vo.DocumentVersionVo;
import com.campusswap.document.vo.DocumentVo;
import com.campusswap.document.vo.FavoriteVo;
import com.campusswap.common.api.PageDtoReq;
import com.campusswap.entity.Document;
import com.campusswap.entity.enums.ChangeType;

/**
 * 文档服务（US-02 ~ US-06）：检索、我的文档、回收站、增删改、发布、派生、版本、收藏。
 *
 * <p>除对外接口外，本服务还向 {@code ReviewService} 暴露若干<b>共用能力</b>
 * （详情组装、可见性校验、归属校验、版本留痕），避免两个 Service 各写一份口径。</p>
 *
 * @author Zyaire
 */
public interface DocumentService {

    /**
     * 检索已发布文档（关键词 + 分类含子孙 + 标签 AND + 时间区间 + 排序）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<DocumentVo> search(DocumentSearchDtoReq req);

    /**
     * 我的文档（作者条件由后端强制注入）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<DocumentVo> mine(DocumentMineDtoReq req);

    /**
     * 回收站列表（普通用户只看自己的，管理员看全部）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<DocumentVo> trash(DocumentTrashDtoReq req);

    /**
     * 新建草稿（写版本留痕 CREATE + 绑定标签）。
     *
     * @param req 新建入参
     * @return 文档详情
     */
    DocumentDetailVo create(DocumentCreateDtoReq req);

    /**
     * 文档详情（可见性校验 + 已发布文档阅读量去重累加）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    DocumentDetailVo detail(Long id);

    /**
     * 编辑文档（版本 +1 并写版本快照；归档/回收站只读）。
     *
     * @param id  文档 ID
     * @param req 编辑入参
     * @return 文档详情
     */
    DocumentDetailVo update(Long id, DocumentUpdateDtoReq req);

    /**
     * 提交发布（DRAFT → PUBLISHED，写 publish_at 与版本留痕）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    DocumentDetailVo publish(Long id);

    /**
     * 派生新草稿（继承分类与正文，记录 derivedFromId）。
     *
     * @param id  源文档 ID
     * @param req 派生入参
     * @return 新文档详情
     */
    DocumentDetailVo derive(Long id, DocumentDeriveDtoReq req);

    /**
     * 删除文档（进回收站，软删除）。
     *
     * @param id 文档 ID
     */
    void delete(Long id);

    /**
     * 从回收站恢复（TRASH → DRAFT，版本 +1）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    DocumentDetailVo restore(Long id);

    /**
     * 彻底删除（物理删除主表 + 版本 + 收藏 + 标签关系）。
     *
     * @param id  文档 ID
     * @param req 二次确认入参
     */
    void destroy(Long id, DocumentDestroyDtoReq req);

    /**
     * 版本历史（version_num 倒序）。
     *
     * @param id   文档 ID
     * @param page 分页
     * @return 分页结果
     */
    PageVo<DocumentVersionVo> versions(Long id, PageDtoReq page);

    /**
     * 收藏文档（幂等）。
     *
     * @param id 文档 ID
     * @return 收藏结果
     */
    FavoriteVo favorite(Long id);

    /**
     * 取消收藏（幂等）。
     *
     * @param id 文档 ID
     * @return 收藏结果
     */
    FavoriteVo unfavorite(Long id);

    /**
     * 我的收藏列表（按收藏时间倒序）。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    PageVo<DocumentVo> favorites(FavoritePageDtoReq req);

    /**
     * 取文档实体（不存在则 404；<b>不做</b>可见性校验，调用方自行判断）。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    Document getDocumentOrThrow(Long id);

    /**
     * 取回收站文档行（原生查询，绕过软删除过滤）。
     *
     * @param id 文档 ID
     * @return 16 列结果行
     */
    Object[] getTrashRowOrThrow(Long id);

    /**
     * 可见性校验：PUBLISHED 全员可见；其余状态仅作者与 {@code doc:manage} 管理员可见。
     *
     * @param doc      文档实体
     * @param viewerId 访问者 ID
     */
    void assertVisible(Document doc, Long viewerId);

    /**
     * 归属校验：非作者且无 {@code doc:manage} 时抛 403。
     *
     * @param doc      文档实体
     * @param operator 操作人 ID
     * @param message  403 时的中文提示
     */
    void assertOwnerOrManage(Document doc, Long operator, String message);

    /**
     * 组装文档详情（分类名 / 作者名 / 标签 / 收藏态）。
     *
     * @param doc      文档实体
     * @param viewerId 访问者 ID
     * @param countView 是否累计阅读量（仅详情接口传 true）
     * @return 文档详情
     */
    DocumentDetailVo buildDetail(Document doc, Long viewerId, boolean countView);

    /**
     * 组装回收站文档详情（走原生行）。
     *
     * @param row      回收站结果行
     * @param viewerId 访问者 ID
     * @return 文档详情
     */
    DocumentDetailVo buildTrashDetail(Object[] row, Long viewerId);

    /**
     * 写一条版本留痕（操作人由 JPA 审计自动写入 {@code created_by}）。
     *
     * @param doc       文档实体（取当前版本号与标题）
     * @param changeType 变更类型
     * @param remark    变更备注 / 审核意见
     */
    void writeVersion(Document doc, ChangeType changeType, String remark);

    /**
     * 判断当前用户对某文档是否可编辑（属主 且 状态为 DRAFT/PUBLISHED）。
     *
     * @param doc    文档
     * @param userId 用户 ID
     * @return true = 可编辑
     */
    boolean canEdit(Document doc, Long userId);

    /**
     * 判断用户是否拥有某权限码（转发到权限缓存服务，供其它服务复用）。
     *
     * @param userId 用户 ID
     * @param code   权限码
     * @return true = 拥有
     */
    boolean hasPermission(Long userId, String code);

    /**
     * 把投影行组装成 {@code PageVo<DocumentVo>}（批量补作者名 / 分类名，两条 IN 查询）。
     *
     * <p>供审核队列复用：那种场景下 {@code canEdit} 对管理员恒为 false
     * （API_SPECIFICATION §4.7.1：管理员用审核/归档接口治理，而不是直接改他人正文）。</p>
     *
     * @param rows               投影行
     * @param total              总条数
     * @param pageNum            页码
     * @param pageSize           每页条数
     * @param viewerId           当前用户
     * @param allowOwnershipEdit 是否允许按归属计算可编辑（false = 全部 false）
     * @return 分页结果
     */
    PageVo<DocumentVo> buildPage(java.util.List<com.campusswap.document.repository.DocumentListRow> rows,
                                 long total, int pageNum, int pageSize, Long viewerId, boolean allowOwnershipEdit);
}
