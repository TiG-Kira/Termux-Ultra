package com.termux.shared.crash;

/**
 * Backward-compat alias for {@link com.termux.shared.termux.crash.TermuxCrashUtils}.
 *
 * <p>TU's custom add-ons reference the old package layout from Termux v0.118.x.
 * Upstream v0.119.0 restructured termux-shared; this subclass keeps old imports working
 * so add-on source files do not need to be touched on every core bump.</p>
 *
 * @deprecated Use the class at {@code com.termux.shared.termux.crash.TermuxCrashUtils} instead.
 */
@Deprecated
public class TermuxCrashUtils extends com.termux.shared.termux.crash.TermuxCrashUtils {
    /**
     * Old-name {@link com.termux.shared.termux.crash.TermuxCrashUtils.TYPE} enum, kept so callers
     * can write {@code TermuxCrashUtils.TYPE} without qualification.
     */
    public enum TYPE {
        UNCAUGHT_EXCEPTION,
        CAUGHT_EXCEPTION;

        com.termux.shared.termux.crash.TermuxCrashUtils.TYPE toNew() {
            return com.termux.shared.termux.crash.TermuxCrashUtils.TYPE.valueOf(name());
        }
    }

    public TermuxCrashUtils() {
        super(com.termux.shared.termux.crash.TermuxCrashUtils.TYPE.UNCAUGHT_EXCEPTION);
    }

    public TermuxCrashUtils(TYPE type) {
        super(type.toNew());
    }
}
