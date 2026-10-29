/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testretry.TestRetryPlugin
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.w3c.dom.Element
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.test.retry)
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    jacoco
}

// The projects at the given paths, for the lists below.
fun includedProjects(vararg paths: String): Set<Project> = paths.map { project(it) }.toSet()

// The repository's test logging, relative to the test JVM's working directory (the project's): a JVM argument is an
// input of the test task, so it is the same in every checkout and the build cache hits across them.
fun Project.testLogbackConfiguration(): String =
    "-Dlogback.configurationFile=${projectDir.toPath().relativize(rootProject.file("config/logback.xml").toPath())}"

val dependenciesProject = project(":wow-dependencies")
// The BOM (`:bom` in bom/bom, artifact wow-bom per D87).
val bomProject = project(":bom")
val bomProjects = setOf(dependenciesProject, bomProject)

// The example, the eShop (D158, D159, D204): built and checked with the modules, never published, and named
// like an application's modules, without the `wow-` prefix of a published artifact. It is five contexts of an API, a domain
// and a server each, and its end-to-end tests, :eshop-e2e.
val eshopProjects = listOf("catalog", "basket", "ordering", "inventory", "payment").flatMap { context ->
    listOf("api", "domain", "server").map { "eshop-$context-$it" }
} + "eshop-e2e"
// The quickstart (D217): the sample of documentation/docs/{en,zh}/start/quickstart.md, one module that is its
// domain and its server, built and tested like the eShop, never published. The Java smoke test (D229 G-8): a domain
// written in Java, built by the compiler and tested with the test DSL, never published.
val exampleNames = eshopProjects + "quickstart" + "java-smoke"
val exampleProjects = includedProjects(*exampleNames.map { ":$it" }.toTypedArray())

val codeCoverageReportProject = project(":code-coverage-report")
// The end-to-end architecture benchmark (decision D224, roadmap M7.5 B.0) under test/benchmarks/: the benchmark
// domain and server and the load generator (the 9.x twin runs from v10-9x-baseline, D262). Applications, like the
// examples: never published, without an ABI dump, and left out of the coverage reports (code-coverage-report reads
// `e2eBenchmarkProjects`). The server builds and checks with the modules (`:check`) as a user of them. The Wow 10 host
// of the blue-green migration rehearsal (roadmap 8c.1, test/migration-rehearsal/) is such an application too: the 9.x
// example ported, which its workflow drives against the 9.x one (migration-rehearsal.yml).
val e2eBenchmarkProjects = includedProjects(":benchmark-server", ":benchmark-load", ":migration-rehearsal")
// The modules (documentation/designs/06-modules.md). Every module builds on JDK 21 (decision D29); these stay
// incubating until their API settles; :architecture (Wow's dependency rules), :routing-spike (spike H2), :lease-spike
// (spike H6) and the examples (:eshop-*, :quickstart, :java-smoke) are never published.
val moduleProjects = listOf(
    "annotations", "api", "manifest", "query-api", "spi", "kernel", "runtime", "reactor", "query", "query-tck",
    "postgres", "mongo", "kafka", "clickhouse", "elasticsearch", "openapi", "http", "client", "cosec",
    "observability", "spring-boot", "cosid", "cocache-api", "cocache",
    "models", "migrate-data", "architecture",
    "test-dsl", "test-query", "test-spring-boot", "tck", "tck-containers", "ops-api", "ops-domain", "ops-server",
    "view-store-api", "view-store-domain", "view-store-server",
    "analytics-catalog-api", "analytics-catalog-domain", "analytics-catalog-server",
    "compiler", "gradle-plugin", "manifest-tools",
    "routing-spike", "lease-spike", "benchmark-server", "migration-rehearsal", "spi-consumer",
).plus(exampleNames).let { names -> includedProjects(*names.map { ":$it" }.toTypedArray()) }

// The contract modules without a third-party runtime dependency of their own (R5.C-4, G8): they import no platform
// but the coroutines BOM :api's coroutines dependency takes its version from, and leave out the SLF4J every other
// module logs with, so an application's domain module that depends on them keeps its own versions of everything.
// wow-cocache-api, the cache of the state views a domain injects (D364), is one too.
val coreContractProjects = includedProjects(":annotations", ":api", ":query-api", ":manifest", ":cocache-api")

// The modules a 10.x release publishes (06-modules.md §6; R.1 turns their publication on). Their ABI is kept in
// `api/<module>.api` (D88, D147, D216; see `abiValidation` below). :migrate-data (a CLI
// application) and :manifest-tools (run in an isolated worker by the same-version Gradle plugin, D83) keep no dump:
// their contract is not a JVM signature.
val abiProjects = includedProjects(
    ":annotations", ":api", ":manifest", ":query-api", ":spi", ":kernel", ":runtime", ":reactor", ":query",
    ":postgres", ":mongo", ":kafka", ":clickhouse", ":elasticsearch", ":openapi", ":http", ":client", ":cosec",
    ":observability", ":spring-boot",
    ":cosid", ":cocache-api", ":cocache", ":models", ":test-dsl", ":test-query", ":test-spring-boot", ":tck", ":query-tck", ":tck-containers", ":ops-api", ":view-store-api",
    ":compiler", ":gradle-plugin",
)
// What a 10.x release publishes to Maven (R.1, D205): the published modules above, :migrate-data and :manifest-tools
// (published, without a dump), and the BOM. :wow-dependencies goes out with them (see `releaseProjects`'
// use below): every POM imports it.
val publishedLibraries = abiProjects + includedProjects(":migrate-data", ":manifest-tools")
val releaseProjects = publishedLibraries + bomProject
// Projects that use Wow the way an application does (D216): the eShop, the platform services' domains and servers,
// and :architecture. They do not opt in to `@InternalWowApi` or `@ExperimentalWowApi`, so the compiler shows where
// they reach past the public API.
val userProjects = exampleProjects + includedProjects(
    ":ops-domain", ":ops-server", ":view-store-domain", ":view-store-server", ":analytics-catalog-domain",
    ":analytics-catalog-server", ":architecture", ":benchmark-server", ":migration-rehearsal", ":spi-consumer",
)

// D87: a project's name and directory carry no `wow-` prefix (`:kernel` in core/kernel); its jar, Kotlin module and
// Maven artifactId do (`wow-kernel`), so the coordinates users see are unchanged. A project whose name cannot be its
// artifact minus the prefix (a Gradle task name, such as the test DSL's `:test-dsl`) is mapped here.
val artifactIds = mapOf("test-dsl" to "wow-test")
fun Project.artifactName(): String =
    if (this == bomProject || this in moduleProjects && this !in exampleProjects && this !in e2eBenchmarkProjects) {
        artifactIds[name] ?: "wow-$name"
    } else {
        name
    }
// Libraries that build and test like the others but are not published yet: the modules a release does not publish
// (the platform's domains and servers, :architecture, the spikes and the eShop).
val incubatingProjects = moduleProjects - publishedLibraries
val publishProjects = subprojects - exampleProjects - codeCoverageReportProject - e2eBenchmarkProjects - incubatingProjects
val libraryProjects = publishProjects - bomProjects + incubatingProjects + e2eBenchmarkProjects
// What the BOM constrains: exactly the libraries a 10.x release publishes.
val bomConstrainedProjects = publishedLibraries
val isInCI = !System.getenv("CI").isNullOrEmpty()
ext.set("libraryProjects", libraryProjects)
ext.set("e2eBenchmarkProjects", e2eBenchmarkProjects)
ext.set("bomConstrainedProjects", bomConstrainedProjects)

enum class WowTestLayer(
    val sourceSetName: String,
    val taskName: String,
    val description: String,
) {
    INTEGRATION(
        sourceSetName = "integrationTest",
        taskName = "integrationTest",
        description = "Runs container-backed integration tests.",
    ),
}

val localTestProjects = libraryProjects
val integrationTestProjects = includedProjects(
    ":postgres",
    ":mongo",
    ":kafka",
    ":clickhouse",
    ":elasticsearch",
    ":spring-boot",
    ":cocache",
    ":routing-spike",
    ":lease-spike",
    ":eshop-ordering-server",
    ":eshop-e2e",
    ":ops-server",
    ":view-store-server",
    ":analytics-catalog-server",
    ":migrate-data",
)
// ci.yml runs the integration tests in shards (its `integration` matrix): `-Pwow.integration.projects=":mongo :postgres"`
// narrows `:integrationTest`, and the test data `integrationCoverageReport` reads, to those projects. The report
// still covers the classes of every project above, so a shard's tests count on the modules of the other shards too.
val integrationShardProjects = providers.gradleProperty("wow.integration.projects").orNull?.let { paths ->
    paths.split(' ', ',').filter(String::isNotBlank).map { path ->
        project(path).also {
            require(it in integrationTestProjects) { "$path is not an integration test project" }
        }
    }.toSet()
} ?: integrationTestProjects

// ci.yml runs `:check` in shards (its `check` matrix): `-Pwow.check.shard=<name>` narrows `:check`, and the test data
// `coverageReport` reads, to the shard's modules. The named shards list theirs and `rest` takes every other module, so
// each module is checked in exactly one shard. They are balanced by the CI jobs' task timings (ubuntu-latest, the
// uncached pull request runs of 2026-10-25 and 26): `:spring-boot:test` runs about 5¼ minutes on its own and the
// compiler's and the Gradle plugin's tests about 6 side by side, so the host and the tools each take a shard with
// little else, and the applications on the host (their tests ½ to 1¼ minutes each) take one of their own; with them
// beside it, the host's tests waited 3 to 4½ minutes for its test classes behind theirs. A module whose code other
// modules' tests exercise sits with them (CoSec, the HTTP edge and the client with the host, the analytics catalog's
// domain with its server, the query engine with the services that query): Codecov merges the shards' reports line by
// line but not branch by branch, so a branch covered half in one shard and half in another would count as partly
// covered.
val checkShards = mapOf(
    "host" to includedProjects(":spring-boot", ":cosec", ":http", ":client"),
    "apps" to includedProjects(
        ":ops-server", ":view-store-server", ":analytics-catalog-server", ":analytics-catalog-domain",
        ":eshop-catalog-server", ":eshop-basket-server", ":eshop-ordering-server", ":eshop-inventory-server",
        ":eshop-payment-server", ":quickstart", ":java-smoke", ":query", ":query-api", ":query-tck",
        ":elasticsearch", ":migrate-data",
    ),
    "tooling" to includedProjects(":compiler", ":gradle-plugin", ":manifest-tools", ":models"),
)
checkShards.forEach { (name, projects) ->
    val strays = projects - moduleProjects
    require(strays.isEmpty()) { "The check shard $name names projects that are not modules: $strays" }
}
checkShards.values.flatten().groupBy { it }.filterValues { it.size > 1 }.keys.let { twice ->
    require(twice.isEmpty()) { "Modules in two check shards: $twice" }
}
val checkShardProjects: Set<Project> = when (val shard = providers.gradleProperty("wow.check.shard").orNull) {
    null -> moduleProjects
    "rest" -> moduleProjects - checkShards.values.flatten().toSet()
    else -> requireNotNull(checkShards[shard]) { "No check shard $shard: ${checkShards.keys + "rest"}" }
}

ext.set("localTestProjects", localTestProjects)
ext.set("integrationTestProjects", integrationTestProjects)
ext.set("moduleProjects", moduleProjects)
ext.set("integrationShardProjects", integrationShardProjects)
ext.set("checkShardProjects", checkShardProjects)

// A hung integration test leaves evidence instead of a silent job timeout (a 37-minute hang of the eShop's end to end
// printed nothing, 2026-10-27). JUnit fails any test or lifecycle method that runs past `wow.test.timeout-minutes`
// (10 by default; the slowest suite takes about 3 minutes); before that, a task that makes no test progress for
// `wow.test.hang-idle-seconds` (480) writes a jstack of every JVM below this build (the test worker and the service
// JVMs it started) to build/reports/hang-dumps/, which CI uploads when the job fails or is cancelled.
val testTimeoutMinutes = providers.gradleProperty("wow.test.timeout-minutes").orNull?.toLong() ?: 10L
val hangIdleSeconds = providers.gradleProperty("wow.test.hang-idle-seconds").orNull?.toLong() ?: 480L

/**
 * Watches one test task: whenever no test or suite of it starts or finishes for [idleSeconds] while it runs, it dumps
 * the threads of every JVM below this build into [dir], at most [MAX_DUMPS] times a run, and says so in the build log.
 */
class HangWatch(
    private val task: String,
    private val dir: File,
    private val idleSeconds: Long,
    private val logger: Logger,
) : TestListener {
    @Volatile
    private var last = System.nanoTime()

    @Volatile
    private var running = "-"

    @Volatile
    private var thread: Thread? = null

    private fun progress(what: String? = null) {
        last = System.nanoTime()
        what?.let { running = it }
    }

    override fun beforeSuite(suite: TestDescriptor) {
        progress(suite.displayName)
        if (suite.parent == null) {
            thread = Thread(::watch, "hang-watch $task").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        progress()
        if (suite.parent == null) thread?.interrupt()
    }

    override fun beforeTest(test: TestDescriptor) = progress("${test.className} > ${test.displayName}")

    override fun afterTest(test: TestDescriptor, result: TestResult) = progress()

    private fun watch() {
        var dumps = 0
        try {
            while (dumps < MAX_DUMPS) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(POLL_SECONDS))
                val idle = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - last)
                if (idle >= idleSeconds) {
                    dump(idle, ++dumps)
                    last = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
            // The task's tests finished.
        }
    }

    private fun dump(idle: Long, n: Int) {
        val into = dir.resolve("dump-$n").apply { mkdirs() }
        val jstack = File(System.getProperty("java.home"), "bin/jstack")
        // The test workers and every JVM below them (the services a test started); all of this build's, without one.
        val all = ProcessHandle.current().descendants()
            .filter { it.info().command().orElse("").endsWith("java") }
            .toList()
        val workers = all.filter { it.info().commandLine().orElse("").contains(TEST_WORKER) }
        val jvms = if (workers.isEmpty()) {
            all
        } else {
            (workers + workers.flatMap { it.descendants().toList() })
                .filter { it.info().command().orElse("").endsWith("java") }
                .distinct()
        }
        into.resolve("README.txt").writeText(
            "$task made no test progress for $idle s; last started: $running\n" +
                jvms.joinToString("\n") { "pid ${it.pid()}: ${it.info().commandLine().orElse("?").take(COMMAND_CHARS)}" } +
                "\n",
        )
        for (jvm in jvms) {
            val process = ProcessBuilder(jstack.path, "-l", jvm.pid().toString())
                .redirectErrorStream(true)
                .redirectOutput(into.resolve("jstack-${jvm.pid()}.txt"))
                .start()
            if (!process.waitFor(JSTACK_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        logger.lifecycle("HANG: $task made no test progress for $idle s (last started: $running); jstack of ${jvms.size} JVM(s) in $into")
    }

    private companion object {
        const val MAX_DUMPS = 3
        const val POLL_SECONDS = 15L
        const val JSTACK_SECONDS = 60L
        const val COMMAND_CHARS = 300
        const val TEST_WORKER = "GradleWorkerMain"
    }
}

fun Project.registerJvmTestLayer(
    layer: WowTestLayer,
    includeInCheck: Boolean,
) {
    val sourceSets = extensions.getByType<SourceSetContainer>()
    val mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    val layerSourceSet = sourceSets.maybeCreate(layer.sourceSetName)

    configurations.named("${layer.sourceSetName}Implementation") {
        extendsFrom(configurations.getByName("testImplementation"))
    }
    configurations.named("${layer.sourceSetName}RuntimeOnly") {
        extendsFrom(configurations.getByName("testRuntimeOnly"))
    }

    layerSourceSet.compileClasspath += mainSourceSet.output
    layerSourceSet.runtimeClasspath += layerSourceSet.output + layerSourceSet.compileClasspath

    extensions.configure<KotlinJvmProjectExtension> {
        target.compilations.getByName(layer.sourceSetName)
            .associateWith(target.compilations.getByName(SourceSet.MAIN_SOURCE_SET_NAME))
    }

    val testTask = tasks.register<Test>(layer.taskName) {
        description = layer.description
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = layerSourceSet.output.classesDirs
        classpath = layerSourceSet.runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        jvmArgs = listOf(testLogbackConfiguration())
        systemProperty("junit.jupiter.execution.timeout.default", "$testTimeoutMinutes m")
        addTestListener(
            HangWatch(
                path,
                layout.buildDirectory.dir("reports/hang-dumps/${layer.taskName}").get().asFile,
                hangIdleSeconds,
                logger,
            ),
        )
        retry {
            if (isInCI) {
                maxRetries = 2
                maxFailures = 20
            }
            failOnPassedAfterRetry = true
        }
    }

    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
        if (includeInCheck) {
            dependsOn(testTask)
        }
    }
}

// The repository build's platform (`allprojects` below): never published.
val BUILD_PLATFORM = "wowBuildPlatform"

// The classpaths the build resolves for Wow's own code: every resolvable configuration (the source sets' compile and
// runtime classpaths, KSP's, the fixtures', `wowTools`, detekt's plugins, ...) but the tools' own (the Kotlin
// compiler's, Dokka's, JaCoCo's, the detekt CLI's, the TestKit's plugin classpath), whose versions are theirs. A
// publication reads none of them (`apiElements` and `runtimeElements` are consumable, not resolvable).
val TOOL_CLASSPATH = Regex("""^(kotlin|dokka|jacoco|detekt$|functionalPluginClasspath).*""")
fun String.isBuildClasspath(): Boolean = !TOOL_CLASSPATH.matches(this)

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    // The repository build's own platform (R5.C-4, D287): the versions Wow is built, tested and linted with, the
    // Spring Boot BOM, the test and lint platforms among them. Only the classpaths the build resolves extend from it,
    // never a configuration a publication reads, so no published POM or module metadata carries it: a published
    // module imports the slim :wow-dependencies alone, and its POM names the versions this platform resolved.
    val buildPlatform = configurations.create(BUILD_PLATFORM) {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    dependencies {
        buildPlatform(platform(dependenciesProject))
        buildPlatform(platform(rootProject.libs.spring.boot.dependencies))
        buildPlatform(platform(rootProject.libs.jsonschema.generator.bom))
        buildPlatform(platform(rootProject.libs.cosid.bom))
        buildPlatform(platform(rootProject.libs.cocache.bom))
        buildPlatform(platform(rootProject.libs.opentelemetry.bom))
        buildPlatform(platform(rootProject.libs.opentelemetry.instrumentation.bom))
        buildPlatform(platform(rootProject.libs.springdoc.bom))
        buildPlatform(platform(rootProject.libs.fluent.assert.bom))
        buildPlatform(platform(rootProject.libs.testcontainers.bom))
        buildPlatform(platform(rootProject.libs.junit.bom))
        constraints {
            // The Guava com.clickhouse:client-v2 brings to :clickhouse (33.4.6), lifted for the build alone: a 9.x
            // carry-over (wow-core used Guava), for no advisory, so a wow-clickhouse application keeps client-v2's.
            buildPlatform(rootProject.libs.guava)
            buildPlatform(rootProject.libs.swagger.annotations)
            buildPlatform(rootProject.libs.swagger.core)
            buildPlatform(rootProject.libs.mockk)
            buildPlatform(rootProject.libs.detekt.formatting)
        }
    }
    configurations.matching { it.isCanBeResolved && it.name.isBuildClasspath() }.configureEach {
        extendsFrom(buildPlatform)
    }
    apply<DetektPlugin>()
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
    }
    dependencies {
        detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting")
    }
    tasks.withType<Jar> {
        manifest {
            attributes["Implementation-Title"] = project.artifactName()
            attributes["Implementation-Version"] = project.version
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

configure(bomProjects) {
    apply<JavaPlatformPlugin>()
    configure<JavaPlatformExtension> {
        allowDependencies()
    }
}

configure(libraryProjects) {
    apply<DokkaPlugin>()
    configure<DokkaExtension> {
        dokkaPublications.html {
            suppressInheritedMembers.set(true)
        }
    }
    apply<JacocoPlugin>()
    apply<JavaLibraryPlugin>()
    if (project in moduleProjects) {
        configure<BasePluginExtension> {
            archivesName.set(artifactName())
        }
    }
    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            // Wow's own modules share internal declarations and build on its experimental ones; a user module
            // (`userProjects`, the end-to-end benchmark) does not (D216).
            if (project !in userProjects && project !in e2eBenchmarkProjects) {
                optIn.addAll(
                    "me.ahoo.wow.api.annotation.InternalWowApi",
                    "me.ahoo.wow.api.annotation.ExperimentalWowApi",
                )
            }
            // Test, contract and benchmark code exercises the SPI directly; main code opts in per module. Wow's own
            // modules implement or wire the `:spi` ports (D216), so they opt in whole; a user module does not.
            val wowModule = project in moduleProjects && project !in userProjects
            if (wowModule || name != "compileKotlin") {
                optIn.add("me.ahoo.wow.api.annotation.WowSpi")
            }
            javaParameters = true
        }
    }
    // Wow's interfaces compile to plain JVM default methods, without the `DefaultImpls` classes (`-jvm-default=
    // no-compatibility`, D216): free before the API freeze, a binary break after it.
    if (project in moduleProjects) {
        tasks.withType<KotlinCompile> {
            compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
        }
    }
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters"))
    }
    apply<TestRetryPlugin>()
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        System.getProperty("wow.snapshot.update")?.let {
            systemProperty("wow.snapshot.update", it)
        }
        // fix logging missing code for JacocoPlugin
        jvmArgs = listOf(testLogbackConfiguration())
        retry {
            if (isInCI) {
                maxRetries = 2
                maxFailures = 20
            }
            failOnPassedAfterRetry = true
        }
    }
    dependencies {
        // The slim platform every published module imports (D287), but the contract modules (`coreContractProjects`);
        // the build's own versions come from `wowBuildPlatform` above.
        if (project !in coreContractProjects) {
            api(platform(dependenciesProject))
            implementation("org.slf4j:slf4j-api")
        }
        testImplementation("io.micrometer:micrometer-core")
        testImplementation("ch.qos.logback:logback-classic")
        testImplementation("me.ahoo.test:fluent-assert-core")
        testImplementation("io.mockk:mockk") {
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }
}

configure(integrationTestProjects) {
    registerJvmTestLayer(WowTestLayer.INTEGRATION, includeInCheck = false)
}

tasks.register("allLocalTest") {
    description = "Runs all local-safe tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(localTestProjects.map { it.tasks.named("test") })
}

// The root `check` (`./gradlew :check`, CI's check job): `check` of every module compiles it and runs its unit tests
// (`:architecture:test` holds the dependency rules); detekt covers the same modules. The ABI of the published modules
// is checked from the API freeze at the M7 exit (D88, D147, D216): `checkKotlinAbi` of `abiProjects` against their
// `api/<module>.api`. `./gradlew check` also runs it, with the `check` of the projects outside `moduleProjects`.
tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(checkShardProjects.map { it.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) })
    dependsOn(checkShardProjects.map { it.tasks.named("detekt") })
    dependsOn(abiProjects.filter { it in checkShardProjects }.map { it.tasks.named("checkKotlinAbi") })
}

// Container-backed (Docker/Testcontainers). The spikes' suites skip themselves unless their flags are set.
tasks.register(WowTestLayer.INTEGRATION.taskName) {
    description = "Runs the container-backed integration tests of the modules."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(integrationShardProjects.map { it.tasks.named(WowTestLayer.INTEGRATION.taskName) })
}

// Signatures: a release signs in CI with the key in SIGNING_*; elsewhere the signing.* properties of
// ~/.gradle/gradle.properties sign. A publication requires them with `-Pwow.publication.signed=true` (the
// release's preflight). Without it, `publicationCheck` signs the publications with a throwaway key it generates
// (GnuPG, in a home of its own), so a pull request, which has no key, checks the signatures a release publishes too.
val requireSignatures = providers.gradleProperty("wow.publication.signed").orNull.toBoolean()
val prebuiltPublication = providers.gradleProperty("wow.publication.prebuilt").orNull.toBoolean()
val publicationProjects = releaseProjects + dependenciesProject

fun runProcess(command: List<String>, stdin: String? = null): String {
    val errors = File.createTempFile("wow-process", ".log")
    try {
        val process = try {
            ProcessBuilder(command).redirectError(errors).start()
        } catch (e: IOException) {
            throw GradleException("Cannot run ${command.first()}: ${e.message}", e)
        }
        process.outputStream.use { input -> stdin?.let { input.write(it.toByteArray()) } }
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("${command.joinToString(" ")} failed:\n${errors.readText()}")
        }
        return output
    } finally {
        errors.delete()
    }
}

// A GnuPG home of its own (a short temporary path: gpg-agent's socket lives in it), so no check touches a keyring.
class GnupgHome {
    val directory: File = Files.createTempDirectory("wow-gnupg").toFile()

    fun gpg(vararg args: String, stdin: String? = null) =
        runProcess(listOf("gpg", "--homedir", directory.path, "--batch", "--quiet") + args, stdin)

    fun close() {
        runCatching { runProcess(listOf("gpgconf", "--homedir", directory.path, "--kill", "gpg-agent")) }
        directory.deleteRecursively()
    }
}

val publicationCheckRequested =
    gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "publicationCheck" }
val throwawaySigningHome: GnupgHome? = if (publicationCheckRequested && !requireSignatures) {
    GnupgHome().apply {
        val unprotected = arrayOf("--pinentry-mode", "loopback", "--passphrase", "")
        gpg(*unprotected, "--quick-gen-key", "Wow publicationCheck (throwaway)", "rsa3072", "sign", "1d")
    }
} else {
    null
}
val throwawaySigningKey: String? = throwawaySigningHome
    ?.gpg("--pinentry-mode", "loopback", "--passphrase", "", "--armor", "--export-secret-keys")

configure(publishProjects) {
    val isBom = bomProjects.contains(this)
    // java-gradle-plugin creates the plugin's publications itself: `pluginMaven` (the jar) and the plugin marker
    // (`me.ahoo.wow:me.ahoo.wow.gradle.plugin`, which resolves `plugins { id("me.ahoo.wow") }` from Maven Central).
    val isGradlePlugin = path == ":gradle-plugin"
    apply<MavenPublishPlugin>()
    apply<SigningPlugin>()
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "projectBuildRepo"
                url = uri(layout.buildDirectory.dir("repos"))
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Ahoo-Wang/Wow")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
            maven {
                name = "LinYiPackages"
                url = uri(project.properties["linyiPackageReleaseUrl"].toString())
                credentials {
                    username = project.properties["linyiPackageUsername"]?.toString()
                    password = project.properties["linyiPackagePwd"]?.toString()
                }
            }
        }
        publications {
            if (!isGradlePlugin) {
                val publishName = if (isBom) "mavenBom" else "mavenLibrary"
                val publishComponentName = if (isBom) "javaPlatform" else "java"
                create<MavenPublication>(publishName) {
                    from(components[publishComponentName])
                }
            }
            withType<MavenPublication>().configureEach {
                // The plugin marker's coordinates are the plugin id's; every other publication is the module's artifact.
                if (name in setOf("mavenBom", "mavenLibrary", "pluginMaven")) {
                    artifactId = project.artifactName()
                }
                // A library's POM and module metadata name the versions the build resolved (D287): the build's
                // platform is not published, and the slim :wow-dependencies does not manage every library.
                if (name in setOf("mavenLibrary", "pluginMaven")) {
                    versionMapping {
                        allVariants {
                            fromResolutionResult()
                        }
                    }
                }
                pom {
                    name.set(rootProject.name)
                    description.set(getPropertyOf("description"))
                    url.set(getPropertyOf("website"))
                    issueManagement {
                        system.set("GitHub")
                        url.set(getPropertyOf("issues"))
                    }
                    scm {
                        url.set(getPropertyOf("website"))
                        connection.set(getPropertyOf("vcs"))
                    }
                    licenses {
                        license {
                            name.set(getPropertyOf("license_name"))
                            url.set(getPropertyOf("license_url"))
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("ahoo-wang")
                            name.set("ahoo wang")
                            organization {
                                url.set(getPropertyOf("website"))
                            }
                        }
                    }
                }
            }
        }
    }
    configure<SigningExtension> {
        val releasePublication = project in publicationProjects
        if (releasePublication && throwawaySigningKey != null) {
            useInMemoryPgpKeys(throwawaySigningKey, "")
        } else if (isInCI) {
            val signingKeyId = System.getenv("SIGNING_KEYID")
            val signingKey = System.getenv("SIGNING_SECRETKEY")
            val signingPassword = System.getenv("SIGNING_PASSWORD")
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }
        // A signature artifact of a publication is published only while its Sign task is required or has a signatory
        // (the signing plugin's `shouldBePublished`). The deploy jobs (`-Pwow.publication.prebuilt=true`) have no key
        // and leave the Sign tasks out (settings.gradle.kts), so they require the signatures: the publications then
        // upload the `.asc` files the preflight made beside the files it signed, and a missing one fails the publish.
        if (releasePublication) {
            isRequired = requireSignatures || throwawaySigningKey != null || prebuiltPublication
        }
        sign(extensions.getByType(PublishingExtension::class).publications)
    }
}

// A module's test fixtures (`:runtime`'s, say) are for Wow's own modules and stay unpublished
// (09-capability-parity.md, "端口 TCK"); the published TCKs are `:tck` and `:query-tck` (review R3.3-2). `java-test-fixtures` would add them to the `java`
// component, so the release would build and ship a `-test-fixtures` jar.
configure(releaseProjects) {
    pluginManager.withPlugin("java-test-fixtures") {
        val java = components["java"] as AdhocComponentWithVariants
        listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach {
            java.withVariantsFromConfiguration(configurations[it]) { skip() }
        }
    }
}

// The release rehearsal of the publications (R.1): `./gradlew publicationCheck` builds and
// signs every release module and :wow-dependencies as the release's preflight does (with the release key and
// `-Pwow.publication.signed=true` there, with a throwaway key elsewhere, see Signatures), publishes them into
// build/publication-check/repo, then checks that each library has its jar, sources and javadoc jars, POM and
// Gradle module metadata, each with its signature; that every POM carries what Maven Central requires; that no
// published POM depends on a Wow module the release leaves out, or on another version; that the BOM constrains
// exactly the published libraries; and that nothing else was published.
//
// A release's deploy jobs (package-deploy.yml) publish again with `-Pwow.publication.prebuilt=true`
// (settings.gradle.kts): they upload the files the preflight's run of this check built and signed, and leave out every
// task that makes one. The check walks this build's task graph the way that exclusion does, from each publish task
// (any repository's publish depends on the same tasks: `publishToSonatype`'s and the GitHub Packages publish's too),
// and requires that only publish tasks remain: a published variant whose files the left-out tasks do not make (as
// `:query`'s test fixtures did, compiled against the left-out `jar`) would be rebuilt there, or fail as 10.0.0-alpha.1's
// deploy jobs did, so it fails here first. Then it runs that publish: a Gradle build of its own, as the deploy jobs run
// it (`-Pwow.publication.prebuilt=true`, CI set, no SIGNING_* key), into build/publication-check/deploy, and
// requires that it uploads exactly the files this build published, byte for byte, and that every signature verifies.
// 10.0.0-alpha.2's deploy jobs uploaded no signature at all, which Maven Central refused.
val publicationCheckRepository: Provider<Directory> = layout.buildDirectory.dir("publication-check/repo")
val deployCheckRepository: Provider<Directory> = layout.buildDirectory.dir("publication-check/deploy")
val cleanPublicationCheck = tasks.register<Delete>("cleanPublicationCheck") {
    delete(publicationCheckRepository, deployCheckRepository)
}
configure(publicationProjects) {
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "publicationCheck"
                url = uri(publicationCheckRepository)
            }
            maven {
                name = "deployCheck"
                url = uri(deployCheckRepository)
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToPublicationCheckRepository")) mustRunAfter(cleanPublicationCheck)
    }
}
@Suppress("UNCHECKED_CAST")
val prebuiltPublicationTasks = gradle.extensions.extraProperties["wowPublicationPrebuiltTasks"] as List<String>
val deployGraphProblems = mutableListOf<String>()
tasks.register("publicationCheck") {
    description = "Publishes the release modules into a scratch repository and checks what a release would ship."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val publishTasks = publicationProjects.map {
        it.tasks.named("publishAllPublicationsToPublicationCheckRepository")
    }
    dependsOn(cleanPublicationCheck)
    dependsOn(publishTasks)
    val repository = publicationCheckRepository.get().asFile
    // The deploy jobs' graph: the publish tasks' dependencies, minus the left-out tasks and what only they need.
    gradle.taskGraph.whenReady {
        if (!hasTask(this@register)) return@whenReady
        val graph = this
        val deployed = mutableSetOf<Task>()
        fun visit(task: Task) {
            if (task.name in prebuiltPublicationTasks || !deployed.add(task)) return
            graph.getDependencies(task).forEach(::visit)
        }
        allTasks.filter { it is PublishToMavenRepository && it.name.endsWith("ToPublicationCheckRepository") }
            .forEach(::visit)
        deployed.filter { it !is PublishToMavenRepository && !it.name.startsWith("publishAllPublicationsTo") }
            .map { it.path }.sorted()
            .forEach { deployGraphProblems += "$it: a deploy job (-Pwow.publication.prebuilt=true) would run it" }
    }
    val groupId = project.group.toString()
    val releaseVersion = project.version.toString()
    val libraries = publicationProjects.filter { it !in bomProjects }.map { it.artifactName() }.toSortedSet()
    val platforms = publicationProjects.filter { it in bomProjects }.map { it.artifactName() }.toSortedSet()
    val bomArtifactId = bomProject.artifactName()
    val bomConstrained = bomConstrainedProjects.map { it.artifactName() }.toSortedSet()
    // D287: a published module imports the slim :wow-dependencies, and the host the Spring Boot BOM too; no other
    // platform (the build's test and lint platforms above all) reaches a POM.
    val dependenciesArtifactId = dependenciesProject.artifactName()
    val contractArtifactIds = coreContractProjects.map { it.artifactName() }.toSortedSet()
    val coroutinesBom = "org.jetbrains.kotlinx:kotlinx-coroutines-bom"
    val hostArtifactId = project(":spring-boot").artifactName()
    val springBootBom = "org.springframework.boot:spring-boot-dependencies"
    val deployRepository = deployCheckRepository.get().asFile
    val rootDirectory = rootDir
    val offline = gradle.startParameter.isOffline
    val throwawaySigning = throwawaySigningHome
    // The release key, to verify its signatures with, from where this build's signatory takes it.
    val releaseKey = if (isInCI) {
        System.getenv("SIGNING_SECRETKEY")
    } else {
        providers.gradleProperty("signing.secretKeyRingFile").orNull
    }
    val releaseKeyPassword = if (isInCI) {
        System.getenv("SIGNING_PASSWORD")
    } else {
        providers.gradleProperty("signing.password").orNull
    }
    // The markers java-gradle-plugin publishes beside wow-gradle-plugin, one per plugin id (D363 ①), each under the
    // group of its id: `me.ahoo.wow.api:me.ahoo.wow.api.gradle.plugin`, in the directory `api` of the group's.
    val pluginIds = listOf("me.ahoo.wow", "me.ahoo.wow.api", "me.ahoo.wow.service")
    val pluginMarkers = pluginIds.associate { id ->
        val directory = "${id.removePrefix(groupId).replace('.', '/')}/$id.gradle.plugin".removePrefix("/")
        directory to "$id.gradle.plugin"
    }
    doLast {
        val problems = mutableListOf<String>()
        val groupDir = File(repository, groupId.replace('.', '/'))
        // The directory of [artifactId] under the group's: a plugin marker's under its id's group.
        fun dirOf(artifactId: String) = pluginMarkers.entries.firstOrNull { it.value == artifactId }?.key ?: artifactId
        fun file(artifactId: String, suffix: String) =
            File(groupDir, "${dirOf(artifactId)}/$releaseVersion/$artifactId-$releaseVersion$suffix")
        fun requireFiles(artifactId: String, suffixes: List<String>) {
            suffixes.flatMap { listOf(it, "$it.asc") }.forEach { suffix ->
                if (!file(artifactId, suffix).isFile) problems += "$artifactId: no $artifactId-$releaseVersion$suffix"
            }
        }
        val librarySuffixes = listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")
        val platformSuffixes = listOf(".pom", ".module")
        libraries.forEach { requireFiles(it, librarySuffixes) }
        platforms.forEach { requireFiles(it, platformSuffixes) }
        pluginMarkers.values.forEach { requireFiles(it, listOf(".pom")) }
        // Nothing else: no further classifier (a `-test-fixtures` jar), only these files, their signatures and checksums.
        val checksums = listOf("", ".md5", ".sha1", ".sha256", ".sha512")
        fun allowedFiles(artifactId: String, suffixes: List<String>) = suffixes
            .flatMap { listOf(it, "$it.asc") }
            .flatMap { suffix -> checksums.map { "$artifactId-$releaseVersion$suffix$it" } }
        (libraries.associateWith { allowedFiles(it, librarySuffixes) } +
            platforms.associateWith { allowedFiles(it, platformSuffixes) } +
            pluginMarkers.values.associateWith { allowedFiles(it, listOf(".pom")) }).forEach { (artifactId, allowed) ->
            File(groupDir, "${dirOf(artifactId)}/$releaseVersion").listFiles().orEmpty().map { it.name }
                .filter { it !in allowed }.sorted()
                .forEach { problems += "$artifactId: publishes $it, which a release does not ship" }
        }
        problems += deployGraphProblems

        // The deploy jobs' publish, run as package-deploy.yml runs it: a build of its own, with the prebuilt files,
        // CI set and no SIGNING_* key (github-deploy and central-deploy have none).
        val deploy = ProcessBuilder(
            listOf(
                File(rootDirectory, "gradlew").path, "--no-daemon", "--quiet",
                "-Pwow.publication.prebuilt=true", "-Pversion=$releaseVersion",
                "publishAllPublicationsToDeployCheckRepository",
            ) + listOfNotNull("--offline".takeIf { offline }),
        ).directory(rootDirectory).inheritIO()
        deploy.environment().keys.removeAll { it.startsWith("SIGNING_") }
        deploy.environment()["CI"] = "true"
        if (deploy.start().waitFor() != 0) {
            problems += "the deploy jobs' publish (-Pwow.publication.prebuilt=true) failed: see its output above"
        }
        fun repositoryFiles(root: File) = root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith("maven-metadata") }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val built = repositoryFiles(repository)
        val uploaded = repositoryFiles(deployRepository)
        (built.keys - uploaded.keys).sorted()
            .forEach { problems += "$it: built and signed here, but a deploy job would not upload it" }
        (uploaded.keys - built.keys).sorted()
            .forEach { problems += "$it: a deploy job would upload it, but this build did not publish it" }
        built.keys.intersect(uploaded.keys).sorted()
            .filter { !built.getValue(it).readBytes().contentEquals(uploaded.getValue(it).readBytes()) }
            .forEach { problems += "$it: a deploy job would upload other bytes than this build published" }

        // Every signature a deploy job uploads verifies against the key this build signed with.
        val verifying = throwawaySigning ?: releaseKey?.let { key ->
            GnupgHome().apply {
                val keyFile = File(key).takeIf { it.isFile } ?: File(directory, "key.asc").apply { writeText(key) }
                gpg(
                    "--pinentry-mode", "loopback", "--passphrase-fd", "0", "--import", keyFile.path,
                    stdin = releaseKeyPassword.orEmpty(),
                )
            }
        }
        try {
            val signatures = uploaded.filterKeys { it.endsWith(".asc") }.toSortedMap()
            if (verifying == null) {
                problems += "no key to verify the signatures with (SIGNING_SECRETKEY or signing.secretKeyRingFile)"
            }
            signatures.forEach { (path, signature) ->
                val signed = File(signature.path.removeSuffix(".asc"))
                verifying?.runCatching { gpg("--verify", signature.path, signed.path) }?.onFailure {
                    problems += "$path: does not verify: ${it.message.orEmpty().trim().lines().last()}"
                }
            }
        } finally {
            verifying?.close()
        }

        // A marker of another group is a directory of the group's, `api` or `service`.
        val expected = (libraries + platforms + pluginMarkers.keys.map { it.substringBefore('/') }).toSortedSet()
        val published = groupDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSortedSet()
        (published - expected).forEach { problems += "$it: published, but not a release module" }

        val documents = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        fun Element.children(tag: String): List<Element> =
            (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == tag }
        fun Element.text(tag: String): String? = children(tag).firstOrNull()?.textContent?.trim()
        fun Element?.dependencies(): List<Element> =
            this?.children("dependencies")?.flatMap { it.children("dependency") }.orEmpty()
        expected.intersect(published).forEach { artifactId ->
            val pomFile = file(artifactId, ".pom")
            if (!pomFile.isFile) return@forEach
            val pom = documents.parse(pomFile).documentElement
            listOf("name", "description", "url").filter { pom.text(it).isNullOrEmpty() }
                .forEach { problems += "$artifactId: the POM has no <$it>" }
            listOf("licenses", "developers", "scm").filter { pom.children(it).isEmpty() }
                .forEach { problems += "$artifactId: the POM has no <$it>" }
            val managed = pom.children("dependencyManagement").firstOrNull().dependencies()
            (pom.dependencies() + managed).filter { it.text("groupId") == groupId }.forEach { dependency ->
                val dependencyId = dependency.text("artifactId")
                val dependencyVersion = dependency.text("version")
                if (dependencyId !in expected) {
                    problems += "$artifactId: depends on $groupId:$dependencyId, which the release does not publish"
                }
                if (dependencyVersion != releaseVersion) {
                    problems += "$artifactId: depends on $groupId:$dependencyId:$dependencyVersion, not $releaseVersion"
                }
            }
            // A contract module imports the coroutines BOM at most, not even :wow-dependencies (G8).
            val contract = artifactId in contractArtifactIds
            val imports = managed.filter { it.text("scope") == "import" }
                .map { "${it.text("groupId")}:${it.text("artifactId")}" }
                .filterNot { !contract && it == "$groupId:$dependenciesArtifactId" }
                .toSortedSet()
            val allowedImports = when (artifactId) {
                hostArtifactId -> setOf(springBootBom)
                dependenciesArtifactId -> setOf(coroutinesBom)
                in contractArtifactIds -> setOf(coroutinesBom)
                else -> emptySet()
            }
            val metadata = file(artifactId, ".module")
            if (contract && metadata.isFile && "\"module\": \"$dependenciesArtifactId\"" in metadata.readText()) {
                problems += "$artifactId: its module metadata depends on the platform $dependenciesArtifactId (G8)"
            }
            if (!allowedImports.containsAll(imports)) {
                problems += "$artifactId: imports the platforms ${imports - allowedImports}, beyond $allowedImports"
            }
            pom.dependencies().filter { it.text("version").isNullOrEmpty() }.forEach { dependency ->
                problems += "$artifactId: depends on ${dependency.text("groupId")}:${dependency.text("artifactId")} " +
                    "without a version"
            }
            if (artifactId == bomArtifactId) {
                val constrained = managed.mapNotNull { it.text("artifactId") }.toSortedSet()
                if (constrained != bomConstrained) {
                    problems += "$artifactId: constrains $constrained, not the published libraries $bomConstrained"
                }
            }
        }
        check(problems.isEmpty()) {
            "The publications of $releaseVersion are not ready to release:\n" + problems.joinToString("\n")
        }
        logger.lifecycle("Checked the publications of $releaseVersion in $repository: ${expected.joinToString()}")
    }
}

// The JVM ABI of every published library but :migrate-data and :manifest-tools (`abiProjects`) is kept in its
// `api/<module>.api`; `check` and `:check` run `checkKotlinAbi` against it, so a public signature cannot change
// unnoticed. AGENTS.md ("Binary Compatibility") says when a dump may change. The dumps date from the
// API freeze (D216); `./gradlew updateKotlinAbi` rewrites their dumps, and between the M7 exit and
// 10.0.0 GA a removed or changed line there takes the `breaking-change` label.
configure(abiProjects) {
    configure<KotlinJvmProjectExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation {
            // Declarations Wow's own modules share but promise nothing about (see its KDoc). Known gap (KGP 2.4.21):
            // a class whose `@InternalWowApi` primary constructor takes a value class (`kotlin.time.Duration`) and has
            // default arguments keeps its synthetic default-argument constructor
            // (`<init>(…, I, DefaultConstructorMarker)`) in the dump, since only the constructor itself carries the
            // annotation (WaitPlan and the *Options, D216 F.5). Those lines are accepted; Wow's users never call them.
            filters.exclude.annotatedWith.add("me.ahoo.wow.api.annotation.InternalWowApi")
        }
    }
}

nexusPublishing {
    this.repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

fun getPropertyOf(name: String) = project.properties[name]?.toString()

dependencies {
    libraryProjects.forEach {
        dokka(it)
    }
}

dokka {
    moduleName.set("Wow")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }
    pluginsConfiguration.html {
        homepageLink.set(getPropertyOf("website")!!)
        customAssets.from("documentation/docs/public/images/logo.svg")
        footerMessage.set(getPropertyOf("website")!!)
    }
}
