// Rewrites invokedynamic lambdas into ordinary classes, because the only dexer available offline
// (dx) cannot desugar them and Android's runtime has no working LambdaMetafactory.
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
rootProject.name = "scrobot-desugar"
