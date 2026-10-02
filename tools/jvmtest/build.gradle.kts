plugins {
    kotlin("jvm") version "2.2.20"
    application
}

sourceSets {
    main {
        kotlin.srcDirs("src")
        kotlin.include(
            "**/HttpServer.kt", "**/Router.kt", "**/ScriptParser.kt", "**/ActionInterpreter.kt",
            "**/ShellBridge.kt", "**/JsBridge.kt",
            "**/StubServer.kt", "**/ParserTest.kt", "**/EngineTest.kt",
        )
        kotlin.srcDir("../../app/src/main/java")
    }
}

dependencies {
    implementation("org.json:json:20250517")
}

application {
    mainClass.set("com.mahmoud.scrobot.StubServerKt")
}
