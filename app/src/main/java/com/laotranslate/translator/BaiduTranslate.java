package com.laotranslate.translator;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Random;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 百度翻译 API 客户端
 * 支持老挝语 ↔ 中文 双向翻译
 */
public class BaiduTranslate {
    private static final String TAG = "BaiduTranslate";
    private static final String API_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate";

    private static volatile String sAppId;
    private static volatile String sSecretKey;
    private static final OkHttpClient client = new OkHttpClient();

    public static void setCredentials(String appId, String secretKey) {
        sAppId = appId;
        sSecretKey = secretKey;
    }

    public static boolean hasCredentials() {
        return sAppId != null && !sAppId.isEmpty()
            && sSecretKey != null && !sSecretKey.isEmpty();
    }

    /**
     * 同步翻译（需在子线程调用）
     * @param text 要翻译的文本
     * @param from 源语言 (lo / zh)
     * @param to   目标语言 (zh / lo)
     * @return 翻译结果，失败返回 null
     */
    public static String translate(String text, String from, String to) {
        if (!hasCredentials()) {
            Log.w(TAG, "No credentials set");
            return null;
        }

        try {
            String salt = String.valueOf(new Random().nextInt(65536) + 32768);
            String sign = md5(sAppId + text + salt + sSecretKey);

            String url = API_URL
                + "?q=" + java.net.URLEncoder.encode(text, "UTF-8")
                + "&from=" + from
                + "&to=" + to
                + "&appid=" + sAppId
                + "&salt=" + salt
                + "&sign=" + sign;

            Request request = new Request.Builder().url(url).build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "HTTP error: " + response.code());
                    return null;
                }

                String body = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(body);

                if (json.has("error_code")) {
                    Log.e(TAG, "API error: " + json.optString("error_msg"));
                    return null;
                }

                JSONArray results = json.optJSONArray("trans_result");
                if (results == null || results.length() == 0) return null;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < results.length(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(results.getJSONObject(i).getString("dst"));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Translation failed", e);
            return null;
        }
    }

    /**
     * MD5 哈希
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
