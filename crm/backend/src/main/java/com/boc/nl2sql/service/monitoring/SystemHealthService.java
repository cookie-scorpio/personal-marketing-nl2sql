package com.boc.nl2sql.service.monitoring;

import com.boc.nl2sql.dao.monitoring.QualityMonitorRepository;
import com.boc.nl2sql.model.ModelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 后台系统健康与资源监控的聚合服务。
 * 只读探测数据库、Redis、模型适配器、磁盘与补偿文件，不修改任何业务状态。
 */
@Service
public class SystemHealthService {
    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);
    private static final int HISTORY_CAPACITY = 240;

    private final QualityMonitorRepository repository;
    private final StringRedisTemplate redis;
    private final List<ModelAdapter> modelAdapters;
    private final Executor queryExecutor;
    private final Executor qualityEventExecutor;
    private final GpuProbe gpuProbe;
    private final Path logDir;
    private final LocalDateTime startedAt = LocalDateTime.now();

    /** 资源采样的内存环形缓冲；进程内保留，重启后重新积累。 */
    private final ArrayDequeResourceHistory history = new ArrayDequeResourceHistory(HISTORY_CAPACITY);

    public SystemHealthService(QualityMonitorRepository repository, StringRedisTemplate redis,
            List<ModelAdapter> modelAdapters,
            @Qualifier("queryExecutor") Executor queryExecutor,
            @Qualifier("qualityEventExecutor") Executor qualityEventExecutor,
            @Value("${app.query.sql-log-dir:./logs}") String logDir) {
        this.repository = repository;
        this.redis = redis;
        this.modelAdapters = modelAdapters;
        this.queryExecutor = queryExecutor;
        this.qualityEventExecutor = qualityEventExecutor;
        this.gpuProbe = new GpuProbe();
        this.logDir = Path.of(logDir);
        history.append(sample());
    }

    /** 每 5 秒采样一次 CPU/内存，供资源监控页面绘制近 20 分钟曲线。 */
    @Scheduled(fixedDelay = 5000)
    public void sampleResources() {
        history.append(sample());
    }

    public List<Map<String, Object>> resourceHistory() {
        return history.snapshot();
    }

    private Map<String, Object> sample() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("sampled_at", LocalDateTime.now().toString());
        point.put("process_cpu_load", ratio(os.getProcessCpuLoad()));
        point.put("system_cpu_load", ratio(os.getSystemCpuLoad()));
        point.put("jvm_heap_used_mb", memory.getHeapMemoryUsage().getUsed() / 1048576);
        point.put("jvm_heap_max_mb", memory.getHeapMemoryUsage().getMax() / 1048576);
        List<Map<String, Object>> gpus = gpuProbe.snapshot();
        if (!gpus.isEmpty()) point.put("gpu_utilization_percent", ((Map<?, ?>) gpus.get(0)).get("utilization_percent"));
        return point;
    }

    /** 系统健康总览：整体状态 + 各组件结论。任一核心组件失败即整体 DEGRADED。 */
    public Map<String, Object> healthSnapshot() {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(databaseHealth());
        components.add(redisHealth());
        components.add(modelHealth());
        components.add(diskHealth());
        components.add(spoolHealth());
        components.add(executorHealth());

        boolean allUp = components.stream().allMatch(item -> Boolean.TRUE.equals(item.get("healthy")));
        var runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", allUp ? "UP" : "DEGRADED");
        result.put("started_at", startedAt.toString());
        result.put("uptime_seconds", Duration.between(startedAt, LocalDateTime.now()).getSeconds());
        result.put("java_version", System.getProperty("java.version"));
        result.put("os_name", os.getName() + " " + os.getVersion() + " / " + os.getArch());
        result.put("available_processors", os.getAvailableProcessors());
        result.put("jvm_heap_used_mb", memory.getHeapMemoryUsage().getUsed() / 1048576);
        result.put("jvm_heap_max_mb", memory.getHeapMemoryUsage().getMax() / 1048576);
        result.put("components", components);
        return result;
    }

    private Map<String, Object> databaseHealth() {
        Map<String, Object> item = base("database", "业务库 MySQL");
        try {
            LocalDateTime now = jdbcNow();
            item.put("healthy", now != null);
            item.put("detail", now == null ? "数据库时间查询返回空" : "连接正常，数据库时间 " + now);
        } catch (RuntimeException error) {
            item.put("healthy", false);
            item.put("detail", "数据库连接失败：" + rootMessage(error));
        }
        return item;
    }

    private Map<String, Object> redisHealth() {
        Map<String, Object> item = base("redis", "Redis（幂等缓存）");
        try {
            Boolean pong = redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>)
                    connection -> connection.ping() != null);
            item.put("healthy", Boolean.TRUE.equals(pong));
            item.put("detail", Boolean.TRUE.equals(pong) ? "PING 正常" : "PING 无响应");
        } catch (RuntimeException error) {
            item.put("healthy", false);
            item.put("detail", "Redis 连接失败：" + rootMessage(error));
        }
        return item;
    }

    private Map<String, Object> modelHealth() {
        Map<String, Object> item = base("model_gateway", "模型网关");
        List<Map<String, Object>> adapters = new ArrayList<>();
        boolean anyAvailable = false;
        boolean realModelAvailable = false;
        for (ModelAdapter adapter : modelAdapters) {
            boolean available = adapter.available();
            anyAvailable = anyAvailable || available;
            // mock 适配器只服务本地规则演示，不在健康页展示；状态结论仅基于对外服务的大模型。
            if ("mock".equalsIgnoreCase(adapter.provider())) continue;
            realModelAvailable = realModelAvailable || available;
            adapters.add(Map.of("provider", adapter.provider(), "available", available));
        }
        item.put("healthy", anyAvailable);
        item.put("detail", realModelAvailable ? "存在可用的大模型服务"
                : anyAvailable ? "大模型服务未配置，当前仅本地演示适配器可用"
                : "没有可用的大模型服务");
        item.put("adapters", adapters);
        return item;
    }

    private Map<String, Object> diskHealth() {
        Map<String, Object> item = base("disk", "日志磁盘");
        try {
            File dir = logDir.toFile();
            long usable = dir.getUsableSpace() / 1073741824;
            long total = dir.getTotalSpace() / 1073741824;
            item.put("healthy", usable > 1);
            item.put("detail", "日志目录 " + logDir + " 可用 " + usable + " GB / 共 " + total + " GB");
        } catch (RuntimeException error) {
            item.put("healthy", false);
            item.put("detail", "磁盘探测失败：" + rootMessage(error));
        }
        return item;
    }

    /** 事实补偿文件有积压说明数据库曾经写入失败，属于需要关注的质量信号。 */
    private Map<String, Object> spoolHealth() {
        Map<String, Object> item = base("quality_spool", "事实补偿文件");
        Path spool = logDir.resolve("quality-spool").resolve("pending-events.jsonl");
        if (!Files.exists(spool)) {
            item.put("healthy", true);
            item.put("detail", "无待补偿事实");
            item.put("pending_events", 0);
            return item;
        }
        long lines;
        try (var reader = Files.newBufferedReader(spool, java.nio.charset.StandardCharsets.UTF_8)) {
            lines = reader.lines().count();
        } catch (java.io.IOException error) {
            item.put("healthy", false);
            item.put("detail", "补偿文件读取失败：" + rootMessage(error));
            return item;
        }
        item.put("healthy", lines == 0);
        item.put("pending_events", lines);
        item.put("detail", lines == 0 ? "无待补偿事实" : "存在 " + lines + " 条待补偿事实，需关注数据库写入");
        return item;
    }

    private Map<String, Object> executorHealth() {
        Map<String, Object> item = base("executors", "异步线程池");
        item.put("healthy", true);
        item.put("detail", "问数线程池与质量事实线程池已注册");
        item.put("query_executor", executorDescription(queryExecutor, "queryExecutor"));
        item.put("quality_executor", executorDescription(qualityEventExecutor, "qualityEventExecutor"));
        return item;
    }

    /** 通过 ThreadPoolTaskExecutor 暴露队列水位；其他实现只给出类型名。 */
    private Map<String, Object> executorDescription(Executor executor, String fallbackName) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("name", fallbackName);
        if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool) {
            description.put("active_count", pool.getActiveCount());
            description.put("pool_size", pool.getPoolSize());
            description.put("queue_size", pool.getThreadPoolExecutor().getQueue().size());
            description.put("queue_capacity", pool.getThreadPoolExecutor().getQueue().size()
                    + pool.getThreadPoolExecutor().getQueue().remainingCapacity());
        }
        return description;
    }

    /** 资源监控面板：最新采样 + GPU 详情 + 线程数。 */
    public Map<String, Object> resourceSnapshot() {
        Map<String, Object> latest = sample();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long totalMemory = os.getTotalMemorySize() / 1048576;
        long freeMemory = os.getFreeMemorySize() / 1048576;

        Map<String, Object> result = new LinkedHashMap<>(latest);
        result.put("os_total_memory_mb", totalMemory);
        result.put("os_free_memory_mb", freeMemory);
        result.put("os_used_memory_mb", totalMemory - freeMemory);
        result.put("thread_count", threads.getThreadCount());
        result.put("peak_thread_count", threads.getPeakThreadCount());
        result.put("gpu", gpuProbe.snapshot());
        result.put("history", history.snapshot());
        return result;
    }

    /** SQL 健康度页面：尝试阶段分布、修复轨迹、失败类型与小时趋势。 */
    public Map<String, Object> sqlHealth(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window_hours", hours);
        result.put("phase_counts", repository.sqlAttemptPhaseCounts(since));
        result.put("repair_counts", repository.repairStatusCounts(since));
        result.put("event_type_counts", repository.auditEventTypeCounts(since));
        result.put("hourly_trend", repository.sqlAttemptHourlyTrend(since));
        result.put("model_calls", repository.modelCallStats(since));
        return result;
    }

    /** 业务监控页面：客户规模、执行量与成功率、耗时分布。 */
    public Map<String, Object> businessSnapshot(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<Double> durations = repository.taskDurations(since);

        Map<String, Object> customers = new LinkedHashMap<>();
        customers.putAll(repository.customerOverview());
        customers.put("by_level", repository.customersByLevel());
        customers.put("top_regions", repository.customersByRegion(10));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window_hours", hours);
        result.put("customers", customers);
        result.put("status_counts", repository.taskStatusCounts(since));
        result.put("hourly_trend", repository.taskHourlyTrend(since));
        result.put("duration", durationSummary(durations));
        result.put("active_sessions", repository.activeSessionCount());
        return result;
    }

    /** 总览页一屏聚合：健康摘要、24 小时业务量、待回流候选与最近评测。 */
    public Map<String, Object> overview() {
        Map<String, Object> health = healthSnapshot();
        Map<String, Object> business = businessSnapshot(24);
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("health_status", health.get("status"));
        result.put("uptime_seconds", health.get("uptime_seconds"));
        result.put("process_cpu_load", ratio(os.getProcessCpuLoad()));
        result.put("jvm_heap_used_mb", health.get("jvm_heap_used_mb"));
        result.put("jvm_heap_max_mb", health.get("jvm_heap_max_mb"));
        result.put("business", Map.of(
                "customers", ((Map<?, ?>) business.get("customers")).get("total_customers"),
                "trend", business.get("hourly_trend"),
                "status_counts", business.get("status_counts"),
                "duration", business.get("duration"),
                "event_counts", repository.auditEventTypeCounts(LocalDateTime.now().minusHours(24))));
        result.put("pending_candidates", repository.pendingCandidateCount());
        result.put("active_sessions", repository.activeSessionCount());
        result.put("candidate_counts_24h", repository.candidateTypeCounts(LocalDateTime.now().minusHours(24)));
        return result;
    }

    private double ratio(double value) {
        return Double.isNaN(value) ? 0.0 : Math.round(value * 10000.0) / 10000.0;
    }

    private Map<String, Object> durationSummary(List<Double> durations) {
        List<Double> sorted = durations.stream().sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("samples", sorted.size());
        summary.put("avg_seconds", sorted.isEmpty() ? 0 : round(sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        summary.put("p50_seconds", sorted.isEmpty() ? 0 : round(percentile(sorted, 0.5)));
        summary.put("p95_seconds", sorted.isEmpty() ? 0 : round(percentile(sorted, 0.95)));
        summary.put("max_seconds", sorted.isEmpty() ? 0 : round(sorted.get(sorted.size() - 1)));
        return summary;
    }

    private double percentile(List<Double> sorted, double ratio) {
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Map<String, Object> base(String key, String label) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        return item;
    }

    private String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private LocalDateTime jdbcNow() {
        return repository.databaseNow();
    }

    /** 每 10 秒最多探测一次 GPU；nvidia-smi 不存在时保持空列表，页面显示“未检测到 GPU”。 */
    private static final class GpuProbe {
        private volatile long refreshedAtNanos;
        private volatile List<Map<String, Object>> cached = List.of();

        List<Map<String, Object>> snapshot() {
            long now = System.nanoTime();
            if (TimeUnit.NANOSECONDS.toMillis(now - refreshedAtNanos) > 10_000) {
                synchronized (this) {
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - refreshedAtNanos) > 10_000) {
                        cached = probe();
                        refreshedAtNanos = System.nanoTime();
                    }
                }
            }
            return cached;
        }

        boolean available() {
            return !snapshot().isEmpty();
        }

        private List<Map<String, Object>> probe() {
            try {
                Process process = new ProcessBuilder("nvidia-smi",
                        "--query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                        "--format=csv,noheader,nounits").start();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return List.of();
                }
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    List<Map<String, Object>> gpus = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("\\s*,\\s*");
                        if (parts.length < 6) continue;
                        Map<String, Object> gpu = new LinkedHashMap<>();
                        gpu.put("index", Integer.parseInt(parts[0].trim()));
                        gpu.put("name", parts[1].trim());
                        gpu.put("utilization_percent", number(parts[2]));
                        gpu.put("memory_used_mb", number(parts[3]));
                        gpu.put("memory_total_mb", number(parts[4]));
                        gpu.put("temperature_celsius", number(parts[5]));
                        gpus.add(gpu);
                    }
                    return List.copyOf(gpus);
                }
            } catch (Exception notInstalled) {
                return List.of();
            }
        }

        private Number number(String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException notInteger) {
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException notNumber) {
                    return null;
                }
            }
        }
    }

    /** 固定容量的采样历史：超出容量时丢弃最旧采样，读写均加锁以支持采样线程与请求线程并发。 */
    private static final class ArrayDequeResourceHistory {
        private final int capacity;
        private final java.util.Deque<Map<String, Object>> points = new java.util.ArrayDeque<>();

        ArrayDequeResourceHistory(int capacity) {
            this.capacity = capacity;
        }

        void append(Map<String, Object> point) {
            synchronized (points) {
                points.addLast(point);
                while (points.size() > capacity) points.pollFirst();
            }
        }

        List<Map<String, Object>> snapshot() {
            synchronized (points) {
                return List.copyOf(points);
            }
        }
    }
}
