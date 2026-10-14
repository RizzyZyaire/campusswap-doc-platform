package com.campusswap.document.search.mq;

import com.campusswap.common.security.RedisKeys;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 异步重建任务的**状态仓库**（Redis）。
 *
 * <p>任务状态放 Redis 而不是数据库：它是短期运行痕迹（2 小时 TTL 自动过期），
 * 读写频繁且不需要事务；与项目既有口径一致（token、权限缓存、阅读去重都在 Redis，
 * 见 ARCHITECTURE §7）。值统一用 JSON 字符串，不用 Java 序列化（类结构变化不会崩旧数据）。</p>
 *
 * @author Zyaire
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchRebuildTaskStore {

    /** 人类可读时间格式（与全站 VO 口径一致）。 */
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 任务记录存活时间。 */
    private static final Duration TTL = Duration.ofHours(2);

    /** 最近任务列表保留条数（界面展示"最近几次重建"）。 */
    private static final int RECENT_KEEP = 20;

    private final StringRedisTemplate redis;

    private final JsonMapper jsonMapper;

    /**
     * 新建一个"已提交"任务并落库。
     *
     * @param operatorId 触发人
     * @param costMs     提交接口耗时（毫秒）
     * @return 新任务
     */
    public SearchRebuildTask create(Long operatorId, long costMs) {
        SearchRebuildTask task = new SearchRebuildTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setStatus(RebuildTaskStatus.SUBMITTED);
        task.setOperatorId(operatorId);
        task.setSubmittedAt(now());
        task.setAttempts(0);
        task.setSubmitCostMs(costMs);
        task.setMessage("任务已提交，等待后台消费者执行");
        save(task);
        return task;
    }

    /**
     * 保存（覆盖）任务。
     *
     * @param task 任务
     */
    public void save(SearchRebuildTask task) {
        try {
            redis.opsForValue().set(RedisKeys.searchRebuildTask(task.getTaskId()),
                    jsonMapper.writeValueAsString(task), TTL);
        } catch (Exception e) {
            log.warn("写入重建任务状态失败: taskId={}, {}", task.getTaskId(), e.getMessage());
        }
    }

    /**
     * 读取任务（不存在返回 null）。
     *
     * @param taskId 任务 ID
     * @return 任务或 null
     */
    public SearchRebuildTask get(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().get(RedisKeys.searchRebuildTask(taskId));
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, SearchRebuildTask.class);
        } catch (Exception e) {
            log.warn("解析重建任务状态失败: taskId={}, {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 标记任务开始执行（消费者拉取后）。
     *
     * @param task 任务
     * @return 更新后的任务
     */
    public SearchRebuildTask markRunning(SearchRebuildTask task) {
        task.setStatus(RebuildTaskStatus.RUNNING);
        task.setStartedAt(now());
        task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        task.setMessage("后台消费者正在重建索引（第 " + task.getAttempts() + " 次消费）");
        save(task);
        return task;
    }

    /**
     * 标记成功。
     *
     * @param task      任务
     * @param indexed   同步条数
     * @param indexCount 索引总数
     * @param elapsedMs 耗时
     * @param message   说明
     * @return 更新后的任务
     */
    public SearchRebuildTask markSucceeded(SearchRebuildTask task, long indexed, long indexCount, long elapsedMs,
                                           String message) {
        task.setStatus(RebuildTaskStatus.SUCCEEDED);
        task.setFinishedAt(now());
        task.setIndexed(indexed);
        task.setIndexCount(indexCount);
        task.setElapsedMs(elapsedMs);
        task.setMessage(message);
        save(task);
        pushRecent(task.getTaskId());
        return task;
    }

    /**
     * 标记失败。
     *
     * @param task    任务
     * @param message 失败原因
     * @return 更新后的任务
     */
    public SearchRebuildTask markFailed(SearchRebuildTask task, String message) {
        task.setStatus(RebuildTaskStatus.FAILED);
        task.setFinishedAt(now());
        task.setMessage(message);
        save(task);
        pushRecent(task.getTaskId());
        return task;
    }

    /**
     * 最近的任务（界面初始化时显示"上次重建结果"）。
     *
     * @return 任务列表（新→旧），无记录时返回空列表
     */
    public List<SearchRebuildTask> recent() {
        List<String> ids = redis.opsForList().range(RedisKeys.SEARCH_REBUILD_RECENT, 0, RECENT_KEEP - 1);
        List<SearchRebuildTask> tasks = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                SearchRebuildTask task = get(id);
                if (task != null) {
                    tasks.add(task);
                }
            }
        }
        return tasks;
    }

    /**
     * 把任务 ID 顶进"最近任务"列表并裁剪长度。
     *
     * @param taskId 任务 ID
     */
    private void pushRecent(String taskId) {
        try {
            redis.opsForList().leftPush(RedisKeys.SEARCH_REBUILD_RECENT, taskId);
            redis.opsForList().trim(RedisKeys.SEARCH_REBUILD_RECENT, 0, RECENT_KEEP - 1);
            redis.expire(RedisKeys.SEARCH_REBUILD_RECENT, TTL);
        } catch (Exception e) {
            log.warn("维护最近重建任务列表失败: {}", e.getMessage());
        }
    }

    /**
     * 当前时间字符串。
     *
     * @return yyyy-MM-dd HH:mm:ss
     */
    private String now() {
        return LocalDateTime.now().format(HUMAN);
    }
}
