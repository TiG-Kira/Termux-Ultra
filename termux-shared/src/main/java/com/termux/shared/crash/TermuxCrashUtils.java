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
}
