package ke.ai254;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView captionText, captionStatus, gloveStatus, signText;
    private Switch swahili;
    private Button captionButton, speak;
    private Glove glove;
    private Signs signs;
    private Captions captions;
    private TextToSpeech speech;
    private boolean voiceReady;
    private boolean signing;
    private boolean connected;
    private boolean foreground;
    private boolean captionBusy;
    private String phrase;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(32, 28, 32, 32);
        scroll.addView(panel);
        setContentView(scroll);
        line(panel, "AI254", 30);
        line(panel, "Lecture captions and classroom signing", 18);
        buttons(panel, "Import Whisper model", 10);
        swahili = new Switch(this);
        swahili.setText("Captions in Swahili (off = English)");
        panel.addView(swahili);
        captionButton = button(panel, "Start captions", () -> {
            if (captionBusy) stopCaptions();
            else require(20, Manifest.permission.RECORD_AUDIO);
        });
        captionStatus = line(panel, "Captions stopped", 16);
        captionText = line(panel, "", 22);
        captionText.setTextIsSelectable(true);
        buttons(panel, "Import KSL model", 11);
        buttons(panel, "Import KSL labels", 12);
        button(panel, "Connect glove", () -> require(21, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT));
        button(panel, "Disconnect glove", () -> {
            if (connected || hasBluetooth()) glove.stop();
            updateGlove("Glove disconnected");
        });
        gloveStatus = line(panel, "Glove disconnected", 16);
        button(panel, "Read one sign", this::startSign);
        signText = line(panel, "", 22);
        speak = button(panel, "Speak recognised phrase", this::speak);
        speak.setEnabled(false);
        glove = new Glove(this, this::updateGlove, this::sample);
        speech = new TextToSpeech(this, code -> main.post(() -> initialiseVoice(code)));
    }

    private boolean hasBluetooth() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private TextView line(LinearLayout panel, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        panel.addView(view);
        return view;
    }

    private Button button(LinearLayout panel, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        panel.addView(button);
        return button;
    }

    private void buttons(LinearLayout panel, String label, int request) {
        button(panel, label, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, request);
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request < 10 || request > 12 || result != RESULT_OK || data == null || data.getData() == null) return;
        File destination = file(request == 10 ? "whisper.bin" : request == 11 ? "ksl.tflite" : "labels.txt");
        new Thread(() -> {
            try {
                File staging = File.createTempFile("model-", ".tmp", getFilesDir());
                try (InputStream input = getContentResolver().openInputStream(data.getData())) {
                    if (input == null) throw new IllegalStateException("Selected file cannot be opened");
                    Files.copy(input, staging.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally { staging.delete(); }
                runOnUiThread(() -> Toast.makeText(this, "Imported " + destination.getName(), Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "Import failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "AI254 model import").start();
    }

    private File file(String name) { return new File(getFilesDir(), name); }

    private void require(int action, String... permissions) {
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, action);
                return;
            }
        }
        perform(action);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (grants.length == 0) return;
        for (int grant : grants) {
            if (grant != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission is needed for this feature", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        perform(request);
    }

    private void perform(int action) {
        if (action == 20) startCaptions();
        if (action == 21) glove.start();
    }

    private void startCaptions() {
        if (captionBusy || !foreground) return;
        if (!file("whisper.bin").isFile()) {
            captionStatus.setText("Import a Whisper model first");
            return;
        }
        captionText.setText("");
        captionButton.setText("Stop captions");
        captionStatus.setText("Starting microphone and Whisper");
        boolean useSwahili = swahili.isChecked();
        final Captions session;
        try {
            session = new Captions();
        } catch (LinkageError error) {
            captionButton.setText("Start captions");
            captionStatus.setText("Native Whisper engine unavailable");
            return;
        }
        captionBusy = true;
        captions = session;
        captionStatus.setText("Listening");
        new Thread(() -> {
            String outcome = "Captions stopped";
            try {
                session.run(file("whisper.bin"), useSwahili, line -> main.post(() -> {
                    if (captions != session || line.isBlank()) return;
                    String text = captionText.getText() + "\n" + line;
                    captionText.setText(text.substring(Math.max(0, text.length() - 6000)));
                }));
            } catch (Exception | LinkageError error) {
                outcome = "Captions unavailable: " + error.getMessage();
            } finally {
                String message = outcome;
                main.post(() -> {
                    captions = null;
                    captionBusy = false;
                    captionButton.setText("Start captions");
                    captionStatus.setText(message);
                });
            }
        }, "AI254 captions").start();
    }

    private void stopCaptions() {
        if (captions != null) {
            captions.stop();
            captions = null;
        }
        captionText.setText("");
    }

    private void updateGlove(String status) {
        connected = status.equals("Glove connected");
        signing = false;
        phrase = null;
        signText.setText("");
        speak.setEnabled(false);
        gloveStatus.setText(status);
    }

    private void startSign() {
        if (!connected) {
            signText.setText("Connect the glove first");
            return;
        }
        signing = false;
        phrase = null;
        speak.setEnabled(false);
        try {
            if (signs != null) signs.close();
            signs = null;
            signs = new Signs(file("ksl.tflite"), Files.readAllLines(file("labels.txt").toPath()));
            signing = true;
            signText.setText("Sign now");
            main.removeCallbacks(signTimeout);
            main.postDelayed(signTimeout, 3500);
        } catch (Exception | LinkageError error) {
            signText.setText("KSL model unavailable: " + error.getMessage());
        }
    }

    private final Runnable signTimeout = () -> {
        if (!signing) return;
        signing = false;
        signText.setText("Incomplete sensor sample. Try again.");
    };

    private void sample(short[] readings) {
        if (!signing) return;
        try {
            String result = signs.add(readings);
            if (result == null) return;
            signing = false;
            phrase = result.isEmpty() ? null : result;
            signText.setText(phrase == null ? "Sign unclear. Try again." : phrase);
            speak.setEnabled(phrase != null);
        } catch (RuntimeException error) {
            signing = false;
            signText.setText("Sign recognition failed");
        }
    }

    private void initialiseVoice(int code) {
        if (isDestroyed() || code != TextToSpeech.SUCCESS) return;
        java.util.Set<Voice> voices = speech.getVoices();
        if (voices == null) return;
        for (Voice voice : voices) {
            if (voice.getLocale().getLanguage().equals(Locale.ENGLISH.getLanguage())
                    && !voice.isNetworkConnectionRequired()
                    && (voice.getFeatures() == null || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                    && speech.setVoice(voice) == TextToSpeech.SUCCESS) {
                voiceReady = true;
                break;
            }
        }
    }

    private void speak() {
        if (phrase == null) return;
        if (!voiceReady || speech.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "ai254-phrase") != TextToSpeech.SUCCESS)
            signText.setText(phrase + "\nInstall an offline English voice in Android settings.");
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override protected void onStop() {
        foreground = false;
        stopCaptions();
        if (hasBluetooth()) glove.stop();
        updateGlove("Glove disconnected");
        if (speech != null) speech.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(signTimeout);
        if (signs != null) signs.close();
        if (speech != null) speech.shutdown();
        super.onDestroy();
    }
}
