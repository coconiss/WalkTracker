# Add project specific ProGuard rules here.

# Keep all data classes in the project. This is important for libraries like Firebase
# that use reflection to serialize/deserialize data.
-keep class com.walktracker.app.model.** { *; }
-keep class com.walktracker.app.viewmodel.** { *; }

# Keep class members for data classes, which are often used with reflection.
-keepclassmembers class * extends kotlin.Metadata { *; }

# Firebase and Google Play Services
-keep class com.google.android.gms.common.api.internal.IStatusCallback { *; }
-keep class com.google.firebase.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.android.gms.internal.** { *; }

# Google Mobile Ads SDK
-keep public class com.google.android.gms.ads.** {
   public *;
}

-keep public class com.google.ads.** {
   public *;
}

# OSMDroid
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }

# Jetpack Compose
-keep class androidx.compose.runtime.Composable { *; }
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep the following for reflection used by some libraries
-keepclassmembers,allowobfuscation class * {
    @com.google.firebase.firestore.PropertyName <methods>;
}

# Keep setters in data classes for Firestore
-keepclassmembers public class com.walktracker.app.model.** {
    <init>();
    void set*(***);
    *** get*();
}


# General rules that are often useful
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
