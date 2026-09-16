package com.termux.shared.compat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.termux.shell.command.environment.TermuxShellCommandShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * Compatibility wrapper for the upstream {@code TermuxSession.execute} signature change.
 *
 * <p>In v0.118.3 the static entry-point was:</p>
 * <pre>
 * TermuxSession.execute(Context, ExecutionCommand, TerminalSessionClient,
 *                        TermuxSessionClient, ShellEnvironmentClient,
 *                        String sessionName, boolean setStdoutOnExit)
 * </pre>
 *
 * <p>In v0.119.0-beta.3, upstream removed the {@code sessionName} parameter (now inferred from
 * the executable basename inside the new {@link ExecutionCommand#shellName}) and replaced
 * {@code ShellEnvironmentClient} with {@link IShellEnvironment}.</p>
 *
 * <p>This class preserves the legacy method signature that TU call sites use, then delegates
 * to the upstream {@link TermuxSession#execute}.</p>
 */
public final class TermuxSessionCompat {

    private static final String LOG_TAG = "TermuxSessionCompat";

    private TermuxSessionCompat() { /* static-only */ }

    /**
     * Legacy-style {@code TermuxSession.execute}.
     *
     * @param context               Package context.
     * @param executionCommand      Command descriptor.
     * @param terminalSessionClient Terminal session client.
     * @param sessionClient         Legacy session exit callback; may be {@code null}.
     * @param shellEnvironment      Optional {@link ShellEnvironmentCompat}.  Falls back to a
     *                              {@link TermuxShellCommandShellEnvironment}.
     * @param sessionName           Optional legacy session name.  Will be written into
     *                              {@link ExecutionCommand#shellName} / {@code commandLabel}.
     * @param setStdoutOnExit       Legacy flag, passed through to upstream unchanged.
     * @return The upstream {@link TermuxSession}, or {@code null} on failure.
     */
    @Nullable
    public static TermuxSession execute(@NonNull final Context context,
                                        @NonNull ExecutionCommand executionCommand,
                                        @NonNull final TerminalSessionClient terminalSessionClient,
                                        @Nullable final TermuxSession.TermuxSessionClient sessionClient,
                                        @Nullable ShellEnvironmentCompat shellEnvironment,
                                        @Nullable String sessionName,
                                        boolean setStdoutOnExit) {

        IShellEnvironment env = shellEnvironment != null
            ? shellEnvironment.getDelegate()
            : new TermuxShellCommandShellEnvironment();

        // Honor the legacy sessionName by writing it into the upstream ExecutionCommand fields.
        if (sessionName != null && !sessionName.isEmpty()) {
            executionCommand.shellName = sessionName;
            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = sessionName;
            }
        }

        Logger.logDebug(LOG_TAG, "Dispatching legacy TermuxSession.execute for \""
            + executionCommand.getCommandIdAndLabelLogString() + "\"");

        return TermuxSession.execute(
            context,
            executionCommand,
            terminalSessionClient,
            sessionClient,
            env,
            null,    // no additional environment from legacy callers
            setStdoutOnExit);
    }
}
