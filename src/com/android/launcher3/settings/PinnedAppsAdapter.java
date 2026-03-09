package com.android.launcher3.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.util.List;
import java.util.Set;

public class PinnedAppsAdapter extends RecyclerView.Adapter<PinnedAppsAdapter.ViewHolder> {
    private final List<AppEntry> mApps;
    private final Set<String> mPinnedApps;
    private final PackageManager mPackageManager;
    private final Runnable mChangeListener;

    public static class AppEntry {
        public String componentName;
        public String label;
        public Drawable icon;
    }

    public PinnedAppsAdapter(Context context, List<AppEntry> apps, Set<String> pinnedApps, Runnable changeListener) {
        mApps = apps;
        mPinnedApps = pinnedApps;
        mPackageManager = context.getPackageManager();
        mChangeListener = changeListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.pinned_app_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppEntry app = mApps.get(position);
        holder.title.setText(app.label);
        holder.icon.setImageDrawable(app.icon);
        holder.checkBox.setChecked(mPinnedApps.contains(app.componentName));

        holder.itemView.setOnClickListener(v -> {
            boolean isChecked = !holder.checkBox.isChecked();
            holder.checkBox.setChecked(isChecked);
            if (isChecked) {
                mPinnedApps.add(app.componentName);
            } else {
                mPinnedApps.remove(app.componentName);
            }
            mChangeListener.run();
        });
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title;
        CheckBox checkBox;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            title = itemView.findViewById(R.id.app_title);
            checkBox = itemView.findViewById(R.id.app_checkbox);
        }
    }
}
