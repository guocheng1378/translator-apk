package com.laotranslate.translator;

import android.content.Context;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
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
    private OrtSession decoderSession;
    private OrtSession encoderSession;
    private SimpleTokenizer tokenizer;
    private boolean isReady = false;
    // 模型运行模式：true=单独 encoder+decoder，false=仅 decoder（兼容旧单文件模型）
    private boolean useSeparateEncoder = false;
    // 最近一次错误信息
    private static volatile String lastError = null;

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
        File tokenizerFile = new File(context.getFilesDir(), TOKENIZER_FILE);
        // 只要 decoder 模型和 tokenizer 存在即可（encoder 可选）
        return modelFile.exists() && tokenizerFile.exists()
                && modelFile.length() > 1_000_000;
    }

    /**
     * 检查是否已就绪
     */
    public boolean isReady() {
        return isReady;
    }

    /**
     * 获取最近一次错误信息
     */
    public static String getLastError() {
        return lastError;
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
            File encoderFile = new File(context.getFilesDir(), ENCODER_FILE);
            File tokenizerFile = new File(context.getFilesDir(), TOKENIZER_FILE);

            if (!modelFile.exists() || !tokenizerFile.exists()) {
                Log.w(TAG, "Model files not found");
                return false;
            }

            // 初始化 ONNX Runtime
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(4);

            // 加载 decoder 模型
            decoderSession = env.createSession(modelFile.getAbsolutePath(), opts);
            Log.i(TAG, "Decoder model loaded. Inputs: " + decoderSession.getInputNames());

            // 如果存在单独的 encoder 文件，加载它
            if (encoderFile.exists() && encoderFile.length() > 1_000_000) {
                try {
                    OrtSession.SessionOptions encOpts = new OrtSession.SessionOptions();
                    encOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    encOpts.setIntraOpNumThreads(4);
                    encoderSession = env.createSession(encoderFile.getAbsolutePath(), encOpts);
                    useSeparateEncoder = true;
                    Log.i(TAG, "Encoder model loaded. Inputs: " + encoderSession.getInputNames());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load encoder, will try decoder-only mode", e);
                    useSeparateEncoder = false;
                }
            } else {
                useSeparateEncoder = false;
                Log.i(TAG, "No separate encoder file, using decoder-only mode");
            }

            // 初始化 tokenizer
            tokenizer = new SimpleTokenizer(tokenizerFile);

            isReady = true;
            Log.i(TAG, "Model initialized successfully (separateEncoder=" + useSeparateEncoder + ")");
            return true;

        } catch (Exception e) {
            lastError = "模型初始化失败: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            Log.e(TAG, lastError, e);
            return false;
        }
    }

    /**
     * 翻译
     * @param text 要翻译的文本
     * @param toZh true=老挝语→中文, false=中文→老挝语
     */
    public String translate(String text, boolean toZh) {
        lastError = null;
        if (!isReady) {
            lastError = "模型未就绪";
            return null;
        }

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

            // 获取目标语言的 forced bos token id
            long forcedBosId = tokenizer.getLangTokenId(tgtLang);

            OrtSession.Result result;
            float[][][] logits;

            if (useSeparateEncoder && encoderSession != null) {
                // === 模式1: 单独 encoder + decoder ===
                // 先运行 encoder
                Map<String, OnnxTensor> encInputs = new HashMap<>();
                encInputs.put("input_ids", inputTensor);
                encInputs.put("attention_mask", maskTensor);

                OrtSession.Result encResult = encoderSession.run(encInputs);
                // encResult.get(String) 返回 Optional<OnnxValue>，需要 unwrap
                OnnxTensor encoderHiddenStates = (OnnxTensor) encResult.get("last_hidden_state").get();
                long[] encShape = encoderHiddenStates.getInfo().getShape();

                // 创建 decoder 的 attention mask（长度 = encoder 输出序列长度）
                int encSeqLen = (int) encShape[1];
                long[] decAttentionMask = new long[encSeqLen];
                Arrays.fill(decAttentionMask, 1L);
                OnnxTensor decMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decAttentionMask), new long[]{1, encSeqLen});

                // decoder 只需要 encoder_hidden_states + decoder_input_ids
                // decoder_input_ids = [BOS, forcedBosId] 用于开始生成
                long[] decoderInputIds;
                if (forcedBosId >= 0) {
                    decoderInputIds = new long[]{BOS_TOKEN_ID, forcedBosId};
                } else {
                    decoderInputIds = new long[]{BOS_TOKEN_ID};
                }
                OnnxTensor decInputTensor = OnnxTensor.createTensor(env,
                        LongBuffer.wrap(decoderInputIds),
                        new long[]{1, decoderInputIds.length});

                Map<String, OnnxTensor> decInputs = new HashMap<>();
                decInputs.put("encoder_attention_mask", decMaskTensor);
                decInputs.put("encoder_hidden_states", encoderHiddenStates);
                decInputs.put("input_ids", decInputTensor);
                // 添加 use_cache_branch 输入 (NLLB-200 模型需要)
                OnnxTensor useCacheBranchTensor = OnnxTensor.createTensor(env, new boolean[]{false}, new long[]{1});
                decInputs.put("use_cache_branch", useCacheBranchTensor);

                result = decoderSession.run(decInputs);
                logits = (float[][][]) result.get(0).getValue();

                // 清理 encoder 资源
                decInputTensor.close();
                decMaskTensor.close();
                encResult.close();

            } else {
                // === 模式2: 仅 decoder（兼容旧模型或合并模型）===
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", inputTensor);
                inputs.put("attention_mask", maskTensor);
                // 添加 use_cache_branch 输入 (NLLB-200 模型需要)
                OnnxTensor useCacheBranchTensor2 = OnnxTensor.createTensor(env, new boolean[]{false}, new long[]{1});
                inputs.put("use_cache_branch", useCacheBranchTensor2);

                result = decoderSession.run(inputs);
                logits = (float[][][]) result.get(0).getValue();
            }

            // 解码输出
            int outputLen = logits[0].length;
            int[] outputIds = new int[outputLen];
            int actualLen = 0;

            for (int i = 0; i < outputLen; i++) {
                int maxIdx = 0;
                float maxVal = logits[0][i][0];
                for (int j = 1; j < logits[0][i].length; j++) {
                    if (logits[0][i][j] > maxVal) {
                        maxVal = logits[0][i][j];
                        maxIdx = j;
                    }
                }
                if (maxIdx == EOS_TOKEN_ID || maxIdx == PAD_TOKEN_ID) break;
                outputIds[actualLen++] = maxIdx;
            }

            String translated = tokenizer.decode(outputIds, actualLen);

            // 清理资源
            inputTensor.close();
            maskTensor.close();
            result.close();

            return translated;

        } catch (Exception e) {
            lastError = "推理异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            Log.e(TAG, "Translation failed: " + lastError, e);
            return null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        if (decoderSession != null) {
            try { decoderSession.close(); } catch (Exception ignored) {}
            decoderSession = null;
        }
        if (encoderSession != null) {
            try { encoderSession.close(); } catch (Exception ignored) {}
            encoderSession = null;
        }
        if (env != null) {
            try { env.close(); } catch (Exception ignored) {}
            env = null;
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
     * 从本地目录导入模型文件
     * @param modelDir 包含模型文件的目录路径
     * @return 是否导入成功
     */
    public boolean importModel(String modelDir) {
        try {
            File srcDir = new File(modelDir);
            if (!srcDir.exists() || !srcDir.isDirectory()) return false;

            File dstDir = context.getFilesDir();

            // 需要复制的文件名映射
            String[][] filePairs = {
                {"decoder_model_merged_quantized.onnx", MODEL_FILE},
                {"encoder_model_quantized.onnx", ENCODER_FILE},
                {"tokenizer.json", TOKENIZER_FILE},
                {"tokenizer_config.json", TOKENIZER_CONFIG_FILE},
                // 也兼容旧名称
                {"nllb-200-distilled-600M-quantized.onnx", MODEL_FILE},
            };

            boolean foundAny = false;
            for (String[] pair : filePairs) {
                File src = new File(srcDir, pair[0]);
                if (src.exists() && src.length() > 100) {
                    File dst = new File(dstDir, pair[1]);
                    copyFile(src, dst);
                    foundAny = true;
                }
            }

            return foundAny && isModelAvailable();

        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
            return false;
        }
    }

    void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    /**
     * 从 ContentResolver InputStream 复制文件（用于 URI 导入）
     */
    boolean copyFromStream(InputStream in, String destFileName) throws IOException {
        File dst = new File(context.getFilesDir(), destFileName);
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        return dst.exists() && dst.length() > 100;
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
