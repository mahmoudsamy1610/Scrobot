import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
}

val androidJar = providers.gradleProperty("androidJar").get()
val genDir = providers.gradleProperty("genDir").get()

sourceSets {
    main {
        java.srcDirs("../../app/src/main/java", genDir)
        kotlin.srcDirs("../../app/src/main/java")
    }
}

dependencies {
    compileOnly(files(androidJar))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // dx (the only dexer available offline) understands plain classes, not invokedynamic.
        freeCompilerArgs.addAll(
            "-Xlambdas=class", "-Xsam-conversions=class",
            "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions",
        )
    }
}

tasks.register<Copy>("copyRuntime") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("runtime"))
}
