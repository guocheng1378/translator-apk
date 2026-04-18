package com.laotranslate.translator;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 老挝语翻译 - 原生 Android 主界面
 *
 * 功能：
 *   - 百度翻译 API（在线）
 *   - NLLB-200 ONNX 离线翻译
 *   - Android 原生语音识别
 *   - Android 原生 TTS 语音播报
 *   - 一键复制翻译结果
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final String PREFS_NAME = "translator_prefs";

    // UI
    private EditText etSource;
    private TextView tvOutput, tvCharCount, tvSrcLang, tvTgtLang;
    private TextView tvEngineTag, tvError, tvVoiceHint, tvStatus, tvOfflineStatus;
    private MaterialButton btnTranslate, btnLangLoZh, btnLangZhLo;
    private MaterialButton btnDownloadModel, btnDeleteModel;
    private ImageButton btnSwap;
    private FloatingActionButton fabVoice, fabSpeak, fabCopy, fabSettings;
    private ProgressBar progressDownload;

    // State
    private boolean isLoToZh = true; // true=老挝语→中文, false=中文→老挝语
    private boolean isTranslating = false;
    private boolean isRecording = false;
    private boolean isSpeaking = false;

    // Engine
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private OfflineTranslator offlineTranslator;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initTTS();
        initOfflineTranslator();
        loadCredentials();
        updateUI();
    }

    // ================================================================
    // Init
    // ================================================================

    private void initViews() {
        etSource = findViewById(R.id.etSource);
        tvOutput = findViewById(R.id.tvOutput);
        tvCharCount = findViewById(R.id.tvCharCount);
        tvSrcLang = findViewById(R.id.tvSrcLang);
        tvTgtLang = findViewById(R.id.tvTgtLang);
        tvEngineTag = findViewById(R.id.tvEngineTag);
        tvError = findViewById(R.id.tvError);
        tvVoiceHint = findViewById(R.id.tvVoiceHint);
        tvStatus = findViewById(R.id.tvStatus);
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus);

        btnTranslate = findViewById(R.id.btnTranslate);
        btnLangLoZh = findViewById(R.id.btnLangLoZh);
        btnLangZhLo = findViewById(R.id.btnLangZhLo);
        btnSwap = findViewById(R.id.btnSwap);
        btnDownloadModel = findViewById(R.id.btnDownloadModel);
        btnDeleteModel = findViewById(R.id.btnDeleteModel);

        fabVoice = findViewById(R.id.fabVoice);
        fabSpeak = findViewById(R.id.fabSpeak);
        fabCopy = findViewById(R.id.fabCopy);
        fabSettings = findViewById(R.id.fabSettings);
        progressDownload = findViewById(R.id.progressDownload);

        // Settings button
        fabSettings.setOnClickListener(v -> showSettingsDialog());

        // TextWatcher for char count
        etSource.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                tvCharCount.setText(String.valueOf(s.length()));
            }
        });

        // Language buttons
        btnLangLoZh.setOnClickListener(v -> setLanguage(true));
        btnLangZhLo.setOnClickListener(v -> setLanguage(false));
        btnSwap.setOnClickListener(v -> swapLanguage());

        // Translate
        btnTranslate.setOnClickListener(v -> doTranslate());

        // Voice input
        fabVoice.setOnClickListener(v -> toggleVoiceInput());

        // Speak output
        fabSpeak.setOnClickListener(v -> speakResult());

        // Copy output
        fabCopy.setOnClickListener(v -> copyResult());

        // Offline model
        btnDownloadModel.setOnClickListener(v -> downloadModel());
        btnDeleteModel.setOnClickListener(v -> deleteModel());
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setSpeechRate(0.9f);
            }
        });
    }

    private void initOfflineTranslator() {
        offlineTranslator = new OfflineTranslator(this);
        executor.execute(() -> {
            if (offlineTranslator.isModelAvailable()) {
                boolean ok = offlineTranslator.initModel();
                mainHandler.post(() -> updateOfflineUI(ok));
            } else {
                mainHandler.post(() -> updateOfflineUI(false));
            }
        });
    }

    // ================================================================
    // Language
    // ================================================================

    private void setLanguage(boolean loToZh) {
        isLoToZh = loToZh;
        updateLanguageButtons();
        updateLabels();
    }

    private void swapLanguage() {
        isLoToZh = !isLoToZh;
        updateLanguageButtons();
        updateLabels();

        // Swap text content
        String output = tvOutput.getText().toString();
        boolean hasOutput = !output.equals("翻译结果将显示在这里")
                && !output.isEmpty()
                && tvOutput.getCurrentTextColor() != getColor(R.color.text_hint);

        if (hasOutput) {
            etSource.setText(output);
            tvOutput.setText("翻译结果将显示在这里");
            tvOutput.setTextColor(getColor(R.color.text_hint));
            tvEngineTag.setText("");
        }
    }

    private void updateLanguageButtons() {
        if (isLoToZh) {
            btnLangLoZh.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.blue)));
            btnLangLoZh.setTextColor(getColor(android.R.color.white));
            btnLangZhLo.setStrokeColor(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.border)));
            btnLangZhLo.setTextColor(getColor(R.color.text_primary));
            btnLangZhLo.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
        } else {
            btnLangZhLo.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.blue)));
            btnLangZhLo.setTextColor(getColor(android.R.color.white));
            btnLangLoZh.setStrokeColor(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.border)));
            btnLangLoZh.setTextColor(getColor(R.color.text_primary));
            btnLangLoZh.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
        }
    }

    private void updateLabels() {
        tvSrcLang.setText(isLoToZh ? "老挝语" : "中文");
        tvTgtLang.setText(isLoToZh ? "中文" : "老挝语");
    }

    private void updateUI() {
        updateLanguageButtons();
        updateLabels();
        updateStatus();
    }

    // ================================================================
    // Translation
    // ================================================================

    private void doTranslate() {
        String text = etSource.getText().toString().trim();
        if (text.isEmpty() || isTranslating) return;

        isTranslating = true;
        btnTranslate.setEnabled(false);
        btnTranslate.setText("翻译中...");
        tvError.setVisibility(View.GONE);
        tvOutput.setText("");
        tvOutput.setTextColor(getColor(R.color.text_primary));
        tvEngineTag.setText("");

        String from = isLoToZh ? "lo" : "zh";
        String to = isLoToZh ? "zh" : "lo";

        executor.execute(() -> {
            String result = null;
            String engine = null;

            // Try Baidu API first
            if (BaiduTranslate.hasCredentials()) {
                result = BaiduTranslate.translate(text, from, to);
                if (result != null) engine = "baidu";
            }

            // Fallback to offline
            if (result == null && offlineTranslator.isReady()) {
                boolean toZh = isLoToZh;
                result = offlineTranslator.translate(text, toZh);
                if (result != null) engine = "offline";
            }

            final String fResult = result;
            final String fEngine = engine;

            mainHandler.post(() -> {
                isTranslating = false;
                btnTranslate.setEnabled(true);
                btnTranslate.setText("翻  译");

                if (fResult != null) {
                    tvOutput.setText(fResult);
                    tvOutput.setTextColor(getColor(R.color.text_primary));

                    if ("baidu".equals(fEngine)) {
                        tvEngineTag.setText("百度API");
                        tvEngineTag.setTextColor(getColor(R.color.green_text));
                        tvEngineTag.setBackgroundColor(getColor(R.color.green_bg));
                        tvEngineTag.setPadding(12, 4, 12, 4);
                    } else {
                        tvEngineTag.setText("离线NLLB");
                        tvEngineTag.setTextColor(getColor(R.color.amber_text));
                        tvEngineTag.setBackgroundColor(getColor(R.color.amber_bg));
                        tvEngineTag.setPadding(12, 4, 12, 4);
                    }
                } else {
                    tvOutput.setText("翻译结果将显示在这里");
                    tvOutput.setTextColor(getColor(R.color.text_hint));

                    if (!BaiduTranslate.hasCredentials() && !offlineTranslator.isReady()) {
                        showError("请配置百度API（右上角⚙设置）或下载离线翻译模型");
                    } else {
                        showError("翻译失败，请检查网络连接或 API 配置");
                    }
                }

                updateStatus();
            });
        });
    }

    // ================================================================
    // Speech Recognition (Native Android)
    // ================================================================

    private void toggleVoiceInput() {
        if (isRecording) {
            stopVoiceInput();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
            return;
        }

        startVoiceInput();
    }

    private static final int REQUEST_VOICE = 2001;

    private void startVoiceInput() {
        // 优先用系统语音识别 Intent（兼容性最好）
        try {
            android.content.Intent intent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    isLoToZh ? "lo_LA" : Locale.CHINESE.getLanguage());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                    isLoToZh ? "请说老挝语..." : "请说中文...");
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (Exception e) {
            // Intent 也不行，试试直接用 SpeechRecognizer API
            startDirectVoiceInput();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                etSource.setText(matches.get(0));
                doTranslate();
            }
        }
    }

    private void startDirectVoiceInput() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        if (speechRecognizer == null) {
            Toast.makeText(this, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show();
            return;
        }

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isRecording = true;
                fabVoice.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.red)));
                tvVoiceHint.setText("● 正在录音... 说完自动翻译");
                tvVoiceHint.setTextColor(getColor(R.color.red));
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {
                isRecording = false;
                fabVoice.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
                tvVoiceHint.setText("");
            }

            @Override public void onError(int error) {
                isRecording = false;
                fabVoice.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));

                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        msg = "未检测到语音";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        msg = "音频录制出错";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        msg = "请允许麦克风权限";
                        break;
                    default:
                        msg = "语音识别出错 (" + error + ")";
                }
                tvVoiceHint.setText(msg);
                mainHandler.postDelayed(() -> tvVoiceHint.setText(""), 3000);
            }

            @Override public void onResults(Bundle results) {
                isRecording = false;
                fabVoice.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));

                ArrayList<String> matches = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    etSource.setText(matches.get(0));
                    tvVoiceHint.setText("");
                    doTranslate();
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    etSource.setText(matches.get(0));
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });

        android.content.Intent intent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                isLoToZh ? "lo_LA" : Locale.CHINESE.getLanguage());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        speechRecognizer.startListening(intent);
    }

    private void stopVoiceInput() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        isRecording = false;
        fabVoice.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
        tvVoiceHint.setText("");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                Toast.makeText(this, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ================================================================
    // TTS (Native Android)
    // ================================================================

    private void speakResult() {
        String text = tvOutput.getText().toString().trim();
        if (text.isEmpty() || text.equals("翻译结果将显示在这里")) return;

        if (isSpeaking) {
            tts.stop();
            isSpeaking = false;
            fabSpeak.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
            return;
        }

        if (tts == null) return;

        // 设置语言，老挝语回退到泰语
        Locale locale;
        if (isLoToZh) {
            locale = Locale.CHINESE;
        } else {
            locale = new Locale("lo", "LA");
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                locale = new Locale("th", "TH"); // 回退到泰语
                tts.setLanguage(locale);
            }
        }
        tts.setLanguage(locale);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                mainHandler.post(() -> {
                    isSpeaking = true;
                    fabSpeak.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(getColor(R.color.green_bg)));
                });
            }

            @Override public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    isSpeaking = false;
                    fabSpeak.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
                });
            }

            @Override public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    isSpeaking = false;
                    fabSpeak.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
                });
            }
        });

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance");
    }

    // ================================================================
    // Copy
    // ================================================================

    private void copyResult() {
        String text = tvOutput.getText().toString().trim();
        if (text.isEmpty() || text.equals("翻译结果将显示在这里")) return;

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("翻译结果", text));

        fabCopy.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.amber_bg)));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(() ->
                fabCopy.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg))),
                1500);
    }

    // ================================================================
    // Offline Model
    // ================================================================

    private void downloadModel() {
        new AlertDialog.Builder(this)
                .setTitle("下载离线模型")
                .setMessage("离线翻译模型约 300MB，是否下载？下载后断网也能翻译。")
                .setPositiveButton("下载", (d, w) -> {
                    btnDownloadModel.setEnabled(false);
                    btnDownloadModel.setText("下载中...");
                    progressDownload.setVisibility(View.VISIBLE);
                    tvOfflineStatus.setText("下载中...");
                    tvOfflineStatus.setTextColor(getColor(R.color.amber));

                    offlineTranslator.downloadModel(new OfflineTranslator.DownloadProgressCallback() {
                        @Override
                        public void onProgress(int percent) {
                            mainHandler.post(() -> progressDownload.setProgress(percent));
                        }

                        @Override
                        public void onComplete(boolean success, String message) {
                            mainHandler.post(() -> {
                                progressDownload.setVisibility(View.GONE);
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                if (success) {
                                    initOfflineTranslator();
                                } else {
                                    btnDownloadModel.setEnabled(true);
                                    btnDownloadModel.setText("重试下载");
                                    tvOfflineStatus.setText("下载失败");
                                }
                            });
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteModel() {
        new AlertDialog.Builder(this)
                .setTitle("删除离线模型")
                .setMessage("确定删除离线翻译模型？")
                .setPositiveButton("删除", (d, w) -> {
                    offlineTranslator.deleteModel();
                    updateOfflineUI(false);
                    updateStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateOfflineUI(boolean ready) {
        if (ready) {
            tvOfflineStatus.setText("✅ 就绪");
            tvOfflineStatus.setTextColor(getColor(R.color.green));
            btnDownloadModel.setVisibility(View.GONE);
            btnDeleteModel.setVisibility(View.VISIBLE);
        } else {
            tvOfflineStatus.setText("未加载");
            tvOfflineStatus.setTextColor(getColor(R.color.amber));
            btnDownloadModel.setVisibility(View.VISIBLE);
            btnDownloadModel.setEnabled(true);
            btnDownloadModel.setText("下载模型");
            btnDeleteModel.setVisibility(View.GONE);
        }
        updateStatus();
    }

    // ================================================================
    // Settings (Baidu API credentials)
    // ================================================================

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        EditText etAppId = dialogView.findViewById(R.id.etAppId);
        EditText etSecretKey = dialogView.findViewById(R.id.etSecretKey);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etAppId.setText(prefs.getString("baidu_app_id", ""));
        etSecretKey.setText(prefs.getString("baidu_secret_key", ""));

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String appId = etAppId.getText().toString().trim();
                    String secretKey = etSecretKey.getText().toString().trim();

                    prefs.edit()
                            .putString("baidu_app_id", appId)
                            .putString("baidu_secret_key", secretKey)
                            .apply();

                    BaiduTranslate.setCredentials(appId, secretKey);
                    updateStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadCredentials() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String appId = prefs.getString("baidu_app_id", "");
        String secretKey = prefs.getString("baidu_secret_key", "");
        BaiduTranslate.setCredentials(appId, secretKey);
    }

    // ================================================================
    // Status & Utils
    // ================================================================

    private void updateStatus() {
        boolean hasApi = BaiduTranslate.hasCredentials();
        boolean hasOffline = offlineTranslator.isReady();

        StringBuilder sb = new StringBuilder();
        sb.append(hasApi ? "● 百度API" : "○ 百度API");
        sb.append("   ");
        sb.append(hasOffline ? "● 离线模型" : "○ 离线模型");
        tvStatus.setText(sb.toString());
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (offlineTranslator != null) {
            offlineTranslator.release();
        }
        executor.shutdown();
    }
}
