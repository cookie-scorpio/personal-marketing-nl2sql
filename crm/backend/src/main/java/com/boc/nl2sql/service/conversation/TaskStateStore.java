package com.boc.nl2sql.service.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.domain.conversation.QueryStatus;
import com.boc.nl2sql.domain.conversation.QueryTaskEntity;
import com.boc.nl2sql.dao.conversation.QueryTaskMapper;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 所有任务变更以版本号原子提交，取消、确认与后台完成不能相互覆盖。 */
@Component
public class TaskStateStore {
    private final QueryTaskMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private ConversationStore conversations;
    public TaskStateStore(QueryTaskMapper mapper) { this.mapper = mapper; }
    @org.springframework.transaction.annotation.Transactional
    public void save(QueryTaskEntity task) {
        if (!trySave(task)) throw new TaskChangedException();
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean trySave(QueryTaskEntity task) {
        if(conversations!=null)conversations.lockTask(task);
        long previous = task.getStateVersion();
        task.setStateVersion(previous + 1);
        task.setUpdatedAt(LocalDateTime.now());
        int updated = mapper.update(task, Wrappers.<QueryTaskEntity>lambdaUpdate()
                .eq(QueryTaskEntity::getTaskId, task.getTaskId())
                .eq(QueryTaskEntity::getStateVersion, previous)
                .notIn(QueryTaskEntity::getStatusCode, "SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "DEGRADED"));
        if (updated == 0) task.setStateVersion(previous);
        if (updated == 1 && conversations != null) conversations.record(task);
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
