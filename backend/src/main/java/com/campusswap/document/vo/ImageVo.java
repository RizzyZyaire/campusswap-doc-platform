package com.campusswap.document.vo;

/**
 * 图片上传出参（API_SPECIFICATION §4.9.1，GLOSSARY §3.7 的 {@code ImageVo}）。
 *
 * @param url 图片相对 URL，如 {@code /uploads/2026/09/8f3c1a2b.png}
 * @author Zyaire
 */
public record ImageVo(String url) {
}
