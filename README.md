# Mir2Ray

یک کلاینت VPN ساده برای اندروید، بر پایه [v2rayNG](https://github.com/2dust/v2rayNG) با هسته [Xray](https://github.com/XTLS/Xray-core)

[![API](https://img.shields.io/badge/API-21%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![GitHub Releases](https://img.shields.io/github/downloads/desmondep/Mir2Ray/total?logo=github)](https://github.com/desmondep/Mir2Ray/releases)

---

## دانلود

| نوع APK | لینک دانلود |
|---------|------------|
| **Universal (پیشنهادی)** | [دانلود](https://github.com/desmondep/Mir2Ray/releases/latest/download/v2rayNG_1.10.32-fdroid_universal.apk) |
| ARM64 (اکثر گوشی‌ها) | [دانلود](https://github.com/desmondep/Mir2Ray/releases/latest/download/v2rayNG_1.10.32-fdroid_arm64-v8a.apk) |
| ARMv7 | [دانلود](https://github.com/desmondep/Mir2Ray/releases/latest/download/v2rayNG_1.10.32-fdroid_armeabi-v7a.apk) |
| x86_64 | [دانلود](https://github.com/desmondep/Mir2Ray/releases/latest/download/v2rayNG_1.10.32-fdroid_x86_64.apk) |
| x86 | [دانلود](https://github.com/desmondep/Mir2Ray/releases/latest/download/v2rayNG_1.10.32-fdroid_x86.apk) |

> اگر نمی‌دانید کدام APK را دانلود کنید، **Universal** را بزنید.

---

## ویژگی‌ها

### رابط ساده سه‌دکمه‌ای
Mir2Ray رابط پیچیده v2rayNG را به سه دکمه ساده تبدیل کرده:

| دکمه | عملکرد |
|-------|--------|
| **Give New Configs** | دریافت کانفیگ‌های جدید از سابسکریپشن، تست خودکار، حذف کانفیگ‌های بد |
| **Optimize** | بهینه‌سازی مجدد کانفیگ‌ها، حذف کندها، انتخاب بهترین |
| **Connect / Disconnect** | اتصال و قطع VPN |
| **Next Config ⏭** | (فقط هنگام اتصال) رد شدن به کانفیگ بعدی و حذف فعلی |

### سوییچ خودکار
- هر ۱۲ ثانیه پینگ واقعی تست می‌شود
- اگر پینگ بالای 500ms یا timeout باشد (تایید دوبار)، کانفیگ فعلی حذف و به بعدی سوییچ می‌شود
- دکمه **Next Config ⏭** برای سوییچ دستی بدون انتظار

### تنظیم تعداد تست موازی
- روی دکمه **Optimize** نگه‌دارید (Long Press)
- گزینه‌ها: `20`، `30`، `40`، `50`، `60`
- مقدار پیش‌فرض: `30`

---

## نحوه استفاده

1. APK را از جدول بالا دانلود و نصب کنید
2. **Give New Configs** بزنید — کانفیگ‌ها خودکار دریافت و تست می‌شوند
3. **Connect** بزنید — وصل می‌شوید
4. اگر کانفیگ فعلی مناسب نبود، **Next Config ⏭** بزنید

> اگر پیام Play Protect نشان داده شد، طبیعی است — فایل را فقط از Release رسمی همین ریپو دانلود کنید.

---

## هشدار امنیتی
- فقط از [صفحه Release رسمی](https://github.com/desmondep/Mir2Ray/releases) دانلود کنید
- SHA256 فایل را قبل از نصب بررسی کنید

---

## تغییرات نسخه فعلی (v1.10.32-r3)

- ✅ **رفع حذف خودکار کانفیگ‌ها**: سوییچ خودکار دیگر کانفیگ حذف نمی‌کند، فقط به بعدی سوییچ می‌کند
- ✅ **دکمه Next Config ⏭**: سوییچ دستی به کانفیگ بعدی و حذف فعلی هنگام اتصال
- ✅ **رفع مشکل اینترنت روی گوشی**: `libtun2socks.so` برای ARM64 ساخته شد
- ✅ سوییچ خودکار با تایید دوبار پینگ (بدون حذف)
- ✅ محافظت از race condition در دکمه اتصال
- ✅ آیکون نوتیفیکیشن M
- ✅ موتور تانل tun2socks-first با fallback به HEV
- ✅ پروگرس‌بار برای Give و Optimize
- ✅ تست Delay موازی با انتخاب کاربر

---

## ساخت از سورس

```bash
cd V2rayNG
./gradlew :app:assembleFdroidDebug
```

APKها در مسیر `V2rayNG/app/build/outputs/apk/fdroid/debug/` ساخته می‌شوند.

---

## لایسنس

[GPL-3.0](LICENSE)
For a quick start, read guide for [Go Mobile](https://github.com/golang/go/wiki/Mobile) and [Makefiles for Go Developers](https://tutorialedge.net/golang/makefiles-for-go-developers/)

v2rayNG can run on Android Emulators. For WSA, VPN permission need to be granted via
`appops set [package name] ACTIVATE_VPN allow`
