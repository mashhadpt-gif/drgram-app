/*
 * DrGram AI configuration. API credentials are encrypted with an Android Keystore
 * AES-GCM key; plaintext API keys are never written to SharedPreferences.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class DrGramAIConfig {
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static final String PREFS = "drgram_ai_tools";
    private static final String KEY_ALIAS = "drgram_ai_api_key_v1";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "api_key_encrypted";

    private DrGramAIConfig() {}

    private static SharedPreferences prefs() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            throw new IllegalStateException("Application context is not ready");
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getEndpoint() {
        try {
            return prefs().getString(KEY_ENDPOINT, DEFAULT_ENDPOINT);
        } catch (Exception e) {
            return DEFAULT_ENDPOINT;
        }
    }

    public static String getModel() {
        try {
            return prefs().getString(KEY_MODEL, DEFAULT_MODEL);
        } catch (Exception e) {
            return DEFAULT_MODEL;
        }
    }

    public static String getApiKey() {
        try {
            String encrypted = prefs().getString(KEY_API_KEY, "");
            return TextUtils.isEmpty(encrypted) ? "" : decrypt(encrypted);
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    /**
     * Only HTTPS endpoints are accepted. A blank key leaves a previously saved
     * key untouched, which lets users change model/endpoint without retyping it.
     */
    public static void save(String endpoint, String model, String apiKey) throws Exception {
        String cleanEndpoint = endpoint == null ? "" : endpoint.trim();
        URL url = new URL(cleanEndpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || TextUtils.isEmpty(url.getHost())) {
            throw new IllegalArgumentException("AI endpoint must use HTTPS");
        }

        String cleanModel = model == null ? "" : model.trim();
        if (TextUtils.isEmpty(cleanModel)) {
            throw new IllegalArgumentException("AI model is required");
        }

        SharedPreferences.Editor editor = prefs().edit()
                .putString(KEY_ENDPOINT, cleanEndpoint)
                .putString(KEY_MODEL, cleanModel);

        String cleanKey = apiKey == null ? "" : apiKey.trim();
        if (!TextUtils.isEmpty(cleanKey)) {
            editor.putString(KEY_API_KEY, encrypt(cleanKey));
        }
        editor.apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) {
                return (SecretKey) key;
            }
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] complete = Base64.decode(encoded, Base64.NO_WRAP);
        if (complete.length <= 12) {
            return "";
        }
        byte[] iv = new byte[12];
        byte[] encrypted = new byte[complete.length - iv.length];
        System.arraycopy(complete, 0, iv, 0, iv.length);
        System.arraycopy(complete, iv.length, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
