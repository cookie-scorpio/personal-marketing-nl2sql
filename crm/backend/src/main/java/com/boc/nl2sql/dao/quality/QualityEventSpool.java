package com.boc.nl2sql.dao.quality;

import com.boc.nl2sql.domain.quality.QualityEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 数据库暂时不可用时使用的本地持久化补偿缓冲。
 *
 * <p>文件采用一行一个 {@link QualityEvent} 的 JSONL 格式。写入和重放共用文件锁，避免
 * 同一进程内并发追加与替换文件互相覆盖；跨实例部署时每个实例应使用独立目录。</p>
 */
@Component
public class QualityEventSpool {
    private static final Logger log = LoggerFactory.getLogger(QualityEventSpool.class);
    private final ObjectMapper json;
    private final QualityEventRepository repository;
    private final MeterRegistry meters;
    private final Path pendingFile;
    private final ReentrantLock fileLock = new ReentrantLock();

    /** 解析配置目录，并将实际待处理文件固定为 pending-events.jsonl。 */
    public QualityEventSpool(ObjectMapper json, QualityEventRepository repository, MeterRegistry meters,
                             @Value("${app.quality.spool-dir:./logs/quality-spool}") String spoolDir) {
        this.json = json;
        this.repository = repository;
        this.meters = meters;
        this.pendingFile = Path.of(spoolDir).toAbsolutePath().normalize().resolve("pending-events.jsonl");
    }

    /**
     * 把未能入库的完整事件追加到补偿文件。文件故障只记指标和日志，不向业务调用方抛出。
     */
    public void append(QualityEvent event) {
        fileLock.lock();
        try {
            Files.createDirectories(pendingFile.getParent());
            Files.writeString(pendingFile, json.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            meters.counter("nl2sql.quality.spool.appended").increment();
        } catch (Exception error) {
            meters.counter("nl2sql.quality.spool.failures").increment();
            log.error("F事实写入数据库和补偿文件均失败：eventId={}, type={}", event.eventId(), event.eventType(), error);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * 定时重放补偿文件。成功事件通过 event_id 幂等入库后移除，失败行继续保留。
     * 重写完成后优先原子替换文件，不支持原子移动的文件系统退回普通替换。
     */
    @Scheduled(fixedDelayString = "${app.quality.spool-retry-interval-ms:30000}")
    public void replay() {
        fileLock.lock();
        try {
            if (!Files.exists(pendingFile) || Files.size(pendingFile) == 0) return;
            var remaining = new ArrayList<String>();
            for (String line : Files.readAllLines(pendingFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    repository.save(json.readValue(line, QualityEvent.class));
                    meters.counter("nl2sql.quality.spool.replayed").increment();
                } catch (Exception error) {
                    remaining.add(line);
                }
            }
            Path replacement = pendingFile.resolveSibling("pending-events.tmp");
            Files.write(replacement, remaining, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(replacement, pendingFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(replacement, pendingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            log.warn("F事实补偿重放暂时失败：{}", error.getMessage());
        } finally {
            fileLock.unlock();
        }
    }
}
