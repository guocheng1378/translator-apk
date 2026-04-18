package com.laotranslate.translator;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/**
 * NLLB-200 简化 Tokenizer
 * 基于 SentencePiece，从 tokenizer.json 加载词表
 */
public class SimpleTokenizer {
    private static final String TAG = "SimpleTokenizer";

    // NLLB-200 语言 token 映射
    private static final Map<String, String> LANG_TOKENS = new HashMap<>();
    static {
        LANG_TOKENS.put("zho_Hans", "zho_Hans");
        LANG_TOKENS.put("lao_Laoo", "lao_Laoo");
    }

    private Map<String, Integer> vocab;       // token -> id
    private Map<Integer, String> idToToken;   // id -> token
    private Map<String, Integer> langTokenIds;
    private int vocabSize;

    public SimpleTokenizer(File tokenizerJsonFile) throws Exception {
        vocab = new HashMap<>();
        idToToken = new HashMap<>();
        langTokenIds = new HashMap<>();

        // 读取 tokenizer.json
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(tokenizerJsonFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        JSONObject json = new JSONObject(sb.toString());

        // 解析词表
        JSONObject addedTokens = json.optJSONObject("added_tokens");
        if (addedTokens != null) {
            Iterator<String> keys = addedTokens.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject tokenInfo = addedTokens.getJSONObject(key);
                String content = tokenInfo.getString("content");
                int id = tokenInfo.getInt("id");
                vocab.put(content, id);
                idToToken.put(id, content);

                // 记录语言 token
                for (Map.Entry<String, String> lang : LANG_TOKENS.entrySet()) {
                    if (content.equals(lang.getValue())) {
                        langTokenIds.put(lang.getKey(), id);
                    }
                }
            }
        }

        // 解析模型词表
        JSONObject model = json.optJSONObject("model");
        if (model != null) {
            JSONObject vocabObj = model.optJSONObject("vocab");
            if (vocabObj != null) {
                Iterator<String> keys = vocabObj.keys();
                while (keys.hasNext()) {
                    String token = keys.next();
                    Object val = vocabObj.get(token);
                    int id;
                    if (val instanceof JSONArray) {
                        id = ((JSONArray) val).getInt(0);
                    } else {
                        id = (int) val;
                    }
                    if (!vocab.containsKey(token)) {
                        vocab.put(token, id);
                        idToToken.put(id, token);
                    }
                }
            }
        }

        vocabSize = vocab.size();
        Log.i(TAG, "Tokenizer loaded: " + vocabSize + " tokens");
    }

    /**
     * 编码文本为 token IDs
     * 简化版：字符级 + BPE 部分匹配
     */
    public long[] encode(String text, String srcLang) {
        // 添加语言前缀 token
        String langToken = LANG_TOKENS.get(srcLang);
        List<Long> ids = new ArrayList<>();

        // BOS
        ids.add(0L);

        // 源语言 token
        if (langToken != null && vocab.containsKey(langToken)) {
            ids.add((long) vocab.get(langToken));
        }

        // 分词：简单按字符和子词匹配
        List<String> tokens = bpeTokenize(text);

        for (String token : tokens) {
            if (vocab.containsKey(token)) {
                ids.add((long) vocab.get(token));
            } else {
                // 未知字符，按 UTF-8 字节拆分处理
                try {
                    byte[] utf8Bytes = token.getBytes("UTF-8");
                    for (byte b : utf8Bytes) {
                        // NLLB-200 byte tokens: <0x00> ~ <0xFF>
                        String byteToken = String.format("<0x%02X>", b & 0xFF);
                        if (vocab.containsKey(byteToken)) {
                            ids.add((long) vocab.get(byteToken));
                        }
                    }
                } catch (Exception e) {
                    // fallback: 单字符直接尝试词表
                    for (char c : token.toCharArray()) {
                        String s = String.valueOf(c);
                        if (vocab.containsKey(s)) {
                            ids.add((long) vocab.get(s));
                        }
                    }
                }
            }
        }

        // EOS
        ids.add(2L);

        long[] result = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    /**
     * BPE 简化分词
     * 尝试匹配词表中最长的子串
     */
    private List<String> bpeTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            String bestMatch = null;
            int bestLen = 0;

            // 尝试从当前位置找最长匹配
            for (int len = Math.min(8, text.length() - i); len >= 1; len--) {
                String candidate = text.substring(i, i + len);
                // NLLB 使用 ▁ 前缀表示空格
                String withPrefix = "▁" + candidate;
                if (vocab.containsKey(candidate)) {
                    bestMatch = candidate;
                    bestLen = len;
                    break;
                } else if (vocab.containsKey(withPrefix)) {
                    bestMatch = withPrefix;
                    bestLen = len;
                    break;
                }
            }

            if (bestMatch != null) {
                tokens.add(bestMatch);
                i += bestLen;
            } else {
                // 单字符
                tokens.add(String.valueOf(text.charAt(i)));
                i++;
            }
        }
        return tokens;
    }

    /**
     * 解码 token IDs 为文本（完整数组）
     */
    public String decode(int[] ids) {
        return decode(ids, ids.length);
    }

    /**
     * 解码 token IDs 为文本（指定有效长度）
     */
    public String decode(int[] ids, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int id = ids[i];
            if (id == 0 || id == 1 || id == 2) continue; // skip BOS, PAD, EOS

            String token = idToToken.get(id);
            if (token != null) {
                // 跳过语言 token
                if (LANG_TOKENS.containsValue(token)) continue;

                // 处理 ▁ 前缀（空格）
                if (token.startsWith("▁")) {
                    if (sb.length() > 0) sb.append(' ');
                    token = token.substring(1);
                }

                // 处理 byte token: <0x0A> ~ <0xFF>
                if (token.startsWith("<0x") && token.endsWith(">")) {
                    try {
                        String hex = token.substring(3, token.length() - 1); // 提取 0A 或 FF
                        int code = Integer.parseInt(hex, 16);
                        sb.append((char) code);
                    } catch (Exception e) {
                        sb.append(token);
                    }
                } else {
                    sb.append(token);
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 获取语言 token ID
     */
    public long getLangTokenId(String langCode) {
        Integer id = langTokenIds.get(langCode);
        if (id == null) {
            // 尝试从词表找
            String langToken = LANG_TOKENS.get(langCode);
            if (langToken != null && vocab.containsKey(langToken)) {
                return vocab.get(langToken);
            }
            return -1;
        }
        return id;
    }
}
