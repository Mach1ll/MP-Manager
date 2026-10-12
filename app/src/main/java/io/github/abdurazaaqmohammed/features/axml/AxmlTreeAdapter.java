package io.github.abdurazaaqmohammed.features.axml;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter rendering the AXML node tree as an expandable flat list.
 * All views are built programmatically; no layout resources needed.
 */
public class AxmlTreeAdapter extends RecyclerView.Adapter<AxmlTreeAdapter.Holder> {

    public interface Listener {
        void onToggle(AxmlNode node);
        void onEdit(AxmlNode node);
    }

    private final Context context;
    private final Listener listener;
    private final List<AxmlNode> flat = new ArrayList<>();
    private final float density;

    public AxmlTreeAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    /** Sets a new root and rebuilds the visible list. */
    public void setRoot(AxmlNode root) {
        flat.clear();
        if (root != null) flatten(root);
        notifyDataSetChanged();
    }

    /** Rebuilds the visible list after structural edits (add/remove/rename). */
    public void refresh(AxmlNode root) {
        setRoot(root);
    }

    private void flatten(AxmlNode node) {
        flat.add(node);
        if (node.isExpanded()) {
            for (AxmlNode c : node.getChildren()) flatten(c);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (8 * density + 0.5f);
        row.setPadding(pad, pad, pad, pad);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Space indent = new Space(context);
        indent.setLayoutParams(new LinearLayout.LayoutParams(0, 1));

        TextView chevron = new TextView(context);
        chevron.setTextSize(16);
        chevron.setPadding(pad, 0, pad, 0);
        chevron.setMinWidth((int) (24 * density + 0.5f));

        TextView label = new TextView(context);
        label.setTypeface(Typeface.MONOSPACE);
        label.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lp);

        ImageButton edit = new ImageButton(context);
        edit.setImageResource(android.R.drawable.ic_menu_edit);
        edit.setBackground(null);

        row.addView(indent);
        row.addView(chevron);
        row.addView(label);
        row.addView(edit);
        return new Holder(row, indent, chevron, label, edit);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        AxmlNode node = flat.get(position);
        h.indent.getLayoutParams().width = (int) (node.depth() * 20 * density + 0.5f);
        h.indent.requestLayout();
        boolean hasKids = !node.getChildren().isEmpty();
        h.chevron.setText(!hasKids ? "" : (node.isExpanded() ? "\u25BE" : "\u25B8"));
        h.label.setText(node.summary());
        h.itemView.setOnClickListener(v -> {
            if (hasKids) listener.onToggle(node);
            else listener.onEdit(node);
        });
        h.edit.setOnClickListener(v -> listener.onEdit(node));
    }

    @Override
    public int getItemCount() {
        return flat.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final Space indent;
        final TextView chevron;
        final TextView label;
        final ImageButton edit;

        Holder(View itemView, Space indent, TextView chevron, TextView label, ImageButton edit) {
            super(itemView);
            this.indent = indent;
            this.chevron = chevron;
            this.label = label;
            this.edit = edit;
        }
    }
}
