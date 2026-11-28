/*
 * Personal use only. See the README for details.
 */

package app.big1mosha.selfhostscrobble.extension;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Settings screen, opened from the entry this patch adds to YouTube Music's settings.
 *
 * <p>Edits the same {@code scrobble.conf} file the extension reads, so there is only one
 * source of truth.
 */
public final class SettingsActivity extends Activity {
    private EditText serverField;
    private EditText tokenField;
    private CheckBox nowPlayingBox;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Self-hosted scrobbling");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        root.setPadding(padding, padding, padding, padding);

        root.addView(label("Server URL"));
        serverField = field();
        serverField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverField.setHint("http://192.168.1.5:4110/apis/listenbrainz");
        root.addView(serverField);

        root.addView(label("API token"));
        tokenField = field();
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenField.setHint("Koito or Maloja API key");
        root.addView(tokenField);

        nowPlayingBox = new CheckBox(this);
        nowPlayingBox.setText("Send now playing");
        nowPlayingBox.setChecked(Config.nowPlaying());
        root.addView(nowPlayingBox);

        Button testButton = new Button(this);
        testButton.setText("Test connection");
        testButton.setOnClickListener(view -> testConnection());
        root.addView(testButton);

        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setOnClickListener(view -> save());
        root.addView(saveButton);

        statusView = label("");
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        serverField.setText(Config.serverUrl());
        tokenField.setText(Config.token());
    }

    private void testConnection() {
        String server = serverField.getText().toString().trim();
        String token = tokenField.getText().toString().trim();
        if (server.isEmpty() || token.isEmpty()) {
            status("Server and token are required");
            return;
        }

        status("Checking...");
        ListenBrainzClient.validateToken(server, token,
                (ok, message) -> runOnUiThread(() -> status(message)));
    }

    private void save() {
        String server = serverField.getText().toString().trim();
        String token = tokenField.getText().toString().trim();
        if (server.isEmpty() || token.isEmpty()) {
            status("Server and token are required");
            return;
        }

        File file = Config.configFile();
        if (file == null) {
            status("Could not find the app storage directory");
            return;
        }

        Map<String, String> extra = readExtra(file);
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("# Self-hosted scrobbling. Written by the in-app settings screen.\n");
            writer.write("server=" + server + "\n");
            writer.write("token=" + token + "\n");
            writer.write("now_playing=" + (nowPlayingBox.isChecked() ? "true" : "false") + "\n");
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
        } catch (Throwable t) {
            status("Could not save: " + t);
            return;
        }

        Config.reload();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Keeps any key the settings screen does not know about. */
    private Map<String, String> readExtra(File file) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (!file.isFile()) {
            return extra;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                int separator = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || separator <= 0) {
                    continue;
                }

                String key = line.substring(0, separator).trim();
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("server") || lower.equals("token") || lower.equals("now_playing")) {
                    continue;
                }
                extra.put(key, line.substring(separator + 1).trim());
            }
        } catch (Throwable ignored) {
            // A broken file is replaced by the values entered above.
        }

        return extra;
    }

    private EditText field() {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private void status(String message) {
        if (statusView != null) {
            statusView.setText(message);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
