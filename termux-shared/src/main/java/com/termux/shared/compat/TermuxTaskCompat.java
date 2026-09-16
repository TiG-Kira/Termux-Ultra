package com.termux.shared.compat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compatibility wrapper for the upstream {@code TermuxTask} that was renamed/refactored into
 * {@link AppShell} in v0.119.0-beta.3. TU call sites still reference the legacy static API
 * {@code TermuxTask.execute(context, executionCommand, taskClient, shellEnvironmentClient, synchronous)}
 * as well as the {@code TermuxTask.TermuxTaskClient} callback interface.
 *
 * <p>This class mirrors the old public surface but delegates to {@link AppShell} under the hood.
 * TU call sites should import this class instead of the removed one.</p>
 *
 * <h3>Legacy API ↔ New API mapping</h3>
 * <pre>
 * TermuxTask.execute(...)                     → AppShell.execute(..., null, synchronous)
 * TermuxTask.TermuxTaskClient.onTermuxTaskExited(t) → AppShell.AppShellClient.onAppShellExited(s)
 * TermuxTask.getProcess()                    → AppShell.getProcess()
 * TermuxTask.getExecutionCommand()           → AppShell.getExecutionCommand()
 * TermuxTask.killIfExecuting(ctx, process)   → AppShell.killIfExecuting(ctx, process)
 * TermuxTask.kill()                          → AppShell.kill()
 * </pre>
 */
public final class TermuxTaskCompat {

    private static final String LOG_TAG = "TermuxTaskCompat";

    /** Holds the delegate {@link AppShell} created by {@link #execute}. */
    @NonNull
    private final AppShell mDelegate;

    /** Bridge that forwards {@link AppShell} exit events to a legacy {@link TermuxTaskClient}. */
    private final LegacyTaskClientBridge mBridge;

    private TermuxTaskCompat(@NonNull AppShell delegate, @NonNull LegacyTaskClientBridge bridge) {
        this.mDelegate = delegate;
        this.mBridge = bridge;
    }

    // ---------------------------------------------------------------------------
    // Legacy static entry point
    // ---------------------------------------------------------------------------

    /**
     * Execute a background shell command using the upstream {@link AppShell}.
     *
     * @param context          The package {@link Context}.
     * @param executionCommand The {@link ExecutionCommand} containing the command to run.
     * @param taskClient       Legacy callback; may be {@code null}.
     * @param shellEnvironment Optional legacy-style environment client.  If {@code null}, a
     *                         {@link TermuxShellCommandShellEnvironment} is used.
     * @param isSynchronous    Whether to run on the caller thread ({@code true}) or spawn a new one.
     * @return A {@link TermuxTaskCompat} wrapper, or {@code null} if the command could not start.
     */
    @Nullable
    public static TermuxTaskCompat execute(@NonNull final Context context,
                                           @NonNull ExecutionCommand executionCommand,
                                           @Nullable final TermuxTaskClient taskClient,
                                           @Nullable ShellEnvironmentCompat shellEnvironment,
                                           final boolean isSynchronous) {

        IShellEnvironment env = shellEnvironment != null
            ? shellEnvironment.getDelegate()
            : new AndroidShellEnvironment();

        LegacyTaskClientBridge bridge = new LegacyTaskClientBridge(taskClient);

        AppShell appShell = AppShell.execute(
            context,
            executionCommand,
            bridge,
            env,
            null,    // no additional environment override for legacy callers
            isSynchronous);

        if (appShell == null) {
            Logger.logError(LOG_TAG, "AppShell.execute returned null for: " + executionCommand.getCommandIdAndLabelLogString());
            return null;
        }

        return new TermuxTaskCompat(appShell, bridge);
    }

    // ---------------------------------------------------------------------------
    // Instance methods mirroring the legacy TermuxTask class
    // ---------------------------------------------------------------------------

    @Nullable
    public Process getProcess() {
        return mDelegate.getProcess();
    }

    @NonNull
    public ExecutionCommand getExecutionCommand() {
        return mDelegate.getExecutionCommand();
    }

    public void killIfExecuting(@NonNull Context context, boolean processResult) {
        mDelegate.killIfExecuting(context, processResult);
    }

    public void kill() {
        mDelegate.kill();
    }

    /** Returns the upstream {@link AppShell} delegate. TU callers that need the real type can use this. */
    @NonNull
    public AppShell getDelegate() {
        return mDelegate;
    }

    // ---------------------------------------------------------------------------
    // Callback bridge
    // ---------------------------------------------------------------------------

    /**
     * Adapts {@link AppShell.AppShellClient} (new) to the legacy
     * {@link TermuxTaskCompat.TermuxTaskClient} interface.  The upstream callback passes an
     * {@link AppShell} — we wrap it in a {@link TermuxTaskCompat} before handing it back so that
     * callers can keep using their legacy API without change.
     */
    private static final class LegacyTaskClientBridge implements AppShell.AppShellClient {

        private final TermuxTaskClient mLegacy;

        LegacyTaskClientBridge(@Nullable TermuxTaskClient legacy) {
            this.mLegacy = legacy;
        }

        @Override
        public void onAppShellExited(@NonNull AppShell appShell) {
            if (mLegacy != null) {
                // Callers need a TermuxTaskCompat. Since we are not its instance, we construct
                // one that wraps the same AppShell so callers can keep calling getProcess() etc.
                mLegacy.onTermuxTaskExited(new TermuxTaskCompat(appShell, this));
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Legacy interface
    // ---------------------------------------------------------------------------

    /**
     * Legacy callback interface matching the old {@code TermuxTask.TermuxTaskClient}.
     * TU code implements this; the compat adapter bridges it to the upstream
     * {@link AppShell.AppShellClient}.
     */
    public interface TermuxTaskClient {
        /** Called when a background {@code TermuxTask} finishes. */
        void onTermuxTaskExited(@NonNull TermuxTaskCompat termuxTask);
    }
}
