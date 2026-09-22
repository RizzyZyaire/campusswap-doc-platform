package com.campusswap.common.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务后置动作工具。
 *
 * <p>ARCHITECTURE §8 明令：<b>事务内禁止删 Redis（缓存失效必须放到事务提交之后）</b> ——
 * 否则事务回滚后缓存已经被删，或者提交前删缓存导致并发读回旧值。
 * 本工具把"提交成功后再执行"这件事收敛到一个方法里，业务代码不必各写一遍同步器。</p>
 *
 * <p>用法：{@code TxUtil.afterCommit(() -> permissionCacheService.evictByRoleId(roleId));}</p>
 *
 * @author Zyaire
 */
public final class TxUtil {

    private TxUtil() {
    }

    /**
     * 在当前事务提交成功后执行动作；若当前没有事务，则立即执行。
     *
     * @param action 待执行动作（通常是缓存失效）
     */
    public static void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
