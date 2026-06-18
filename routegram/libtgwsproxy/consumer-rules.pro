# Consumer-правила: едут с :routegram:libtgwsproxy в потребителя (приложение),
# применяются его R8 при минификации (например, buildType standalone).
#
# КРИТИЧНО: JNA маппит методы интерфейса на C-функции .so ПО ИМЕНАМ. Если R8
# переименует StartProxy -> a(), движок не найдётся и обход молча отвалится.

# ─── JNA ───
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.swing.**
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.Library { *; }
-keepclassmembers class * implements com.sun.jna.Library { <methods>; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }

# ─── Наш JNA-биндинг: имена методов = имена C-функций ───
-keep interface com.routegram.wsproxy.ProxyLibrary { *; }
-keep class com.routegram.wsproxy.WsProxyNative { *; }

# ─── Нативные загрузчики ───
-keepclasseswithmembernames class * {
    native <methods>;
}
