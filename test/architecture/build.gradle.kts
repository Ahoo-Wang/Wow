description = "Wow V10 architecture rules: module graph, forbidden dependencies and compatibility markers"

// The rules scan the build scripts and production sources of the V10 modules, and the settings and every build script
// for the module graph; declare them as inputs so a change there re-runs the tests instead of reusing a cached result.
val v10ModuleDirs = listOf(
    "api/api", "api/manifest", "api/query-api", "core/spi", "core/kernel", "core/runtime",
    "core/reactor", "query/query", "backends/postgres", "backends/mongo", "backends/kafka", "backends/clickhouse",
    "edge/openapi", "edge/http", "edge/cosec", "edge/opentelemetry", "host/spring-boot", "extensions/cosid", "migration/migrate-data", "compiler/compiler",
    "compiler/gradle-plugin", "compiler/manifest-tools", "test/test-dsl", "test/tck", "test/tck-containers",
) + listOf("catalog", "basket", "ordering", "inventory", "payment").flatMap { context ->
    listOf("api", "domain", "server").map { "example/eshop/$context/$context-$it" }
} + "example/eshop/e2e" + listOf("ops-api", "ops-domain", "ops-server").map { "platform/ops/$it" }
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
    inputs.files(rootProject.file("settings.gradle.kts"), rootProject.subprojects.map { it.buildFile })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("buildScripts")
}
