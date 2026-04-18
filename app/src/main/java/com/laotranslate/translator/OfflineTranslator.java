package com.laotranslate.translator;

import android.content.Context;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.LongBuffer;
import java.util.*;

/**
 * 离线翻译引擎 - 基于 NLLB-200 ONNX 模型
 * 支持 老挝语 <-> 中文 双向翻译
 */
public class OfflineTranslator {
    private static final String TAG = "OfflineTranslator";

    // 模型下载地址 - HuggingFace NLLB-200 distilled 600M
    private static final String MODEL_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/onnx/decoder_model_merged_quantized.onnx";
    private static final String ENCODER_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/onnx/encoder_model_quantized.onnx";
    private static final String TOKENIZER_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/tokenizer.json";
    private static final String TOKENIZER_CONFIG_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/tokenizer_config.json";

    private static final String MODEL_FILE = "decoder_model_merged_quantized.onnx";
    private static final String ENCODER_FILE = "encoder_model_quantized.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";
    private static final String TOKENIZER_CONFIG_FILE = "tokenizer_config.json";

    // NLLB-200 语言代码
    private static final String LANG_ZH = "zho_Hans";  // 中文简体
    private static final String LANG_LO = "lao_Laoo";  // 老挝语

    // 特殊 token ID（NLLB-200）
    private static final int PAD_TOKEN_ID = 1;
    private static final int EOS_TOKEN_ID = 2;
    private static final int BOS_TOKEN_ID = 0;

    private Context context;
    private OrtEnvironment env;
    private OrtSession session;
    private SimpleTokenizer tokenizer;
    private boolean isReady = false;

    public interface DownloadProgressCallback {
        void onProgress(int percent);
        void onComplete(boolean success, String message);
    }

    public OfflineTranslator(Context context) {
        this.context = context;
    }

    /**
     * 检查模型是否已下载
     */
    public boolean isModelAvailable() {
        File modelFile = new File(context.getFilesDir(), MODEL_FILE);
        File encoderFile = new File(context.getFilesDir(), ENCODER_FILE);
        File tokenizerFile = new File(context.getFilesDir(), TOKENIZER_FILE);
        return modelFile.exists() && encoderFile.exists() && tokenizerFile.exists()
                && modelFile.length() > 1_000_000 && encoderFile.length() > 1_000_000;
    }

    /**
     * 检查是否已就绪
     */
    public boolean isReady() {
        return isReady;
    }

    /**
     * 下载模型文件
     */
    public void downloadModel(DownloadProgressCallback callback) {
        new Thread(() -> {
            try {
                File modelFile = new File(context.getFilesDir(), MODEL_FILE);
                File encoderFile = new File(context.getFilesDir(), ENCODER_FILE);
                File tokenizerFile = new File(context.getFilesDir(), TOKENIZER_FILE);
                File tokenizerConfigFile = new File(context.getFilesDir(), TOKENIZER_CONFIG_FILE);

                // 下载 tokenizer
                if (!tokenizerFile.exists()) {
                    downloadFile(TOKENIZER_URL, tokenizerFile, null);
                }

                // 下载 tokenizer config
                if (!tokenizerConfigFile.exists()) {
                    downloadFile(TOKENIZER_CONFIG_URL, tokenizerConfigFile, null);
                }

                // 下载 encoder（带进度）
                if (!encoderFile.exists() || encoderFile.length() < 1_000_000) {
                    downloadFile(ENCODER_URL, encoderFile, callback);
                }

                // 下载 decoder（带进度）
                if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                    downloadFile(MODEL_URL, modelFile, callback);
                }

                callback.onComplete(true, "模型下载完成");
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                callback.onComplete(false, "下载失败: " + e.getMessage());
            }
        }).start();
    }

    private void downloadFile(String urlStr, File outputFile, DownloadProgressCallback callback) throws IOException {
        // Handle redirects (HuggingFace returns 302)
        HttpURLConnection conn;
        int redirects = 0;
        while (true) {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.connect();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                urlStr = location;
                redirects++;
                if (redirects > 5) throw new IOException("Too many redirects");
                continue;
            }
            break;
        }

        int fileLength = conn.getContentLength();
        try (InputStream input = new BufferedInputStream(conn.getInputStream());
             OutputStream output = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            int lastPercent = 0;

            while ((count = input.read(buffer)) != -1) {
                total += count;
                output.write(buffer, 0, count);

                if (callback != null && fileLength > 0) {
                    int percent = (int) (total * 100 / fileLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int p = percent;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> callback.onProgress(p));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 初始化模型
     */
    public boolean initModel() {
        if (isReady) return true;

        try {
            File modelFile = new File(context.getFilesDir(), MODEL_FILE);
            File tokenizerFile = new File(context.getFilesDir(), TOKENIZER_FILE);

            if (!modelFile.exists() || !tokenizerFile.exists()) {
                Log.w(TAG, "Model files not found");
                return false;
            }

            // 初始化 ONNX Runtime
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

            // 优化选项
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(4);

            session = env.createSession(modelFile.getAbsolutePath(), opts);

            // 初始化 tokenizer
            tokenizer = new SimpleTokenizer(tokenizerFile);

            isReady = true;
            Log.i(TAG, "Model initialized successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to init model", e);
            return false;
        }
    }

    /**
     * 翻译
     * @param text 要翻译的文本
     * @param toZh true=老挝语→中文, false=中文→老挝语
     */
    public String translate(String text, boolean toZh) {
        if (!isReady) return null;

        try {
            String srcLang = toZh ? LANG_LO : LANG_ZH;
            String tgtLang = toZh ? LANG_ZH : LANG_LO;

            // 编码输入
            long[] inputIds = tokenizer.encode(text, srcLang);
            int seqLen = inputIds.length;

            // 创建输入张量
            LongBuffer inputBuf = LongBuffer.wrap(inputIds);
            long[] attentionMask = new long[seqLen];
            Arrays.fill(attentionMask, 1L);

            LongBuffer maskBuf = LongBuffer.wrap(attentionMask);

            long[] shape = {1, seqLen};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuf, shape);
            OnnxTensor maskTensor = OnnxTensor.createTensor(env, maskBuf, shape);

            // 设置目标语言 forced bos token
            long forcedBosId = tokenizer.getLangTokenId(tgtLang);

            // 运行推理
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputTensor);
            inputs.put("attention_mask", maskTensor);

            OrtSession.Result result = session.run(inputs);

            // 解码输出
            float[][][] logits = (float[][][]) result.get(0).getValue();
            int outputLen = logits[0].length;
            int[] outputIds = new int[outputLen];

            for (int i = 0; i < outputLen; i++) {
                int maxIdx = 0;
                float maxVal = logits[0][i][0];
                for (int j = 1; j < logits[0][i].length; j++) {
                    if (logits[0][i][j] > maxVal) {
                        maxVal = logits[0][i][j];
                        maxIdx = j;
                    }
                }
                outputIds[i] = maxIdx;
                if (maxIdx == EOS_TOKEN_ID) break;
            }

            String translated = tokenizer.decode(outputIds);

            // 清理资源
            inputTensor.close();
            maskTensor.close();
            result.close();

            return translated;

        } catch (Exception e) {
            Log.e(TAG, "Translation failed", e);
            return null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
        }
        if (env != null) {
            try { env.close(); } catch (Exception ignored) {}
        }
        isReady = false;
    }

    /**
     * 获取模型文件大小（MB）
     */
    public long getModelSizeMB() {
        File modelFile = new File(context.getFilesDir(), MODEL_FILE);
        return modelFile.exists() ? modelFile.length() / 1024 / 1024 : 0;
    }

    /**
     * 删除模型文件释放空间
     */
    public void deleteModel() {
        release();
        new File(context.getFilesDir(), MODEL_FILE).delete();
        new File(context.getFilesDir(), ENCODER_FILE).delete();
        new File(context.getFilesDir(), TOKENIZER_FILE).delete();
        new File(context.getFilesDir(), TOKENIZER_CONFIG_FILE).delete();
    }
}
