# ───────────────────────── kotlinx.serialization ─────────────────────────
# Generated $$serializer classes + Companion.serializer() are looked up reflectively at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Our own @Serializable models (domain + remote DTOs): keep their synthetic serializer + Companion.
-keep,includedescriptorclasses class com.dschat.app.**$$serializer { *; }
-keepclassmembers class com.dschat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.dschat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ───────────────────────── Rhino (run_javascript sandbox) ─────────────────────────
# Rhino resolves host classes/methods by reflection; renaming/stripping breaks the JS engine.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**

# ───────────────────────── JSch fork (com.github.mwiede) — SSH/SFTP "control PC" ─────────────────────────
# Crypto algorithm implementations are instantiated by reflection from their class names.
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jzlib.** { *; }
-dontwarn com.jcraft.jsch.**
# Optional JCE providers JSch may probe for; absent on Android but referenced.
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**

# ───────────────────────── PdfBox-Android (offline PDF) ─────────────────────────
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-dontwarn javax.**

# ───────────────────────── Networking (OkHttp / Okio) ─────────────────────────
# These ship consumer rules; the dontwarns silence optional-TLS-provider references on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ───────────────────────── Jsoup (HTML parsing) ─────────────────────────
-dontwarn org.jsoup.**

# ───────────────────────── ML Kit ─────────────────────────
# ML Kit bundles its own keep rules; just silence optional references.
-dontwarn com.google.mlkit.**
