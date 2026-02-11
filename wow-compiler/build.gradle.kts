description = "Wow Symbol Processing"

dependencies {
    implementation(project(":wow-core"))
    implementation(libs.ksp.symbol.processing.api)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation(libs.kotlin.compile.testing)
    testImplementation(libs.ksp.symbol.processing)
    testImplementation(project(":wow-api"))
    testImplementation(project(":wow-apiclient"))
    testImplementation(project(":wow-core"))
    testImplementation(project(":wow-spring"))
    testImplementation("me.ahoo.coapi:coapi-api")
    testImplementation("org.springframework:spring-web")
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
