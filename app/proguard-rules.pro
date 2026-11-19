# HEIMDALL: keep this deliberately minimal.
# Android Gradle and AndroidX libraries provide their own consumer rules.
# The manifest roots (Activity, Service and TileService) are kept by R8.
# Broad -keep class * rules would defeat the point of shrinking.
# Add targeted keep rules only for verified reflection/serialization failures.

# AndroidJUnitRunner invokes tracing through the app classloader in optimized
# connectedAndroidTest. AndroidX Activity brings tracing transitively, but R8
# otherwise removes it as unused in the app itself. Keep only this tiny API
# family so the optimized APK can be exercised by the real test runner.
-keep class androidx.tracing.** { *; }

# The separate R8-minified AndroidX Test runner calls kotlin.LazyKt through
# the application's class loader. R8 can remove that facade because our app
# does not reference it directly. Keep ONLY the required facade and lazy
# implementation, not the complete Kotlin stdlib (which would inflate APK).
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__* { *; }
