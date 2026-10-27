description = "Wow V10 architecture rules: module graph, forbidden dependencies and compatibility markers"

// The rules scan the build scripts and production sources of the V10 modules, and the settings and every build script
// for the module graph; declare them as inputs so a change there re-runs the tests instead of reusing a cached result.
val moduleDirs = listOf(
    "api/annotations", "api/api", "api/manifest", "api/query-api", "core/spi", "core/kernel", "core/runtime",
    "core/reactor", "query/query", "backends/postgres", "backends/mongo", "backends/kafka", "backends/clickhouse",
    "backends/elasticsearch",
    "edge/openapi", "edge/http", "edge/client", "edge/cosec", "edge/observability", "host/spring-boot", "extensions/cosid", "extensions/models", "migration/migrate-data", "compiler/compiler",
    "compiler/gradle-plugin", "compiler/manifest-tools", "test/test-dsl", "test/tck", "query/query-tck", "test/tck-containers",
) + listOf("catalog", "basket", "ordering", "inventory", "payment").flatMap { context ->
    listOf("api", "domain", "server").map { "example/eshop/$context/$context-$it" }
} + listOf("example/eshop/e2e", "example/quickstart", "example/java-smoke", "test/benchmarks/server") +
    // The migration rehearsal's host, whose configuration the 9.x-key rule reads too (review R5.C-10).
    listOf("test/migration-rehearsal") +
    listOf("ops-api", "ops-domain", "ops-server").map { "platform/ops/$it" } +
    listOf("api", "domain", "server").map { "platform/view-store/view-store-$it" } +
    listOf("api", "domain", "server").map { "platform/analytics/catalog/$it" }
tasks.named<Test>("test") {
    systemProperty("wow.repository.root", projectDir.toPath().relativize(rootDir.toPath()).toString())
    inputs.files(
        moduleDirs.flatMap {
            listOf(
                rootProject.layout.projectDirectory.dir("$it/src/main"),
                rootProject.layout.projectDirectory.file("$it/build.gradle.kts"),
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleSources")
    // The ABI dumps: no data class in some (decisions D227, D228), no nested class without its outer one in any.
    inputs.files(moduleDirs.map { rootProject.layout.projectDirectory.dir("$it/api") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("abiDumps")
    // The design-ID rule reads every production source and README of the repository, wherever its project lives, and
    // the user documentation.
    inputs.files(
        rootProject.fileTree(rootDir) {
            include("**/src/main/**/*.kt", "**/src/main/**/*.java", "**/README.md", "documentation/docs/**/*.md")
            exclude("**/build/**", "**/node_modules/**", ".git/**", ".gradle/**", ".claude/**", "documentation/designs/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("designIdSources")
    inputs.files(rootProject.file("settings.gradle.kts"), rootProject.subprojects.map { it.buildFile })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("buildScripts")
}
