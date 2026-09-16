package com.gaurav.avnc.util;

import android.view.View;
import android.widget.TextView;
import androidx.databinding.BindingAdapter;

public class BindingAdapters {

    @BindingAdapter("isVisible")
    public static void setVisible(View view, boolean isVisible) {
        view.setVisibility(isVisible ? View.VISIBLE : View.GONE);
    }

    @BindingAdapter("backgroundAlpha")
    public static void setBackgroundAlpha(View view, double alpha) {
        if (view.getBackground() != null) {
            int alphaInt = Math.max(0, Math.min(255, (int) (alpha * 255)));
            view.getBackground().setAlpha(alphaInt);
        }
    }

    @BindingAdapter("isEnabled")
    public static void setEnabled(TextView view, boolean isEnabled) {
        view.setEnabled(isEnabled);
        view.setAlpha(isEnabled ? 1f : 0.38f);
    }
}
