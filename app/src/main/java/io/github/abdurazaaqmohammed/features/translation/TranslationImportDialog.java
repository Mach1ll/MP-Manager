package io.github.abdurazaaqmohammed.features.translation;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.ui.dialogs.FilePickerDialog;
import io.github.abdurazaaqmohammed.utils.DialogUtil;

/**
 * Dialog for importing a translated strings file (.json / .mtd, as produced by
 * {@link TranslationExporter}) back into resources.arsc as a new locale.
 */
public class TranslationImportDialog {

    /** Called when the user confirms the import. */
    public interface ImportCallback {
        void onImport(File translationFile, String locale);
    }

    private final AppCompatActivity activity;
    private final DialogUtil dialogUtil;
    private File pickedFile;

    public TranslationImportDialog(AppCompatActivity activity) {
        this.activity = activity;
        this.dialogUtil = new DialogUtil(activity);
    }

    public void show(ImportCallback callback) {
        pickedFile = null;
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_translation_import, null);
        TextInputEditText pathInput = view.findViewById(R.id.translation_import_path);
        TextInputEditText localeInput = view.findViewById(R.id.translation_import_locale);
        Button browseButton = view.findViewById(R.id.translation_import_browse_button);

        View.OnClickListener pick = v -> pickFile(pathInput);
        pathInput.setOnClickListener(pick);
        browseButton.setOnClickListener(pick);

        dialogUtil.styleAlertDialog(new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_translation)
                .setView(view)
                .setPositiveButton(R.string.imports, (dialog, which) -> {
                    if (pickedFile == null || !pickedFile.isFile()) {
                        Toast.makeText(activity, R.string.invalid_file_path,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String locale = localeInput.getText() == null ? ""
                            : localeInput.getText().toString().trim();
                    if (TranslationImporter.normalizeLocale(locale) == null) {
                        Toast.makeText(activity, R.string.invalid_locale,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onImport(pickedFile, locale);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void pickFile(TextInputEditText pathInput) {
        FilePickerDialog.Properties props = new FilePickerDialog.Properties();
        props.selection_mode = FilePickerDialog.SINGLE_MODE;
        props.selection_type = FilePickerDialog.FILE_SELECT;
        props.extensions = new String[]{".json", ".mtd"};
        FilePickerDialog picker = new FilePickerDialog(activity, props);
        picker.setTitle(activity.getString(R.string.select_translation_file));
        picker.setDialogSelectionListener(files -> {
            if (files == null || files.length == 0 || files[0] == null) return;
            pickedFile = new File(files[0]);
            pathInput.setText(pickedFile.getAbsolutePath());
        });
        picker.show();
    }
}
