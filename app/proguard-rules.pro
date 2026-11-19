# HEIMDALL: keep this deliberately minimal.
# Android Gradle and AndroidX libraries provide their own consumer rules.
# The manifest roots (Activity, Service and TileService) are kept by R8.
# Broad -keep class * rules would defeat the point of shrinking.
# Add targeted keep rules only for verified reflection/serialization failures.
