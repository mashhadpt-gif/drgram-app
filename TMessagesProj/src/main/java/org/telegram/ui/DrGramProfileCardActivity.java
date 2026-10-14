/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Profile Card (Fix 36) — renders a luxury portrait card (1080x1350)
 * of the current user: onyx gradient, gold hairline frame, avatar, name,
 * username, id and a LuxGram wordmark. Saved as PNG in the app's external
 * files dir and offered to the system share sheet.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import androidx.core.content.FileProvider;

public class DrGramProfileCardActivity extends BaseFragment {

    private ImageView preview;
    private File cardFile;

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
        actionBar.setTitle(LocaleController.getString(R.string.DrGramCardTitle));
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
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        contentView.addView(preview, previewParams);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(DrGramGlassKit.panel(context, 20));
        DrGramGlassKit.applyDividers(card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        contentView.addView(card, cardParams);

        card.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramCardRegenerate), v -> {
            renderCard();
            toast(LocaleController.getString(R.string.DrGramCardRendered));
        }));
        card.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramCardShare), v -> shareCard()));

        renderCard();
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    private View makeActionRow(Context context, String title, View.OnClickListener listener) {
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
        row.setOnClickListener(listener);
        return row;
    }

    private void toast(String text) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    //region card rendering

    private File cardDir() {
        File dir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "LuxGramCards");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void renderCard() {
        try {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            Bitmap bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawCard(canvas, me);
            cardFile = new File(cardDir(), "LuxGramCard.png");
            FileOutputStream fos = new FileOutputStream(cardFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            preview.setImageBitmap(bitmap);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void drawCard(Canvas canvas, TLRPC.User me) {
        int love = DrGramThemeInstaller.isLoveMode() ? 1 : 0;
        int frame = love ? 0xFFF06A82 : 0xFFF4D27A;
        int softText = love ? 0xFFE8B8C3 : 0xFFE4D2A5;

        // onyx canvas with a faint center glow
        Paint bgPaint = new Paint();
        Bitmap gradient = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(gradient);
        android.graphics.LinearGradient linear = new android.graphics.LinearGradient(0, 0, 0, 1350,
                new int[]{0xFF0B0A07, 0xFF1B150A, 0xFF0B0A07},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        bgPaint.setShader(linear);
        gc.drawRect(0, 0, 1080, 1350, bgPaint);
        RadialGradient glow = new RadialGradient(540, 420, 620,
                (love ? 0x33F06A82 : 0x33F4D27A), 0x00000000, Shader.TileMode.CLAMP);
        bgPaint.setShader(glow);
        gc.drawCircle(540, 420, 620, bgPaint);
        canvas.drawBitmap(gradient, 0, 0, null);

        // double hairline frame
        Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3f);
        framePaint.setColor((0x66 << 24) | (frame & 0x00FFFFFF));
        canvas.drawRoundRect(new RectF(36, 36, 1044, 1314), 64, 64, framePaint);
        framePaint.setStrokeWidth(1.5f);
        framePaint.setColor((0x40 << 24) | (frame & 0x00FFFFFF));
        canvas.drawRoundRect(new RectF(52, 52, 1028, 1298), 54, 54, framePaint);

        // ornaments
        Paint ornament = new Paint(Paint.ANTI_ALIAS_FLAG);
        ornament.setColor(frame);
        ornament.setTextSize(46f);
        ornament.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(love ? "♡   ✦   ♡" : "✦   ◈   ✦", 540, 168, ornament);

        // avatar ring + photo
        float avatarCx = 540;
        float avatarCy = 430;
        float radius = 210f;
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(5f);
        ringPaint.setColor(frame);
        canvas.drawCircle(avatarCx, avatarCy, radius + 12, ringPaint);
        Bitmap photo = loadAvatar(me);
        Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (photo != null) {
            canvas.save();
            android.graphics.Path circle = new android.graphics.Path();
            circle.addCircle(avatarCx, avatarCy, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(circle);
            float size = Math.min(photo.getWidth(), photo.getHeight());
            float scale = (radius * 2) / size;
            canvas.translate(avatarCx - radius, avatarCy - radius);
            canvas.scale(scale, scale);
            canvas.drawBitmap(photo, -(photo.getWidth() - size) / 2f, -(photo.getHeight() - size) / 2f, photoPaint);
            canvas.restore();
        } else {
            Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            circlePaint.setColor(0xFF2A2313);
            canvas.drawCircle(avatarCx, avatarCy, radius, circlePaint);
            String initials = initials(me);
            Paint initialsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            initialsPaint.setColor(frame);
            initialsPaint.setTextSize(radius * 0.85f);
            initialsPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            initialsPaint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = initialsPaint.getFontMetrics();
            canvas.drawText(initials, avatarCx, avatarCy - (fm.ascent + fm.descent) / 2f, initialsPaint);
        }

        if (me != null) {
            Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            namePaint.setColor(0xFFF1EADA);
            namePaint.setTextSize(74f);
            namePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            namePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(ellipsize(namePaint, UserObject.getUserName(me), 940), 540, 760, namePaint);

            Paint usernamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            usernamePaint.setColor(frame);
            usernamePaint.setTextSize(44f);
            usernamePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            usernamePaint.setTextAlign(Paint.Align.CENTER);
            String uname = me.username == null ? "" : "@" + me.username;
            if (uname.length() > 0) {
                canvas.drawText(ellipsize(usernamePaint, uname, 900), 540, 836, usernamePaint);
            } else {
                canvas.drawText(ellipsize(usernamePaint, "ID " + me.id, 900), 540, 836, usernamePaint);
            }

            Paint idPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            idPaint.setColor(softText);
            idPaint.setTextSize(34f);
            idPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            idPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("ID  " + me.id, 540, 906, idPaint);
        }

        // caption
        Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        captionPaint.setColor(softText);
        captionPaint.setTextSize(30f);
        captionPaint.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        captionPaint.setTextAlign(Paint.Align.CENTER);
        String date = new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date());
        canvas.drawText("✦   " + date + "   ✦", 540, 1050, captionPaint);

        // wordmark
        Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wordPaint.setColor((0x8C << 24) | (frame & 0x00FFFFFF));
        wordPaint.setTextSize(30f);
        wordPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        wordPaint.setLetterSpacing(0.25f);
        wordPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("L U X G R A M", 540, 1224, wordPaint);
    }

    private String ellipsize(Paint paint, String text, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && paint.measureText(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private String initials(TLRPC.User user) {
        if (user == null) {
            return "L";
        }
        String name = UserObject.getUserName(user).trim();
        if (name.length() == 0) {
            return "L";
        }
        String[] parts = name.split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.US);
        }
        return name.substring(0, 1).toUpperCase(Locale.US);
    }

    private Bitmap loadAvatar(TLRPC.User me) {
        try {
            if (me == null || me.photo == null) {
                return null;
            }
            File file = FileLoader.getInstance(currentAccount).getPathToAttach(me.photo.photo_big, true);
            if (file != null && file.exists() && file.length() > 0) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    //endregion

    private void shareCard() {
        try {
            if (cardFile == null || !cardFile.exists()) {
                renderCard();
            }
            Context context = getParentActivity();
            if (context == null) {
                return;
            }
            ArrayList<Object> uris = new ArrayList<>();
            Uri uri = FileProvider.getUriForFile(context,
                    ApplicationLoader.applicationContext.getPackageName() + ".provider", cardFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent,
                    LocaleController.getString(R.string.DrGramCardShare)));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
