package com.campusswap.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusswap.document.search.mq.RebuildTaskStatus;
import com.campusswap.document.search.mq.SearchRebuildTask;
import com.campusswap.document.search.mq.SearchRebuildTaskStore;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M13 重建任务状态仓库集成测试（真实 Redis）。
 *
 * <p>验证三件事：提交即 SUBMITTED、状态流转带回显中文名、最近任务列表能查到——
 * 界面上的"重建任务：执行中/已完成"全靠这份状态。</p>
 *
 * @author Zyaire
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("M13 重建任务状态仓库（Redis）")
class SearchRebuildTaskStoreTest {

    @Autowired
    private SearchRebuildTaskStore taskStore;

    @Test
    @DisplayName("提交→执行中→成功：状态、条数、耗时都能回读")
    void statusLifecycle() {
        SearchRebuildTask task = taskStore.create(1L, 4L);
        assertThat(task.getTaskId()).isNotBlank();
        assertThat(task.getStatus()).isEqualTo(RebuildTaskStatus.SUBMITTED);
        assertThat(task.getStatusLabel()).isEqualTo("已提交");
        assertThat(task.getSubmitCostMs()).isEqualTo(4L);

        taskStore.markRunning(task);
        SearchRebuildTask running = taskStore.get(task.getTaskId());
        assertThat(running.getStatus()).isEqualTo(RebuildTaskStatus.RUNNING);
        assertThat(running.getAttempts()).isEqualTo(1);
        assertThat(running.getStartedAt()).isNotBlank();

        taskStore.markSucceeded(task, 28, 28, 550, "索引重建完成");
        SearchRebuildTask done = taskStore.get(task.getTaskId());
        assertThat(done.getStatus()).isEqualTo(RebuildTaskStatus.SUCCEEDED);
        assertThat(done.getIndexed()).isEqualTo(28);
        assertThat(done.getIndexCount()).isEqualTo(28);
        assertThat(done.getElapsedMs()).isEqualTo(550);
        assertThat(done.getFinishedAt()).isNotBlank();
    }

    @Test
    @DisplayName("失败状态与原因能回读，且出现在最近任务列表里")
    void failedTaskAppearsInRecent() {
        SearchRebuildTask task = taskStore.create(1L, 3L);
        taskStore.markFailed(task, "Elasticsearch 未连接");

        SearchRebuildTask failed = taskStore.get(task.getTaskId());
        assertThat(failed.getStatus()).isEqualTo(RebuildTaskStatus.FAILED);
        assertThat(failed.getMessage()).contains("Elasticsearch 未连接");

        List<SearchRebuildTask> recent = taskStore.recent();
        assertThat(recent).isNotEmpty();
        assertThat(recent.get(0).getTaskId()).isEqualTo(task.getTaskId());
    }

    @Test
    @DisplayName("不存在的任务返回 null（接口层据此回 404）")
    void unknownTaskReturnsNull() {
        assertThat(taskStore.get("not-exist-task-id")).isNull();
    }

    /**
     * 语义检查：任务对象能被 Jackson 3 正常序列化（写进 Redis 的就是这个 JSON）。
     *
     * @throws Exception 序列化异常
     */
    @Test
    @DisplayName("任务 JSON 可序列化（Redis 存的就是它）")
    void jsonRoundTrip() throws Exception {
        SearchRebuildTask task = taskStore.create(9L, 2L);
        String json = JsonMapper.builder().build().writeValueAsString(task);
        assertThat(json).contains(task.getTaskId()).contains("SUBMITTED").contains("已提交");
    }
}
