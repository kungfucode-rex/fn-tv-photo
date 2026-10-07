-keepattributes *Annotation*, InnerClasses

# OkHttp platform classes referenced only on other platforms.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink is pulled in by androidx.security internals; not used at runtime here.
-dontwarn com.google.crypto.tink.**
