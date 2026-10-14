package com.campusswap.system.repository;

import com.campusswap.entity.SysOperationLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 操作日志仓储（M14）。
 *
 * <p>只读为主（日志由切面异步写入，业务侧不修改），查询用
 * {@link JpaSpecificationExecutor} 拼动态条件（时间范围 + 模块 + 动作 + 操作人 + 成功与否）。</p>
 *
 * @author Zyaire
 */
@Repository
public interface SysOperationLogRepository
        extends JpaRepository<SysOperationLog, Long>, JpaSpecificationExecutor<SysOperationLog> {

    /**
     * 已出现过的模块清单（筛选下拉）。
     *
     * @return 去重模块名（字典序）
     */
    @Query("select distinct l.module from SysOperationLog l order by l.module")
    List<String> findDistinctModules();

    /**
     * 已出现过的动作清单（筛选下拉）。
     *
     * @return 去重动作名（字典序）
     */
    @Query("select distinct l.action from SysOperationLog l order by l.action")
    List<String> findDistinctActions();
}
