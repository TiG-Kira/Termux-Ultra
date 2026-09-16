package com.termux.shared.models.errors;

import androidx.annotation.NonNull;

/**
 * Backward-compat alias for {@link com.termux.shared.errors.Errno}.
 *
 * <p>See {@link com.termux.shared.errors.Error} for the rational.</p>
 *
 * @deprecated Use the class at {@code com.termux.shared.errors.Errno} instead.
 */
@Deprecated
public class Errno extends com.termux.shared.errors.Errno {
    public Errno(@NonNull final String type, final int code, @NonNull final String message) {
        super(type, code, message);
    }
}
