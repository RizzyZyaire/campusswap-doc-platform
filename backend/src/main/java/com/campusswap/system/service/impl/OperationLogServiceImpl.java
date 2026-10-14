package com.campusswap.system.service.impl;

import com.campusswap.common.api.PageVo;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.entity.SysOperationLog;
import com.campusswap.system.dto.OperationLogPageDtoReq;
import com.campusswap.system.repository.OperationLogSpecifications;
import com.campusswap.system.repository.SysOperationLogRepository;
import com.campusswap.system.service.OperationLogService;
import com.campusswap.system.vo.OperationLogVo;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作日志服务实现（M14）。
 *
 * <p>只读 + 动态条件：用 {@link OperationLogSpecifications} 拼 Criteria（禁字符串 SQL 拼接），
 * 排序固定"时间倒序、同秒按 ID 倒序"（日志是时序数据，同秒多条时 ID 才是稳定次序）。</p>
 *
 * @author Zyaire
 */
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    /** 排序：时间倒序 + ID 倒序（同秒稳定）。 */
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final SysOperationLogRepository logRepository;

    @Override
    @Transactional(readOnly = true)
    public PageVo<OperationLogVo> page(OperationLogPageDtoReq req) {
        LocalDateTime start = TimeUtil.parse(req.getStartTime(), "startTime");
        LocalDateTime end = TimeUtil.parse(req.getEndTime(), "endTime");
        TimeUtil.assertRange(start, end);
        Page<SysOperationLog> page = logRepository.findAll(
                OperationLogSpecifications.of(req.getUsername(), req.getModule(), req.getAction(), req.getSuccess(),
                        start, end),
                req.toPageable(SORT));
        return PageVo.of(page.map(OperationLogVo::from));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> modules() {
        return logRepository.findDistinctModules();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> actions() {
        return logRepository.findDistinctActions();
    }
}
