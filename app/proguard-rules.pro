# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# Gson
-keep class com.bicilona.data.model.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
