# KestrelVPN (خلاصه فارسی)
کلاینت VPN اندروید (Kotlin + Compose + هسته sing-box) که اشتراک عمومی را می‌گیرد، سرورها را با تست پینگ‌محور رتبه‌بندی می‌کند و به بهترین سرور وصل می‌شود.

## ساخت
1. `bash scripts/build-libbox.sh` (نیاز به Go؛ فایل `app/libs/libbox.aar` را می‌سازد)
2. فایل‌های `local.properties.example` و `keystore.properties.example` را کپی و پر کنید.
3. `./gradlew testDebugUnitTest assembleDebug`
4. مقدار `SubscriptionConfig.DEFAULT_SUBSCRIPTION_URL` را روی مخزن خودتان بگذارید.

## نکات مهم
- پروژه هنوز کامپایل و روی گوشی تست نشده؛ قبل از انتشار `docs/DEVICE_TEST_CHECKLIST.md` را اجرا کنید.
- اشتراک پیش‌فرض یک لیست عمومی شخص ثالث است؛ برای کار حساس از لینک اشتراک خودتان استفاده کنید (`SECURITY.md`).
- ساختار کد: `docs/ARCHITECTURE.md`
