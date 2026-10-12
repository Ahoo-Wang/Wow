description = "Wow V10 symbol processing: aggregate models and Manifest fragments from the V10 annotations"

dependencies {
    implementation(project(":api"))
    implementation(project(":manifest"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.ksp.symbol.processing.api)
    testImplementation(libs.kotlin.compile.testing)
    testImplementation(libs.ksp.symbol.processing)
    // The tests compile the generated models; the 9.x types they reject are stubs (NineXStubs), never 9.x wow-api.
    testImplementation(project(":kernel"))
    testImplementation(project(":reactor"))
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
