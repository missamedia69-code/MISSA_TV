# ─────────────────────────────────────────────────────────────────────────────
# MISSA TV — règles ProGuard / R8
#
# R8 est actif sur la variante release. Les règles ci-dessous couvrent les
# bibliothèques utilisant la réflexion ou la sérialisation.
# ─────────────────────────────────────────────────────────────────────────────

# --- Modèles sérialisés (kotlinx.serialization) ------------------------------
# Les sérialiseurs générés doivent être conservés.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.missa.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.missa.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.missa.tv.**$$serializer { *; }

# --- Retrofit / OkHttp -------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# --- Hilt / Dagger -----------------------------------------------------------
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# --- Media3 ------------------------------------------------------------------
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# --- Tink (chiffrement de la configuration locale) ---------------------------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Coil --------------------------------------------------------------------
-dontwarn coil3.**

# --- Informations utiles au diagnostic des plantages -------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
