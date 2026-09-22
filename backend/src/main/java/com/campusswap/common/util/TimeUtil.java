package com.campusswap.common.util;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间与字符串的转换工具。
 *
 * <p>本项目对外时间格式固定为 {@code yyyy-MM-dd HH:mm:ss}、时区 {@code Asia/Shanghai}（BR-22）。
 * VO 里时间字段是 <b>String</b>，因此格式化只在本类做，既不依赖 Jackson 的日期模块，也不受其版本差异影响。</p>
 *
 * @author Zyaire
 */
public final class TimeUtil {

    /** 平台统一时间格式。 */
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 平台统一时间格式的可读描述（错误提示用）。 */
    public static final String PATTERN_TEXT = "yyyy-MM-dd HH:mm:ss";

    private TimeUtil() {
    }

    /**
     * LocalDateTime → 平台统一格式字符串（null 安全）。
     *
     * @param time 时间
     * @return 格式化字符串；入参为 null 时返回 null
     */
    public static String format(LocalDateTime time) {
        return time == null ? null : FORMATTER.format(time);
    }

    /**
     * 平台统一格式字符串 → LocalDateTime（可空，非法值 400）。
     *
     * @param text  时间字符串
     * @param field 字段中文名（用于提示）
     * @return 时间；空值时返回 null
     */
    public static LocalDateTime parse(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + "格式不正确，应为 " + PATTERN_TEXT);
        }
    }

    /**
     * 校验时间区间合法性（API_SPECIFICATION §2.6：{@code startTime > endTime} 返回 400）。
     *
     * @param startTime 起始时间（可空）
     * @param endTime   结束时间（可空）
     */
    public static void assertRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
    }
}
