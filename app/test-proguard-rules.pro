# R8-only Android instrumentation variant: Error Prone annotations refer to
# javax.lang.model APIs present in a JDK, not on Android. The annotations are
# compile-time metadata and are not executed by tests on device.
# Do not disable missing-class warnings for app code.
-dontwarn javax.lang.model.element.Modifier
