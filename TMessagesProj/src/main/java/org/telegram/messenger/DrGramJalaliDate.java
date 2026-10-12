/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 21: Solar Hijri (Jalali) calendar formatting for chat and dialog
 * date separators. Pure math — no network, no storage. The toggle lives in
 * the DrGram control center (SharedConfig.drgramJalaliDates).
 */
package org.telegram.messenger;

import java.util.Calendar;
import java.util.Date;

public final class DrGramJalaliDate {

    private static final String[] MONTHS = {
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };

    private static final String[] DIGITS = {"۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹"};

    private DrGramJalaliDate() {
    }

    public static int[] gregorianToJalali(int gy, int gm, int gd) {
        int[] cumulative = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int gy2 = gm > 2 ? gy + 1 : gy;
        int days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + cumulative[gm - 1];
        int jy = -1595 + 33 * (days / 12053);
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            jy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        if (days < 186) {
            return new int[]{jy, 1 + days / 31, 1 + days % 31};
        }
        return new int[]{jy, 7 + (days - 186) / 30, 1 + (days - 186) % 30};
    }

    public static String faDigits(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(DIGITS[c - '0']);
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /** Chat separator style: short inside the current solar year, full otherwise. */
    public static String formatChat(long dateSeconds, boolean checkYear) {
        try {
            long millis = dateSeconds * 1000L;
            int[] now = fromMillis(System.currentTimeMillis());
            int[] d = fromMillis(millis);
            boolean shortForm = checkYear ? now[0] == d[0] : Math.abs(System.currentTimeMillis() - millis) < 31536000000L;
            if (shortForm) {
                return faDigits(d[2] + " " + MONTHS[d[1] - 1]);
            }
            return faDigits(d[2] + " " + MONTHS[d[1] - 1] + " " + d[0]);
        } catch (Throwable t) {
            FileLog.e(t);
            return "";
        }
    }

    /** Dialog list style: today shows the clock, yesterday keeps its word, then solar months. */
    public static String formatDialog(long dateSeconds, boolean withToday) {
        try {
            long millis = dateSeconds * 1000L;
            int[] now = fromMillis(System.currentTimeMillis());
            int[] d = fromMillis(millis);
            if (now[0] == d[0] && now[1] == d[1] && now[2] == d[2]) {
                if (withToday) {
                    return LocaleController.getString(R.string.ShortToday);
                }
                return LocaleController.getInstance().getFormatterDay().format(new Date(millis));
            }
            int[] yesterday = fromMillis(System.currentTimeMillis() - 86400000L);
            if (yesterday[0] == d[0] && yesterday[1] == d[1] && yesterday[2] == d[2]) {
                return LocaleController.getString("Yesterday", R.string.Yesterday);
            }
            if (Math.abs(System.currentTimeMillis() - millis) < 31536000000L) {
                return faDigits(d[2] + " " + MONTHS[d[1] - 1]);
            }
            return faDigits(d[2] + " " + MONTHS[d[1] - 1] + " " + d[0]);
        } catch (Throwable t) {
            FileLog.e(t);
            return "";
        }
    }

    /** Small month/day style used by media calendars. */
    public static String formatSmall(long dateSeconds, boolean full) {
        try {
            int[] now = fromMillis(System.currentTimeMillis());
            int[] d = fromMillis(dateSeconds * 1000L);
            if (!full && now[0] == d[0]) {
                return faDigits(d[2] + " " + MONTHS[d[1] - 1]);
            }
            return faDigits(d[2] + " " + MONTHS[d[1] - 1] + " " + d[0]);
        } catch (Throwable t) {
            FileLog.e(t);
            return "";
        }
    }

    private static int[] fromMillis(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return gregorianToJalali(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }
}
