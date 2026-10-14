package com.campusswap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 操作日志（sys_operation_log，M14 新增第 15 张表）。
 *
 * <p>由 {@code OperationLogAspect} 切面自动写入：所有控制器上的写操作（POST/PUT/DELETE）
 * 都会被记录，重点接口用 {@code @OperationLog(module, action)} 补充中文模块名与动作名。</p>
 *
 * <p>为什么冗余 {@code username}：用户改名或停用后，历史日志仍要能读懂（审计日志的第一要求）；
 * 为什么记录 {@code params} 摘要：出问题时能复现"当时提交了什么"，密码类字段已在切面里脱敏。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sys_operation_log")
@SQLDelete(sql = "UPDATE sys_operation_log SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
public class SysOperationLog extends BaseEntity {

    /** 操作人 ID（关联 sys_user.id；登录接口等未登录场景为空）。 */
    @Column(name = "user_id")
    private Long userId;

    /** 操作人账号（冗余，改名后日志仍可读）。 */
    @Column(name = "username", length = 64)
    private String username;

    /** 模块（文档 / 分类 / 标签 / 用户 / 角色 / 机构 / 检索 …）。 */
    @Column(name = "module", nullable = false, length = 32)
    private String module;

    /** 动作（新增 / 修改 / 删除 / 发布 / 审核 / 重建索引 …）。 */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    /** HTTP 方法（POST / PUT / DELETE）。 */
    @Column(name = "method", length = 8)
    private String method;

    /** 请求路径。 */
    @Column(name = "uri", length = 255)
    private String uri;

    /** 目标对象 ID（能从路径或入参解析时记录）。 */
    @Column(name = "target_id", length = 32)
    private String targetId;

    /** 中文摘要（一句话说明做了什么，列表页直接显示）。 */
    @Column(name = "summary", length = 255)
    private String summary;

    /** 入参摘要（已脱敏、已截断）。 */
    @Column(name = "params", length = 1000)
    private String params;

    /** 客户端 IP。 */
    @Column(name = "ip", length = 45)
    private String ip;

    /** 是否成功（0 否 1 是）。 */
    @Column(name = "success", nullable = false)
    private Integer success;

    /** 失败原因（截断 500 字符）。 */
    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    /** 耗时（毫秒）。 */
    @Column(name = "cost_ms")
    private Integer costMs;
}
