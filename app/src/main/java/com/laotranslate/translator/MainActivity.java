package com.laotranslate.translator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView webView;
    private OfflineTranslator offlineTranslator;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);

        // 注入离线翻译接口
        offlineTranslator = new OfflineTranslator(this);
        webView.addJavascriptInterface(new OfflineTranslationBridge(), "OfflineTranslate");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("https://guocheng1378.github.io")) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 注入离线翻译状态
                injectOfflineStatus();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // 加载在线翻译页面
        webView.loadUrl("https://guocheng1378.github.io/translator-apk/");

        // 检查并初始化离线模型
        initOfflineModel();
    }

    private void initOfflineModel() {
        new Thread(() -> {
            if (offlineTranslator.isModelAvailable()) {
                boolean ok = offlineTranslator.initModel();
                runOnUiThread(() -> {
                    if (ok) {
                        injectOfflineStatus();
                    }
                });
            }
        }).start();
    }

    private void injectOfflineStatus() {
        boolean ready = offlineTranslator.isReady();
        boolean available = offlineTranslator.isModelAvailable();
        webView.evaluateJavascript(
            "window.__offlineReady = " + ready + ";" +
            "window.__offlineAvailable = " + available + ";" +
            "if(typeof onOfflineStatusChange === 'function') onOfflineStatusChange(" + ready + ");",
            null
        );
    }

    /**
     * JavaScript 桥接 - 离线翻译
     */
    class OfflineTranslationBridge {

        @JavascriptInterface
        public boolean isOfflineReady() {
            return offlineTranslator.isReady();
        }

        @JavascriptInterface
        public boolean isModelAvailable() {
            return offlineTranslator.isModelAvailable();
        }

        @JavascriptInterface
        public long getModelSizeMB() {
            return offlineTranslator.getModelSizeMB();
        }

        @JavascriptInterface
        public String translate(String text, boolean toZh) {
            if (!offlineTranslator.isReady()) {
                // 尝试初始化
                if (!offlineTranslator.initModel()) {
                    return null;
                }
            }
            return offlineTranslator.translate(text, toZh);
        }

        @JavascriptInterface
        public void downloadModel() {
            runOnUiThread(() -> {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("下载离线模型")
                    .setMessage("离线翻译模型约 300MB，是否下载？下载后断网也能翻译。")
                    .setPositiveButton("下载", (d, w) -> {
                        Toast.makeText(MainActivity.this, "开始下载模型...", Toast.LENGTH_SHORT).show();
                        offlineTranslator.downloadModel(new OfflineTranslator.DownloadProgressCallback() {
                            @Override
                            public void onProgress(int percent) {
                                runOnUiThread(() -> {
                                    webView.evaluateJavascript(
                                        "if(typeof onDownloadProgress === 'function') onDownloadProgress(" + percent + ");",
                                        null
                                    );
                                });
                            }

                            @Override
                            public void onComplete(boolean success, String message) {
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                    if (success) {
                                        initOfflineModel();
                                    }
                                });
                            }
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show();
            });
        }

        @JavascriptInterface
        public void deleteModel() {
            offlineTranslator.deleteModel();
            runOnUiThread(() -> injectOfflineStatus());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (offlineTranslator != null) {
            offlineTranslator.release();
        }
        super.onDestroy();
    }
}
