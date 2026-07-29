# R8 / ProGuard rules for the release build.
#
# Most of the libraries used here ship their own consumer rules, but the entries below
# protect the reflection-dependent surfaces this app relies on: Hilt's generated
# components, Room's generated DAO/database implementations, and the Moshi models used
# for HubSpot API parsing. Without these, release builds compile cleanly and then fail at
# runtime with ClassNotFoundException or a null adapter.

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# WorkManager
# ---------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker { *; }

# ---------------------------------------------------------------------------
# Moshi (reflection + codegen adapters for HubSpot request/response models)
# ---------------------------------------------------------------------------
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier @interface *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep class **JsonAdapter { *; }
-keepnames @com.squareup.moshi.JsonClass class *
-dontwarn org.jetbrains.annotations.**

# Keep the app's own data-transfer models used by Moshi.
-keep class com.digiroth.smsfilter.data.remote.** { *; }

# ---------------------------------------------------------------------------
# Retrofit / OkHttp
# ---------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# ---------------------------------------------------------------------------
# Strip debug logging from release builds.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
