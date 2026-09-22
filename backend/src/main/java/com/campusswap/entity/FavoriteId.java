package com.campusswap.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link Favorite} 的复合主键 {@code (user_id, document_id)}。
 *
 * @author Zyaire
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID。 */
    private Long userId;

    /** 文档 ID。 */
    private Long documentId;
}
