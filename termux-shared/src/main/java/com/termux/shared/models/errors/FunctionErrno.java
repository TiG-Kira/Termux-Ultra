package com.termux.shared.models.errors;

import androidx.annotation.NonNull;

/**
 * Backward-compat alias for {@link com.termux.shared.errors.FunctionErrno}.
 *
 * <p>See {@link com.termux.shared.errors.Error} for the rational.</p>
 *
 * @deprecated Use the class at {@code com.termux.shared.errors.FunctionErrno} instead.
 */
@Deprecated
public class FunctionErrno extends com.termux.shared.errors.FunctionErrno {
    public FunctionErrno(@NonNull final String type, final int code, @NonNull final String message) {
        super(type, code, message);
    }
}
