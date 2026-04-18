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

    // 最近一次错误信息，供 UI 展示
    private static volatile String lastError = null;

    public static void setCredentials(String appId, String secretKey) {
        sAppId = appId;
        sSecretKey = secretKey;
    }

    public static boolean hasCredentials() {
        return sAppId != null && !sAppId.isEmpty()
            && sSecretKey != null && !sSecretKey.isEmpty();
    }

    public static String getLastError() {
        return lastError;
    }

    /**
     * 同步翻译（需在子线程调用）
     * @param text 要翻译的文本
     * @param from 源语言 (lo / zh)
     * @param to   目标语言 (zh / lo)
     * @return 翻译结果，失败返回 null（错误信息通过 getLastError() 获取）
     */
    public static String translate(String text, String from, String to) {
        lastError = null;

        if (!hasCredentials()) {
            lastError = "未配置百度 API 密钥";
            Log.w(TAG, lastError);
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
                    lastError = "HTTP 错误: " + response.code();
                    Log.e(TAG, lastError);
                    return null;
                }

                String body = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(body);

                if (json.has("error_code")) {
                    String code = json.optString("error_code");
                    String msg = json.optString("error_msg");
                    lastError = getErrorDescription(code, msg);
                    Log.e(TAG, "API error " + code + ": " + msg);
                    return null;
                }

                JSONArray results = json.optJSONArray("trans_result");
                if (results == null || results.length() == 0) {
                    lastError = "翻译结果为空";
                    Log.w(TAG, "Empty trans_result, response: " + body);
                    return null;
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < results.length(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(results.getJSONObject(i).getString("dst"));
                }
                return sb.toString();
            }
        } catch (java.net.UnknownHostException e) {
            lastError = "网络连接失败，请检查网络";
            Log.e(TAG, lastError, e);
            return null;
        } catch (java.net.SocketTimeoutException e) {
            lastError = "请求超时，请检查网络";
            Log.e(TAG, lastError, e);
            return null;
        } catch (Exception e) {
            lastError = "翻译异常: " + e.getMessage();
            Log.e(TAG, lastError, e);
            return null;
        }
    }

    /**
     * 百度 API 错误码中文说明
     */
    private static String getErrorDescription(String code, String msg) {
        switch (code) {
            case "52001": return "请求超时（52001），请重试";
            case "52002": return "系统错误（52002）";
            case "52003": return "未授权用户，请检查 APP ID 和密钥是否正确";
            case "54000": return "必填参数为空";
            case "54001": return "签名错误，请检查 APP ID 和密钥";
            case "54003": return "访问频率受限";
            case "54004": return "账户余额不足，请充值";
            case "54005": return "长 query 请求频繁";
            case "58000": return "客户端 IP 非法";
            case "58001": return "翻译语言方向不支持（检查 lo/zh 是否正确）";
            case "58002": return "服务当前已关闭";
            case "90107": return "认证未通过或未生效";
            default: return "百度 API 错误 " + code + ": " + msg;
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
