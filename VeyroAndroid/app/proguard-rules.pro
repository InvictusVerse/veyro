# Veyro R8 / ProGuard rules.
#
# Keep this file small and justified. Every rule should say why it exists, because a blanket
# keep rule silently disables shrinking for whole packages.

# ── kotlinx.serialization ──────────────────────────────────────────────────────────────────
# Serializers are generated as synthetic members and found reflectively by name.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Coroutines ─────────────────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Ktor (ktor-network only; no HTTP client/server in Veyro) ───────────────────────────────
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**

# ── BouncyCastle ───────────────────────────────────────────────────────────────────────────
# NOTE: there are deliberately NO `-keep class org.bouncycastle.**` rules here.
#
# bcprov-jdk18on is ~12 MB because it carries the full JCA provider plus post-quantum, CMS,
# S/MIME and OpenPGP. Veyro uses the *lightweight API* only (direct class references such as
# X25519Agreement), and never registers BouncyCastleProvider. That distinction is what lets R8
# strip the ~95% we do not touch. Adding a blanket keep rule for the provider packages would
# silently undo that and put 12 MB back into the release APK (ADR-016).
#
# If a crash in release points at a missing BouncyCastle class, the correct fix is to replace the
# reflective usage with a direct reference, not to add a keep rule.
-dontwarn org.bouncycastle.**
# BouncyCastle references desktop-only JDK APIs that do not exist on Android.
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# ── Okio ───────────────────────────────────────────────────────────────────────────────────
-dontwarn okio.**