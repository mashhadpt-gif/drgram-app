/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Encrypted Backup (Fix 36) — packs every LuxGram drgram_* preference
 * file of this account (tools, smart reply, anti-delete, vault lists, style,
 * guard, favorites metadata...) into ONE AES-GCM encrypted .luxbak file.
 * The key is derived from a user password (PBKDF2, 150k rounds); the file is
 * stored in the app's external files dir and can be shared / re-imported.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class DrGramBackupActivity extends BaseFragment {

    private static final String MAGIC = "LUXBAK1";
    private static final int PBKDF2_ROUNDS = 150_000;

    private LinearLayout listCard;
    private TextView countView;

    @Override
    public View createView(Context context) {
        try {
            return drgramCreateViewInternal(context);
        } catch (Throwable t) {
            FileLog.e(t);
            LinearLayout fb = new LinearLayout(context);
            fb.setBackgroundColor(0xFF0B0A07);
            return fb;
        }
    }

    private View drgramCreateViewInternal(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramBackupTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setPadding(0, 0, 0, AndroidUtilities.dp(28));
        contentView.setBackground(DrGramGlassKit.pageGradient());

        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            TextView hero = new TextView(context);
            hero.setText("✦  ◈  ✦");
            hero.setTextColor(0xFFFFE6A0);
            hero.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            hero.setGravity(Gravity.CENTER);
            hero.setLetterSpacing(0.14f);
            hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(6));
            hero.setBackground(DrGramGlassKit.heroPanel(context));
            contentView.addView(hero, heroParams());

            TextView brand = new TextView(context);
            brand.setText("LUX  VAULT  BACKUP");
            brand.setTextColor(0xFFFFE8AE);
            brand.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            brand.setTypeface(AndroidUtilities.bold());
            brand.setGravity(Gravity.CENTER);
            brand.setLetterSpacing(0.12f);
            brand.setPadding(0, AndroidUtilities.dp(14), 0, 0);
            contentView.addView(brand);

            TextView tagline = new TextView(context);
            tagline.setText(LocaleController.getString(R.string.DrGramBackupHero));
            tagline.setTextColor(DrGramGlassKit.hint());
            tagline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            tagline.setGravity(Gravity.CENTER);
            tagline.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
            contentView.addView(tagline);
        } catch (Throwable t) {
            FileLog.e(t);
        }

        contentView.addView(DrGramGlassKit.header(context,
                LocaleController.getString(R.string.DrGramBackupActions), 0));
        countView = new TextView(context);
        LinearLayout card = glassCard(context);
        card.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramBackupCreate), v ->
                showCreateDialog()));
        card.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramBackupRestoreHint), null));
        LinearLayout countRow = makeActionRow(context, LocaleController.getString(R.string.DrGramBackupCount), null);
        countView = (TextView) countRow.getChildAt(0);
        card.addView(countRow);
        contentView.addView(card, cardParams());

        contentView.addView(DrGramGlassKit.header(context,
                LocaleController.getString(R.string.DrGramBackupFiles), 0));
        listCard = glassCard(context);
        contentView.addView(listCard, cardParams());

        rebuildList();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuildList();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    private LinearLayout.LayoutParams heroParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(4));
        return params;
    }

    private LinearLayout glassCard(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(DrGramGlassKit.panel(context, 20));
        DrGramGlassKit.applyDividers(panel);
        return panel;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        return params;
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(DrGramGlassKit.rowRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        labelView.setTextColor(DrGramGlassKit.ROW_TITLE);
        labelView.setText(title);
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        return row;
    }

    //region backup store

    private File backupDir() {
        File dir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "LuxGramBackup");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private ArrayList<File> listBackups() {
        ArrayList<File> files = new ArrayList<>();
        File[] all = backupDir().listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().endsWith(".luxbak")) {
                    files.add(f);
                }
            }
        }
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    private void rebuildList() {
        if (listCard == null) {
            return;
        }
        listCard.removeAllViews();
        ArrayList<File> files = listBackups();
        Context context = listCard.getContext();
        if (countView != null) {
            countView.setText(LocaleController.getString(R.string.DrGramBackupCount) + "  ·  " + files.size());
        }
        if (files.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            empty.setTextColor(DrGramGlassKit.hint());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(18), AndroidUtilities.dp(17), AndroidUtilities.dp(18));
            empty.setText(LocaleController.getString(R.string.DrGramBackupEmpty));
            listCard.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US);
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundDrawable(DrGramGlassKit.rowRipple());
            row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(11), AndroidUtilities.dp(17), AndroidUtilities.dp(11));
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(DrGramGlassKit.ROW_TITLE);
            name.setText("🔒  " + file.getName());
            row.addView(name);
            TextView meta = new TextView(context);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
            meta.setTextColor(DrGramGlassKit.hint());
            meta.setText(fmt.format(new Date(file.lastModified())) + "  ·  " + (file.length() / 1024) + " KB");
            LinearLayout.LayoutParams metaParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(2);
            row.addView(meta, metaParams);
            row.setOnClickListener(v -> showRestoreDialog(file));
            listCard.addView(row);
        }
    }

    private void showCreateDialog() {
        if (getParentActivity() == null) {
            return;
        }
        showPasswordDialog(LocaleController.getString(R.string.DrGramBackupCreate),
                LocaleController.getString(R.string.DrGramBackupPasswordHint), password -> {
                    if (password.length() < 4) {
                        toast(LocaleController.getString(R.string.DrGramBackupShortPassword));
                        return;
                    }
                    try {
                        JSONObject payload = collectPrefs();
                        payload.put("created", System.currentTimeMillis());
                        payload.put("account", currentAccount);
                        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                        File out = new File(backupDir(), "LuxGram-" + stamp + ".luxbak");
                        encryptToFile(payload.toString(), out, password);
                        toast(LocaleController.getString(R.string.DrGramBackupDone));
                        rebuildList();
                    } catch (Throwable t) {
                        FileLog.e(t);
                        toast(LocaleController.getString(R.string.DrGramBackupFailed));
                    }
                });
    }

    private void showRestoreDialog(File file) {
        if (getParentActivity() == null) {
            return;
        }
        showPasswordDialog(LocaleController.getString(R.string.DrGramBackupRestore) + ": " + file.getName(),
                LocaleController.getString(R.string.DrGramBackupPasswordHint), password -> {
                    try {
                        String json = decryptFromFile(file, password);
                        applyPrefs(new JSONObject(json));
                        toast(LocaleController.getString(R.string.DrGramBackupRestored));
                    } catch (Throwable t) {
                        FileLog.e(t);
                        toast(LocaleController.getString(R.string.DrGramBackupWrongPassword));
                    }
                });
    }

    private interface PasswordCallback {
        void run(String password);
    }

    private void showPasswordDialog(String title, String hint, PasswordCallback callback) {
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(title);
        builder.setMessage(hint);
        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = AndroidUtilities.dp(16);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setTextColor(0xFFF7F0DF);
        input.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        input.setBackground(bg);
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) ->
                callback.run(input.getText().toString().trim()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private void toast(String text) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_LONG).show();
        }
    }

    //endregion

    //region prefs collection / apply

    /** Every drgram_* shared-prefs file of this account → one JSON object. */
    private JSONObject collectPrefs() throws Throwable {
        JSONObject root = new JSONObject();
        File prefsDir = new File(ApplicationLoader.getFilesDirFixed().getParentFile(), "shared_prefs");
        File[] files = prefsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".xml") || !name.startsWith("drgram_")) {
                    continue;
                }
                String prefsName = name.substring(0, name.length() - 4);
                SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                JSONObject fileJson = new JSONObject();
                for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    Object value = entry.getValue();
                    JSONObject typed = new JSONObject();
                    if (value instanceof Boolean) {
                        typed.put("type", "boolean").put("value", value);
                    } else if (value instanceof Integer) {
                        typed.put("type", "int").put("value", value);
                    } else if (value instanceof Long) {
                        typed.put("type", "long").put("value", value);
                    } else if (value instanceof Float) {
                        typed.put("type", "float").put("value", value);
                    } else if (value instanceof java.util.Set) {
                        typed.put("type", "string_set");
                        org.json.JSONArray values = new org.json.JSONArray();
                        for (Object item : (java.util.Set<?>) value) {
                            if (item instanceof String) values.put((String) item);
                        }
                        typed.put("value", values);
                    } else {
                        typed.put("type", "string").put("value", value == null ? "" : String.valueOf(value));
                    }
                    fileJson.put(entry.getKey(), typed);
                }
                root.put(prefsName, fileJson);
            }
        }
        JSONObject wrapper = new JSONObject();
        wrapper.put(MAGIC, true);
        wrapper.put("prefs", root);
        return wrapper;
    }

    private void applyPrefs(JSONObject wrapper) throws Throwable {
        if (!wrapper.optBoolean(MAGIC, false)) {
            throw new IllegalArgumentException("bad file");
        }
        JSONObject root = wrapper.getJSONObject("prefs");
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String prefsName = keys.next();
            // Never allow a backup to overwrite Telegram's own preferences.
            if (prefsName == null || !prefsName.startsWith("drgram_")) {
                throw new IllegalArgumentException("unsupported preferences");
            }
            JSONObject fileJson = root.getJSONObject(prefsName);
            SharedPreferences.Editor editor = ApplicationLoader.applicationContext
                    .getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit();
            editor.clear();
            Iterator<String> entries = fileJson.keys();
            while (entries.hasNext()) {
                String key = entries.next();
                Object raw = fileJson.get(key);
                // Version 1 backups stored every value as a string; keep them restorable.
                if (!(raw instanceof JSONObject)) {
                    editor.putString(key, raw == JSONObject.NULL ? "" : String.valueOf(raw));
                    continue;
                }
                JSONObject typed = (JSONObject) raw;
                String type = typed.optString("type", "string");
                Object value = typed.opt("value");
                if ("boolean".equals(type)) {
                    editor.putBoolean(key, typed.optBoolean("value", false));
                } else if ("int".equals(type)) {
                    editor.putInt(key, typed.optInt("value", 0));
                } else if ("long".equals(type)) {
                    editor.putLong(key, typed.optLong("value", 0L));
                } else if ("float".equals(type)) {
                    editor.putFloat(key, (float) typed.optDouble("value", 0.0d));
                } else if ("string_set".equals(type)) {
                    org.json.JSONArray values = typed.optJSONArray("value");
                    java.util.HashSet<String> set = new java.util.HashSet<>();
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            if (!values.isNull(i)) set.add(values.optString(i));
                        }
                    }
                    editor.putStringSet(key, set);
                } else if ("string".equals(type)) {
                    editor.putString(key, value == null || value == JSONObject.NULL ? "" : String.valueOf(value));
                } else {
                    throw new IllegalArgumentException("unsupported preference type");
                }
            }
            editor.apply();
        }
    }

    //endregion

    //region AES-GCM

    private static SecretKeySpec deriveKey(String password, byte[] salt) throws Throwable {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private void encryptToFile(String plaintext, File out, String password) throws Throwable {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));
        JSONObject file = new JSONObject();
        file.put("magic", MAGIC);
        file.put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP));
        file.put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP));
        file.put("data", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP));
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(file.toString().getBytes("UTF-8"));
        fos.close();
    }

    private String decryptFromFile(File in, String password) throws Throwable {
        StringBuilder builder = new StringBuilder();
        FileReader reader = new FileReader(in);
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) > 0) {
            builder.append(buffer, 0, read);
        }
        reader.close();
        JSONObject file = new JSONObject(builder.toString());
        if (!MAGIC.equals(file.optString("magic"))) {
            throw new IllegalArgumentException("bad magic");
        }
        byte[] salt = android.util.Base64.decode(file.getString("salt"), android.util.Base64.NO_WRAP);
        byte[] iv = android.util.Base64.decode(file.getString("iv"), android.util.Base64.NO_WRAP);
        byte[] data = android.util.Base64.decode(file.getString("data"), android.util.Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(data), "UTF-8");
    }

    //endregion
}
