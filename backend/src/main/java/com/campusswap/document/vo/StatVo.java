package com.campusswap.document.vo;

/**
 * 统计概览出参（API_SPECIFICATION §4.9.2，GLOSSARY §3.7 的 {@code StatVo}）。
 *
 * @param myDocumentCount  我的文档总数（不含回收站）
 * @param myFavoriteCount  我的收藏数
 * @param publishedCount   平台已发布文档数
 * @author Zyaire
 */
public record StatVo(long myDocumentCount, long myFavoriteCount, long publishedCount) {
}
