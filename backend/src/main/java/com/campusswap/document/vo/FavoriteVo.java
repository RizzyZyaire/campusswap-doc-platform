package com.campusswap.document.vo;

/**
 * 收藏操作出参（API_SPECIFICATION §4.6.13/§4.6.14，GLOSSARY §3.7 的 {@code FavoriteVo}）。
 *
 * <p>组合型 VO，<b>不</b>继承 {@code AuditVo}（API_SPECIFICATION §2.7.1 铁律 3）。</p>
 *
 * @param documentId    文档 ID（字符串）
 * @param favorited     操作后是否已收藏
 * @param favoriteCount 操作后文档收藏数
 * @author Zyaire
 */
public record FavoriteVo(String documentId, boolean favorited, Integer favoriteCount) {
}
