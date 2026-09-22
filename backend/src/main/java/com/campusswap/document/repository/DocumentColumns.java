package com.campusswap.document.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 文档「宽表列清单」与原生查询结果的下标/类型转换约定。
 *
 * <p>为什么需要它：回收站与我的收藏这两条链路<b>必须绕过</b> {@code @SQLRestriction("deleted = 0")}
 * （回收站要查 {@code deleted = 1}，收藏要 JOIN 关系表），只能用原生 SQL。
 * 原生查询返回 {@code Object[]}，列顺序与 JDBC 类型必须在一处钉死，否则改一个查询就会静默错位。</p>
 *
 * <p>类型容错：MySQL 的 {@code INT UNSIGNED}（{@code price_cents}）经 Connector/J 可能回传
 * {@link Long}，{@code DATETIME} 可能回传 {@link Timestamp}，因此统一走本类的转换方法。</p>
 *
 * @author Zyaire
 */
public final class DocumentColumns {

    /** 原生查询的列清单（顺序即下标，禁止随意调整）。 */
    public static final String COLUMNS =
            "d.id, d.title, d.summary, d.category_id, d.created_by, d.status, d.version_num,"
                    + " d.price_cents, d.view_count, d.favorite_count, d.created_at, d.updated_at,"
                    + " d.updated_by, d.content_md, d.derived_from_id, d.reject_reason";

    /** 下标：主键。 */
    public static final int ID = 0;
    /** 下标：标题。 */
    public static final int TITLE = 1;
    /** 下标：摘要。 */
    public static final int SUMMARY = 2;
    /** 下标：分类 ID。 */
    public static final int CATEGORY_ID = 3;
    /** 下标：作者 ID（= created_by）。 */
    public static final int CREATED_BY = 4;
    /** 下标：状态。 */
    public static final int STATUS = 5;
    /** 下标：版本号。 */
    public static final int VERSION_NUM = 6;
    /** 下标：价格标记（分）。 */
    public static final int PRICE_CENTS = 7;
    /** 下标：阅读量。 */
    public static final int VIEW_COUNT = 8;
    /** 下标：收藏数。 */
    public static final int FAVORITE_COUNT = 9;
    /** 下标：创建时间。 */
    public static final int CREATED_AT = 10;
    /** 下标：最后更新时间。 */
    public static final int UPDATED_AT = 11;
    /** 下标：最后修改人。 */
    public static final int UPDATED_BY = 12;
    /** 下标：正文。 */
    public static final int CONTENT_MD = 13;
    /** 下标：派生来源。 */
    public static final int DERIVED_FROM_ID = 14;
    /** 下标：驳回理由。 */
    public static final int REJECT_REASON = 15;

    private DocumentColumns() {
    }

    /**
     * 取 Long 列。
     *
     * @param row   结果行
     * @param index 下标
     * @return Long 值（可空）
     */
    public static Long longVal(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    /**
     * 取 Integer 列（兼容 MySQL 把 INT UNSIGNED 回传成 Long 的情况）。
     *
     * @param row   结果行
     * @param index 下标
     * @return Integer 值（可空）
     */
    public static Integer intVal(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    /**
     * 取 String 列。
     *
     * @param row   结果行
     * @param index 下标
     * @return 字符串（可空）
     */
    public static String str(Object[] row, int index) {
        Object value = row[index];
        return value == null ? null : value.toString();
    }

    /**
     * 取时间列（兼容 {@link Timestamp} 与 {@link LocalDateTime}）。
     *
     * @param row   结果行
     * @param index 下标
     * @return 时间（可空）
     */
    public static LocalDateTime time(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
