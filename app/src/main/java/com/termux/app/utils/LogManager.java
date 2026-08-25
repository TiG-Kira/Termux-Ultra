package com.termux.app.utils;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用日志管理器，负责收集、存储和查询应用日志。
 * 支持 INFO、WARNING、EXCEPTION 三种级别，并可通过 logcat 实时收集本应用日志。
 */
public class LogManager {

    private static final String TAG = "LogManager";
    private static final String LOG_FILE_NAME = "app_log.txt";
    private static final String LOG_DIR_NAME = "logs";

    public static final int LEVEL_INFO = 0;
    public static final int LEVEL_WARNING = 1;
    public static final int LEVEL_EXCEPTION = 2;

    private static LogManager instance;
    private final Context appContext;
    private final File logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private Thread logcatThread;
    private final AtomicBoolean logcatRunning = new AtomicBoolean(false);

    // 日志缓存：避免高频重复解析同一文件
    private List<LogEntry> cachedLogs;
    private long cachedFileModTime;
    private int cachedLevelFilter = -1;

    private LogManager(Context context) {
        this.appContext = context.getApplicationContext();
        File logDir = new File(appContext.getFilesDir(), LOG_DIR_NAME);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        logFile = new File(logDir, LOG_FILE_NAME);
        // 自动清理3天前的旧日志
        cleanOldLogs(3);
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new LogManager(context.getApplicationContext());
        }
    }

    public static synchronized LogManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("LogManager not initialized. Call init() first.");
        }
        return instance;
    }

    /**
     * 记录 INFO 级别日志
     */
    public void info(String tag, String message) {
        log(LEVEL_INFO, tag, message, null);
    }

    /**
     * 记录 WARNING 级别日志
     */
    public void warning(String tag, String message) {
        log(LEVEL_WARNING, tag, message, null);
    }

    /**
     * 记录 EXCEPTION 级别日志
     */
    public void exception(String tag, String message, Throwable throwable) {
        log(LEVEL_EXCEPTION, tag, message, throwable);
    }

    /**
     * 内部日志记录方法
     */
    private synchronized void log(int level, String tag, String message, Throwable throwable) {
        String timestamp = dateFormat.format(new Date());
        String levelStr = getLevelString(level);

        StringBuilder logEntry = new StringBuilder();
        logEntry.append("[").append(timestamp).append("] ")
                .append("[").append(levelStr).append("] ")
                .append("[").append(tag).append("] ")
                .append(message);

        if (throwable != null) {
            logEntry.append("\n").append(Log.getStackTraceString(throwable));
        }

        logEntry.append("\n");

        // 写入文件
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(logEntry.toString());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write log", e);
        }
    }

    /**
     * 启动实时 logcat 收集，仅收集当前应用进程（com.termux）的日志。
     */
    public synchronized void startLogcatCollection() {
        if (logcatRunning.get()) {
            return;
        }
        logcatRunning.set(true);

        logcatThread = new Thread(() -> {
            java.lang.Process process = null;
            try {
                int pid = Process.myPid();
                // 优先使用 --pid 仅读取当前进程日志；若不支持则回退到按 PID 过滤
                ProcessBuilder pb;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    pb = new ProcessBuilder("logcat", "--pid=" + pid, "-v", "threadtime");
                } else {
                    pb = new ProcessBuilder("logcat", "-v", "threadtime");
                }
                pb.redirectErrorStream(true);
                process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (logcatRunning.get() && (line = reader.readLine()) != null) {
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                            // 低版本按 PID 过滤
                            if (!line.contains(" " + pid + " ") && !line.startsWith(String.valueOf(pid) + " ")) {
                                continue;
                            }
                        }
                        parseAndWriteLogcatLine(line);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Logcat collection failed", e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
                logcatRunning.set(false);
            }
        }, "LogManager-Logcat");
        logcatThread.setDaemon(true);
        logcatThread.start();
    }

    /**
     * 停止实时 logcat 收集。
     */
    public synchronized void stopLogcatCollection() {
        logcatRunning.set(false);
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }

    /**
     * 解析 logcat threadtime 行并写入日志文件。
     * 格式示例：08-25 10:30:45.123  1234  5678 D Tag: message
     */
    private synchronized void parseAndWriteLogcatLine(String line) {
        try {
            if (line.length() < 21) return;

            // 时间部分：MM-dd HH:mm:ss.SSS
            String timePart = line.substring(0, 18);
            Date timestamp = parseLogcatTime(timePart);
            if (timestamp == null) return;

            // 跳过分隔空白，解析 PID/TID/级别/Tag
            int cursor = 19;
            int len = line.length();
            while (cursor < len && Character.isWhitespace(line.charAt(cursor))) cursor++;

            // PID
            while (cursor < len && !Character.isWhitespace(line.charAt(cursor))) cursor++;
            while (cursor < len && Character.isWhitespace(line.charAt(cursor))) cursor++;

            // TID
            while (cursor < len && !Character.isWhitespace(line.charAt(cursor))) cursor++;
            while (cursor < len && Character.isWhitespace(line.charAt(cursor))) cursor++;

            if (cursor >= len) return;
            char levelChar = line.charAt(cursor);
            int level = logcatLevelToLevel(levelChar);
            cursor++;

            while (cursor < len && Character.isWhitespace(line.charAt(cursor))) cursor++;

            int colonIndex = line.indexOf(':', cursor);
            if (colonIndex == -1) return;

            String tag = line.substring(cursor, colonIndex).trim();
            String message = line.substring(colonIndex + 1).trim();

            String timestampStr = dateFormat.format(timestamp);
            String levelStr = getLevelString(level);

            StringBuilder logEntry = new StringBuilder();
            logEntry.append("[").append(timestampStr).append("] ")
                    .append("[").append(levelStr).append("] ")
                    .append("[").append(tag).append("] ")
                    .append(message)
                    .append("\n");

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(logEntry.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse logcat line: " + line, e);
        }
    }

    private Date parseLogcatTime(String timePart) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
            Date date = sdf.parse(timePart);
            if (date == null) return null;
            // 补全年份（取当前年）
            Calendar now = Calendar.getInstance();
            Calendar parsed = Calendar.getInstance();
            parsed.setTime(date);
            parsed.set(Calendar.YEAR, now.get(Calendar.YEAR));
            return parsed.getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private int logcatLevelToLevel(char levelChar) {
        switch (levelChar) {
            case 'W':
                return LEVEL_WARNING;
            case 'E':
            case 'F':
                return LEVEL_EXCEPTION;
            case 'I':
            case 'D':
            case 'V':
            default:
                return LEVEL_INFO;
        }
    }

    /**
     * 获取所有日志条目
     */
    public List<LogEntry> getAllLogs() {
        return getLogs(null, -1, -1);
    }

    /**
     * 按级别过滤日志
     */
    public List<LogEntry> getLogsByLevel(int level) {
        return getLogs(level, -1, -1);
    }

    /**
     * 按时间范围过滤日志
     * @param startTime 开始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     */
    public List<LogEntry> getLogsByTimeRange(long startTime, long endTime) {
        return getLogs(null, startTime, endTime);
    }

    /**
     * 综合过滤日志
     * @param level 日志级别，null 表示不过滤
     * @param startTime 开始时间戳，-1 表示不过滤
     * @param endTime 结束时间戳，-1 表示不过滤
     */
    public List<LogEntry> getLogs(Integer level, long startTime, long endTime) {
        long currentModTime = logFile.exists() ? logFile.lastModified() : 0;
        int levelKey = level != null ? level : -1;

        // 命中缓存：文件未修改且过滤条件相同
        if (cachedLogs != null
                && currentModTime == cachedFileModTime
                && levelKey == cachedLevelFilter
                && startTime == -1 && endTime == -1) {
            return new ArrayList<>(cachedLogs);
        }

        List<LogEntry> entries = new ArrayList<>();

        if (!logFile.exists()) {
            return entries;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            StringBuilder currentEntry = null;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[")) {
                    if (currentEntry != null) {
                        LogEntry entry = parseLogEntry(currentEntry.toString());
                        if (entry != null && shouldInclude(entry, level, startTime, endTime)) {
                            entries.add(entry);
                        }
                    }
                    currentEntry = new StringBuilder(line).append("\n");
                } else if (currentEntry != null) {
                    currentEntry.append(line).append("\n");
                }
            }

            if (currentEntry != null) {
                LogEntry entry = parseLogEntry(currentEntry.toString());
                if (entry != null && shouldInclude(entry, level, startTime, endTime)) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read logs", e);
        }

        Collections.reverse(entries);

        // 仅缓存无时间过滤的全量结果
        if (startTime == -1 && endTime == -1) {
            cachedLogs = new ArrayList<>(entries);
            cachedFileModTime = currentModTime;
            cachedLevelFilter = levelKey;
        }

        return entries;
    }

    private LogEntry parseLogEntry(String entryStr) {
        try {
            // 格式: [timestamp] [level] [tag] message
            int firstBracket = entryStr.indexOf('[');
            int firstClose = entryStr.indexOf(']', firstBracket);
            if (firstBracket == -1 || firstClose == -1) return null;

            String timestampStr = entryStr.substring(firstBracket + 1, firstClose).trim();
            Date timestamp = dateFormat.parse(timestampStr);

            int secondBracket = entryStr.indexOf('[', firstClose);
            int secondClose = entryStr.indexOf(']', secondBracket);
            if (secondBracket == -1 || secondClose == -1) return null;

            String levelStr = entryStr.substring(secondBracket + 1, secondClose).trim();
            int level = getLevelFromString(levelStr);

            int thirdBracket = entryStr.indexOf('[', secondClose);
            int thirdClose = entryStr.indexOf(']', thirdBracket);
            if (thirdBracket == -1 || thirdClose == -1) return null;

            String tag = entryStr.substring(thirdBracket + 1, thirdClose).trim();

            String message = entryStr.substring(thirdClose + 1).trim();

            return new LogEntry(timestamp.getTime(), level, tag, message);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse log entry", e);
            return null;
        }
    }

    private boolean shouldInclude(LogEntry entry, Integer level, long startTime, long endTime) {
        if (level != null && entry.level != level) {
            return false;
        }
        if (startTime != -1 && entry.timestamp < startTime) {
            return false;
        }
        if (endTime != -1 && entry.timestamp > endTime) {
            return false;
        }
        return true;
    }

    /**
     * 清空所有日志。
     * 采用截断文件内容的方式而非删除文件，避免 logcat 实时收集线程在删除后立即重建文件。
     * @return true 表示成功清除，false 表示没有日志可清除或清除失败
     */
    public synchronized boolean clearLogs() {
        if (!logFile.exists() || logFile.length() == 0) {
            return false;
        }
        try (FileWriter writer = new FileWriter(logFile, false)) {
            writer.write("");
            // 清除缓存
            cachedLogs = null;
            cachedFileModTime = 0;
            cachedLevelFilter = -1;
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear logs", e);
            return false;
        }
    }

    /**
     * 清理超过指定天数的旧日志，仅保留近期日志。
     * @param days 保留最近 N 天的日志
     */
    public synchronized void cleanOldLogs(int days) {
        if (!logFile.exists() || logFile.length() == 0) {
            return;
        }

        long cutoffTime = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000;

        try {
            List<LogEntry> allEntries = getLogs(null, -1, -1);
            if (allEntries.isEmpty()) {
                return;
            }

            List<LogEntry> filteredEntries = new ArrayList<>();
            for (LogEntry entry : allEntries) {
                if (entry.timestamp >= cutoffTime) {
                    filteredEntries.add(entry);
                }
            }

            if (filteredEntries.size() == allEntries.size()) {
                return;
            }

            // 将保留的日志重新写回文件
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, false))) {
                for (LogEntry entry : filteredEntries) {
                    StringBuilder logEntry = new StringBuilder();
                    logEntry.append("[").append(dateFormat.format(new Date(entry.timestamp))).append("] ")
                            .append("[").append(getLevelString(entry.level)).append("] ")
                            .append("[").append(entry.tag).append("] ")
                            .append(entry.message).append("\n");
                    writer.write(logEntry.toString());
                }
            }

            Log.i(TAG, "Cleaned old logs: kept " + filteredEntries.size() + " entries from " + allEntries.size());
            // 文件已修改，清除缓存
            cachedLogs = null;
            cachedFileModTime = 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to clean old logs", e);
        }
    }

    /**
     * 获取日志文件大小（字节）
     */
    public long getLogFileSize() {
        return logFile.exists() ? logFile.length() : 0;
    }

    /**
     * 获取日志文件最后修改时间戳，用于快速判断日志是否有更新。
     * @return 最后修改时间戳（毫秒），文件不存在返回 0
     */
    public long getLogFileLastModified() {
        return logFile.exists() ? logFile.lastModified() : 0;
    }

    private String getLevelString(int level) {
        switch (level) {
            case LEVEL_INFO: return "INFO";
            case LEVEL_WARNING: return "WARNING";
            case LEVEL_EXCEPTION: return "EXCEPTION";
            default: return "UNKNOWN";
        }
    }

    private int getLevelFromString(String levelStr) {
        switch (levelStr) {
            case "INFO": return LEVEL_INFO;
            case "WARNING": return LEVEL_WARNING;
            case "EXCEPTION": return LEVEL_EXCEPTION;
            default: return LEVEL_INFO;
        }
    }

    /**
     * 日志条目数据类
     */
    public static class LogEntry {
        public final long timestamp;
        public final int level;
        public final String tag;
        public final String message;

        public LogEntry(long timestamp, int level, String tag, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        public String getFormattedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }

        public String getLevelString() {
            switch (level) {
                case LEVEL_INFO: return "INFO";
                case LEVEL_WARNING: return "WARNING";
                case LEVEL_EXCEPTION: return "EXCEPTION";
                default: return "UNKNOWN";
            }
        }
    }
}
