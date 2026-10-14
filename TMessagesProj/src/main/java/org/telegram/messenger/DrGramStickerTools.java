/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Sticker Tools (Fix 40) — reply-driven media converters for the
 * smart-command engine:
 *   • استیکر شو   — replied photo/image -> 512 px square WEBP sent with a
 *                   sticker attribute, so it renders like a real sticker.
 *   • عکس شو      — replied WEBP sticker -> JPEG photo (sent compressed).
 *   • ذخیره       — replied post (text / photo / document) re-sent to the
 *                   owner's Saved Messages ("ذخیره شو در پی وی").
 *
 * Downloads are kicked with FileLoader and polled briefly; every failure
 * answers with a friendly Persian notice instead of failing silently.
 */
package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;

public class DrGramStickerTools {

    private static final int MAX_ATTEMPTS = 30; // 30 * 500 ms = 15 s

    //region entry points (called from DrGramSmartReply on the UI thread)

    /** Reply to a photo or image document -> sticker-style WEBP document. */
    public static void toSticker(int account, long dialogId, MessageObject replied) {
        try {
            if (replied == null || replied.messageOwner == null || replied.messageOwner.media == null) {
                notice(dialogId, "↩️ " + LocaleController.getString(R.string.DrGramSmartNeedReply));
                return;
            }
            TLRPC.MessageMedia media = replied.messageOwner.media;
            if (media.photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 1280);
                if (size == null || size.location == null) {
                    notice(dialogId, "⛔️ عکسی برای تبدیل پیدا نشد");
                    return;
                }
                File target = FileLoader.getPathToAttach(size, true);
                if (target == null || !target.exists() || target.length() == 0) {
                    FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, media.photo), media.photo, null, 0, 0);
                    notice(dialogId, "⏳ عکس در حال دانلود است، چند لحظه بعد دوباره بفرست");
                    poll(target, () -> toSticker(account, dialogId, replied), dialogId);
                    return;
                }
                processSticker(account, dialogId, target);
            } else if (media.document instanceof TLRPC.TL_document) {
                TLRPC.TL_document doc = (TLRPC.TL_document) media.document;
                File target = FileLoader.getPathToAttach(doc, true);
                if (target == null || !target.exists() || target.length() == 0) {
                    FileLoader.getInstance(account).loadFile(doc, null, 0, 0);
                    notice(dialogId, "⏳ فایل در حال دانلود است، چند لحظه بعد دوباره بفرست");
                    poll(target, () -> toSticker(account, dialogId, replied), dialogId);
                    return;
                }
                processSticker(account, dialogId, target);
            } else {
                notice(dialogId, "⛔️ روی یک عکس ریپلای کن تا استیکر شود");
            }
        } catch (Throwable t) {
            FileLog.e(t);
            notice(dialogId, "⛔️ تبدیل به استیکر ناموفق بود");
        }
    }

    /** Reply to a WEBP sticker -> JPEG photo sent as compressed photo. */
    public static void toPhoto(int account, long dialogId, MessageObject replied) {
        try {
            if (replied == null || replied.messageOwner == null || replied.messageOwner.media == null) {
                notice(dialogId, "↩️ " + LocaleController.getString(R.string.DrGramSmartNeedReply));
                return;
            }
            TLRPC.MessageMedia media = replied.messageOwner.media;
            if (media.photo != null) {
                notice(dialogId, "🙂 این از قبل عکس است");
                return;
            }
            if (!(media.document instanceof TLRPC.TL_document)) {
                notice(dialogId, "⛔️ روی یک استیکر یا فایل وب‌پی ریپلای کن");
                return;
            }
            TLRPC.TL_document doc = (TLRPC.TL_document) media.document;
            String mime = doc.mime_type == null ? "" : doc.mime_type;
            boolean webp = mime.contains("webp");
            if (mime.contains("tgs") || "application/x-tgsticker".equals(mime)) {
                notice(dialogId, "⛔️ استیکرهای انیمیشنی (TGS) پشتیبانی نمی‌شوند");
                return;
            }
            if (mime.contains("webm") || mime.contains("video")) {
                notice(dialogId, "⛔️ استیکرهای ویدیویی (WEBM) پشتیبانی نمی‌شوند");
                return;
            }
            if (!webp && !mime.contains("image")) {
                notice(dialogId, "⛔️ این فایل تصویر نیست");
                return;
            }
            File target = FileLoader.getPathToAttach(doc, true);
            if (target == null || !target.exists() || target.length() == 0) {
                FileLoader.getInstance(account).loadFile(doc, null, 0, 0);
                notice(dialogId, "⏳ استیکر در حال دانلود است، چند لحظه بعد دوباره بفرست");
                poll(target, () -> toPhoto(account, dialogId, replied), dialogId);
                return;
            }
            processPhoto(account, dialogId, target);
        } catch (Throwable t) {
            FileLog.e(t);
            notice(dialogId, "⛔️ تبدیل به عکس ناموفق بود");
        }
    }

    /** Reply to any post -> re-send its content to the owner's Saved Messages. */
    public static void saveToPv(int account, long fromDialogId, MessageObject replied) {
        try {
            if (replied == null || replied.messageOwner == null) {
                notice(fromDialogId, "↩️ " + LocaleController.getString(R.string.DrGramSmartNeedReply));
                return;
            }
            long saved = UserConfig.getInstance(account).getClientUserId();
            if (saved == 0) {
                notice(fromDialogId, "⛔️ پیام‌های ذخیره‌شده در دسترس نیست");
                return;
            }
            TLRPC.MessageMedia media = replied.messageOwner.media;
            if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media.photo == null && media.document == null) {
                String text = replied.messageOwner.message;
                if (text == null || text.length() == 0) {
                    notice(fromDialogId, "⛔️ این پست قابل ذخیره نیست");
                    return;
                }
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, saved));
                notice(fromDialogId, "✅ در پیام‌های ذخیره‌شده ذخیره شد");
                return;
            }
            if (media.photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 1280);
                if (size == null || size.location == null) {
                    notice(fromDialogId, "⛔️ عکس پست پیدا نشد");
                    return;
                }
                File target = FileLoader.getPathToAttach(size, true);
                if (target == null || !target.exists() || target.length() == 0) {
                    FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, media.photo), media.photo, null, 0, 0);
                    notice(fromDialogId, "⏳ در حال دانلود عکس…");
                    poll(target, () -> sendSavedPhoto(account, saved, target, fromDialogId), fromDialogId);
                    return;
                }
                sendSavedPhoto(account, saved, target, fromDialogId);
            } else if (media.document instanceof TLRPC.TL_document) {
                TLRPC.TL_document doc = (TLRPC.TL_document) media.document;
                File target = FileLoader.getPathToAttach(doc, true);
                if (target == null || !target.exists() || target.length() == 0) {
                    FileLoader.getInstance(account).loadFile(doc, null, 0, 0);
                    notice(fromDialogId, "⏳ در حال دانلود فایل…");
                    poll(target, () -> sendSavedDocument(account, saved, doc, target, fromDialogId), fromDialogId);
                    return;
                }
                sendSavedDocument(account, saved, doc, target, fromDialogId);
            } else {
                notice(fromDialogId, "⛔️ این نوع پست قابل ذخیره نیست");
            }
        } catch (Throwable t) {
            FileLog.e(t);
            notice(fromDialogId, "⛔️ ذخیره‌سازی ناموفق بود");
        }
    }

    //endregion

    //region converters

    private static void processSticker(int account, long dialogId, File source) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Bitmap sourceBitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
                if (sourceBitmap == null) {
                    notice(dialogId, "⛔️ فایل تصویری قابل خواندن نبود");
                    return;
                }
                int side = Math.min(sourceBitmap.getWidth(), sourceBitmap.getHeight());
                int x = (sourceBitmap.getWidth() - side) / 2;
                int y = (sourceBitmap.getHeight() - side) / 2;
                Bitmap square = Bitmap.createBitmap(sourceBitmap, x, y, side, side);
                Bitmap scaled = Bitmap.createScaledBitmap(square, 512, 512, true);
                if (scaled != square && square != sourceBitmap) {
                    square.recycle();
                }
                if (scaled != sourceBitmap) {
                    sourceBitmap.recycle();
                }
                File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "drgram_sticker_" + System.currentTimeMillis() + ".webp");
                Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
                FileOutputStream fos = new FileOutputStream(out);
                scaled.compress(format, 90, fos);
                fos.close();

                TLRPC.TL_document document = new TLRPC.TL_document();
                document.id = 0;
                document.dc_id = 0;
                document.mime_type = "image/webp";
                document.file_reference = new byte[0];
                document.date = ConnectionsManager.getInstance(account).getCurrentTime();
                document.size = out.length();
                TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
                fileName.file_name = "luxsticker.webp";
                document.attributes.add(fileName);
                TLRPC.TL_documentAttributeSticker stickerAttr = new TLRPC.TL_documentAttributeSticker();
                stickerAttr.alt = "";
                stickerAttr.stickerset = new TLRPC.TL_inputStickerSetEmpty();
                stickerAttr.mask = false;
                document.attributes.add(stickerAttr);
                // small stripped preview so the bubble has an image before upload
                Bitmap thumb = Bitmap.createScaledBitmap(scaled, 72, 72, true);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, 60, bos);
                TLRPC.TL_photoStrippedSize stripped = new TLRPC.TL_photoStrippedSize();
                stripped.type = "i";
                stripped.bytes = bos.toByteArray();
                document.thumbs.add(stripped);

                SendMessagesHelper.getInstance(account).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(document, null, out.getAbsolutePath(), dialogId,
                                null, null, null, null, null, null, true, 0, 0, 0, null, null, false));
                scaled.recycle();
                thumb.recycle();
            } catch (Throwable t) {
                FileLog.e(t);
                notice(dialogId, "⛔️ تبدیل به استیکر ناموفق بود");
            }
        });
    }

    private static void processPhoto(int account, long dialogId, File source) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                Bitmap bmp = BitmapFactory.decodeFile(source.getAbsolutePath(), opts);
                if (bmp == null) {
                    // animated webp may need a second try with smaller bounds
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(source.getAbsolutePath(), opts);
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = Math.max(1, Math.min(opts.outWidth, opts.outHeight) / 640);
                    bmp = BitmapFactory.decodeFile(source.getAbsolutePath(), opts);
                }
                if (bmp == null) {
                    notice(dialogId, "⛔️ استیکر قابل تبدیل نبود");
                    return;
                }
                File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "drgram_photo_" + System.currentTimeMillis() + ".jpg");
                FileOutputStream fos = new FileOutputStream(out);
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                fos.close();
                if (!bmp.isRecycled()) {
                    bmp.recycle();
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        SendMessagesHelper.prepareSendingPhoto(
                                AccountInstance.getInstance(account),
                                out.getAbsolutePath(), null, null,
                                dialogId, null, null, null, null,
                                null, null, null, 0, null, null,
                                true, 0, 0, 0, false, null, null, 0, 0, 0, null);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                });
            } catch (Throwable t) {
                FileLog.e(t);
                notice(dialogId, "⛔️ تبدیل به عکس ناموفق بود");
            }
        });
    }

    private static void sendSavedPhoto(int account, long saved, File target, long notifyDialog) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SendMessagesHelper.prepareSendingPhoto(
                        AccountInstance.getInstance(account),
                        target.getAbsolutePath(), null, null,
                        saved, null, null, null, null,
                        null, null, null, 0, null, null,
                        true, 0, 0, 0, false, null, null, 0, 0, 0, null);
                notice(notifyDialog, "✅ در پیام‌های ذخیره‌شده ذخیره شد");
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    private static void sendSavedDocument(int account, long saved, TLRPC.TL_document doc, File target, long notifyDialog) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SendMessagesHelper.getInstance(account).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(doc, null, target.getAbsolutePath(), saved,
                                null, null, null, null, null, null, true, 0, 0, 0, null, null, false));
                notice(notifyDialog, "✅ در پیام‌های ذخیره‌شده ذخیره شد");
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    //endregion

    //region helpers

    private static void poll(File target, Runnable action, long dialogId) {
        pollOnce(target, action, dialogId, 0);
    }

    private static void pollOnce(File target, Runnable action, long dialogId, int attempt) {
        if (target != null && target.exists() && target.length() > 0) {
            AndroidUtilities.runOnUIThread(action);
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            notice(dialogId, "⌛️ دانلود طول کشید؛ لطفاً دوباره امتحان کن");
            return;
        }
        AndroidUtilities.runOnUIThread(() -> pollOnce(target, action, dialogId, attempt + 1), 500);
    }

    private static void notice(long dialogId, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SendMessagesHelper.getInstance(UserConfig.selectedAccount)
                        .sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
            } catch (Throwable ignore) {
            }
        });
    }

    //endregion
}
