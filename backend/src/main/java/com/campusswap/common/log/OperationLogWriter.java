package com.campusswap.common.log;

import com.campusswap.entity.SysOperationLog;
import com.campusswap.system.repository.SysOperationLogRepository;
import com.campusswap.system.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 操作日志落库器（M14）：在独立线程池里写库，不占请求线程。
 *
 * <p>为什么单独一个 Bean 而不是在切面里直接 {@code @Async}：Spring 的 {@code @Async} 靠代理生效，
 * <b>同类内部调用不走代理</b>（最常见的"加了 @Async 却没异步"的坑），拆成独立 Bean 才稳。</p>
 *
 * <p>为什么在这里补账号：异步线程里没有 {@code SecurityContext} 的 ThreadLocal，
 * 账号只能在独立线程里按 userId 查一次 —— 查询失败不影响日志落库（账号留空）。</p>
 *
 * @author Zyaire
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationLogWriter {

    private final SysOperationLogRepository logRepository;

    private final UserRepository userRepository;

    /**
     * 异步写入操作日志。
     *
     * @param entity 日志实体（切面已在请求线程里组装完毕）
     */
    @Async("operationLogExecutor")
    public void write(SysOperationLog entity) {
        try {
            if (entity.getUserId() != null && entity.getUsername() == null) {
                entity.setUsername(userRepository.findById(entity.getUserId())
                        .map(user -> user.getUsername())
                        .orElse(null));
            }
            logRepository.save(entity);
        } catch (Exception e) {
            // 日志落库失败只告警：审计不能反向影响业务
            log.warn("操作日志落库失败: module={}, action={}, err={}", entity.getModule(), entity.getAction(),
                    e.getMessage());
        }
    }
}
