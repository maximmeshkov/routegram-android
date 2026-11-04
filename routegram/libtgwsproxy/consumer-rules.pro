# JNA resolves the module's flat C ABI by these method names.
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.swing.**
-keep class com.sun.jna.** { *; }
-keep interface com.routegram.wsproxy.PythonLibrary { *; }
