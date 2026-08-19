# Proguard rules for MeshWhisper
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.meshwhisper.app.data.model.** { *; }
-keep class com.meshwhisper.app.protocol.** { *; }
