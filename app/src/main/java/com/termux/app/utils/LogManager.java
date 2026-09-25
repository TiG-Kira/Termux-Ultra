package com.termux.app.utils;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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

    /**
     * 日志文件大小上限（字节）。超过后按「保留末尾」方式裁剪，避免长期运行后
     * 日志文件无限增长占满用户存储（配合 {@link #cleanOldLogs(int)} 的按天清理双保险）。
     */
    private static final long MAX_LOG_FILE_SIZE = 2 * 1024 * 1024L; // 2 MB
    /** 裁剪后保留的文件尾部大小（字节）。 */
    private static final long TRIM_KEEP_SIZE = 1024 * 1024L; // 1 MB
    /** 每写入 N 条日志检查一次文件大小，避免每条日志都做一次 stat 系统调用。 */
    private static final int SIZE_CHECK_WRITE_INTERVAL = 64;

    public static final int LEVEL_INFO = 0;
    public static final int LEVEL_WARNING = 1;
    public static final int LEVEL_EXCEPTION = 2;

    private static LogManager instance;
    private final Context appContext;
    private final File logFile;

    private Thread logcatThread;
    private final AtomicBoolean logcatRunning = new AtomicBoolean(false);

    private final ThreadLocal<SimpleDateFormat> dateFormatThreadLocal = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        }
    };

    private final ThreadLocal<SimpleDateFormat> logcatTimeFormatThreadLocal = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
        }
    };

    private static final ThreadLocal<SimpleDateFormat> displayTimeFormatThreadLocal = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        }
    };

    // 日志缓存：避免高频重复解析同一文件
    private List<LogEntry> cachedLogs;
    private long cachedFileModTime;
    private int cachedLevelFilter = -1;

    /** 距上次文件大小检查的写入条数，用于降低 stat 调用频率。 */
    private int writesSinceSizeCheck = 0;

    private LogManager(Context context) {
        this.appContext = context.getApplicationContext();
        File logDir = new File(appContext.getFilesDir(), LOG_DIR_NAME);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        logFile = new File(logDir, LOG_FILE_NAME);
        // cleanOldLogs 涉及读+重写整个日志文件（磁盘 I/O），放到后台线程异步执行，
        // 避免在 Application.onCreate() 主线程阻塞冷启动。
        // 先做一次大小兜底裁剪，兼容历史版本遗留的超大日志文件（避免首次解析时内存峰值过高）。
        new Thread(() -> {
            trimLogFileIfNeeded();
            cleanOldLogs(3);
        }, "LogManager-Cleanup").start();
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
        String timestamp = dateFormatThreadLocal.get().format(new Date());
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
        afterWrite();
    }

    /**
     * 写入后的收尾工作：使读取缓存失效，并按间隔检查/裁剪日志文件大小。
     * 每次写入都会清缓存（保证 UI 读到最新数据），但只每
     * {@link #SIZE_CHECK_WRITE_INTERVAL} 条做一次文件大小检查（避免频繁 stat）。
     */
    private void afterWrite() {
        // 写后立即使缓存失效，避免 UI 读到过期数据
        cachedLogs = null;
        cachedFileModTime = 0;

        if (++writesSinceSizeCheck >= SIZE_CHECK_WRITE_INTERVAL) {
            writesSinceSizeCheck = 0;
            trimLogFileIfNeeded();
        }
    }

    /**
     * 日志文件超过 {@link #MAX_LOG_FILE_SIZE} 时，仅保留文件末尾 {@link #TRIM_KEEP_SIZE}
     * 字节的**完整行**，防止日志文件无限增长占满用户存储。
     *
     * 采用「从尾部随机定位 + 流式按行复制」的方式实现，不会把整个文件读入内存
     * （对比 cleanOldLogs 的全量解析，这里刻意避免大文件造成的内存峰值）。
     * 全程以 UTF-8 按行处理，避免按字节截断破坏多字节字符（中文日志）。
     */
    private synchronized void trimLogFileIfNeeded() {
        if (!logFile.exists() || logFile.length() <= MAX_LOG_FILE_SIZE) {
            return;
        }

        final long fileLength = logFile.length();
        File tmpFile = new File(logFile.getParentFile(), LOG_FILE_NAME + ".trim.tmp");

        try (FileInputStream fis = new FileInputStream(logFile);
             BufferedWriter writer = new BufferedWriter(
                 new OutputStreamWriter(new FileOutputStream(tmpFile, false), StandardCharsets.UTF_8))) {

            long start = Math.max(0, fileLength - TRIM_KEEP_SIZE);
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long s = fis.skip(start - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                // 定位点可能落在某一行中间，丢弃该不完整行的剩余部分，
                // 保证裁剪后的文件以完整行开头（否则解析时会得到半条日志）。
                int b;
                while ((b = fis.read()) != -1 && b != '\n') {
                    // skip partial line
                }
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.write('\n');
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to trim log file", e);
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            return;
        }

        // 同目录内 rename 替换原文件
        if (!tmpFile.renameTo(logFile)) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
            if (!tmpFile.renameTo(logFile)) {
                Log.w(TAG, "Failed to replace trimmed log file");
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
                return;
            }
        }

        cachedLogs = null;
        cachedFileModTime = 0;
        writesSinceSizeCheck = 0;
        Log.i(TAG, "Trimmed log file from " + fileLength + " to " + logFile.length() + " bytes");
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
                pb = new ProcessBuilder("logcat", "--pid=" + pid, "-v", "threadtime");
                pb.redirectErrorStream(true);
                process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (logcatRunning.get() && (line = reader.readLine()) != null) {
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

            String timestampStr = dateFormatThreadLocal.get().format(timestamp);
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
            afterWrite();
        } catch (Exception e) {
            // 不记录完整异常栈，避免 logcat 收集形成循环
            Log.w(TAG, "Skipped unparseable logcat line: " + e.getClass().getSimpleName());
        }
    }

    private Date parseLogcatTime(String timePart) {
        try {
            SimpleDateFormat sdf = logcatTimeFormatThreadLocal.get();
            Date date = sdf.parse(timePart);
            if (date == null) return null;
            Calendar now = Calendar.getInstance();
            Calendar parsed = Calendar.getInstance();
            parsed.setTime(date);
            parsed.set(Calendar.YEAR, now.get(Calendar.YEAR));
            return parsed.getTime();
        } catch (ArrayIndexOutOfBoundsException e) {
            // Android SimpleDateFormat/Calendar 在某些 locale 下可能触发 ArrayIndexOutOfBoundsException
            // 回退方案：手动解析时间字符串
            try {
                return parseLogcatTimeFallback(timePart);
            } catch (Exception e2) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 回退的 logcat 时间解析方法，使用字符串手动解析避免 SimpleDateFormat 的已知 bug。
     */
    private Date parseLogcatTimeFallback(String timePart) {
        // 格式: MM-dd HH:mm:ss.SSS
        if (timePart == null || timePart.length() < 18) return null;
        try {
            int month = Integer.parseInt(timePart.substring(0, 2));
            int day = Integer.parseInt(timePart.substring(3, 5));
            int hour = Integer.parseInt(timePart.substring(6, 8));
            int minute = Integer.parseInt(timePart.substring(9, 11));
            int second = Integer.parseInt(timePart.substring(12, 14));
            int millis = Integer.parseInt(timePart.substring(15, 18));

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, month - 1);
            cal.set(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, second);
            cal.set(Calendar.MILLISECOND, millis);
            return cal.getTime();
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
    public synchronized List<LogEntry> getLogs(Integer level, long startTime, long endTime) {
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
            int firstBracket = entryStr.indexOf('[');
            int firstClose = entryStr.indexOf(']', firstBracket);
            if (firstBracket == -1 || firstClose == -1) return null;

            String timestampStr = entryStr.substring(firstBracket + 1, firstClose).trim();
            Date timestamp = parseTimestampSafely(timestampStr);
            if (timestamp == null) return null;

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
            // 不记录完整异常栈到 logcat，避免形成: 异常->logcat->收集->再异常 的恶性循环
            Log.w(TAG, "Skipped unparseable log entry: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 安全解析日志时间戳，处理 ArrayIndexOutOfBoundsException 等已知的 SimpleDateFormat bug。
     */
    private Date parseTimestampSafely(String timestampStr) {
        try {
            return dateFormatThreadLocal.get().parse(timestampStr);
        } catch (ArrayIndexOutOfBoundsException e) {
            // SimpleDateFormat 在某些 locale 下的已知 bug，使用手动解析回退
            return parseTimestampFallback(timestampStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 手动解析 yyyy-MM-dd HH:mm:ss.SSS 格式时间戳。
     */
    private Date parseTimestampFallback(String timestampStr) {
        if (timestampStr == null || timestampStr.length() < 23) return null;
        try {
            int year = Integer.parseInt(timestampStr.substring(0, 4));
            int month = Integer.parseInt(timestampStr.substring(5, 7));
            int day = Integer.parseInt(timestampStr.substring(8, 10));
            int hour = Integer.parseInt(timestampStr.substring(11, 13));
            int minute = Integer.parseInt(timestampStr.substring(14, 16));
            int second = Integer.parseInt(timestampStr.substring(17, 19));
            int millis = Integer.parseInt(timestampStr.substring(20, 23));

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month - 1);
            cal.set(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, second);
            cal.set(Calendar.MILLISECOND, millis);
            return cal.getTime();
        } catch (Exception e) {
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
            clearMemoryCache();
            writesSinceSizeCheck = 0;
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear logs", e);
            return false;
        }
    }

    /**
     * 清空内存中的日志读取缓存（{@link #cachedLogs}）。
     *
     * 该缓存保存的是完整解析后的日志条目列表，是 LogManager 唯一的常驻内存大头。
     * 清空后下次 {@link #getLogs} 会重新从文件解析（结果一致，仅多一次磁盘读取）。
     * 供 Application 在系统内存紧张（{@code onTrimMemory}）时主动释放。
     */
    public synchronized void clearMemoryCache() {
        cachedLogs = null;
        cachedFileModTime = 0;
        cachedLevelFilter = -1;
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
                    logEntry.append("[").append(dateFormatThreadLocal.get().format(new Date(entry.timestamp))).append("] ")
                            .append("[").append(getLevelString(entry.level)).append("] ")
                            .append("[").append(entry.tag).append("] ")
                            .append(entry.message).append("\n");
                    writer.write(logEntry.toString());
                }
            }

            Log.i(TAG, "Cleaned old logs: kept " + filteredEntries.size() + " entries from " + allEntries.size());
            // 文件已修改，清除缓存
            clearMemoryCache();
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
            SimpleDateFormat sdf = displayTimeFormatThreadLocal.get();
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
