# Manifest components and WorkManager workers are kept by AGP and WorkManager's own rules.
# Strip debug and verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
