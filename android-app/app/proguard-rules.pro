# Stable Obfuscation and Shrinking
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# Basic Obfuscation
-allowaccessmodification
-optimizationpasses 3

# Keep Entry Points only
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService

# Keep Accessibility service and Device Admin
-keep class com.adex.app.service.AppMonitorAccessibilityService { *; }
-keep class com.adex.app.admin.ADexDeviceAdminReceiver { *; }

# Keep used annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod

# Strip debug info
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Handle Room
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.Room
-keep class androidx.room.RoomDatabase
-keep class * { @androidx.room.Dao *; }
-keep class * { @androidx.room.Entity *; }

# Handle OkHttp/Okio
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Handle Coroutines Extended
-keepnames class kotlinx.coroutines.internal.MainDispatcherLoader { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepnames class kotlinx.coroutines.android.HandlerContext { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainLifecycleControl { *; }
-keep class kotlinx.coroutines.internal.AndroidMainDispatcherFactory { *; }
-keep class kotlinx.coroutines.test.internal.TestMainDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineId { *; }

-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,SourceFile,LineNumberTable

# Handle OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Remove all Logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
