package com.boc.nl2sql.service.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.boc.nl2sql.common.exception.BusinessException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台日志中心：只允许读取 logback 配置的固定日志文件（运行、SQL 复核、会话与大模型调用），
 * 按尾部行数返回。从文件末尾反向读取，避免大文件把内存占满；关键字过滤在读取结果内进行。
 */
@Service
public class LogQueryService {
    private static final int MAX_LINES = 500;
    private static final long MAX_SCAN_BYTES = 4L * 1024 * 1024;
    private static final Map<String, String> FILES = Map.of(
            "application", "application.log",
            "sql-review", "sql-review.log",
            "conversation", "conversation.log",
            "model", "model.log");

    private final Path logDir;

    public LogQueryService(@Value("${app.query.sql-log-dir:./logs}") String logDir) {
        this.logDir = Path.of(logDir);
    }

    /** 三个日志文件的元信息，用于前端文件切换页签。 */
    public List<Map<String, Object>> fileCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        FILES.forEach((key, fileName) -> {
            Path file = logDir.resolve(fileName);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("file_name", fileName);
            item.put("exists", Files.exists(file));
            try {
                item.put("size_bytes", Files.exists(file) ? Files.size(file) : 0);
                item.put("last_modified", Files.exists(file)
                        ? LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(),
                                java.time.ZoneId.systemDefault()).toString()
                        : null);
            } catch (IOException error) {
                item.put("size_bytes", 0);
                item.put("last_modified", null);
            }
            catalog.add(item);
        });
        return catalog;
    }

    /**
     * 读取指定日志的尾部。lines 收敛到 1..500；keyword 为空时不过滤。
     * 最多回扫 4MB，仍不足 lines 行时返回已读取部分，并在响应中说明是否被截断。
     */
    public Map<String, Object> tail(String fileKey, int lines, String keyword) {
        String fileName = FILES.get(fileKey);
        if (fileName == null) throw new BusinessException(400101, "未知的日志文件：" + fileKey);
        int safeLines = Math.max(1, Math.min(lines, MAX_LINES));
        Path file = logDir.resolve(fileName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", fileKey);
        result.put("file_name", fileName);
        if (!Files.exists(file)) {
            result.put("exists", false);
            result.put("lines", List.of());
            result.put("truncated", false);
            return result;
        }
        List<String> collected = new ArrayList<>();
        boolean truncated = false;
        try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
            long fileLength = reader.length();
            long start = Math.max(0, fileLength - MAX_SCAN_BYTES);
            truncated = start > 0;
            byte[] buffer = new byte[(int) (fileLength - start)];
            reader.seek(start);
            reader.readFully(buffer);
            String content = new String(buffer, StandardCharsets.UTF_8);
            List<String> allLines = new ArrayList<>(content.lines().toList());
            if (start > 0 && !allLines.isEmpty()) allLines.remove(0); // 丢弃可能不完整的行
            for (int index = allLines.size() - 1; index >= 0 && collected.size() < safeLines; index--) {
                String line = allLines.get(index);
                if (line.isBlank()) continue;
                if (keyword == null || keyword.isBlank() || line.contains(keyword)) collected.add(line);
            }
        } catch (IOException error) {
            throw new BusinessException(500102, "日志文件读取失败：" + fileName);
        }
        java.util.Collections.reverse(collected);
        result.put("exists", true);
        result.put("lines", collected);
        result.put("truncated", truncated);
        result.put("keyword", keyword == null || keyword.isBlank() ? null : keyword);
        return result;
    }
}
