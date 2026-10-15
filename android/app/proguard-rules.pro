
# 1. Keep JNA core classes, methods, and fields (Fixes the Pointer peer crash)
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# 2. Keep all classes/interfaces implementing JNA Library, Structure, or Callback
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure {
    public *;
    <init>(...);
}

# 3. If using Rust UniFFI (common with JNA on Android), keep generated bindings
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }

# 4. Keep any custom JNA bridge classes inside your own package
-keepclasseswithmembernames class com.teilen.app.** {
    native <methods>;
}

# 5. Keep Kotlin Metadata & Data Serialization (if using JSON/Retrofit/Coroutines)
-keepattributes *Annotation*,InnerClasses,Signature,Exceptions
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}