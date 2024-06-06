description = "Wow Symbol Processing"

dependencies {
    implementation(project(":wow-core"))
    implementation(libs.kspSymbolProcessingApi)
    testImplementation(libs.kotlinCompileTesting)
    testImplementation(libs.kspSymbolProcessing)
    testImplementation(project(":wow-spring"))
    testImplementation("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation("jakarta.validation:jakarta.validation-api")
}

tasks.withType<Test>().all {
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    )
}
