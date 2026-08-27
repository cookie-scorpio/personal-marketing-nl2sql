package com.boc.nl2sql.conversation.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 所有任务变更以版本号原子提交，取消、确认与后台完成不能相互覆盖。 */
@Component
public class TaskStateStore {
    private final QueryTaskMapper mapper;
    public TaskStateStore(QueryTaskMapper mapper) { this.mapper = mapper; }
    public void save(QueryTaskEntity task) {
        if (!trySave(task)) throw new TaskChangedException();
    }
    public boolean trySave(QueryTaskEntity task) {
        long previous = task.getStateVersion();
        task.setStateVersion(previous + 1);
        task.setUpdatedAt(LocalDateTime.now());
        int updated = mapper.update(task, Wrappers.<QueryTaskEntity>lambdaUpdate()
                .eq(QueryTaskEntity::getTaskId, task.getTaskId())
                .eq(QueryTaskEntity::getStateVersion, previous)
                .notIn(QueryTaskEntity::getStatusCode, "SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "DEGRADED"));
        if (updated == 0) task.setStateVersion(previous);
        return updated == 1;
    }
    public boolean active(String taskId) {
        QueryTaskEntity current = mapper.selectById(taskId);
        return current != null && !QueryStatus.terminal(current.getStatusCode());
    }
    public void ensureActive(String taskId) {
        if (!active(taskId)) throw new TaskChangedException();
    }
    public static class TaskChangedException extends RuntimeException {
        public TaskChangedException() { super("任务状态已变更"); }
    }
}
