package com.campusswap.document.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.DocumentVersion;
import com.campusswap.entity.enums.ChangeType;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档版本出参（API_SPECIFICATION §4.6.12，GLOSSARY §3.7 的 {@code DocumentVersionVo}）。
 *
 * <p>{@code operatorId} / {@code operatorName} 是展示层命名，对应数据库列 {@code created_by}。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentVersionVo extends AuditVo {

    /** 版本记录 ID（字符串）。 */
    private String id;

    /** 文档 ID（字符串）。 */
    private String documentId;

    /** 版本号。 */
    private Integer versionNum;

    /** 该版本标题快照。 */
    private String title;

    /** 该版本正文快照。 */
    private String contentMd;

    /** 变更类型。 */
    private ChangeType changeType;

    /** 变更备注 / 审核意见（可空）。 */
    private String changeRemark;

    /** 操作人 ID（= created_by）。 */
    private String operatorId;

    /** 操作人姓名。 */
    private String operatorName;

    /**
     * 由实体组装。
     *
     * @param version  版本实体
     * @param operator 操作人姓名（批量补齐）
     * @return 版本出参
     */
    public static DocumentVersionVo of(DocumentVersion version, String operator) {
        DocumentVersionVo vo = new DocumentVersionVo();
        vo.fillAudit(version);
        vo.id = IdUtil.toStr(version.getId());
        vo.documentId = IdUtil.toStr(version.getDocumentId());
        vo.versionNum = version.getVersionNum();
        vo.title = version.getTitle();
        vo.contentMd = version.getContentMd();
        vo.changeType = version.getChangeType();
        vo.changeRemark = version.getChangeRemark();
        vo.operatorId = IdUtil.toStr(version.getCreatedBy());
        vo.operatorName = operator;
        return vo;
    }
}
