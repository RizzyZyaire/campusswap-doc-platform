package com.campusswap.system.vo;

import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.entity.SysOperationLog;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作日志出参（API_SPECIFICATION §4.10.1，GLOSSARY §3.10 的 {@code OperationLogVo}）。
 *
 * <p>字段与列表页一一对应：谁（{@code username}）、什么时候（{@code createdAt}）、
 * 在哪个模块做了什么（{@code module} / {@code action} / {@code summary}）、
 * 成不成功（{@code success} / {@code errorMsg}）、耗时与来源 IP。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class OperationLogVo {

    /** 日志 ID（字符串）。 */
    private String id;

    /** 操作人 ID（字符串；未登录场景为空）。 */
    private String userId;

    /** 操作人账号。 */
    private String username;

    /** 模块。 */
    private String module;

    /** 动作。 */
    private String action;

    /** HTTP 方法。 */
    private String method;

    /** 请求路径。 */
    private String uri;

    /** 目标对象 ID。 */
    private String targetId;

    /** 中文摘要。 */
    private String summary;

    /** 入参摘要（已脱敏）。 */
    private String params;

    /** 客户端 IP。 */
    private String ip;

    /** 是否成功。 */
    private Boolean success;

    /** 失败原因。 */
    private String errorMsg;

    /** 耗时（毫秒）。 */
    private Integer costMs;

    /** 记录时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String createdAt;

    /**
     * 实体 → VO。
     *
     * @param entity 日志实体
     * @return 出参
     */
    public static OperationLogVo from(SysOperationLog entity) {
        OperationLogVo vo = new OperationLogVo();
        vo.setId(IdUtil.toStr(entity.getId()));
        vo.setUserId(IdUtil.toStr(entity.getUserId()));
        vo.setUsername(entity.getUsername());
        vo.setModule(entity.getModule());
        vo.setAction(entity.getAction());
        vo.setMethod(entity.getMethod());
        vo.setUri(entity.getUri());
        vo.setTargetId(entity.getTargetId());
        vo.setSummary(entity.getSummary());
        vo.setParams(entity.getParams());
        vo.setIp(entity.getIp());
        vo.setSuccess(entity.getSuccess() != null && entity.getSuccess() == 1);
        vo.setErrorMsg(entity.getErrorMsg());
        vo.setCostMs(entity.getCostMs());
        vo.setCreatedAt(TimeUtil.format(entity.getCreatedAt()));
        return vo;
    }
}
