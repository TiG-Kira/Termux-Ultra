package com.termux.app.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import coil.ImageLoader;
import coil.request.ImageRequest;
import coil.transform.CircleCropTransformation;

import com.termux.R;

/**
 * Preference that loads a circular network avatar into the icon slot.
 * Call {@link #setIconUrl(String)} from the host Fragment to supply the URL.
 * Falls back to the default icon when no URL is provided or loading fails.
 */
public class CircleIconPreference extends Preference {

    private String mIconUrl;

    public CircleIconPreference(@NonNull Context context) {
        super(context);
    }

    public CircleIconPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CircleIconPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CircleIconPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Set the network URL for the circular avatar. Pass {@code null} or
     * empty to fall back to the default icon.
     */
    public void setIconUrl(@Nullable String url) {
        if ((url == null && mIconUrl == null)
                || (url != null && url.equals(mIconUrl))) {
            return;
        }
        mIconUrl = url;
        notifyChanged();
    }

    @Nullable
    public String getIconUrl() {
        return mIconUrl;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        ImageView iconView = (ImageView) holder.findViewById(android.R.id.icon);
        if (iconView == null) return;

        iconView.setVisibility(View.VISIBLE);

        if (mIconUrl != null && !mIconUrl.isEmpty()) {
            ImageLoader loader = new ImageLoader.Builder(getContext()).build();
            ImageRequest request = new ImageRequest.Builder(getContext())
                    .data(mIconUrl)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .target(iconView)
                    .transformations(new CircleCropTransformation())
                    .build();
            loader.enqueue(request);
        }
    }
}
