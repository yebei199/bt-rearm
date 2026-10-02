package io.github.yebei199.btrearm.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 从公开写入接口验证实际文件内容、轮转及错误隔离。 */
public final class PersistentLogTest {
    /** 每次运行独占临时目录，结束只删除自身资源。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("btrearm-log-test-");
        try {
            writesAndReopens(root.resolve("persist"));
            rotatesWithinLimit(root.resolve("rotate"));
            survivesStorageFailure(root.resolve("failure"));
            System.out.println("PASS: persistence, escaping, rotation, recovery");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /** 重启不清旧日志，记录中的换行不能伪造下一条事件。 */
    private static void writesAndReopens(Path dir) throws Exception {
        PersistentLog log = new PersistentLog(dir.toFile(), 1024, 3);
        require(log.append("first\nline\ttab\\end"), "first write");
        log = new PersistentLog(dir.toFile(), 1024, 3);
        require(log.append("second"), "reopened write");
        String content = Files.readString(dir.resolve("events.log"));
        require(content.equals("first\\nline\\ttab\\\\end\nsecond\n"), content);
    }

    /** 小容量驱动真实轮转，保留最新记录且总文件数和字节数都有上限。 */
    private static void rotatesWithinLimit(Path dir) throws Exception {
        PersistentLog log = new PersistentLog(dir.toFile(), 64, 3);
        for (int i = 0; i < 30; i++) require(log.append("event-" + i + "-汉字"), "rotate write");
        require(log.append("汉😀".repeat(200)), "oversized event");
        require(log.append("latest"), "latest write");
        try (var paths = Files.list(dir)) {
            var files = paths.toList();
            require(files.size() <= 3, "file count");
            for (Path file : files) {
                require(Files.size(file) <= 64, "file byte limit");
                Files.readString(file);
            }
        }
        require(Files.readString(dir.resolve("events.log")).contains("latest"), "latest retained");
        require(Files.readString(dir.resolve("events.1.log")).contains("truncated"), "previous retained");
        require(Files.readString(dir.resolve("events.2.log")).contains("event-29"), "older retained");
    }

    /** 存储路径不可用时返回失败，恢复后继续写，不向连接线程抛异常。 */
    private static void survivesStorageFailure(Path dir) throws Exception {
        Files.writeString(dir, "occupied");
        PersistentLog log = new PersistentLog(dir.toFile(), 128, 2);
        require(!log.append("unwritable"), "failure reported");
        require(log.failures() == 1 && !log.lastError().isEmpty(), "failure observable");
        Files.delete(dir);
        require(log.append("recovered"), "recovery");
        require(Files.readString(dir.resolve("events.log")).contains("recovered"), "recovery bytes");
    }

    /** 不依赖 JVM 的 -ea 开关。 */
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
