# 📱 راهنمای ساخت (Build) پروژه DrGram — نسخه اندروید

DrGram فورک لوکس و شخصی‌سازی‌شده تلگرام اندروید است با هویت بصری **مشکی-طلایی متالیک**.
این سند قدم‌به‌قدم نحوه ساخت APK روی سیستم شخصی خودتان را توضیح می‌دهد.

---

## ۱) پیش‌نیازها

| ابزار | نسخه دقیق | توضیح |
|---|---|---|
| Android Studio | **2025.1.4** یا جدیدتر | شامل JDK سازگار |
| Android SDK | **API 36** | از SDK Manager نصب کنید |
| Android NDK | **27.2.12479018** | دقیقاً همین نسخه (از SDK Manager → SDK Tools → NDK (Side by side) → Show Package Details) |
| CMake | **3.22.1** | از SDK Manager |
| فضای دیسک | حداقل **15GB آزاد** | سورس + ساب‌ماژول‌ها + کش Gradle + خروجی NDK |
| رم | حداقل **16GB** توصیه می‌شود | بیلد NDK سنگین است |

> 💡 نکته: مخزن فعلی شامل `release.keystore` و `google-services.json` نمایشی (dummy) است؛
> یعنی برای **تست و بیلد محلی** به هیچ سرویس خارجی نیاز نیست و مستقیم می‌توانید بیلد بگیرید.
> برای انتشار عمومی باید فایل‌های خودتان را جایگزین کنید.

---

## ۲) دریافت سورس کامل (با ساب‌ماژول‌ها)

پوشه فعلی `telegram-android` در سندباکس **بدون ساب‌ماژول‌ها** است (برای صرفه‌جویی در فضا حذف شده‌اند).
روی سیستم خودتان یکی از دو راه زیر:

**راه الف — کلون کامل (پیشنهادی):**
```bash
git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git DrGram
cd DrGram
```

**راه ب — اگر سورس را بدون ساب‌ماژول کپی کردید:**
```bash
cd DrGram
git submodule init
git submodule update --init --recursive --depth=1
```

ساب‌ماژول‌های ضروری (۱۵ مورد): `ffmpeg`, `td`, `boringssl`, `libvpx`, `dav1d`, `libyuv`,
`openh264`, `absl`, `wamr`, `xiph (ogg/opus/opusfile)`, `tlottie`, `media`, `jlatexmath` و...

سپس تغییرات DrGram را روی آن اعمال کنید (فایل‌های تغییر یافته را از این مخزن کپی کنید —
لیست کامل در بخش ۵).

---

## ۳) باز کردن و بیلد در Android Studio

1. **File → Open** و پوشه ریشه پروژه را انتخاب کنید (دقت: *Open* نه *Import*).
2. صبر کنید Gradle Sync تمام شود (بار اول چند دقیقه طول می‌کشد و حجم زیادی دانلود می‌کند).
3. از منوی **Build Variant** (سمت چپ پایین) ماژول `TMessagesProj_App` را روی
   یکی از واریانت‌های زیر بگذارید:
   - `afatDebug` → APK تست با همه معماری‌ها (پیشنهادی برای شروع)
   - `arm64Debug` → سریع‌ترین بیلد برای گوشی خودتان (فقط arm64)
   - `afatRelease` → خروجی نهایی امضاشده
4. گوشی را با USB وصل و **USB Debugging** را فعال کنید → دکمه ▶️ (Run).

**ساخت APK از خط فرمان:**
```bash
./gradlew :TMessagesProj_App:assembleAfatDebug
# خروجی: TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
```

> ⚠️ اگر رم سیستم ۸GB یا کمتر است، در `gradle.properties` مقدار
> `org.gradle.jvmargs=-Xmx8g` را به `-Xmx4g` کاهش دهید و `org.gradle.parallel=false` کنید.

---

## ۴) تنظیمات اختصاصی DrGram (اختیاری اما توصیه‌شده)

### الف) هویت اپ در سرویس‌های تلگرام
1. از [my.telegram.org](https://my.telegram.org) یک **api_id / api_hash** برای اپ خودتان بگیرید.
2. در `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`
   مقادیر `TELEGRAM_APP_ID` و `TELEGRAM_APP_HASH` را جایگزین کنید.
3. در [console.firebase.google.com](https://console.firebase.google.com) یک پروژه بسازید،
   دو اپ با شناسه‌های `org.telegram.messenger` و `org.telegram.messenger.beta` اضافه کنید،
   **Firebase Messaging** را فعال کنید و `google-services.json` را در پوشه `TMessagesProj/`
   جایگزین کنید (برای نوتیفیکیشن پوش لازم است).

### ب) امضای شخصی (Release)
1. یک keystore بسازید:
   ```bash
   keytool -genkey -v -keystore release.keystore -alias drgram -keyalg RSA -keysize 2048 -validity 10000
   ```
2. فایل را در `TMessagesProj/config/` بگذارید و در `gradle.properties` مقادیر
   `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_STORE_PASSWORD` را پر کنید.

### ج) تغییر پکیج‌نیم (در صورت تمایل)
در `gradle.properties` مقدار `APP_PACKAGE=org.telegram.messenger` را می‌توانید به مثلاً
`com.drgram.messenger` تغییر دهید (سیستم بیلد تلگرام از همین متغیر استفاده می‌کند).
⚠️ اگر پکیج را عوض کردید، `google-services.json` و api_id باید با پکیج جدید ساخته شوند.

---

## ۵) تغییرات اعمال‌شده DrGram (Stage 1)

| فایل | تغییر |
|---|---|
| `TMessagesProj/src/main/res/values/strings.xml` | نام اپ → **DrGram** |
| `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java` | ۲۸ رنگ پیش‌فرض آبی → پالت طلایی متالیک (`#F5B90D` و خانواده آن) |
| `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java` | accent پیش‌فرض تم Blue → طلایی؛ گرادیان پیام‌های خروجی تم‌های Dark Blue/Night → طلایی؛ پس‌زمینه تم Night → مشکی-طلایی |
| `TMessagesProj/src/main/assets/*.attheme` | ۹۳۴ رنگ در ۵ تم داخلی با تبدیل هوشمند hue از آبی به طلایی (رنگ‌های نمودارها عمداً دست‌نخورده) |
| `TMessagesProj/src/main/res/mipmap-*/ic_launcher*.png` | آیکون لانچر جدید: **الماس طلایی بر مشکی لوکس** (همه چگالی‌ها) |
| `TMessagesProj/src/main/res/mipmap-*/icon_foreground*.png` | فورگراند adaptive icon الماس طلایی |
| `TMessagesProj/src/main/res/drawable/icon_background*.xml` | پس‌زمینه adaptive icon → گرادیان مشکی گرم |
| `TMessagesProj/src/main/res/drawable/tg_splash_320.xml` | دایره اسپلش آبی → طلایی |
| `TMessagesProj/src/main/res/values-night/styles.xml` | پس‌زمینه اسپلش شب → مشکی `#050505` |

> 🔄 بازگشت هر تغییر: `git checkout <file>` یا بازگشت کامل به بیلاین با `git reset --hard`

---

## ۶) تغییرات Stage 2 — تم طلایی/قرمز، آیکون‌های جایگزین، ورود انگلیسی

| فایل | تغییر |
|---|---|
| `Theme.java` + ۱۴ فایل دیگر | نام تم‌های داخلی: **Blue → Gold**، **Dark Blue → Red** (تم پیش‌فرض طلایی + تم دوم قرمز) |
| `Theme.java` (بلوک DrGram) | گرادیان پیام خروجی قرمز + والپیپر مشکی-قرمز برای تم Red |
| `assets/darkblue.attheme` | بازسازی با hue-remap آبی → قرمز (`#E63B52`) |
| `res/mipmap-*/icon_{2..6}*` | ۶ آیکون جایگزین: Black Gold / Black Red / Onyx / Royal / Crimson / Eclipse |
| `LauncherIconController` + `AndroidManifest` | انتخابگر آیکون داخلی اپ (تنظیمات → چت‌ها → App Icon) |
| `LoginActivity` | دکمه کره زمین برای تغییر زبان در صفحه ورود (پیش‌فرض انگلیسی) |

---

## ۷) تغییرات Stage 3 — افکت جرقه، والپیپر طلایی، CI/CD، فارسی‌سازی

| فایل | تغییر |
|---|---|
| `ui/Components/DrGramSparkEffect.java` | **جدید** — سیستم ذرات جرقه طلایی (ستاره چهارپر + درخشش + رد سرعت، گرانش و تلونکله) — مشابه FireworksEffect تلگرام |
| `ui/Components/SizeNotifierFrameLayout.java` | `startDrgramSparks(x,y)` + رسم اورلی جرقه در `dispatchDraw` (روی همه چیز، مثل کانفتی) |
| `ui/ChatActivity.java` | تریگر انفجار جرقه روی دکمه ارسال در `onMessageSend` (فقط ارسال فوری) |
| `messenger/SharedConfig.java` | `drgramSendSparks` (پیش‌فرض روشن) + `toggleDrgramSendSparks()` |
| `ui/ThemeActivity.java` | بخش **DrGram Premium** در تنظیمات چت با سوییچ «جرقه هنگام ارسال» |
| `ui/ActionBar/Theme.java` | شاخه Gold home: **والپیپر پیش‌فرض مشکی-طلایی** (`#0B0A08 → #171207/#2E220A/#0F0C06`) + حباب خروجی طلایی متالیک؛ رنگ‌های پیش‌نمایش تم Gold/Red مشکی‌شدند |
| `messenger/LocaleController.java` | گارد برندینگ: رشته‌های `AppName`/`AppNameBeta` هرگز از رشته‌های ابری (تلگرام) بازنویسی نمی‌شوند |
| `res/values/strings.xml` | رشته‌های جدید: `DrGramPremium`, `SparksOnSend`, `SparksOnSendInfo`, `SparksOnSendHint` |
| `res/values-fa/strings.xml` | **جدید** — فارسی‌سازی: برند (درگرم)، پریمیوم، آیکون‌ها، نام تم‌ها، صفحه ورود، منوها و تنظیمات (~۸۵ رشته) |
| `.github/workflows/build-apk.yml` | **جدید** — بیلد خودکار APK با GitHub Actions (بخش ۸) |

**نحوه کار افکت جرقه:** با هر ارسال پیام متنی، یک انفجار ۲۴–۳۸ ذره‌ای از دکمه ارسال شلیک می‌شود:
فلش درخشش اولیه + ذرات نقطه‌ای با هاله + ستاره‌های چهارپر چرخان + ردهای سرعت — با گرانش، اصطکاک هوا و چشمک‌زن بودن (twinkle). انیمیشن تا تخلیه کامل ذرات (~۱ ثانیه) ادامه دارد و دقیقاً با الگوی `FireworksEffect` تلگرام روی همان canvas رسم می‌شود. خاموش/روشن از **تنظیمات → چت‌ها → درگرم پریمیوم → جرقه هنگام ارسال**.

---

## ۸) CI/CD — بیلد خودکار APK با GitHub Actions

فایل `.github/workflows/build-apk.yml` به مخزن اضافه شده است.

### راه‌اندازی
1. سورس (با کامیت‌های DrGram) را به یک ریپازیتوری GitHub پوش کنید:
   ```bash
   cd telegram-android
   git remote add origin https://github.com/<USER>/<REPO>.git
   git push -u origin master --tags
   ```
   ⚠️ ریپو باید **private** باشد (سورس تلگرام + keystore).

2. دو روش بیلد:
   - **دستی**: تب Actions → «DrGram APK Build» → Run workflow → انتخاب `release` یا `debug`
   - **خودکار با تگ**: هر تگ نسخه‌ای پوش کنید، Release + APK ساخته می‌شود:
     ```bash
     git tag v12.10.6-drgram.3
     git push origin v12.10.6-drgram.3
     ```

### مشخصات بیلد CI
| مورد | مقدار |
|---|---|
| رانر | `ubuntu-latest` |
| JDK | 17 (Temurin) |
| SDK | platform 36 + build-tools 36.0.0 |
| NDK | 27.2.12479018 (نصب خودکار در ورک‌فلو) |
| CMake | 3.22.1 |
| زیرماژول‌ها | `submodules: recursive` (ffmpeg, td, boringssl و…) |
| امضا | `TMessagesProj/config/release.keystore` (موجود در مخزن) |
| خروجی | `DrGram-v<نسخه>-afat-release.apk` — ۴ معماری (armv7, arm64, x86, x64) |
| مدت تقریبی | ۳۵–۷۰ دقیقه (بار اول بیشتر، به‌خاطر کش Gradle سریع‌تر می‌شود) |

دانلود خروجی: تب **Actions** → اجرای بیلد → بخش **Artifacts** (و برای تگ‌ها: بخش **Releases**).

> 💡 keystore پیش‌فرض موجود در مخزن، keystore تستی عمومی تلگرام است؛ برای انتشار عمومی، keystore شخصی بسازید و مقادیر `gradle.properties` را عوض کنید.

---

## ۹) نقشه راه مراحل بعدی (پیشنهاد)

1. **گلس‌مورفیسم اندروید**: پول `LiquidGlassEffect` خود تلگرام را روی نوار اکشن فعال کنید (SharedConfig.useNewBlur)
2. **افکت‌های بیشتر**: جرقه روی واکنش‌ها، موج طلایی هنگام ارسال استوری
3. **فارسی‌سازی عمیق‌تر**: تکمیل ترجمه‌ها یا بستن رشته‌های ابری برای کل UI
4. **F-Droid / توزیع**: انتشار APK از طریق Releases یا مکمن
