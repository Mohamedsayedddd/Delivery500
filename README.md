# Delivery — تطبيق Android (WebView)

تطبيق Android بسيط (Kotlin) يعرض موقع:
`https://deliveryy.infinityfreeapp.com/login.php`
داخل WebView، دون إعادة بناء الموقع أو نسخة محلية منه — التطبيق يعتمد بالكامل على الموقع الحي على الإنترنت.

**Package name:** `com.delivery.app`

## المزايا المطبّقة

- فتح الموقع مباشرة عند التشغيل داخل WebView فقط (بدون Chrome/متصفح خارجي).
- كل روابط الموقع (بأي دومين HTTPS/HTTP) تبقى داخل WebView؛ الروابط غير المتصفّحية فقط
  (`tel:`, `mailto:`, `sms:`, `whatsapp:`, `geo:`, `intent:` ...) تُفتح بتطبيق خارجي لأنها فعلاً تحتاج ذلك.
- JavaScript، DOM Storage (Local Storage)، وقاعدة بيانات WebView مفعّلة بالكامل.
- Cookies وThird-party cookies مفعّلة ويتم حفظها (`CookieManager.flush()`) بحيث تبقى جلسة تسجيل
  الدخول محفوظة بين مرات فتح التطبيق.
- رفع الملفات (`<input type="file">`) مدعوم مع اختيار من المعرض أو التصوير المباشر بالكاميرا.
- صلاحية الكاميرا تُطلب وقت الحاجة فقط (Runtime Permission)، وتُمنح لصفحات الويب فقط بعد موافقة المستخدم.
- زر Back في أندرويد يرجع لصفحة سابقة داخل WebView، وإن لم توجد صفحة سابقة يحتاج ضغطة ثانية خلال
  ثانيتين للخروج من التطبيق (مع رسالة تنبيه).
- شريط تحميل (Progress) أعلى الشاشة + مؤشر تحميل مركزي عند أول فتح.
- شاشة "لا يوجد اتصال بالإنترنت" مع زر "إعادة المحاولة"، ويتم أيضًا فحص الاتصال قبل أي محاولة تحميل.
- سحب للأسفل لتحديث الصفحة (Swipe to Refresh).
- التصميم يستخدم كامل الشاشة (edge-to-edge) مع مراعاة شريط الحالة وشريط التنقل.
- Zoom بالقرص (Pinch-to-zoom) مفعّل بدون أزرار +/- ظاهرة على الشاشة.
- دعم HTTPS بالكامل (`usesCleartextTraffic="false"`).
- تنزيل الملفات من الموقع (فواتير PDF مثلًا) يتم عبر `DownloadManager` النظامي مع إرسال الـ Cookies.

## فتح المشروع في Android Studio

1. افتح Android Studio → **Open** → اختر مجلد المشروع (`Delivery/`).
2. اترك Android Studio يقوم بعملية الـ Gradle Sync تلقائيًا (يحتاج اتصال إنترنت لأول مرة لتنزيل
   Gradle والمكتبات).
3. إن ظهرت رسالة تفيد بعدم وجود `gradle-wrapper.jar`، اختر السماح لـ Android Studio بإنشائه تلقائيًا،
   أو نفّذ من الطرفية داخل مجلد المشروع (إذا كان لديك Gradle مثبت محليًا):
   ```bash
   gradle wrapper --gradle-version 8.7
   ```
4. لتشغيل التطبيق: اختر جهازًا أو محاكيًا ثم اضغط **Run ▶**.

## بناء APK / AAB يدويًا

```bash
# Debug APK (للتجربة السريعة)
./gradlew assembleDebug
# الناتج في: app/build/outputs/apk/debug/app-debug.apk

# Release APK (غير موقّع)
./gradlew assembleRelease
# الناتج في: app/build/outputs/apk/release/app-release-unsigned.apk

# Release AAB لرفعه على Google Play (غير موقّع)
./gradlew bundleRelease
# الناتج في: app/build/outputs/bundle/release/app-release.aab
```

> **ملاحظة:** نسخة الـ Release في هذا المشروع غير موقّعة (unsigned) لتسهيل البناء الأولي. قبل نشر
> التطبيق على Google Play يجب توقيعه بمفتاح (`keystore`) خاص بك — راجع
> [توثيق التوقيع الرسمي من Google](https://developer.android.com/studio/publish/app-signing).

## البناء التلقائي عبر GitHub Actions

يحتوي المشروع على Workflow جاهز في:
`.github/workflows/android-build.yml`

يعمل تلقائيًا عند كل `push` أو `pull request`، ويقوم بـ:
1. تجهيز JDK 17 وAndroid SDK.
2. بناء `assembleDebug` و`assembleRelease` و`bundleRelease`.
3. رفع الملفات الناتجة (APK Debug، APK Release، AAB Release) كـ **Artifacts** في صفحة الـ Workflow
   على GitHub — يمكن تنزيلها مباشرة من تبويب **Actions** بعد انتهاء التشغيل.

لتشغيله يدويًا أيضًا: تبويب **Actions** → اختر الـ Workflow → **Run workflow**.

## هيكل المشروع

```
Delivery/
├── app/
│   ├── src/main/java/com/delivery/app/
│   │   ├── DeliveryApp.kt        # Application class
│   │   └── MainActivity.kt       # كل منطق WebView
│   ├── src/main/res/             # Layout, colors, themes, strings, icons
│   └── src/main/AndroidManifest.xml
├── .github/workflows/android-build.yml
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── gradlew / gradlew.bat
```

## تغيير رابط الموقع مستقبلًا

الرابط معرَّف في مكان واحد فقط:
`app/src/main/res/values/strings.xml` → `site_url`
