# SIP-GSM Gateway - no proguard rules needed for debug builds
# For release builds, keep SIP and RTP classes:
-keep class com.callagent.gateway.sip.** { *; }
-keep class com.callagent.gateway.rtp.** { *; }
-keep class com.callagent.gateway.gsm.** { *; }

# AndroidX Security (EncryptedSharedPreferences) — Tink crypto primitives use
# reflection to instantiate key/value encryption schemes.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
# Tink references optional Google HTTP / Joda / JSR305 classes not on Android.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn javax.annotation.**
