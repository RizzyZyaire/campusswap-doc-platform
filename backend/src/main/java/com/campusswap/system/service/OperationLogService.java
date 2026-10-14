package com.campusswap.system.service;

import com.campusswap.common.api.PageVo;
import com.campusswap.system.dto.OperationLogPageDtoReq;
import com.campusswap.system.vo.OperationLogVo;
import java.util.List;

/**
 * 操作日志服务（M14）。
 *
 * @author Zyaire
 */
public interface OperationLogService {

    /**
     * 分页查询操作日志（组合条件：账号 / 模块 / 动作 / 成功与否 / 时间范围）。
     *
     * @param req 查询入参
     * @return 分页结果（按时间倒序）
     */
    PageVo<OperationLogVo> page(OperationLogPageDtoReq req);

    /**
     * 已出现过的模块清单（给前端筛选下拉用）。
     *
     * @return 去重后的模块名
     */
    List<String> modules();

    /**
     * 已出现过的动作清单。
     *
     * @return 去重后的动作名
     */
    List<String> actions();
}
