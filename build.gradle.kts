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
val v10ExampleProjects = includedProjects(*eshopProjects.map { ":$it" }.toTypedArray())
val exampleProjects =
    exampleLibraries + includedProjects(":example-server", ":example-transfer-server", ":wow-compensation-server") +
        viewStoreServerProject + listOfNotNull(analyticsServerProject) + v10ExampleProjects

val codeCoverageReportProject = project(":code-coverage-report")
val benchmarksProject = includedProjects(":wow-benchmarks")
// The V10 modules (documentation/designs/v10/06-modules.md). Every module builds on JDK 21 (decision D29); these stay
// incubating until their API settles; :architecture (V10's dependency rules), :kernel-benchmarks (spike H3),
// :routing-spike (spike H2), :lease-spike (spike H6) and the example (:eshop-*) are never published.
val v10Projects = listOf(
    "api", "manifest", "query-api", "spi", "kernel", "runtime", "reactor", "query",
    "postgres", "mongo", "kafka", "clickhouse", "openapi", "http", "cosec", "opentelemetry", "spring-boot", "cosid",
    "migrate-data", "architecture",
    "test-dsl", "tck", "tck-containers", "ops-api", "ops-domain", "ops-server",
    "view-store-api", "view-store-domain", "view-store-server",
    "kernel-benchmarks", "compiler", "gradle-plugin", "manifest-tools",
    "routing-spike", "lease-spike",
).plus(eshopProjects).let { names -> includedProjects(*names.map { ":$it" }.toTypedArray()) }

// The V10 modules a 10.x release publishes (06-modules.md §6; R.1 turns their publication on). Their ABI is kept in
// `api/<module>.api` like a 9.x module's (D88, D147, D216; see `abiValidation` below). :migrate-data (a CLI
// application) and :manifest-tools (run in an isolated worker by the same-version Gradle plugin, D83) keep no dump:
// their contract is not a JVM signature.
val v10PublishedProjects = includedProjects(
    ":api", ":manifest", ":query-api", ":spi", ":kernel", ":runtime", ":reactor", ":query",
    ":postgres", ":mongo", ":kafka", ":clickhouse", ":openapi", ":http", ":cosec", ":opentelemetry", ":spring-boot",
    ":cosid", ":test-dsl", ":tck", ":tck-containers", ":ops-api", ":view-store-api",
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
    ":ops-domain", ":ops-server", ":view-store-domain", ":view-store-server", ":architecture",
)

// D87: a V10 project's name and directory carry no `wow-` prefix (`:kernel` in core/kernel); its jar, Kotlin module and
// Maven artifactId do (`wow-kernel`), so the coordinates users see are unchanged. A project whose name cannot be its
// artifact minus the prefix (a Gradle task name, such as the test DSL's `:test-dsl`) is mapped here.
val v10ArtifactIds = mapOf("test-dsl" to "wow-test")
fun Project.artifactName(): String =
    if (this == v10BomProject || this in v10Projects && this !in v10ExampleProjects) {
        v10ArtifactIds[name] ?: "wow-$name"
    } else {
        name
    }
// Libraries that build and test like the others but are not published yet. The dataset registry (dataset platform
// design R1b) left with the analytics service (R5d). On v10: the V10 modules a release does not publish (the platform's
// domains and servers, :architecture, the spikes and the eShop).
val incubatingProjects = v10Projects - v10PublishedLibraries
val publishProjects =
    subprojects - exampleProjects - codeCoverageReportProject - benchmarksProject - incubatingProjects
val libraryProjects = publishProjects - bomProjects + exampleLibraries + benchmarksProject + incubatingProjects
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
            // (`v10UserProjects`, the 9.x example libraries) does not (D216).
            if (project !in v10UserProjects && project !in exampleLibraries) {
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
// ~/.gradle/gradle.properties sign. A V10 publication requires them only with `-Pwow.publication.signed=true` (the
// release's preflight), so `v10PublicationCheck` also runs in a pull request, which has no key.
val requireV10Signatures = providers.gradleProperty("wow.publication.signed").orNull.toBoolean()

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
        if (isInCI) {
            val signingKeyId = System.getenv("SIGNING_KEYID")
            val signingKey = System.getenv("SIGNING_SECRETKEY")
            val signingPassword = System.getenv("SIGNING_PASSWORD")
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }
        if (project in v10ReleaseProjects || project == dependenciesProject) {
            isRequired = requireV10Signatures
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

// The release rehearsal of the V10 publications (R.1): `./gradlew -Pwow.scope=v10 v10PublicationCheck` publishes
// every V10 release module and :wow-dependencies into build/v10-publication-check/repo, then checks that each library
// has its jar, sources and javadoc jars, POM and Gradle module metadata (and their signatures, with
// `-Pwow.publication.signed=true`); that every POM carries what Maven Central requires; that no published POM depends
// on a Wow module the release leaves out, or on another version; that the V10 BOM constrains exactly the published
// libraries; and that nothing else (no 9.x module) was published.
val v10PublicationCheckRepository: Provider<Directory> = layout.buildDirectory.dir("v10-publication-check/repo")
val v10PublicationProjects = v10ReleaseProjects + dependenciesProject
val cleanV10PublicationCheck = tasks.register<Delete>("cleanV10PublicationCheck") {
    delete(v10PublicationCheckRepository)
}
configure(v10PublicationProjects) {
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "v10PublicationCheck"
                url = uri(v10PublicationCheckRepository)
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToV10PublicationCheckRepository")) mustRunAfter(cleanV10PublicationCheck)
    }
}
tasks.register("v10PublicationCheck") {
    description = "Publishes the V10 release modules into a scratch repository and checks what a release would ship."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val publishTasks = v10PublicationProjects.map {
        it.tasks.named("publishAllPublicationsToV10PublicationCheckRepository")
    }
    dependsOn(cleanV10PublicationCheck)
    dependsOn(publishTasks)
    val repository = v10PublicationCheckRepository.get().asFile
    val groupId = project.group.toString()
    val releaseVersion = project.version.toString()
    val libraries = v10PublicationProjects.filter { it !in bomProjects }.map { it.artifactName() }.toSortedSet()
    val platforms = v10PublicationProjects.filter { it in bomProjects }.map { it.artifactName() }.toSortedSet()
    val bomArtifactId = v10BomProject.artifactName()
    val bomConstrained = v10BomConstrainedProjects.map { it.artifactName() }.toSortedSet()
    val signed = requireV10Signatures
    // The marker java-gradle-plugin publishes beside wow-gradle-plugin.
    val pluginMarker = "me.ahoo.wow.gradle.plugin"
    doLast {
        val problems = mutableListOf<String>()
        val groupDir = File(repository, groupId.replace('.', '/'))
        fun file(artifactId: String, suffix: String) =
            File(groupDir, "$artifactId/$releaseVersion/$artifactId-$releaseVersion$suffix")
        fun requireFiles(artifactId: String, suffixes: List<String>) {
            suffixes.flatMap { if (signed) listOf(it, "$it.asc") else listOf(it) }.forEach { suffix ->
                if (!file(artifactId, suffix).isFile) problems += "$artifactId: no $artifactId-$releaseVersion$suffix"
            }
        }
        libraries.forEach { requireFiles(it, listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")) }
        platforms.forEach { requireFiles(it, listOf(".pom", ".module")) }
        requireFiles(pluginMarker, listOf(".pom"))

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
