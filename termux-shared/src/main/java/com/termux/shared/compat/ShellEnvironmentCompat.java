package com.termux.shared.compat;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;

import java.util.HashMap;

/**
 * Compatibility adapter wrapping a {@link IShellEnvironment} and exposing the legacy
 * {@code ShellEnvironmentClient} contract (buildEnvironment + setupProcessArgs).
 *
 * <p>Used by TU code that still calls the old {@code TermuxTask.execute} /
 * {@code TermuxSession.execute} signatures. Termux 0.119 changed the environment contract from
 * a 2-method interface ({@code buildEnvironment}, {@code setupProcessArgs}) to a
 * {@link IShellEnvironment} with different method names and a new {@link ExecutionCommand}
 * parameter. This adapter bridges the two.</p>
 */
public final class ShellEnvironmentCompat {

    private final IShellEnvironment mDelegate;

    public ShellEnvironmentCompat(@NonNull IShellEnvironment delegate) {
        mDelegate = delegate;
    }

    @NonNull
    public IShellEnvironment getDelegate() {
        return mDelegate;
    }

    @NonNull
    public String getDefaultWorkingDirectoryPath() {
        return mDelegate.getDefaultWorkingDirectoryPath();
    }

    @NonNull
    public String getDefaultBinPath() {
        return mDelegate.getDefaultBinPath();
    }

    /**
     * Legacy {@code ShellEnvironmentClient.buildEnvironment(Context, boolean, String)} ->
     * new {@link IShellEnvironment#setupShellCommandEnvironment(Context, ExecutionCommand)}.
     *
     * <p>The new API requires an {@link ExecutionCommand}; we build a transient one from the
     * given working directory since callers never set more than that.</p>
     */
    @NonNull
    public String[] buildEnvironment(@NonNull Context context, boolean isFailSafe, @NonNull String workingDirectory) {
        ExecutionCommand ec = new ExecutionCommand();
        ec.workingDirectory = workingDirectory;
        ec.isFailsafe = isFailSafe;
        HashMap<String, String> envMap = mDelegate.setupShellCommandEnvironment(context, ec);
        java.util.List<String> envList = com.termux.shared.shell.command.environment.ShellEnvironmentUtils.convertEnvironmentToEnviron(envMap);
        java.util.Collections.sort(envList);
        return envList.toArray(new String[0]);
    }

    /**
     * Legacy {@code ShellEnvironmentClient.setupProcessArgs(String, String[])} ->
     * new {@link IShellEnvironment#setupShellCommandArguments(String, String[])}.
     */
    @NonNull
    public String[] setupProcessArgs(@NonNull String fileToExecute, String[] arguments) {
        return mDelegate.setupShellCommandArguments(fileToExecute, arguments);
    }
}
