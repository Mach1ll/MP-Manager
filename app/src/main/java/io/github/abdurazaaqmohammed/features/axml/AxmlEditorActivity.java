package io.github.abdurazaaqmohammed.features.axml;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.apk.axml.ResourceTableParser;
import com.apk.axml.aXMLDecoder;
import com.apk.axml.aXMLEncoder;
import com.apk.axml.serializableItems.ResEntry;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.core.ui.base.BaseActivity;
import io.github.abdurazaaqmohammed.utils.ErrorUtil;
import io.github.abdurazaaqmohammed.utils.FileUtils;
import io.github.abdurazaaqmohammed.utils.RootStaging;
import io.github.codehasan.colorpicker.extensions.Extensions;

/**
 * Tree editor for binary (compiled) Android XML files, e.g. {@code AndroidManifest.xml}
 * or any {@code res/*.xml} inside an APK.
 *
 * <p>Flow: the file is decoded with {@code aXMLDecoder} (resolving resource references
 * through the APK's {@code resources.arsc} when available), parsed into an
 * {@link AxmlNode} tree, edited structurally (attributes, text, add/remove nodes),
 * serialized back to text and re-encoded with {@code aXMLEncoder}.
 *
 * <p>When launched with the {@code zf} + {@code zipEntryPath} extras (file staged out
 * of an archive), saving returns {@code setResult(757, ...)} with the same contract as
 * {@code TextEditorActivity}, so {@code MainActivity} offers to write the entry back
 * into the archive (with optional auto-sign for APKs).
 */
public class AxmlEditorActivity extends BaseActivity implements AxmlTreeAdapter.Listener {

    private File file;
    private String zipPath;       // null when editing a standalone file
    private String zipEntryPath;  // null when editing a standalone file
    private String rootOriginalPath; // non-null when the file was staged via root
    private List<ResEntry> resEntries;

    private AxmlNode root;
    private Map<String, String> namespaces;
    private boolean dirty;

    private AxmlTreeAdapter adapter;
    private ProgressBar progress;
    private TextView titleView;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(8);
        header.setPadding(pad, pad, pad, pad);

        titleView = new TextView(this);
        titleView.setTextSize(16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        titleView.setLayoutParams(titleLp);
        header.addView(titleView);

        Button saveBtn = new Button(this);
        saveBtn.setText(R.string.axml_save);
        saveBtn.setOnClickListener(v -> saveAndFinish());
        header.addView(saveBtn);
        content.addView(header);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressLp.gravity = android.view.Gravity.CENTER;
        progress.setLayoutParams(progressLp);
        content.addView(progress);

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new AxmlTreeAdapter(this, this);
        list.setAdapter(adapter);
        content.addView(list);

        setContentView(content);
        load();
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        new Thread(() -> {
            try {
                Intent intent = getIntent();
                zipPath = intent.getStringExtra("zf");
                zipEntryPath = intent.getStringExtra("zipEntryPath");
                rootOriginalPath = intent.getStringExtra("rootOriginalPath");
                String path = intent.getStringExtra("path");
                String fileName = intent.getStringExtra("fileName");
                if (path == null) throw new IllegalArgumentException("missing path extra");
                file = new File(path);

                resEntries = loadResEntries(zipPath);

                String xml;
                try (InputStream is = FileUtils.getInputStream(file)) {
                    xml = new aXMLDecoder(is, resEntries).decodeAsString();
                }
                AxmlTreeParser.Result r = AxmlTreeParser.parse(xml);
                root = r.root;
                namespaces = r.namespaces;
                String title = fileName != null ? fileName : file.getName();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    titleView.setText(title);
                    adapter.setRoot(root);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    new ErrorUtil(this).showError(e);
                    finish();
                });
            }
        }).start();
    }

    /**
     * Parses {@code resources.arsc} from the containing APK so symbolic references
     * (e.g. {@code @string/app_name}) survive the decode/encode round-trip.
     */
    private List<ResEntry> loadResEntries(String zipPath) {
        if (zipPath == null || !zipPath.endsWith(".apk")) return null;
        try (ZipFile zf = new ZipFile(zipPath)) {
            FileHeader h = zf.getFileHeader("resources.arsc");
            if (h == null) return null;
            try (InputStream is = zf.getInputStream(h)) {
                return new ResourceTableParser(is).parse();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ saving

    private void saveAndFinish() {
        if (root == null) {
            finish();
            return;
        }
        final byte[] bytes;
        try {
            String xml = AxmlTreeSerializer.serialize(root, namespaces);
            bytes = new aXMLEncoder().encodeString(xml, this, resEntries);
        } catch (Exception e) {
            new ErrorUtil(this).showError(e);
            return;
        }
        if (rootOriginalPath != null) {
            // Root-staged file: confirm before touching key system paths, then
            // write the staged copy locally and push bytes back via su.
            Runnable doSave = () -> {
                try (OutputStream os = FileUtils.getOutputStream(file)) {
                    os.write(bytes);
                } catch (Exception e) {
                    new ErrorUtil(this).showError(e);
                    return;
                }
                try {
                    RootStaging.writeBack(this, file, rootOriginalPath);
                } catch (Exception e) {
                    new ErrorUtil(this).showError(e);
                    return;
                }
                dirty = false;
                Extensions.showMessage(this, R.string.axml_saved);
                finish();
            };
            if (RootStaging.needsWriteConfirm(rootOriginalPath)) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.editor_write_system))
                        .setMessage(getString(R.string.editor_write_system_msg, rootOriginalPath))
                        .setPositiveButton(android.R.string.ok, (d, w) -> doSave.run())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                doSave.run();
            }
            return;
        }
        try (OutputStream os = FileUtils.getOutputStream(file)) {
            os.write(bytes);
        } catch (Exception e) {
            new ErrorUtil(this).showError(e);
            return;
        }
        dirty = false;
        if (zipPath != null && zipEntryPath != null) {
            // Same result contract as TextEditorActivity: MainActivity offers to
            // write the entry back into the archive (with auto-sign for APKs).
            Intent result = new Intent();
            result.setData(Uri.fromFile(file));
            result.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            result.putExtra("zipFilePath", zipPath);
            result.putExtra("zipEntryPath", zipEntryPath);
            setResult(757, result);
        } else {
            Extensions.showMessage(this, R.string.axml_saved);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (dirty && root != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.changes_made)
                    .setMessage(getString(R.string.confirm_save, file != null ? file.getName() : ""))
                    .setPositiveButton(R.string.save_and_exit, (d, w) -> saveAndFinish())
                    .setNegativeButton(R.string.dont_save, (d, w) -> finish())
                    .setNeutralButton(android.R.string.cancel, null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ tree UI

    @Override
    public void onToggle(AxmlNode node) {
        node.setExpanded(!node.isExpanded());
        adapter.refresh(root);
    }

    @Override
    public void onEdit(AxmlNode node) {
        showNodeDialog(node);
    }

    private void markDirty() {
        dirty = true;
    }

    private TextView sectionLabel(int resId) {
        TextView tv = new TextView(this);
        tv.setText(resId);
        tv.setTextSize(13);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private void showNodeDialog(AxmlNode node) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, pad / 2, pad, pad / 2);

        body.addView(sectionLabel(R.string.axml_tag_name));
        EditText tagInput = new EditText(this);
        tagInput.setText(node.qualifiedName());
        tagInput.setSingleLine(true);
        // Live edit (same as the manifest tree editor): every keystroke applies.
        tagInput.addTextChangedListener(watcher(s -> {
            String q = s.trim();
            int c = q.indexOf(':');
            if (c < 0) {
                node.setPrefix("");
                node.setTag(q);
            } else {
                node.setPrefix(q.substring(0, c));
                node.setTag(q.substring(c + 1));
                ensureNamespace(node.getPrefix());
            }
            markDirty();
            adapter.refresh(root);
        }));
        body.addView(tagInput);

        body.addView(sectionLabel(R.string.axml_attributes));
        LinearLayout attrContainer = new LinearLayout(this);
        attrContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(attrContainer);
        for (AxmlNode.Attr a : node.getAttributes()) {
            attrContainer.addView(attrRow(a, attrContainer, node));
        }
        Button addAttr = new Button(this);
        addAttr.setText(R.string.axml_add_attribute);
        addAttr.setOnClickListener(v -> {
            AxmlNode.Attr a = new AxmlNode.Attr("", "", "");
            node.getAttributes().add(a);
            attrContainer.addView(attrRow(a, attrContainer, node));
            markDirty();
        });
        body.addView(addAttr);

        body.addView(sectionLabel(R.string.axml_text_content));
        EditText textInput = new EditText(this);
        textInput.setText(node.getText());
        textInput.setMinLines(2);
        textInput.addTextChangedListener(watcher(s -> {
            node.setText(s.trim());
            markDirty();
        }));
        body.addView(textInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button addChild = new Button(this);
        addChild.setText(R.string.axml_add_child);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addChild.setLayoutParams(btnLp);
        Button delete = new Button(this);
        delete.setText(R.string.axml_delete_node);
        delete.setLayoutParams(btnLp);
        actions.addView(addChild);
        actions.addView(delete);
        body.addView(actions);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.axml_edit_node, node.qualifiedName()))
                .setView(scroll)
                .setPositiveButton(R.string.done, null)
                .create();

        addChild.setOnClickListener(v -> {
            AxmlNode child = new AxmlNode("", "element");
            node.addChild(child);
            node.setExpanded(true);
            markDirty();
            adapter.refresh(root);
            dialog.dismiss();
            showNodeDialog(child);
        });
        delete.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDelete(node);
        });
        dialog.show();
    }

    private View attrRow(AxmlNode.Attr attr, ViewGroup container, AxmlNode node) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        EditText name = new EditText(this);
        name.setText(attr.qualifiedName());
        name.setHint(R.string.axml_attr_name);
        name.setSingleLine(true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText value = new EditText(this);
        value.setText(attr.value);
        value.setHint(R.string.axml_attr_value);
        value.setSingleLine(true);
        value.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        ImageButton del = new ImageButton(this);
        del.setImageResource(android.R.drawable.ic_menu_delete);
        del.setBackground(null);

        row.addView(name);
        row.addView(value);
        row.addView(del);

        name.addTextChangedListener(watcher(s -> {
            String q = s.trim();
            int c = q.indexOf(':');
            if (c < 0) {
                attr.prefix = "";
                attr.name = q;
            } else {
                attr.prefix = q.substring(0, c);
                attr.name = q.substring(c + 1);
                ensureNamespace(attr.prefix);
            }
            markDirty();
        }));
        value.addTextChangedListener(watcher(s -> {
            attr.value = s;
            markDirty();
        }));
        del.setOnClickListener(v -> {
            node.getAttributes().remove(attr);
            container.removeView(row);
            markDirty();
        });
        return row;
    }

    /**
     * A newly introduced prefix has no declaration yet; fall back to the same URI
     * {@code aXMLDecoder} uses for unknown prefixes so the re-encoded file stays valid.
     */
    private void ensureNamespace(String prefix) {
        if (prefix != null && !prefix.isEmpty() && namespaces != null
                && !namespaces.containsKey(prefix)) {
            namespaces.put(prefix, "http://schemas.android.com/apk/res-auto");
        }
    }

    private void confirmDelete(AxmlNode node) {
        AxmlNode parent = node.getParent();
        if (parent == null) {
            Extensions.showMessage(this, R.string.axml_cannot_delete_root);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.axml_confirm_delete)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    parent.removeChild(node);
                    markDirty();
                    adapter.refresh(root);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static TextWatcher watcher(Consumer<String> onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { onChange.accept(s.toString()); }
        };
    }
}
