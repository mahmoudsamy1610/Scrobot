// Runs the app's HttpServer + Router (the Android-free part of the API) on a plain JVM with stub
// endpoints, so the token check, JSON handling and the `hands` client can be tested without a phone.
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
rootProject.name = "scrobot-jvmtest"
