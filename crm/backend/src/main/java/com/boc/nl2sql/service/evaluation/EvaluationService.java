package com.boc.nl2sql.service.evaluation;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.dao.evaluation.EvalCandidateRepository;
import com.boc.nl2sql.dao.evaluation.EvalDatasetItemMapper;
import com.boc.nl2sql.dao.evaluation.EvalDatasetMapper;
import com.boc.nl2sql.dao.evaluation.EvalRunItemMapper;
import com.boc.nl2sql.dao.evaluation.EvalRunMapper;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.evaluation.EvalCandidateReviewEntity;
import com.boc.nl2sql.dao.evaluation.EvalCandidateReviewMapper;
import com.boc.nl2sql.domain.evaluation.EvalDatasetEntity;
import com.boc.nl2sql.domain.evaluation.EvalDatasetItemEntity;
import com.boc.nl2sql.domain.evaluation.EvalDatasetStatus;
import com.boc.nl2sql.domain.evaluation.EvalRunEntity;
import com.boc.nl2sql.domain.evaluation.EvalRunItemEntity;
import com.boc.nl2sql.domain.evaluation.EvalRunStatus;
import com.boc.nl2sql.domain.evaluation.EvalRunTrigger;
import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import com.boc.nl2sql.service.execution.SqlSafetyValidator;
import com.boc.nl2sql.service.quality.QualityFacts;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据回流与评测集管理：候选审核、草稿维护、发布与运行触发。
 *
 * <p>发布语义：草稿内容冻结为只读的 PUBLISHED 版本并自动触发一次评测运行，
 * 同时克隆出下一份草稿供继续维护；任何写操作都只允许发生在 DRAFT 上。</p>
 */
@Service
public class EvaluationService {
    private final EvalCandidateRepository candidates;
    private final EvalCandidateReviewMapper reviews;
    private final EvalDatasetMapper datasets;
    private final EvalDatasetItemMapper items;
    private final EvalRunMapper runs;
    private final EvalRunItemMapper runItems;
    private final EvaluationRunner runner;
    private final SqlSafetyValidator safety;
    private final QualityFacts facts;
    private final ObjectMapper json;

    public EvaluationService(EvalCandidateRepository candidates, EvalCandidateReviewMapper reviews,
            EvalDatasetMapper datasets, EvalDatasetItemMapper items, EvalRunMapper runs,
            EvalRunItemMapper runItems, EvaluationRunner runner, SqlSafetyValidator safety,
            QualityFacts facts, ObjectMapper json) {
        this.candidates = candidates;
        this.reviews = reviews;
        this.datasets = datasets;
        this.items = items;
        this.runs = runs;
        this.runItems = runItems;
        this.runner = runner;
        this.safety = safety;
        this.facts = facts;
        this.json = json;
    }

    /** 候选池分页；载荷中的问题、SQL 与错误信息提升到顶层，供采纳对话框预填。 */
    public Map<String, Object> candidatePage(String status, int pageNo, int pageSize) {
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int safePage = Math.max(1, pageNo);
        List<Map<String, Object>> rows = candidates.page(status, safeSize, (long) (safePage - 1) * safeSize)
                .stream().map(this::decodeCandidate).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page_no", safePage);
        result.put("page_size", safeSize);
        result.put("total", candidates.count(status));
        result.put("items", rows);
        return result;
    }

    /** 采纳候选：写入当前草稿并记录审核结论，重复采纳或已忽略都会被拒绝。 */
    @Transactional
    public Map<String, Object> acceptCandidate(String eventId, String questionText, String expectedSql,
            String note, CurrentUser reviewer) {
        Map<String, Object> event = candidates.payload(eventId);
        if (event == null) throw new BusinessException(404201, "候选事实不存在或不属于候选数据");
        EvalCandidateReviewEntity existing = reviews.selectById(eventId);
        if (existing != null) throw new BusinessException(409201, "该候选已审核，不能重复处理");
        if (questionText == null || questionText.isBlank()) throw new BusinessException(400202, "问题原文不能为空");
        if (expectedSql == null || expectedSql.isBlank()) throw new BusinessException(400203, "期望 SQL 不能为空");
        safety.validate(expectedSql);

        EvalDatasetEntity draft = requireDraft();
        EvalDatasetItemEntity item = new EvalDatasetItemEntity();
        item.setDatasetId(draft.getId());
        item.setSourceEventId(eventId);
        item.setSourceTaskId((String) event.get("task_id"));
        item.setQuestionText(questionText.strip());
        item.setExpectedSql(expectedSql.strip());
        item.setNote(blankToNull(note));
        items.insert(item);
        refreshItemCount(draft);

        EvalCandidateReviewEntity review = new EvalCandidateReviewEntity();
        review.setEventId(eventId);
        review.setDecision("ACCEPTED");
        review.setDatasetItemId(item.getId());
        review.setReviewedBy(reviewer.userId());
        review.setNote(blankToNull(note));
        reviews.insert(review);
        facts.publish(QualityFact.builder(QualityEventType.EVAL_CANDIDATE_REVIEWED, "QUALITY")
                .userId(reviewer.userId()).summary("candidate accepted")
                .detail("event_id", eventId).detail("dataset_item_id", item.getId()).build());
        return Map.of("dataset_item_id", item.getId(), "dataset_id", draft.getId());
    }

    /** 忽略候选：仅记录结论，不进入评测集。已采纳的候选不允许改为忽略，避免误操作丢数据。 */
    @Transactional
    public void ignoreCandidate(String eventId, String note, CurrentUser reviewer) {
        if (candidates.payload(eventId) == null) throw new BusinessException(404201, "候选事实不存在或不属于候选数据");
        EvalCandidateReviewEntity existing = reviews.selectById(eventId);
        if (existing != null) throw new BusinessException(409201, "该候选已审核，不能重复处理");
        EvalCandidateReviewEntity review = new EvalCandidateReviewEntity();
        review.setEventId(eventId);
        review.setDecision("IGNORED");
        review.setReviewedBy(reviewer.userId());
        review.setNote(blankToNull(note));
        reviews.insert(review);
        facts.publish(QualityFact.builder(QualityEventType.EVAL_CANDIDATE_REVIEWED, "QUALITY")
                .userId(reviewer.userId()).summary("candidate ignored")
                .detail("event_id", eventId).build());
    }

    /** 全部评测集（草稿与历次发布版本）。 */
    public List<Map<String, Object>> listDatasets() {
        List<EvalDatasetEntity> all = datasets.selectList(
                new LambdaQueryWrapper<EvalDatasetEntity>().orderByDesc(EvalDatasetEntity::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (EvalDatasetEntity dataset : all) {
            Map<String, Object> item = datasetView(dataset);
            if (dataset.isDraft()) item.put("item_count", countItems(dataset.getId()));
            result.add(item);
        }
        return result;
    }

    /** 单个评测集详情，附带全部条目。 */
    public Map<String, Object> datasetDetail(long datasetId) {
        EvalDatasetEntity dataset = datasets.selectById(datasetId);
        if (dataset == null) throw new BusinessException(404202, "评测集不存在");
        Map<String, Object> result = datasetView(dataset);
        List<Map<String, Object>> itemViews = new ArrayList<>();
        for (EvalDatasetItemEntity item : selectItems(datasetId)) itemViews.add(itemView(item));
        result.put("items", itemViews);
        return result;
    }

    /** 当前草稿视图，懒创建逻辑见 {@link #requireDraft()}。 */
    public Map<String, Object> currentDraft() {
        return datasetView(requireDraft());
    }

    /** 向草稿追加条目；发布版本不可修改。 */
    @Transactional
    public Map<String, Object> addItem(String questionText, String expectedSql, String note, CurrentUser user) {
        EvalDatasetEntity draft = requireDraft();
        EvalDatasetItemEntity item = insertItem(draft.getId(), null, null, questionText, expectedSql, note, user);
        refreshItemCount(draft);
        return itemView(item);
    }

    /** 修改草稿条目；发布版本不可修改。 */
    @Transactional
    public void updateItem(long itemId, String questionText, String expectedSql, String note, CurrentUser user) {
        EvalDatasetItemEntity item = items.selectById(itemId);
        if (item == null) throw new BusinessException(404203, "评测条目不存在");
        requireDraft(item.getDatasetId());
        if (questionText != null && !questionText.isBlank()) item.setQuestionText(questionText.strip());
        if (expectedSql != null && !expectedSql.isBlank()) {
            safety.validate(expectedSql);
            item.setExpectedSql(expectedSql.strip());
        }
        if (note != null) item.setNote(blankToNull(note));
        items.updateById(item);
        facts.publish(QualityFact.builder(QualityEventType.EVAL_CANDIDATE_REVIEWED, "QUALITY")
                .userId(user.userId()).summary("dataset item updated")
                .detail("dataset_item_id", itemId).build());
    }

    /** 删除草稿条目；发布版本不可修改。 */
    @Transactional
    public void deleteItem(long itemId) {
        EvalDatasetItemEntity item = items.selectById(itemId);
        if (item == null) return;
        requireDraft(item.getDatasetId());
        items.deleteById(itemId);
        refreshItemCount(datasets.selectById(item.getDatasetId()));
    }

    /**
     * 发布评测集：草稿冻结为新的发布版本（version 递增），克隆下一份草稿，
     * 并创建一次 AUTO_PUBLISH 评测运行异步执行。返回发布版本与运行信息。
     */
    @Transactional
    public Map<String, Object> publish(long datasetId, CurrentUser publisher) {
        EvalDatasetEntity draft = requireDraft(datasetId);
        List<EvalDatasetItemEntity> draftItems = selectItems(datasetId);
        if (draftItems.isEmpty()) throw new BusinessException(400204, "评测集为空，无法发布");

        int nextVersion = nextVersion();
        draft.setStatus(EvalDatasetStatus.PUBLISHED.name());
        draft.setVersion(nextVersion);
        draft.setItemCount(draftItems.size());
        draft.setPublishedAt(LocalDateTime.now());
        draft.setPublishedBy(publisher.userId());
        datasets.updateById(draft);

        EvalDatasetEntity successor = new EvalDatasetEntity();
        successor.setName(draft.getName());
        successor.setDescription(draft.getDescription());
        successor.setStatus(EvalDatasetStatus.DRAFT.name());
        successor.setVersion(0);
        successor.setItemCount(0);
        successor.setCreatedBy(publisher.userId());
        datasets.insert(successor);
        for (EvalDatasetItemEntity source : draftItems) {
            insertItem(successor.getId(), source.getSourceEventId(), source.getSourceTaskId(),
                    source.getQuestionText(), source.getExpectedSql(), source.getNote(), publisher);
        }

        EvalRunEntity run = createRun(draft, draftItems.size(), EvalRunTrigger.AUTO_PUBLISH, publisher);
        facts.publish(QualityFact.builder(QualityEventType.EVAL_DATASET_PUBLISHED, "QUALITY")
                .userId(publisher.userId()).evaluationRunId(String.valueOf(run.getId()))
                .summary("dataset published as version " + nextVersion)
                .detail("dataset_id", draft.getId()).detail("version", nextVersion)
                .detail("item_count", draftItems.size()).build());
        runner.startAsync(run.getId(), publisher);
        return Map.of("dataset", datasetView(draft), "run_id", run.getId());
    }

    /** 对已发布版本手动重跑一次评测。 */
    public Map<String, Object> startRun(long datasetId, CurrentUser user) {
        EvalDatasetEntity dataset = datasets.selectById(datasetId);
        if (dataset == null) throw new BusinessException(404202, "评测集不存在");
        if (dataset.isDraft()) throw new BusinessException(400205, "草稿不能直接评测，请先发布");
        long total = countItems(datasetId);
        if (total == 0) throw new BusinessException(400204, "评测集为空，无法评测");
        EvalRunEntity run = createRun(dataset, (int) total, EvalRunTrigger.MANUAL, user);
        runner.startAsync(run.getId(), user);
        return Map.of("run_id", run.getId());
    }

    /** 评测运行分页列表，附带维度汇总，供评测后台的历史记录表使用。 */
    public Map<String, Object> runPage(int pageNo, int pageSize) {
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int safePage = Math.max(1, pageNo);
        Long total = runs.selectCount(null);
        List<EvalRunEntity> rows = runs.selectList(new LambdaQueryWrapper<EvalRunEntity>()
                .orderByDesc(EvalRunEntity::getId)
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize));
        List<Map<String, Object>> items = new ArrayList<>();
        for (EvalRunEntity run : rows) items.add(runView(run));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page_no", safePage);
        result.put("page_size", safeSize);
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    /** 单次运行详情：聚合指标、逐条明细与评测集元信息。 */
    public Map<String, Object> runDetail(long runId) {
        EvalRunEntity run = runs.selectById(runId);
        if (run == null) throw new BusinessException(404204, "评测运行不存在");
        List<EvalRunItemEntity> details = runItems.selectList(new LambdaQueryWrapper<EvalRunItemEntity>()
                .eq(EvalRunItemEntity::getRunId, runId).orderByAsc(EvalRunItemEntity::getId));
        Map<String, Object> result = runView(run);
        result.put("dataset", datasetView(datasets.selectById(run.getDatasetId())));
        result.put("summary", summarize(details));
        result.put("items", details.stream().map(this::runItemView).toList());
        return result;
    }

    /** 维度汇总：执行成功率、SQL 匹配率、结果一致率与耗时分位，均为实时聚合。 */
    private Map<String, Object> summarize(List<EvalRunItemEntity> details) {
        long total = details.size();
        long executionSuccess = details.stream().filter(item -> Boolean.TRUE.equals(item.getExecutionSuccess())).count();
        long sqlMatch = details.stream().filter(item -> Boolean.TRUE.equals(item.getSqlMatch())).count();
        long consistent = details.stream().filter(item -> Boolean.TRUE.equals(item.getResultConsistent())).count();
        long passed = details.stream().filter(item -> "PASSED".equals(item.getOutcome())).count();
        List<Long> latencies = details.stream().map(EvalRunItemEntity::getElapsedMs)
                .filter(value -> value != null).sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_items", total);
        summary.put("passed_items", passed);
        summary.put("execution_success_items", executionSuccess);
        summary.put("sql_match_items", sqlMatch);
        summary.put("result_consistent_items", consistent);
        summary.put("execution_success_rate", rate(executionSuccess, total));
        summary.put("sql_match_rate", rate(sqlMatch, total));
        summary.put("result_consistent_rate", rate(consistent, total));
        summary.put("pass_rate", rate(passed, total));
        summary.put("avg_elapsed_ms", latencies.isEmpty() ? 0 : Math.round(
                latencies.stream().mapToLong(Long::longValue).average().orElse(0)));
        summary.put("p50_elapsed_ms", latencies.isEmpty() ? 0 : percentile(latencies, 0.5));
        summary.put("p95_elapsed_ms", latencies.isEmpty() ? 0 : percentile(latencies, 0.95));
        return summary;
    }

    private long percentile(List<Long> sorted, double ratio) {
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double rate(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 10000.0 / total) / 100.0;
    }

    private EvalRunEntity createRun(EvalDatasetEntity dataset, int totalItems, EvalRunTrigger trigger,
            CurrentUser user) {
        EvalRunEntity run = new EvalRunEntity();
        run.setDatasetId(dataset.getId());
        run.setDatasetVersion(dataset.getVersion());
        run.setTriggerType(trigger.name());
        run.setStatus(EvalRunStatus.PENDING.name());
        run.setTotalItems(totalItems);
        run.setFinishedItems(0);
        run.setPassedItems(0);
        run.setTriggeredBy(user.userId());
        runs.insert(run);
        return run;
    }

    private EvalDatasetItemEntity insertItem(long datasetId, String sourceEventId, String sourceTaskId,
            String questionText, String expectedSql, String note, CurrentUser user) {
        if (questionText == null || questionText.isBlank()) throw new BusinessException(400202, "问题原文不能为空");
        if (expectedSql == null || expectedSql.isBlank()) throw new BusinessException(400203, "期望 SQL 不能为空");
        safety.validate(expectedSql);
        EvalDatasetItemEntity item = new EvalDatasetItemEntity();
        item.setDatasetId(datasetId);
        item.setSourceEventId(sourceEventId);
        item.setSourceTaskId(sourceTaskId);
        item.setQuestionText(questionText.strip());
        item.setExpectedSql(expectedSql.strip());
        item.setNote(blankToNull(note));
        items.insert(item);
        return item;
    }

    /** 当前草稿；首次访问时自动创建，评测后台始终存在可编辑的落点。 */
    private EvalDatasetEntity requireDraft() {
        EvalDatasetEntity draft = findDraft();
        if (draft != null) return draft;
        EvalDatasetEntity created = new EvalDatasetEntity();
        created.setName("默认评测集");
        created.setDescription("由数据回流候选审核沉淀的评测样本");
        created.setStatus(EvalDatasetStatus.DRAFT.name());
        created.setVersion(0);
        created.setItemCount(0);
        datasets.insert(created);
        return created;
    }

    private EvalDatasetEntity requireDraft(long datasetId) {
        EvalDatasetEntity dataset = datasets.selectById(datasetId);
        if (dataset == null) throw new BusinessException(404202, "评测集不存在");
        if (!dataset.isDraft()) throw new BusinessException(409202, "评测集已发布，内容不可修改");
        return dataset;
    }

    private EvalDatasetEntity findDraft() {
        List<EvalDatasetEntity> drafts = datasets.selectList(new LambdaQueryWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getStatus, EvalDatasetStatus.DRAFT.name())
                .orderByDesc(EvalDatasetEntity::getId).last("LIMIT 1"));
        return drafts.isEmpty() ? null : drafts.get(0);
    }

    private List<EvalDatasetItemEntity> selectItems(long datasetId) {
        return items.selectList(new LambdaQueryWrapper<EvalDatasetItemEntity>()
                .eq(EvalDatasetItemEntity::getDatasetId, datasetId).orderByAsc(EvalDatasetItemEntity::getId));
    }

    private long countItems(long datasetId) {
        return items.selectCount(new LambdaQueryWrapper<EvalDatasetItemEntity>()
                .eq(EvalDatasetItemEntity::getDatasetId, datasetId));
    }

    private void refreshItemCount(EvalDatasetEntity dataset) {
        EvalDatasetEntity entity = new EvalDatasetEntity();
        entity.setId(dataset.getId());
        entity.setItemCount((int) countItems(dataset.getId()));
        datasets.updateById(entity);
    }

    /** 发布版本号在全部数据集上递增，保持单一版本谱系。 */
    private int nextVersion() {
        List<EvalDatasetEntity> latest = datasets.selectList(new LambdaQueryWrapper<EvalDatasetEntity>()
                .isNotNull(EvalDatasetEntity::getVersion).orderByDesc(EvalDatasetEntity::getVersion)
                .last("LIMIT 1"));
        return latest.isEmpty() ? 1 : latest.get(0).getVersion() + 1;
    }

    private Map<String, Object> datasetView(EvalDatasetEntity dataset) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", dataset.getId());
        view.put("name", dataset.getName());
        view.put("description", dataset.getDescription());
        view.put("status", dataset.getStatus());
        view.put("version", dataset.getVersion());
        view.put("item_count", dataset.getItemCount());
        view.put("published_at", dataset.getPublishedAt());
        view.put("created_at", dataset.getCreatedAt());
        view.put("updated_at", dataset.getUpdatedAt());
        view.put("editable", dataset.isDraft());
        return view;
    }

    private Map<String, Object> itemView(EvalDatasetItemEntity item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("dataset_id", item.getDatasetId());
        view.put("source_event_id", item.getSourceEventId());
        view.put("source_task_id", item.getSourceTaskId());
        view.put("question_text", item.getQuestionText());
        view.put("expected_sql", item.getExpectedSql());
        view.put("note", item.getNote());
        view.put("created_at", item.getCreatedAt());
        view.put("updated_at", item.getUpdatedAt());
        return view;
    }

    private Map<String, Object> runView(EvalRunEntity run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("dataset_id", run.getDatasetId());
        view.put("dataset_version", run.getDatasetVersion());
        view.put("trigger_type", run.getTriggerType());
        view.put("status", run.getStatus());
        view.put("total_items", run.getTotalItems());
        view.put("finished_items", run.getFinishedItems());
        view.put("passed_items", run.getPassedItems());
        view.put("error_message", run.getErrorMessage());
        view.put("started_at", run.getStartedAt());
        view.put("finished_at", run.getFinishedAt());
        view.put("created_at", run.getCreatedAt());
        return view;
    }

    private Map<String, Object> runItemView(EvalRunItemEntity item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("item_id", item.getItemId());
        view.put("question_text", item.getQuestionText());
        view.put("expected_sql", item.getExpectedSql());
        view.put("generated_sql", item.getGeneratedSql());
        view.put("execution_success", item.getExecutionSuccess());
        view.put("sql_match", item.getSqlMatch());
        view.put("result_consistent", item.getResultConsistent());
        view.put("expected_rows", item.getExpectedRows());
        view.put("actual_rows", item.getActualRows());
        view.put("elapsed_ms", item.getElapsedMs());
        view.put("outcome", item.getOutcome());
        view.put("failure_stage", item.getFailureStage());
        view.put("error_message", item.getErrorMessage());
        return view;
    }

    /** 解码候选行：payload 解析为对象，并尽力提取问题原文与 SQL 供前端预填。 */
    private Map<String, Object> decodeCandidate(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        Object payloadText = result.remove("payload_json");
        Map<String, Object> payload = payloadText == null ? Map.of()
                : json.readValue(payloadText.toString(), Map.class);
        result.put("payload", payload);
        result.put("question_text", firstText(payload, "query_text", "merged_query_text", "question_text"));
        result.put("sql_text", firstText(payload, "sql", "original_sql", "repaired_sql"));
        result.put("error_text", firstText(payload, "error_message", "reason", "failure"));
        return result;
    }

    private String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
