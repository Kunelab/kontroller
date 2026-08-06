# R8 configuration.
#
# There is deliberately almost nothing here. The app has no third-party dependencies and
# no reflection, so R8's defaults plus the AGP-generated rules are enough.
#
# This file used to contain `-keep class **`, `-keepclassmembers class *{*;}` and
# `-keepattributes *`, which told R8 to keep every class and every member. That kept the
# whole of kotlin-stdlib in the APK: classes.dex was 2.19 MB of a 2.32 MB APK. Removing
# those three lines takes the APK to ~330 KB. Do not add blanket keep rules back -- if
# something is stripped that should not be, keep that one thing by name.
#
# `-dontwarn **` was removed for the same reason: it silenced every warning R8 emits,
# including the ones worth reading.

# Strip verbose/debug/info logging from release builds. Log.w and Log.e are kept: those
# report genuine failures (a HID report that would not send, a lost registration).
# This also removes the string concatenation that builds their arguments, which matters
# because some of these calls sit in the touch path.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers so release stack traces stay readable, but drop the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
