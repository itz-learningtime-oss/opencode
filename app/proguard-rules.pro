-keep class ai.opencode.cli.terminal.NativePty { *; }
-keep class ai.opencode.cli.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn android.system.**
