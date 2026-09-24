package com.campusswap.document.service;

import com.campusswap.document.vo.StatVo;

/**
 * 统计服务（API_SPECIFICATION §4.9.2）。
 *
 * <p><b>为什么单独抽一个接口</b>：M6 代码审查发现原 {@code StatController} 直接注入了
 * {@code DocumentRepository} 并在 Controller 里拼 {@code StatVo}，违反 MASTER-PLAN §2.3
 * 分层五条（Controller「绝不做」业务逻辑与直接调 Repository）。本次把原生聚合查询的
 * 调用与 VO 组装下沉到本层，Controller 只保留路由、权限注解与统一响应包装。</p>
 *
 * @author Zyaire
 */
public interface StatService {

    /**
     * 统计概览：我的文档数 / 我的收藏数 / 平台已发布数（一条原生 SQL 聚合，SQL 预算 1 条）。
     *
     * @param userId 当前用户 ID（由 Controller 从 {@code SecurityContext} 取出后传入）
     * @return 统计出参
     */
    StatVo overview(Long userId);
}
