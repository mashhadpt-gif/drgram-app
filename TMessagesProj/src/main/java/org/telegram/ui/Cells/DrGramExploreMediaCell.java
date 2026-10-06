/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramExploreMediaCell — a single square tile of the Explore grid.
 * Renders a photo or video thumb of one channel message; video tiles show
 * a duration badge like the built-in shared media grid.
 */
package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PhotoViewer;

public class DrGramExploreMediaCell extends FrameLayout {

    private final BackupImageView imageView;
    private final FrameLayout videoInfoContainer;
    private final TextView videoTextView;
    private final View selector;
    private final Paint backgroundPaint = new Paint();

    private MessageObject currentMessageObject;
    private final int currentAccount = org.telegram.messenger.UserConfig.selectedAccount;

    public DrGramExploreMediaCell(Context context) {
        super(context);
        setWillNotDraw(false);

        backgroundPaint.setColor(Theme.getColor(Theme.key_sharedMedia_photoPlaceholder));

        imageView = new BackupImageView(context);
        imageView.getImageReceiver().setNeedsQualityThumb(true);
        imageView.getImageReceiver().setShouldGenerateQualityThumb(true);
        imageView.getImageReceiver().setRoundRadius(AndroidUtilities.dp(6));
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        videoInfoContainer = new FrameLayout(context) {
            private final RectF rect = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                canvas.drawRoundRect(rect, AndroidUtilities.dp(4), AndroidUtilities.dp(4), Theme.chat_timeBackgroundPaint);
            }
        };
        videoInfoContainer.setWillNotDraw(false);
        videoInfoContainer.setPadding(AndroidUtilities.dp(5), 0, AndroidUtilities.dp(5), 0);
        addView(videoInfoContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 17, Gravity.BOTTOM | Gravity.LEFT, 4, 0, 0, 4));

        ImageView playImageView = new ImageView(context);
        playImageView.setImageResource(R.drawable.play_mini_video);
        videoInfoContainer.addView(playImageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        videoTextView = new TextView(context);
        videoTextView.setTextColor(0xffffffff);
        videoTextView.setTypeface(AndroidUtilities.bold());
        videoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        videoTextView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        videoInfoContainer.addView(videoTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 13, -0.7f, 0, 0));

        selector = new View(context);
        selector.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        addView(selector, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        selector.drawableHotspotChanged(event.getX(), event.getY());
        return super.onTouchEvent(event);
    }

    public MessageObject getMessageObject() {
        return currentMessageObject;
    }

    public BackupImageView getImageView() {
        return imageView;
    }

    public org.telegram.messenger.ImageReceiver getImageReceiver() {
        return imageView.getImageReceiver();
    }

    public void setMessageObject(MessageObject messageObject) {
        currentMessageObject = messageObject;
        imageView.getImageReceiver().setVisible(!PhotoViewer.isShowingImage(messageObject), false);
        String restrictionReason = MessagesController.getInstance(currentAccount).getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            videoInfoContainer.setVisibility(INVISIBLE);
            imageView.setImageResource(R.drawable.photo_placeholder_in);
        } else if (messageObject.isVideo()) {
            videoInfoContainer.setVisibility(VISIBLE);
            videoTextView.setText(AndroidUtilities.formatShortDuration((int) messageObject.getDuration()));
            org.telegram.tgnet.TLRPC.Document document = messageObject.getDocument();
            org.telegram.tgnet.TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 50);
            org.telegram.tgnet.TLRPC.PhotoSize qualityThumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
            if (thumb == qualityThumb) {
                qualityThumb = null;
            }
            if (thumb != null) {
                if (messageObject.strippedThumb != null) {
                    imageView.setImage(ImageLocation.getForDocument(qualityThumb, document), "100_100", null, messageObject.strippedThumb, messageObject);
                } else {
                    imageView.setImage(ImageLocation.getForDocument(qualityThumb, document), "100_100", ImageLocation.getForDocument(thumb, document), "b", ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.photo_placeholder_in), null, null, 0, messageObject);
                }
            } else {
                imageView.setImageResource(R.drawable.photo_placeholder_in);
            }
        } else if (messageObject.messageOwner.media instanceof org.telegram.tgnet.TLRPC.TL_messageMediaPhoto && messageObject.messageOwner.media.photo != null && !messageObject.photoThumbs.isEmpty()) {
            videoInfoContainer.setVisibility(INVISIBLE);
            org.telegram.tgnet.TLRPC.PhotoSize currentPhotoObjectThumb = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
            org.telegram.tgnet.TLRPC.PhotoSize currentPhotoObject = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 320, false, currentPhotoObjectThumb, false);
            if (messageObject.mediaExists || DownloadController.getInstance(currentAccount).canDownloadMedia(messageObject)) {
                if (currentPhotoObject == currentPhotoObjectThumb) {
                    currentPhotoObjectThumb = null;
                }
                if (messageObject.strippedThumb != null) {
                    imageView.getImageReceiver().setImage(ImageLocation.getForObject(currentPhotoObject, messageObject.photoThumbsObject), "100_100", null, null, messageObject.strippedThumb, currentPhotoObject != null ? currentPhotoObject.size : 0, null, messageObject, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 1);
                } else {
                    imageView.getImageReceiver().setImage(ImageLocation.getForObject(currentPhotoObject, messageObject.photoThumbsObject), "100_100", ImageLocation.getForObject(currentPhotoObjectThumb, messageObject.photoThumbsObject), "b", currentPhotoObject != null ? currentPhotoObject.size : 0, null, messageObject, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 1);
                }
            } else {
                if (messageObject.strippedThumb != null) {
                    imageView.setImage(null, null, null, null, messageObject.strippedThumb, null, null, 0, messageObject);
                } else {
                    imageView.setImage(null, null, ImageLocation.getForObject(currentPhotoObjectThumb, messageObject.photoThumbsObject), "b", ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.photo_placeholder_in), null, null, 0, messageObject);
                }
            }
        } else {
            videoInfoContainer.setVisibility(INVISIBLE);
            imageView.setImageResource(R.drawable.photo_placeholder_in);
        }
        setContentDescription(messageObject.isVideo() ? LocaleController.getString(R.string.AttachVideo) : LocaleController.getString(R.string.AttachPhoto));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (currentMessageObject == null || !imageView.getImageReceiver().hasBitmapImage() || imageView.getImageReceiver().getCurrentAlpha() != 1.0f || PhotoViewer.isShowingImage(currentMessageObject)) {
            canvas.drawRect(0, 0, getMeasuredWidth(), getMeasuredHeight(), backgroundPaint);
        }
    }
}
