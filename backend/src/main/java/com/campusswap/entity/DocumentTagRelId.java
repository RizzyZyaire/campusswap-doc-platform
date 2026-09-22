package com.campusswap.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link DocumentTagRel} 的复合主键 {@code (document_id, tag_id)}。
 *
 * @author Zyaire
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTagRelId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档 ID。 */
    private Long documentId;

    /** 标签 ID。 */
    private Long tagId;
}
