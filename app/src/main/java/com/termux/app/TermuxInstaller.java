package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.utils.BootstrapDownloader;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * bootstrap 过程回调。传非空实现时由调用方自己展示进度与错误（OOBE 用 Compose 页面），
     * 不再弹系统 ProgressDialog / AlertDialog；传 null 沿用系统弹窗的旧行为。
     */
    public interface BootstrapCallback {
        void onDownloadStart();

        void onInstallStart();

        void onError(String message);
    }

    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        setupBootstrapIfNeeded(activity, whenDone, null);
    }

    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone,
                                       final BootstrapCallback callback) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            // 有回调时必须交给调用方展示：这条分支原本直接 System.exit，
            // OOBE 会连同安装失败页一起被杀掉，状态机停在「正在下载」且无法重试。
            if (callback != null) {
                reportBootstrapError(activity, whenDone, callback, bootstrapErrorMessage);
            } else {
                sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
                MessageDialogUtils.exitAppWithErrorMessage(activity,
                    activity.getString(R.string.bootstrap_error_title),
                    bootstrapErrorMessage);
            }
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            // 同上：原本无条件弹系统对话框就 return，回调永不触发，
            // OOBE 永远停在转圈状态且没有重试入口。
            if (callback != null) {
                reportBootstrapError(activity, whenDone, callback, bootstrapErrorMessage);
            } else {
                sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
                MessageDialogUtils.showMessage(activity,
                    activity.getString(R.string.bootstrap_error_title),
                    bootstrapErrorMessage, null);
            }
            return;
        }

        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            File[] PREFIX_FILE_LIST = TERMUX_PREFIX_DIR.listFiles();
            if(PREFIX_FILE_LIST == null || PREFIX_FILE_LIST.length == 0 || (PREFIX_FILE_LIST.length == 1 && TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH.equals(PREFIX_FILE_LIST[0].getAbsolutePath()))) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains the tmp directory.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = callback == null
            ? ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false)
            : null;
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        reportBootstrapError(activity, whenDone, callback, Error.getErrorMarkdownString(error));
                        return;
                    }

                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        reportBootstrapError(activity, whenDone, callback, Error.getErrorMarkdownString(error));
                        return;
                    }

                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        reportBootstrapError(activity, whenDone, callback, Error.getErrorMarkdownString(error));
                        return;
                    }

                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        reportBootstrapError(activity, whenDone, callback, Error.getErrorMarkdownString(error));
                        return;
                    }

                    final String bootstrapArch = BootstrapDownloader.getArchForAbi();
                    if (callback != null) {
                        activity.runOnUiThread(callback::onDownloadStart);
                    }

                    Logger.logInfo(LOG_TAG, "Downloading bootstrap zip for arch: " + bootstrapArch);
                    final byte[] zipBytes = BootstrapDownloader.getBootstrapZip(bootstrapArch);

                    if (callback != null) {
                        activity.runOnUiThread(callback::onInstallStart);
                    }

                    extractBootstrapZip(zipBytes);
                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    activity.runOnUiThread(whenDone);

                } catch (final Throwable t) {
                    // 必须连 Error 一起兜住：zip 约 30MB 全量读进内存，低端机可能 OOM，
                    // 只 catch Exception 会让回调不触发，OOBE 停在转圈且没有重试入口。
                    reportBootstrapError(activity, whenDone, callback, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(t)));

                } finally {
                    if (progress != null) {
                        activity.runOnUiThread(() -> {
                            // 下载+安装耗时可达数分钟，Activity 可能已被销毁，此时 dismiss 会抛。
                            try {
                                progress.dismiss();
                            } catch (RuntimeException ignored) {
                            }
                        });
                    }
                }
            }
        }.start();
    }

    /** 失败统一入口：有回调就交给调用方展示（OOBE 的 Compose 错误页），否则弹系统对话框。 */
    private static void reportBootstrapError(Activity activity, Runnable whenDone,
                                             BootstrapCallback callback, String message) {
        try {
            Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
        } catch (Throwable ignored) {
        }

        // 崩溃通知是尽力而为的旁路：它要写外部文件、建通知通道，失败概率不低
        // （bootstrap 失败的原因之一恰恰就是存储不可访问）。它一旦抛异常，
        // 下面的失败回调就永远不会执行，OOBE 会永远停在转圈且不给重试入口。
        try {
            sendBootstrapCrashReportNotification(activity, message);
        } catch (Throwable t) {
            try {
                Logger.logError(LOG_TAG, "Failed to send bootstrap crash report: " + t);
            } catch (Throwable ignored) {
            }
        }

        if (callback != null) {
            // Activity 已销毁时 post 的 runnable 不会再跑，回调丢失等于静默失败；
            // 此时无处展示，只能记日志。Activity 存活时一律走 UI 线程回调。
            if (activity.isFinishing() || activity.isDestroyed()) {
                try {
                    Logger.logError(LOG_TAG, "Activity gone, bootstrap error not surfaced to UI: " + message);
                } catch (Throwable ignored) {
                }
                return;
            }
            activity.runOnUiThread(() -> callback.onError(message));
        } else {
            showBootstrapErrorDialog(activity, whenDone, message);
        }
    }

    private static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        CrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            "## Bootstrap Error\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Error.getErrorMarkdownString(error), true, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    final File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 1) {
                        for (int i = 1; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)), true, true);
                }
            }
        }.start();
    }

    static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    /**
     * 把已校验通过的 bootstrap zip 解压到 staging 目录，处理 SYMLINKS.txt，
     * 修正 Unix 权限，然后 atomic rename staging -> prefix。
     *
     * 调用方必须确保调用前 staging 和 prefix 目录都已清空，且 zip 已完成 SHA-256 校验。
     * 任何异常（解压失败、symlink 格式错误、rename 失败）都会抛出 RuntimeException。
     *
     * 本方法为 OOBE bootstrap 和「重置运行环境」功能共享。
     */
    static void extractBootstrapZip(byte[] zipBytes) {
        Logger.logInfo(LOG_TAG, "Bootstrap zip (" + zipBytes.length + " bytes), extracting to prefix staging directory "" + TERMUX_STAGING_PREFIX_DIR_PATH + "".");

        final byte[] buffer = new byte[8096];
        final List<Pair<String, String>> symlinks = new ArrayList<>(50);

        Error error;
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("←");
                        if (parts.length != 2)
                            throw new RuntimeException("Malformed symlink line: " + line);
                        String oldPath = parts[0];
                        String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                        symlinks.add(Pair.create(oldPath, newPath));

                        error = ensureDirectoryExists(new File(newPath).getParentFile());
                        if (error != null)
                            throw new RuntimeException(Error.getErrorMarkdownString(error));
                    }
                } else {
                    String zipEntryName = zipEntry.getName();
                    File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                    boolean isDirectory = zipEntry.isDirectory();

                    error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                    if (error != null)
                        throw new RuntimeException(Error.getErrorMarkdownString(error));

                    if (!isDirectory) {
                        try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            int readBytes;
                            while ((readBytes = zipInput.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        boolean isExecutable = zipEntryName.startsWith("bin/")
                            || zipEntryName.startsWith("usr/bin/")
                            || zipEntryName.startsWith("usr/sbin/")
                            || zipEntryName.startsWith("usr/libexec/")
                            || zipEntryName.startsWith("libexec/")
                            || zipEntryName.startsWith("lib/apt/apt-helper")
                            || zipEntryName.startsWith("lib/apt/methods");
                        if (isExecutable)
                            Os.chmod(targetFile.getAbsolutePath(), 0755);
                    }
                }
            }
        } catch (Throwable t) {
            // 解压阶段可能 OOM / IOException，统一包装成 RuntimeException 向上抛
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;
            throw new RuntimeException(t);
        }

        if (symlinks.isEmpty())
            throw new RuntimeException("No SYMLINKS.txt encountered");
        for (Pair<String, String> symlink : symlinks)
            Os.symlink(symlink.first, symlink.second);

        Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");
        if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR))
            throw new RuntimeException("Moving termux prefix staging to prefix directory failed");

        Logger.logInfo(LOG_TAG, "Bootstrap packages extracted successfully.");
    }

}