package com.campusswap.document.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.service.StatService;
import com.campusswap.document.vo.StatVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口（API_SPECIFICATION §4.9.2）。
 *
 * <p>三个计数用<b>一条</b>原生 SQL 聚合取回（禁止 3 次单独 count）：SQL 预算 1 条。</p>
 *
 * <p><b>M6 修正</b>：本类原先把 {@code DocumentRepository} 直接注入 Controller、在 Controller
 * 里取行并组装 {@code StatVo}，违反 MASTER-PLAN §2.3 分层五条（Controller「绝不做」业务逻辑、
 * 直接调 Repository）。现在查询与组装都在 {@link StatService} 内，本类只做路由 + 权限注解 +
 * 统一响应包装；事务边界随之移到 Service 层。</p>
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;

    /**
     * 统计概览：我的文档数 / 我的收藏数 / 平台已发布数。
     *
     * @return 统计出参
     */
    @GetMapping("/overview")
    @RequiresPermission("doc:center")
    public ResponseResult<StatVo> overview() {
        return ResponseResult.ok(statService.overview(SecurityContext.requireUserId()));
    }
}
