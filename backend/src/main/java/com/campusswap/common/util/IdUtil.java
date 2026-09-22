package com.campusswap.common.util;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;

/**
 * ID 与 JSON 字符串之间的转换工具。
 *
 * <p>铁律（API_SPECIFICATION §2.3、GLOSSARY §1.1）：<b>JSON 里所有 ID 一律是字符串</b>
 * （如 {@code "1001"}），Java 侧一律是 {@code Long}。转换只在这一个类里做，禁止各处散写
 * {@code String.valueOf} / {@code Long.parseLong}。</p>
 *
 * @author Zyaire
 */
public final class IdUtil {

    private IdUtil() {
    }

    /**
     * Long → JSON 字符串（null 安全）。
     *
     * @param id 主键
     * @return 字符串形式；入参为 null 时返回 null
     */
    public static String toStr(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    /**
     * JSON 字符串 → Long（必填，非法值直接 400）。
     *
     * @param value 字符串形式的 ID
     * @param field 字段中文名（用于提示）
     * @return 主键
     */
    public static Long toLong(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + "不能为空");
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + "格式不正确");
        }
    }

    /**
     * JSON 字符串 → Long（可空，空串按 null 处理，非法值 400）。
     *
     * @param value 字符串形式的 ID
     * @param field 字段中文名（用于提示）
     * @return 主键；空值时返回 null
     */
    public static Long toLongOrNull(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return toLong(value, field);
    }
}
