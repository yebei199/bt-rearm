package io.github.yebei199.btrearm.diagnostics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 限额文件日志；存储失败不能影响蓝牙控制流程。 */
public final class PersistentLog {
    /** 日志目录及单片字节上限、总文件数。 */
    private final File directory;
    private final int maxBytes;
    private final int fileCount;
    /** 错误留在内存状态里，下一次成功心跳可以带出。 */
    private long failures;
    private String lastError = "";

    /** 目录与限额由调用者提供，便于隔离验证。 */
    public PersistentLog(File directory, int maxBytes, int fileCount) {
        if (maxBytes < 64 || fileCount < 1) throw new IllegalArgumentException("invalid log limits");
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.fileCount = fileCount;
    }

    /** 写入一条事件，失败通过返回值报告。 */
    public synchronized boolean append(String line) {
        try {
            Files.createDirectories(directory.toPath());
            byte[] bytes = encode(line);
            File current = file(0);
            if (current.length() + bytes.length > maxBytes) rotate();
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write(bytes);
            }
            return true;
        } catch (IOException | SecurityException e) {
            failures++;
            lastError = e.toString();
            return false;
        }
    }

    /** 每条只有一行；截断按 Unicode 码点，文件始终保持合法 UTF-8。 */
    private byte[] encode(String line) {
        String value = line.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return bytes;
        String suffix = " [truncated]\n";
        int end = Math.min(value.length(), maxBytes - suffix.length());
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        while ((value.substring(0, end) + suffix).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end = value.offsetByCodePoints(end, -1);
        }
        return (value.substring(0, end) + suffix).getBytes(StandardCharsets.UTF_8);
    }

    /** 最新文件为 events.log，其余按新到旧编号，最多保留指定数量。 */
    private File file(int index) {
        return new File(directory, index == 0 ? "events.log" : "events." + index + ".log");
    }

    /** 只轮转本采集器自己的文件，不遍历或清理目录里的其他文件。 */
    private void rotate() throws IOException {
        Files.deleteIfExists(file(fileCount - 1).toPath());
        for (int i = fileCount - 2; i >= 0; i--) {
            if (file(i).exists()) Files.move(file(i).toPath(), file(i + 1).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 累计失败次数供心跳报告。 */
    public synchronized long failures() { return failures; }

    /** 最近错误供诊断状态展示。 */
    public synchronized String lastError() { return lastError; }
}
