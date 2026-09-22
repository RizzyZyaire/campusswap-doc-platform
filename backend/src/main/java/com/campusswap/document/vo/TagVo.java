package com.campusswap.document.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.Tag;
import lombok.Getter;
import lombok.Setter;

/**
 * 标签出参（API_SPECIFICATION §4.8.5，GLOSSARY §3.7 的 {@code TagVo}）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class TagVo extends AuditVo {

    /** 标签 ID（字符串）。 */
    private String id;

    /** 标签名称。 */
    private String name;

    /** 被引用文档数。 */
    private Integer useCount;

    /**
     * 由实体组装。
     *
     * @param tag 标签实体
     * @return 标签出参
     */
    public static TagVo of(Tag tag) {
        TagVo vo = new TagVo();
        vo.fillAudit(tag);
        vo.id = IdUtil.toStr(tag.getId());
        vo.name = tag.getName();
        vo.useCount = tag.getUseCount();
        return vo;
    }
}
