# نگهداشت کلاس‌های مربوط به تونل
-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.config.** { *; }

# جلوگیری از حذف متدهای بازتابی جاواکریپتو
-keep class javax.crypto.** { *; }
-keepclassmembers class * {
    @javax.crypto.** *;
}