package com.campusswap.system.controller;

import com.campusswap.common.api.PageVo;
import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.system.dto.OperationLogPageDtoReq;
import com.campusswap.system.service.OperationLogService;
import com.campusswap.system.vo.OperationLogVo;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志接口（M14，老师《作业说明》功能模块"操作日志：…提供日志查询"）。
 *
 * <p>权限沿用系统管理域的 {@code sys:center}（只有 SYS_ADMIN 持有）——审计日志是管理员的只读视图，
 * 刻意不新增权限点，保持 M0 冻结的 39 个权限点口径不变。</p>
 *
 * @author Zyaire
 */
@RestController
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 分页查询操作日志。
     *
     * @param req 组合查询条件（账号 / 模块 / 动作 / 成功与否 / 时间范围）+ 分页
     * @return 分页结果（时间倒序）
     */
    @GetMapping("/api/logs")
    @RequiresPermission("sys:center")
    public ResponseResult<PageVo<OperationLogVo>> page(@Valid @ModelAttribute OperationLogPageDtoReq req) {
        return ResponseResult.ok(operationLogService.page(req));
    }

    /**
     * 筛选下拉用的模块与动作清单。
     *
     * @return {@code {"modules": [...], "actions": [...]}}
     */
    @GetMapping("/api/logs/filters")
    @RequiresPermission("sys:center")
    public ResponseResult<Map<String, List<String>>> filters() {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        filters.put("modules", operationLogService.modules());
        filters.put("actions", operationLogService.actions());
        return ResponseResult.ok(filters);
    }
}
