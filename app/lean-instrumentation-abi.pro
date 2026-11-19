# HEIMDALL v0.7.1: QA-only AndroidX instrumentation compatibility.
#
# A separate, non-minified androidTest APK is linked against the public
# kotlinx.coroutines facade names (JobKt, BuildersKt, CoroutineScopeKt, etc.).
# R8 normally obfuscates/removes these facades from the lean app because
# Heimdall does not need their original names at runtime. On an instrumented
# app, classes from the target APK are shared with its test classloader;
# AndroidX Compose Test expects those stable binary names.
#
# Keep coroutines' public facade ABI in the debug-signed, instrumented QA
# variant only. Production release retains full R8 optimization.
# This does NOT change any recorder, HUD, database, or analytics logic.
-keep class kotlinx.coroutines.** { *; }
