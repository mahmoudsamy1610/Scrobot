// Compiles the app's Kotlin sources against the Android 16 framework jar without the
// Android Gradle Plugin. Used by tools/build-offline.sh; normal builds use the root project.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "scrobot-offline"
