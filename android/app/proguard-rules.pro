# 1. Keep JNA core classes, methods, and fields (Prevents UnsatisfiedLinkError on Pointer.peer)
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

# 3. Keep LazySodium & Native JNI Bridges
-keep class com.goterl.lazysodium.** { *; }
-keep interface com.goterl.lazysodium.** { *; }
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }
-keepclasseswithmembernames class com.teilen.app.** {
    native <methods>;
}

# 4. Keep WebRTC Native & Java Classes
-keep class org.webrtc.** { *; }

# 5. Keep Kotlin Metadata & Serialization Models
-keepattributes *Annotation*,InnerClasses,Signature,Exceptions
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-keep class com.teilen.app.** { *; }