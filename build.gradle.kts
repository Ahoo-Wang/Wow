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
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.test.retry)
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    jacoco
}

// settings.gradle.kts includes the 9.x projects and :kernel-benchmarks only in the full build (`wow.scope` is `all`,
// the default); `-Pwow.scope=v10` leaves them out. Every list below names its projects through `includedProjects`,
// which skips the ones the scope left out, so the same lists serve both scopes.
fun includedProjects(vararg paths: String): Set<Project> = paths.mapNotNull { findProject(it) }.toSet()

val dependenciesProject = project(":wow-dependencies")
// The V10 BOM (`:bom` in bom/bom, artifact wow-bom per D87) replaces 9.x's `:wow-bom` on this line; v10 publishes only
// the V10 one (D90), so the two never go out under the same version.
val v10BomProject = project(":bom")
val bomProjects = includedProjects(":wow-bom") + dependenciesProject + v10BomProject

val exampleLibraries =
    includedProjects(":example-api", ":example-domain", ":example-transfer-api", ":example-transfer-domain")
val viewStoreServerProject = includedProjects(":wow-view-store-server")
val analyticsServerProject = findProject(":wow-analytics-server")
// The V10 example, the eShop (D158, D159, D204): built and checked with the V10 modules, never published, and named
// like the 9.x examples, without the `wow-` prefix of a V10 artifact. It is five contexts of an API, a domain and a
// server each, and its end-to-end tests, :eshop-e2e.
val eshopProjects = listOf("catalog", "basket", "ordering", "inventory", "payment").flatMap { context ->
    listOf("api", "domain", "server").map { "eshop-$context-$it" }
} + "eshop-e2e"
// The V10 quickstart (D217): the sample of documentation/docs/{en,zh}/guide/v10/quickstart.md, one module that is its
// domain and its server, built and tested like the eShop, never published. The Java smoke test (D229 G-8): a domain
// written in Java, built by the compiler and tested with the test DSL, never published.
val v10ExampleNames = eshopProjects + "quickstart" + "java-smoke"
val v10ExampleProjects = includedProjects(*v10ExampleNames.map { ":$it" }.toTypedArray())
val exampleProjects =
    exampleLibraries + includedProjects(":example-server", ":example-transfer-server", ":wow-compensation-server") +
        viewStoreServerProject + listOfNotNull(analyticsServerProject) + v10ExampleProjects

val codeCoverageReportProject = project(":code-coverage-report")
val benchmarksProject = includedProjects(":wow-benchmarks")
// The end-to-end architecture benchmark (decision D224, roadmap M7.5 B.0) under wow-benchmarks/e2e/: the twin benchmark
// domain and server of each version and the load generator. Applications, like the examples: never published, without
// an ABI dump, and left out of the coverage reports (code-coverage-report reads `e2eBenchmarkProjects`). The V10 one
// builds and checks with the V10 modules (`v10Check`) as a user of them; the 9.x one only in the full build.
val e2eBenchmarkProjects = includedProjects(":e2e-bench-9x", ":e2e-bench-v10", ":e2e-bench-load")
// The V10 modules (documentation/designs/v10/06-modules.md). Every module builds on JDK 21 (decision D29); these stay
// incubating until their API settles; :architecture (V10's dependency rules), :kernel-benchmarks (spike H3),
// :routing-spike (spike H2), :lease-spike (spike H6) and the examples (:eshop-*, :quickstart, :java-smoke) are
// never published.
val v10Projects = listOf(
    "api", "manifest", "query-api", "spi", "kernel", "runtime", "reactor", "query", "query-tck",
    "postgres", "mongo", "kafka", "clickhouse", "openapi", "http", "cosec", "opentelemetry", "spring-boot", "cosid",
    "models", "migrate-data", "architecture",
    "test-dsl", "tck", "tck-containers", "ops-api", "ops-domain", "ops-server",
    "view-store-api", "view-store-domain", "view-store-server",
    "dataset-registry-api", "dataset-registry-domain", "dataset-registry-server",
    "kernel-benchmarks", "compiler", "gradle-plugin", "manifest-tools",
    "routing-spike", "lease-spike", "e2e-bench-v10",
).plus(v10ExampleNames).let { names -> includedProjects(*names.map { ":$it" }.toTypedArray()) }

// The V10 modules a 10.x release publishes (06-modules.md §6; R.1 turns their publication on). Their ABI is kept in
// `api/<module>.api` like a 9.x module's (D88, D147, D216; see `abiValidation` below). :migrate-data (a CLI
// application) and :manifest-tools (run in an isolated worker by the same-version Gradle plugin, D83) keep no dump:
// their contract is not a JVM signature.
val v10PublishedProjects = includedProjects(
    ":api", ":manifest", ":query-api", ":spi", ":kernel", ":runtime", ":reactor", ":query",
    ":postgres", ":mongo", ":kafka", ":clickhouse", ":openapi", ":http", ":cosec", ":opentelemetry", ":spring-boot",
    ":cosid", ":models", ":test-dsl", ":tck", ":query-tck", ":tck-containers", ":ops-api", ":view-store-api",
    ":compiler", ":gradle-plugin",
)
// What a 10.x release publishes to Maven (R.1, D205): the published modules above, :migrate-data and :manifest-tools
// (published, without a dump), and the V10 BOM. :wow-dependencies goes out with them (see `v10ReleaseProjects`'
// use below): every V10 POM imports it.
val v10PublishedLibraries = v10PublishedProjects + includedProjects(":migrate-data", ":manifest-tools")
val v10ReleaseProjects = v10PublishedLibraries + v10BomProject
// The V10 modules whose ABI is checked: every published one but :migrate-data and :manifest-tools, frozen at the M7
// exit (D216 F.5, which committed their first dumps).
val v10AbiProjects = v10PublishedProjects
// V10 modules that use Wow the way an application does (D216): the eShop, the platform services' domains and servers,
// and :architecture. They do not opt in to `@InternalWowApi` or `@ExperimentalWowApi`, so the compiler shows where
// they reach past the public API.
val v10UserProjects = v10ExampleProjects + includedProjects(
    ":ops-domain", ":ops-server", ":view-store-domain", ":view-store-server", ":dataset-registry-domain",
    ":dataset-registry-server", ":architecture", ":e2e-bench-v10",
)

// D87: a V10 project's name and directory carry no `wow-` prefix (`:kernel` in core/kernel); its jar, Kotlin module and
// Maven artifactId do (`wow-kernel`), so the coordinates users see are unchanged. A project whose name cannot be its
// artifact minus the prefix (a Gradle task name, such as the test DSL's `:test-dsl`) is mapped here.
val v10ArtifactIds = mapOf("test-dsl" to "wow-test")
fun Project.artifactName(): String =
    if (this == v10BomProject || this in v10Projects && this !in v10ExampleProjects && this !in e2eBenchmarkProjects) {
        v10ArtifactIds[name] ?: "wow-$name"
    } else {
        name
    }
// Libraries that build and test like the others but are not published yet. The dataset registry (dataset platform
// design R1b) left with the analytics service (R5d). On v10: the V10 modules a release does not publish (the platform's
// domains and servers, :architecture, the spikes and the eShop).
val incubatingProjects = v10Projects - v10PublishedLibraries
val publishProjects = subprojects - exampleProjects - codeCoverageReportProject - benchmarksProject -
    e2eBenchmarkProjects - incubatingProjects
val libraryProjects =
    publishProjects - bomProjects + exampleLibraries + benchmarksProject + incubatingProjects + e2eBenchmarkProjects
// What 9.x's wow-bom constrains: the published 9.x libraries only, never an example, the benchmarks, an incubating
// module, whose coordinates are not on Maven Central, or a V10 module (the V10 BOM constrains those).
val bomConstrainedProjects = publishProjects - bomProjects - v10PublishedLibraries
// What the V10 BOM constrains: exactly the V10 libraries a 10.x release publishes.
val v10BomConstrainedProjects = v10PublishedLibraries
// Modules whose main code implements or wires a `@WowSpi` (the query backend SPI and its registration, the transport SPI
// and its Kafka and Redis implementations, the command aggregate SPI in wow-core and the aggregate test DSL that drives
// it). Every other module's main code opts in where
// it uses one (`@OptIn(WowSpi::class)`) and otherwise gets the compiler's warning, as an application would.
val wowSpiProjects = includedProjects(
    ":wow-core",
    ":wow-test",
    ":wow-query",
    ":wow-clickhouse",
    ":wow-mongo",
    ":wow-elasticsearch",
    ":wow-kafka",
    ":wow-redis",
    ":wow-tck",
    ":wow-spring-boot-starter",
)
val isInCI = !System.getenv("CI").isNullOrEmpty()
ext.set("libraryProjects", libraryProjects)
ext.set("e2eBenchmarkProjects", e2eBenchmarkProjects)
ext.set("bomConstrainedProjects", bomConstrainedProjects)
ext.set("v10BomConstrainedProjects", v10BomConstrainedProjects)

enum class WowTestLayer(
    val sourceSetName: String,
    val taskName: String,
    val description: String,
) {
    CONTRACT(
        sourceSetName = "contractTest",
        taskName = "contractTest",
        description = "Runs local-safe TCK contract tests.",
    ),
    INTEGRATION(
        sourceSetName = "integrationTest",
        taskName = "integrationTest",
        description = "Runs container-backed integration tests.",
    ),
}

val localTestProjects = libraryProjects - benchmarksProject
val localTestTaskProjects =
    localTestProjects + includedProjects(":wow-compensation-server") + viewStoreServerProject +
        listOfNotNull(analyticsServerProject)
val localContractTestProjects = includedProjects(
    ":wow-core",
    ":wow-opentelemetry",
    ":wow-mock",
)
val v10IntegrationTestProjects = includedProjects(
    ":postgres",
    ":mongo",
    ":kafka",
    ":clickhouse",
    ":spring-boot",
    ":routing-spike",
    ":lease-spike",
    ":eshop-ordering-server",
    ":eshop-e2e",
    ":ops-server",
    ":view-store-server",
    ":dataset-registry-server",
    ":migrate-data",
)
val integrationTestProjects = includedProjects(
    ":wow-bi",
    ":wow-clickhouse",
    ":wow-mongo",
    ":wow-redis",
    ":wow-kafka",
    ":wow-elasticsearch",
    ":wow-it",
    ":wow-view-store-starter",
    ":wow-dataset-registry-starter",
) + v10IntegrationTestProjects

ext.set("localTestProjects", localTestProjects)
ext.set("localContractTestProjects", localContractTestProjects)
ext.set("integrationTestProjects", integrationTestProjects)
ext.set("v10Projects", v10Projects)
ext.set("v10IntegrationTestProjects", v10IntegrationTestProjects)

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
        jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
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

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    apply<DetektPlugin>()
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
    }
    dependencies {
        detektPlugins(dependenciesProject)
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
    if (project in v10Projects) {
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
            // (`v10UserProjects`, the 9.x example libraries, the end-to-end benchmark) does not (D216).
            if (project !in v10UserProjects && project !in exampleLibraries && project !in e2eBenchmarkProjects) {
                optIn.addAll(
                    "me.ahoo.wow.api.annotation.InternalWowApi",
                    "me.ahoo.wow.api.annotation.ExperimentalWowApi",
                )
            }
            // Test, contract and benchmark code exercises the SPI directly; main code opts in per module. Wow's own V10
            // modules implement or wire the `:spi` ports (D216), so they opt in whole; a user module does not.
            val wowV10Module = project in v10Projects && project !in v10UserProjects
            if (project in wowSpiProjects || wowV10Module || name != "compileKotlin") {
                optIn.add("me.ahoo.wow.api.annotation.WowSpi")
            }
            javaParameters = true
        }
    }
    // V10 interfaces compile to plain JVM default methods, without the `DefaultImpls` classes (`-jvm-default=
    // no-compatibility`, D216): free before the API freeze, a binary break after it. The 9.x modules keep the default,
    // `enable`, so their ABI does not change.
    if (project in v10Projects) {
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
        jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
        retry {
            if (isInCI) {
                maxRetries = 2
                maxFailures = 20
            }
            failOnPassedAfterRetry = true
        }
    }
    dependencies {
        api(platform(dependenciesProject))
        testImplementation(platform(rootProject.libs.junit.bom))
        implementation("org.slf4j:slf4j-api")
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

configure(localContractTestProjects) {
    registerJvmTestLayer(WowTestLayer.CONTRACT, includeInCheck = true)
}

configure(integrationTestProjects) {
    registerJvmTestLayer(WowTestLayer.INTEGRATION, includeInCheck = false)
}

tasks.register("allLocalTest") {
    description = "Runs all local-safe tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(localTestTaskProjects.map { it.tasks.named("test") })
}

tasks.register("allContractTest") {
    description = "Runs all local-safe contract tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(localContractTestProjects.map { it.tasks.named(WowTestLayer.CONTRACT.taskName) })
}

tasks.register("allIntegrationTest") {
    description = "Runs all container-backed integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(integrationTestProjects.map { it.tasks.named(WowTestLayer.INTEGRATION.taskName) })
    // The analytics server registers its own layer (an application's Java plugin comes from its own build script).
    analyticsServerProject?.let { dependsOn("${it.path}:${WowTestLayer.INTEGRATION.taskName}") }
}

tasks.register("benchmarkSmoke") {
    description = "Runs the PR-safe JMH smoke benchmark set."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(benchmarksProject.map { it.tasks.named("benchmarkSmoke") })
}

// The V10 checks (v10.yml), in either build scope; `-Pwow.scope=v10` configures no 9.x module. `check` of every V10
// module compiles it and runs its unit tests (`:architecture:test` holds the V10 dependency rules); detekt covers
// the same modules. The ABI of the published V10 modules is checked from the API freeze at the M7 exit (D88, D147,
// D216): `checkKotlinAbi` of `v10AbiProjects` against their `api/<module>.api`. The full build also compiles the JMH
// sources of :kernel-benchmarks, which the v10 scope leaves out.
tasks.register("v10Check") {
    description = "Compiles, unit-tests and runs detekt over the V10 modules."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(v10Projects.map { it.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) })
    dependsOn(v10Projects.map { it.tasks.named("detekt") })
    dependsOn(v10AbiProjects.map { it.tasks.named("checkKotlinAbi") })
    findProject(":kernel-benchmarks")?.let { dependsOn(it.tasks.named("compileJmhKotlin")) }
}

// Container-backed (Docker/Testcontainers). The spikes' suites skip themselves unless their flags are set.
tasks.register("v10IntegrationTest") {
    description = "Runs the container-backed integration tests of the V10 modules."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(v10IntegrationTestProjects.map { it.tasks.named(WowTestLayer.INTEGRATION.taskName) })
}

// Signatures: a release signs in CI with the key in SIGNING_*; elsewhere the signing.* properties of
// ~/.gradle/gradle.properties sign. A V10 publication requires them with `-Pwow.publication.signed=true` (the
// release's preflight). Without it, `v10PublicationCheck` signs the V10 publications with a throwaway key it generates
// (GnuPG, in a home of its own), so a pull request, which has no key, checks the signatures a release publishes too.
val requireV10Signatures = providers.gradleProperty("wow.publication.signed").orNull.toBoolean()
val prebuiltPublication = providers.gradleProperty("wow.publication.prebuilt").orNull.toBoolean()
val v10PublicationProjects = v10ReleaseProjects + dependenciesProject

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

val v10PublicationCheckRequested =
    gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "v10PublicationCheck" }
val v10ThrowawaySigning: GnupgHome? = if (v10PublicationCheckRequested && !requireV10Signatures) {
    GnupgHome().apply {
        val unprotected = arrayOf("--pinentry-mode", "loopback", "--passphrase", "")
        gpg(*unprotected, "--quick-gen-key", "Wow v10PublicationCheck (throwaway)", "rsa3072", "sign", "1d")
    }
} else {
    null
}
val v10ThrowawaySigningKey: String? = v10ThrowawaySigning
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
        val v10Publication = project in v10PublicationProjects
        if (v10Publication && v10ThrowawaySigningKey != null) {
            useInMemoryPgpKeys(v10ThrowawaySigningKey, "")
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
        if (v10Publication) {
            isRequired = requireV10Signatures || v10ThrowawaySigningKey != null || prebuiltPublication
        }
        sign(extensions.getByType(PublishingExtension::class).publications)
    }
}

// D90: v10 does not publish the 9.x modules; main does. A 10.0 pre-release from v10 would otherwise publish them as
// 10.x. Their publications are still built (and signed), and their ABI is still checked below. A 10.x release
// publishes the V10 modules, the V10 BOM and :wow-dependencies, which every V10 POM imports (R.1).
configure(publishProjects - v10ReleaseProjects - dependenciesProject) {
    tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
    tasks.withType<PublishToMavenLocal>().configureEach { enabled = false }
}

// A V10 module's test fixtures (`:manifest`'s, say) are for Wow's own modules and stay unpublished
// (09-capability-parity.md, "端口 TCK"); the published TCKs are `:tck` and `:query-tck` (review R3.3-2). `java-test-fixtures` would add them to the `java`
// component, so the release would build and ship a `-test-fixtures` jar.
configure(v10ReleaseProjects) {
    pluginManager.withPlugin("java-test-fixtures") {
        val java = components["java"] as AdhocComponentWithVariants
        listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach {
            java.withVariantsFromConfiguration(configurations[it]) { skip() }
        }
    }
}

// The release rehearsal of the V10 publications (R.1): `./gradlew -Pwow.scope=v10 v10PublicationCheck` builds and
// signs every V10 release module and :wow-dependencies as the release's preflight does (with the release key and
// `-Pwow.publication.signed=true` there, with a throwaway key elsewhere, see Signatures), publishes them into
// build/v10-publication-check/repo, then checks that each library has its jar, sources and javadoc jars, POM and
// Gradle module metadata, each with its signature; that every POM carries what Maven Central requires; that no
// published POM depends on a Wow module the release leaves out, or on another version; that the V10 BOM constrains
// exactly the published libraries; and that nothing else (no 9.x module) was published.
//
// A release's deploy jobs (package-deploy.yml) publish again with `-Pwow.publication.prebuilt=true`
// (settings.gradle.kts): they upload the files the preflight's run of this check built and signed, and leave out every
// task that makes one. The check walks this build's task graph the way that exclusion does, from each publish task
// (any repository's publish depends on the same tasks: `publishToSonatype`'s and the GitHub Packages publish's too),
// and requires that only publish tasks remain: a published variant whose files the left-out tasks do not make (as
// `:query`'s test fixtures did, compiled against the left-out `jar`) would be rebuilt there, or fail as 10.0.0-alpha.1's
// deploy jobs did, so it fails here first. Then it runs that publish: a Gradle build of its own, as the deploy jobs run
// it (`-Pwow.publication.prebuilt=true`, CI set, no SIGNING_* key), into build/v10-publication-check/deploy, and
// requires that it uploads exactly the files this build published, byte for byte, and that every signature verifies.
// 10.0.0-alpha.2's deploy jobs uploaded no signature at all, which Maven Central refused.
val v10PublicationCheckRepository: Provider<Directory> = layout.buildDirectory.dir("v10-publication-check/repo")
val v10DeployCheckRepository: Provider<Directory> = layout.buildDirectory.dir("v10-publication-check/deploy")
val cleanV10PublicationCheck = tasks.register<Delete>("cleanV10PublicationCheck") {
    delete(v10PublicationCheckRepository, v10DeployCheckRepository)
}
configure(v10PublicationProjects) {
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "v10PublicationCheck"
                url = uri(v10PublicationCheckRepository)
            }
            maven {
                name = "v10DeployCheck"
                url = uri(v10DeployCheckRepository)
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToV10PublicationCheckRepository")) mustRunAfter(cleanV10PublicationCheck)
    }
}
@Suppress("UNCHECKED_CAST")
val prebuiltPublicationTasks = gradle.extensions.extraProperties["wowPublicationPrebuiltTasks"] as List<String>
val deployGraphProblems = mutableListOf<String>()
tasks.register("v10PublicationCheck") {
    description = "Publishes the V10 release modules into a scratch repository and checks what a release would ship."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val publishTasks = v10PublicationProjects.map {
        it.tasks.named("publishAllPublicationsToV10PublicationCheckRepository")
    }
    dependsOn(cleanV10PublicationCheck)
    dependsOn(publishTasks)
    val repository = v10PublicationCheckRepository.get().asFile
    // The deploy jobs' graph: the publish tasks' dependencies, minus the left-out tasks and what only they need.
    gradle.taskGraph.whenReady {
        if (!hasTask(this@register)) return@whenReady
        val graph = this
        val deployed = mutableSetOf<Task>()
        fun visit(task: Task) {
            if (task.name in prebuiltPublicationTasks || !deployed.add(task)) return
            graph.getDependencies(task).forEach(::visit)
        }
        allTasks.filter { it is PublishToMavenRepository && it.name.endsWith("ToV10PublicationCheckRepository") }
            .forEach(::visit)
        deployed.filter { it !is PublishToMavenRepository && !it.name.startsWith("publishAllPublicationsTo") }
            .map { it.path }.sorted()
            .forEach { deployGraphProblems += "$it: a deploy job (-Pwow.publication.prebuilt=true) would run it" }
    }
    val groupId = project.group.toString()
    val releaseVersion = project.version.toString()
    val libraries = v10PublicationProjects.filter { it !in bomProjects }.map { it.artifactName() }.toSortedSet()
    val platforms = v10PublicationProjects.filter { it in bomProjects }.map { it.artifactName() }.toSortedSet()
    val bomArtifactId = v10BomProject.artifactName()
    val bomConstrained = v10BomConstrainedProjects.map { it.artifactName() }.toSortedSet()
    val deployRepository = v10DeployCheckRepository.get().asFile
    val rootDirectory = rootDir
    val offline = gradle.startParameter.isOffline
    val throwawaySigning = v10ThrowawaySigning
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
    // The marker java-gradle-plugin publishes beside wow-gradle-plugin.
    val pluginMarker = "me.ahoo.wow.gradle.plugin"
    doLast {
        val problems = mutableListOf<String>()
        val groupDir = File(repository, groupId.replace('.', '/'))
        fun file(artifactId: String, suffix: String) =
            File(groupDir, "$artifactId/$releaseVersion/$artifactId-$releaseVersion$suffix")
        fun requireFiles(artifactId: String, suffixes: List<String>) {
            suffixes.flatMap { listOf(it, "$it.asc") }.forEach { suffix ->
                if (!file(artifactId, suffix).isFile) problems += "$artifactId: no $artifactId-$releaseVersion$suffix"
            }
        }
        val librarySuffixes = listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")
        val platformSuffixes = listOf(".pom", ".module")
        libraries.forEach { requireFiles(it, librarySuffixes) }
        platforms.forEach { requireFiles(it, platformSuffixes) }
        requireFiles(pluginMarker, listOf(".pom"))
        // Nothing else: no further classifier (a `-test-fixtures` jar), only these files, their signatures and checksums.
        val checksums = listOf("", ".md5", ".sha1", ".sha256", ".sha512")
        fun allowedFiles(artifactId: String, suffixes: List<String>) = suffixes
            .flatMap { listOf(it, "$it.asc") }
            .flatMap { suffix -> checksums.map { "$artifactId-$releaseVersion$suffix$it" } }
        (libraries.associateWith { allowedFiles(it, librarySuffixes) } +
            platforms.associateWith { allowedFiles(it, platformSuffixes) } +
            (pluginMarker to allowedFiles(pluginMarker, listOf(".pom")))).forEach { (artifactId, allowed) ->
            File(groupDir, "$artifactId/$releaseVersion").listFiles().orEmpty().map { it.name }
                .filter { it !in allowed }.sorted()
                .forEach { problems += "$artifactId: publishes $it, which a release does not ship" }
        }
        problems += deployGraphProblems

        // The deploy jobs' publish, run as package-deploy.yml runs it: a build of its own, with the prebuilt files,
        // CI set and no SIGNING_* key (github-deploy and central-deploy have none).
        val deploy = ProcessBuilder(
            listOf(
                File(rootDirectory, "gradlew").path, "--no-daemon", "--quiet",
                "-Pwow.scope=v10", "-Pwow.publication.prebuilt=true", "-Pversion=$releaseVersion",
                "publishAllPublicationsToV10DeployCheckRepository",
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

        val expected = (libraries + platforms + pluginMarker).toSortedSet()
        val published = groupDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSortedSet()
        (published - expected).forEach { problems += "$it: published, but not a V10 release module" }

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
            if (artifactId == bomArtifactId) {
                val constrained = managed.mapNotNull { it.text("artifactId") }.toSortedSet()
                if (constrained != bomConstrained) {
                    problems += "$artifactId: constrains $constrained, not the published libraries $bomConstrained"
                }
            }
        }
        check(problems.isEmpty()) {
            "The V10 publications of $releaseVersion are not ready to release:\n" + problems.joinToString("\n")
        }
        logger.lifecycle("Checked the V10 publications of $releaseVersion in $repository: ${expected.joinToString()}")
    }
}

// The JVM ABI of every published library is kept in its `api/<module>.api`; `check` runs `checkKotlinAbi`
// against it, so a 9.x patch cannot change a public signature unnoticed. `./gradlew updateKotlinAbi` rewrites
// the dumps; AGENTS.md ("Binary Compatibility") says when that is allowed. The published V10 modules joined at the
// API freeze (D216); `./gradlew -Pwow.scope=v10 updateKotlinAbi` rewrites their dumps, and between the M7 exit and
// 10.0.0 GA a removed or changed line there takes the `breaking-change` label.
configure(publishProjects - bomProjects - v10PublishedLibraries + v10AbiProjects) {
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
