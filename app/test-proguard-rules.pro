# Instrumentation-only R8 rules.
# These do NOT affect production or leanDebug application shrinking.
# Keep JUnit 4 test classes and reflection-based AndroidX test entry points.
-keep class org.junit.** { *; }
-keep class androidx.test.** { *; }
-keep class com.locutus5382.heimdall.*Test { *; }
-keep class com.locutus5382.heimdall.*Test$* { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod
