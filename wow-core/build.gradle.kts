description = "Wow Shared Kernel"

dependencies {
    api(project(":wow-api"))
    api("me.ahoo.cosid:cosid-core")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("jakarta.validation:jakarta.validation-api")
    api("com.google.guava:guava")
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    api("io.github.oshai:kotlin-logging-jvm")
    api(kotlin("reflect"))
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    api("io.micrometer:micrometer-core")
    testImplementation(project(":wow-tck"))
}

// DependencyRulesTest scans the production sources of these modules; declare them as inputs so a change there
// re-runs wow-core's tests instead of reusing a cached result.
val architectureRuleModules = listOf("wow-core", "wow-query", "wow-mongo", "wow-redis", "wow-kafka", "wow-elasticsearch")
tasks.named<Test>("test") {
    systemProperty("wow.repository.root", projectDir.toPath().relativize(rootDir.toPath()).toString())
    inputs.files(architectureRuleModules.map { rootProject.layout.projectDirectory.dir("$it/src/main") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("architectureRuleSources")
}
