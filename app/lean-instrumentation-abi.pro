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
# Instrumentation also links to Kotlin intrinsics and Compose runtime/UI types.
# The optimized target and separate test APK need a consistent ABI. Limit
# these wider rules to debug-signed QA: production release stays fully shrunk.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# AndroidX Test talks to the host Compose runtime and semantics/UI types.
# Preserve those shared test ABI surfaces, but allow Material3, Foundation,
# animation and unrelated Compose components to shrink normally. If the
# optimized instrumentation suite reveals a missing API, add only that API.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# Separately compiled Android instrumentation calls Heimdall classes and
# constructors by their original binary names. In optimized QA only, retain
# that test-facing ABI: this is NOT included in release R8 rules.
# Without it R8 renames LocalRecordingStore and changes the two-argument
# DukoSessionContextStore constructor, although the normal app still works.
-keep class com.locutus5382.heimdall.** { *; }

# AndroidX test infrastructure references this interface from an otherwise
# unreachable runtime path; test instrumentation shares target class loading.
-keep class com.google.common.util.concurrent.ListenableFuture { *; }

# AndroidX Compose test rule installs a LifecycleOwner through reflection
# in the separately compiled test runner. Preserve this AndroidX class in
# the optimized QA target; production release shrinking is unaffected.
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }

# The separately compiled SAF export instrumentation invokes CreateDocument
# virtual methods directly. R8's optimized QA target previously renamed the
# contract to f.b and removed/renamed createIntent and parseResult, causing
# NoSuchMethodError in BOTH test cases even though normal app export compiles.
# Preserve only this AndroidX contract's binary test ABI in leanDebug; the
# production release rules and actual document-picker behavior stay unchanged.
-keep class androidx.activity.result.contract.ActivityResultContracts$CreateDocument { *; }
