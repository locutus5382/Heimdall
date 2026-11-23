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

# M1.4g: the separate Compose UI test APK calls Arrangement.INSTANCE (default
# Column/Row arrangement). The minified target APK had R8-renamed
# androidx.compose.foundation.layout.Arrangement -> q.i and stripped its
# INSTANCE field; this produced NoSuchFieldError q.i.INSTANCE in the focused
# editor test. Release/releaseQa mapping independently confirms q.i is
# androidx.compose.foundation.layout.Arrangement.
# Keep the object + nested layout interfaces in leanDebug ONLY; do not keep
# unrelated Foundation Text or all Foundation UI, and do not alter production.
-keep class androidx.compose.foundation.layout.Arrangement { *; }
-keep class androidx.compose.foundation.layout.Arrangement$* { *; }

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

# M1.4g: independently compiled Compose UI tests invoke the Activity Compose
# setContent extension from their test APK, even though optimized app code
# normally calls an R8-renamed copy. Keep the public bridge in the QA target.
# A missing binary name caused NoSuchMethodError on ComponentActivityKt.
# Preserve the receiver type in the independently compiled setContent ABI.
# Keeping only the facade is insufficient if R8 renames ComponentActivity.
-keep class androidx.activity.ComponentActivity { *; }
-keep class androidx.activity.compose.ComponentActivityKt { *; }

# M1.3 tests instantiate Activity-scoped ViewModels through the *public*
# ViewModelProvider binary API. Optimized app execution may inline/eliminate
# it, but androidTest runs in the target process and still links that name.
# Preserve this small AndroidX Lifecycle surface in the testable QA build.
# Release/releaseQa remain governed only by the production rules.
-keep class androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.ViewModelProvider { *; }
-keep class androidx.lifecycle.ViewModelProvider$* { *; }
-keep class androidx.lifecycle.ViewModelStore { *; }
-keep class androidx.lifecycle.ViewModelStoreOwner { *; }
-keep class androidx.lifecycle.ViewModelProvider$Factory { *; }
