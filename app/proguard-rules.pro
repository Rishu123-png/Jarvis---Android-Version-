# J.A.R.V.I.S. — release shrinker rules.
#
# Minification is on. Everything the app calls by name at runtime has to survive R8, and this file is
# the complete list of what that is. The rule of thumb used here: keep the *boundary* (a library that
# reflects on its own classes, a JNI bridge, a manifest component), never the app's own code — the
# app's own code is exactly what shrinking is for.

# ---------------------------------------------------------------------------------------------
# Attributes
# ---------------------------------------------------------------------------------------------

# Annotations and signatures are read at runtime by several libraries; without them reflection
# silently finds nothing and the failure appears far from its cause.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, MethodParameters

# Keep the line numbers of crashes: a stack trace with no names is not a stack trace.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------------------
# Vosk — the offline wake word
# ---------------------------------------------------------------------------------------------

# Vosk is JNA in a trench coat: it builds a proxy for a native library and calls it through
# reflection, so every class it names is looked up by string.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { *; }
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ---------------------------------------------------------------------------------------------
# Shizuku — the one companion JARVIS cannot do without
# ---------------------------------------------------------------------------------------------

-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class com.jarvis.assistant.shizuku.** { *; }
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**

# ---------------------------------------------------------------------------------------------
# Platform pieces that are found by name, not by reference
# ---------------------------------------------------------------------------------------------

# The accessibility service, the voice interaction service and the session are instantiated by the
# system from the manifest; R8 cannot see that and would rename them out of existence.
-keep class * extends android.accessibilityservice.AccessibilityService
-keep class * extends android.service.voice.VoiceInteractionService
-keep class * extends android.service.voice.VoiceInteractionSession
-keep class * extends android.service.voice.VoiceInteractionSessionService
-keep class * extends android.service.notification.NotificationListenerService
-keep class * extends android.service.quicksettings.TileService
-keep class * extends android.appwidget.AppWidgetProvider
-keep class * extends android.content.BroadcastReceiver

# The AIDL interface Shizuku's user service is described by.
-keep class com.jarvis.assistant.shizuku.IUserService { *; }
-keep class * implements android.os.IInterface { *; }

# ---------------------------------------------------------------------------------------------
# Kotlin and Compose
# ---------------------------------------------------------------------------------------------

# Lambdas and companion objects survive by default under AGP's rules; the metadata is what R8 needs
# in order not to trip over `@Composable`-generated classes.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ---------------------------------------------------------------------------------------------
# Warnings from optional code paths
# ---------------------------------------------------------------------------------------------

# Coil and ML Kit both carry optional integrations this app never calls.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
