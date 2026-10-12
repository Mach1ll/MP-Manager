package io.github.abdurazaaqmohammed.features.script;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.abdurazaaqmohammed.MPManager.R;

/**
 * Runs a JavaScript file with the {@code mp} automation API and shows the
 * console output. The script can be edited in place before running.
 */
public class ScriptRunnerActivity extends AppCompatActivity {

    public static final String EXTRA_SCRIPT_PATH = "script_path";

    private EditText sourceView;
    private TextView consoleView;
    private ScrollView consoleScroll;
    private Button runButton;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_runner);

        sourceView = findViewById(R.id.script_source);
        consoleView = findViewById(R.id.script_console);
        consoleScroll = (ScrollView) consoleView.getParent();
        runButton = findViewById(R.id.script_run_button);

        String path = getIntent().getStringExtra(EXTRA_SCRIPT_PATH);
        if (path != null) {
            setTitle(new File(path).getName());
            try {
                sourceView.setText(new String(Files.readAllBytes(new File(path).toPath()),
                        StandardCharsets.UTF_8));
            } catch (Exception e) {
                appendConsole("Cannot read script: " + e.getMessage());
            }
        } else {
            setTitle(R.string.run_script);
        }

        runButton.setOnClickListener(v -> runScript(path));
        findViewById(R.id.script_clear_button).setOnClickListener(v -> consoleView.setText(""));
    }

    private void runScript(String name) {
        if (running) return;
        running = true;
        runButton.setEnabled(false);
        String script = sourceView.getText().toString();
        appendConsole("$ " + (name == null ? "script" : new File(name).getName()));

        MpApi api = new MpApi(this::appendConsole,
                msg -> runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()));

        MpScriptEngine.eval(script, name == null ? "script.js" : name, api,
                new MpScriptEngine.Listener() {
                    @Override
                    public void onPrint(String text) {
                        appendConsole(text);
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            appendConsole("Error: " + error);
                            finishRun();
                        });
                    }

                    @Override
                    public void onDone(String result) {
                        runOnUiThread(() -> {
                            if (result != null) appendConsole("=> " + result);
                            appendConsole("[done]");
                            finishRun();
                        });
                    }
                });
    }

    private void finishRun() {
        running = false;
        runButton.setEnabled(true);
    }

    private void appendConsole(String text) {
        runOnUiThread(() -> {
            consoleView.append(text);
            consoleView.append("\n");
            consoleScroll.post(() -> consoleScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
