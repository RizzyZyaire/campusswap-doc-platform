package com.campusswap.document.service.impl;

import com.campusswap.document.repository.DocumentColumns;
import com.campusswap.document.repository.DocumentRepository;
import com.campusswap.document.service.StatService;
import com.campusswap.document.vo.StatVo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统计服务实现。
 *
 * <p>三个计数在<b>一条</b>原生 SQL 里用三个子查询取回（禁止 3 次单独 count，也禁止
 * 把 {@code COUNT} 叠成多次全表扫描）：SQL 预算 1 条，实测 1 条
 * （见 docs/03-qa-review/TEST_CHECKLIST.md 的 M6 逐接口点数表）。</p>
 *
 * @author Zyaire
 */
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    private final DocumentRepository documentRepository;

    /**
     * 统计概览（我的文档 / 我的收藏 / 平台已发布）。
     *
     * @param userId 当前用户 ID
     * @return 统计出参（无行时三项均为 0）
     */
    @Override
    @Transactional(readOnly = true)
    public StatVo overview(Long userId) {
        List<Object[]> rows = documentRepository.statOverview(userId);
        Object[] row = rows.isEmpty() ? new Object[] {0L, 0L, 0L} : rows.get(0);
        return new StatVo(
                DocumentColumns.longVal(row, 0),
                DocumentColumns.longVal(row, 1),
                DocumentColumns.longVal(row, 2));
    }
}
