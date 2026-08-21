# Proguard rules for MeshWhisper
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# SQLCipher
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# Room & Data Models
-keep class com.meshwhisper.app.data.model.** { *; }
-keep class com.meshwhisper.app.data.dao.** { *; }
-keep class com.meshwhisper.app.protocol.** { *; }
-keep class com.meshwhisper.app.crypto.** { *; }

