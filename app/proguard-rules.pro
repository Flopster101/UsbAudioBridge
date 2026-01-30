# ProGuard / R8 rules (minimal)
# Basic keeps for Kotlin, JNI, Parcelable, and enum reflection.

-keepattributes *Annotation*
-keepclassmembers class kotlin.Metadata { *; }

-keepclasseswithmembers class * {
    native <methods>;
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}