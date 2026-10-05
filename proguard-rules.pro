# ExoPlayer
-keep class androidx.media3.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# Room
-keep class androidx.room.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.load.ImageLoaderFactory
-keep class * extends com.bumptech.glide.GeneratedAppGlideModule

# Keep BuildConfig
-keep class com.portalstream.app.BuildConfig { *; }
