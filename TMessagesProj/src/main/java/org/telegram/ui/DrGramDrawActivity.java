/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Draw & Send — a blank canvas that turns whatever you scribble into
 * a normal photo message in the current chat. Pure client-side: the canvas
 * is rendered to a PNG on this device and handed to the standard photo
 * sending pipeline, exactly as if the picture came from the gallery.
 */
package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

public class DrGramDrawActivity extends BaseFragment {

    private static final int[] COLORS = {0xFFF2B33C, 0xFFE85D4A, 0xFF7B4BD6, 0xFF1FA98F, 0xFF222222, 0xFFFFFFFF};
    private static final int[] WIDTHS = {5, 11, 22};

    private final long dialogId;
    private DrawingView drawingView;
    private int selectedColor = 0;
    private int selectedWidth = 1;
    private final View[] colorDots = new View[COLORS.length];
    private final TextView[] widthButtons = new TextView[WIDTHS.length];

    public DrGramDrawActivity(long dialogId) {
        super();
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramDrawTitle));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(1, R.drawable.ic_ab_done);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    sendDrawing();
                }
            }
        });

        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        fragmentView = rootLayout;

        drawingView = new DrawingView(context);
        drawingView.setBackgroundColor(0xFFFFFFFF);
        rootLayout.addView(drawingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout tools = new LinearLayout(context);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setGravity(Gravity.CENTER);
        tools.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        LinearLayout colorRow = new LinearLayout(context);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < COLORS.length; ++i) {
            GradientDrawable dotDrawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{COLORS[i], COLORS[i]});
            dotDrawable.setShape(GradientDrawable.OVAL);
            View dot = new View(context);
            dot.setBackgroundDrawable(dotDrawable);
            final int index = i;
            dot.setOnClickListener(v -> {
                selectedColor = index;
                updateToolSelection();
            });
            colorDots[i] = dot;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(AndroidUtilities.dp(30), AndroidUtilities.dp(30));
            params.setMargins(AndroidUtilities.dp(9), AndroidUtilities.dp(8), AndroidUtilities.dp(9), AndroidUtilities.dp(4));
            colorRow.addView(dot, params);
        }
        tools.addView(colorRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 6, 0, 0));

        LinearLayout widthRow = new LinearLayout(context);
        widthRow.setOrientation(LinearLayout.HORIZONTAL);
        widthRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < WIDTHS.length; ++i) {
            TextView button = new TextView(context);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            button.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            button.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
            button.setText(WIDTHS[i] == WIDTHS[0] ? LocaleController.getString(R.string.DrGramDrawThin) : i == 1 ? LocaleController.getString(R.string.DrGramDrawMedium) : LocaleController.getString(R.string.DrGramDrawThick));
            final int index = i;
            button.setOnClickListener(v -> {
                selectedWidth = index;
                updateToolSelection();
            });
            widthButtons[i] = button;
            widthRow.addView(button);
            TextView separator = new TextView(context);
            separator.setText("  ");
            widthRow.addView(separator);
        }
        tools.addView(widthRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 2, 0, 0));

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        TextView undoButton = new TextView(context);
        undoButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        undoButton.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        undoButton.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(8), AndroidUtilities.dp(18), AndroidUtilities.dp(8));
        undoButton.setText(LocaleController.getString(R.string.DrGramDrawUndo));
        undoButton.setOnClickListener(v -> drawingView.undo());
        actionRow.addView(undoButton);

        TextView clearButton = new TextView(context);
        clearButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        clearButton.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        clearButton.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(8), AndroidUtilities.dp(18), AndroidUtilities.dp(8));
        clearButton.setText(LocaleController.getString(R.string.DrGramDrawClear));
        clearButton.setOnClickListener(v -> drawingView.clearAll());
        actionRow.addView(clearButton);
        tools.addView(actionRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 2, 0, 6));

        rootLayout.addView(tools, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateToolSelection();
        return fragmentView;
    }

    private void updateToolSelection() {
        for (int i = 0; i < colorDots.length; ++i) {
            if (colorDots[i] != null) {
                float scale = i == selectedColor ? 1.2f : 1.0f;
                colorDots[i].setScaleX(scale);
                colorDots[i].setScaleY(scale);
            }
        }
        for (int i = 0; i < widthButtons.length; ++i) {
            if (widthButtons[i] != null) {
                widthButtons[i].setTypeface(AndroidUtilities.bold());
                widthButtons[i].setTextColor(getThemedColor(i == selectedWidth ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteBlackText));
            }
        }
    }

    private void sendDrawing() {
        if (getParentActivity() == null || drawingView == null) {
            return;
        }
        if (drawingView.isEmpty()) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramDrawEmpty), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, drawingView.getWidth()), Math.max(1, drawingView.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        drawingView.draw(canvas);
        final File outputFile = new File(ApplicationLoader.applicationContext.getCacheDir(), "drgram_draw_" + System.currentTimeMillis() + ".png");
        Utilities.globalQueue.postRunnable(() -> {
            boolean saved = false;
            try {
                FileOutputStream stream = new FileOutputStream(outputFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.close();
                saved = true;
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean success = saved;
            bitmap.recycle();
            AndroidUtilities.runOnUIThread(() -> {
                if (!success) {
                    if (getParentActivity() != null) {
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramDrawError), android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                try {
                    SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(currentAccount),
                            outputFile.getAbsolutePath(), null, dialogId,
                            null, null, null,
                            null, null, null, null,
                            0, null, true, 0, 0, null);
                    if (getParentActivity() != null) {
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramDrawSent), android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                finishFragment();
            });
        });
    }

    private class DrawingView extends View {

        private class Stroke {
            final Path path = new Path();
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        private final ArrayList<Stroke> strokes = new ArrayList<>();
        private Stroke currentStroke;

        DrawingView(Context context) {
            super(context);
        }

        boolean isEmpty() {
            return strokes.isEmpty();
        }

        void undo() {
            if (!strokes.isEmpty()) {
                strokes.remove(strokes.size() - 1);
                invalidate();
            }
        }

        void clearAll() {
            strokes.clear();
            invalidate();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    currentStroke = new Stroke();
                    currentStroke.path.moveTo(event.getX(), event.getY());
                    currentStroke.paint.setColor(COLORS[selectedColor]);
                    currentStroke.paint.setStyle(Paint.Style.STROKE);
                    currentStroke.paint.setStrokeCap(Paint.Cap.ROUND);
                    currentStroke.paint.setStrokeJoin(Paint.Join.ROUND);
                    currentStroke.paint.setStrokeWidth(AndroidUtilities.dp(WIDTHS[selectedWidth]));
                    strokes.add(currentStroke);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (currentStroke != null) {
                        int history = event.getHistorySize();
                        for (int i = 0; i < history; ++i) {
                            currentStroke.path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i));
                        }
                        currentStroke.path.lineTo(event.getX(), event.getY());
                        invalidate();
                        return true;
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (currentStroke != null) {
                        currentStroke.path.lineTo(event.getX(), event.getY());
                        currentStroke = null;
                        invalidate();
                        return true;
                    }
                    break;
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int i = 0; i < strokes.size(); ++i) {
                Stroke stroke = strokes.get(i);
                canvas.drawPath(stroke.path, stroke.paint);
            }
        }
    }
}
