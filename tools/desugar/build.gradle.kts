plugins {
    kotlin("jvm") version "2.2.20"
    application
}

dependencies {
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-commons:9.9")
}

sourceSets {
    main {
        kotlin.srcDirs("src")
    }
}

application {
    mainClass.set("com.mahmoud.scrobot.desugar.DesugarKt")
}
