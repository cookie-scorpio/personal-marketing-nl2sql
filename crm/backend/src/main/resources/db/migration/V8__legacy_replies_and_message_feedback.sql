-- 从旧任务已保存的结果恢复回答，不调用模型、不重跑SQL、不编造历史执行过程。
ALTER TABLE conversation_message
 ADD COLUMN feedback_code VARCHAR(8) NULL COMMENT '本人对助手回复的LIKE或DISLIKE',
 ADD COLUMN feedback_updated_at DATETIME(3) NULL;
CREATE INDEX idx_message_chronology ON conversation_message(session_id,created_at,message_id);
CREATE INDEX idx_session_created ON conversation_session(user_id,deleted_at,created_at,session_id);

INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,payload_json,created_at,updated_at)
SELECT q.session_id,q.task_id,'ASSISTANT','legacy-answer',
 COALESCE(q.error_message,NULLIF(q.stage_message,''),'旧版未保存完整回答，请重新提问。'),
 JSON_OBJECT('task_id',q.task_id,'session_id',q.session_id,'status',q.status_code,
   'progress',q.progress,'message',COALESCE(q.error_message,NULLIF(q.stage_message,''),'旧版未保存完整回答，请重新提问。'),
   'clarification_round',q.clarification_round,'repair_attempts',q.repair_attempts,
   'state_version',q.state_version,'thinking_enabled',IF(q.thinking_enabled,CAST('true' AS JSON),CAST('false' AS JSON)),
   'cancellable',CAST('false' AS JSON),'display_query',COALESCE(q.display_query,q.query_text),
   'result',q.result_json,'legacy_recovered',CAST('true' AS JSON),
   'legacy_notice',IF(q.result_json IS NULL,'旧版未保存完整结果，仅恢复已保存的状态或摘要；不会重新执行SQL。','已从旧版保存结果恢复；没有重新调用模型或执行SQL。')),
 COALESCE(q.updated_at,q.created_at),COALESCE(q.updated_at,q.created_at)
FROM query_task q JOIN conversation_session s ON s.session_id=q.session_id AND s.user_id=q.user_id
WHERE q.idempotency_key IS NULL AND s.deleted_at IS NULL
 AND NOT EXISTS (SELECT 1 FROM conversation_message m WHERE m.task_id=q.task_id AND m.role_code='ASSISTANT');
