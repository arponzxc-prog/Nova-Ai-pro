# Nexa Ai ProGuard / R8 Keep Rules

# Room Entities, DAOs, and Database
-keep class com.example.data.local.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# Models
-keep class com.example.model.** { *; }

# Moshi & Retrofit
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Sherpa-ONNX JNI (optional offline KWS library)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# BroadcastReceivers & Services registered in AndroidManifest.xml
-keep class com.example.assistant.RoutineAlarmReceiver { *; }
-keep class com.example.assistant.BootReceiver { *; }
-keep class com.example.assistant.AssistantService { *; }
-keep class com.example.assistant.AssistantTileService { *; }
-keep class com.example.accessibility.AssistantAccessibilityService { *; }
-keep class com.example.accessibility.AssistantNotificationListenerService { *; }

# Voice engine (Vosk + JNA use reflection/native binding)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
