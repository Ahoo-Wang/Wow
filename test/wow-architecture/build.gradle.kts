description = "Wow V10 architecture rules: module graph, forbidden dependencies and compatibility markers"

// The rules scan the build scripts and production sources of the V10 modules; declare them as inputs so a change
// there re-runs the tests instead of reusing a cached result.
val v10ModuleDirs = listOf(
    "api/wow-manifest", "api/wow-query-api", "core/wow-spi", "core/wow-kernel", "core/wow-runtime",
    "core/wow-reactor", "backends/wow-postgres", "edge/wow-http", "host/wow-spring-boot",
    "migration/wow-migrate-data", "compiler/wow-gradle-plugin", "wow-api",
)
tasks.named<Test>("test") {
    systemProperty("wow.repository.root", projectDir.toPath().relativize(rootDir.toPath()).toString())
    inputs.files(
        v10ModuleDirs.flatMap {
            listOf(
                rootProject.layout.projectDirectory.dir("$it/src/main"),
                rootProject.layout.projectDirectory.file("$it/build.gradle.kts"),
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("v10Sources")
}
