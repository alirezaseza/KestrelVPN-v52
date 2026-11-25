# --- sing-box / gomobile (libbox.aar) ---
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**

# --- Reflection bridge ---
-keep class com.kestrel.vpn.engine.core.SingBoxProxyCore { *; }

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kestrel.vpn.**$$serializer { *; }
-keepclassmembers class com.kestrel.vpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.kestrel.vpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room & WorkManager ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters, ...);
}
-dontwarn androidx.room.paging.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Strip verbose logs in release ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
