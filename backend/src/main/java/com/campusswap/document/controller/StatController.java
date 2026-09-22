package com.campusswap.document.controller;

import com.campusswap.common.api.ResponseResult;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.document.repository.DocumentColumns;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.vo.StatVo;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口（API_SPECIFICATION §4.9.2）。
 *
 * <p>三个计数用<b>一条</b>原生 SQL 聚合取回（禁止 3 次单独 count）：SQL 预算 1 条。</p>
 *
 * @author Zyaire
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatController {

    private final DocumentRepository documentRepository;

    /**
     * 统计概览：我的文档数 / 我的收藏数 / 平台已发布数。
     *
     * @return 统计出参
     */
    @GetMapping("/overview")
    @RequiresPermission("doc:center")
    @Transactional(readOnly = true)
    public ResponseResult<StatVo> overview() {
        Long me = SecurityContext.requireUserId();
        java.util.List<Object[]> rows = documentRepository.statOverview(me);
        Object[] row = rows.isEmpty() ? new Object[] {0L, 0L, 0L} : rows.get(0);
        return ResponseResult.ok(new StatVo(
                DocumentColumns.longVal(row, 0),
                DocumentColumns.longVal(row, 1),
                DocumentColumns.longVal(row, 2)));
    }
}
