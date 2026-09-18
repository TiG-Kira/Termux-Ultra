package com.termux.shared.models.errors;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Backward-compat alias for {@link com.termux.shared.errors.Error}.
 *
 * <p>TU's custom add-ons reference the old package layout from Termux v0.118.x.
 * Upstream v0.119.0 moved Error/Errno/FunctionErrno from
 * {@code com.termux.shared.models.errors} to {@code com.termux.shared.errors};
 * this subclass keeps old imports working without touching add-on source.
 *
 * <p>All constructors and static fields are inherited from the upstream class; this
 * alias only adds forwarding constructors so callers can {@code new Error(...)} with
 * the old package path. The canonical implementation lives at the new upstream path.</p>
 *
 * @deprecated Use the class at {@code com.termux.shared.errors.Error} for new TU code.
 */
@Deprecated
public class Error extends com.termux.shared.errors.Error {
    public Error() {
        super();
    }

    public Error(@NonNull final String type, final Integer code, @NonNull final String message,
                 final List<Throwable> throwablesList) {
        super(type, code, message, throwablesList);
    }

    public Error(@NonNull final String type, final Integer code, @NonNull final String message,
                 final Throwable throwable) {
        super(type, code, message, throwable);
    }

    public Error(@NonNull final String type, final Integer code, @NonNull final String message) {
        super(type, code, message);
    }

    public Error(final Integer code, @NonNull final String message,
                 final List<Throwable> throwablesList) {
        super(code, message, throwablesList);
    }

    public Error(final Integer code, @NonNull final String message, final Throwable throwable) {
        super(code, message, throwable);
    }

    public Error(final Integer code, @NonNull final String message) {
        super(code, message);
    }

    public Error(@NonNull final String message, final Throwable throwable) {
        super(message, throwable);
    }

    public Error(@NonNull final String message, final List<Throwable> throwablesList) {
        super(message, throwablesList);
    }

    public Error(@NonNull final String message) {
        super(message);
    }
}
