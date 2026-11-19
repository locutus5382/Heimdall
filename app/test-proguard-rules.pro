# Instrumentation-only R8 rules.
# These do NOT affect production or leanDebug application shrinking.
# Keep JUnit 4 test classes and reflection-based AndroidX test entry points.
-keep class org.junit.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
# The optimized app can strip Kotlin entrypoints used only by instrumentation.
-keep class kotlin.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class com.locutus5382.heimdall.*Test { *; }
-keep class com.locutus5382.heimdall.*Test$* { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod

# ErrorProne's metadata refers to javac's javax.lang.model types. Android
# runtime never evaluates the annotation processor; suppress only these
# unshipped JDK types in the *instrumentation* shrinker.
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.element.ElementKind
