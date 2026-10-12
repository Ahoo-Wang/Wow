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
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
val bomProjects = includedProjects(":wow-bom") + dependenciesProject

val exampleLibraries =
    includedProjects(":example-api", ":example-domain", ":example-transfer-api", ":example-transfer-domain")
val viewStoreServerProject = includedProjects(":wow-view-store-server")
val analyticsServerProject = findProject(":wow-analytics-server")
val exampleProjects =
    exampleLibraries + includedProjects(":example-server", ":example-transfer-server", ":wow-compensation-server") +
        viewStoreServerProject + listOfNotNull(analyticsServerProject)

val codeCoverageReportProject = project(":code-coverage-report")
val benchmarksProject = includedProjects(":wow-benchmarks")
// The V10 modules (documentation/designs/v10/06-modules.md). Every module builds on JDK 21 (decision D29); these stay
// incubating until their API settles; :architecture (V10's dependency rules), :kernel-benchmarks (spike H3) and
// :routing-spike (spike H2) and :lease-spike (spike H6) are never published.
val v10Projects = listOf(
    "api", "manifest", "query-api", "spi", "kernel", "runtime", "reactor",
    "postgres", "mongo", "http", "spring-boot", "migrate-data", "architecture", "kernel-benchmarks",
    "compiler", "gradle-plugin", "manifest-tools",
    "routing-spike", "lease-spike",
).let { names -> includedProjects(*names.map { ":$it" }.toTypedArray()) }

// D87: a V10 project's name and directory carry no `wow-` prefix (`:kernel` in core/kernel); its jar, Kotlin module and
// Maven artifactId do (`wow-kernel`), so the coordinates users see are unchanged. A project whose name cannot be its
// artifact minus the prefix (a Gradle task name, such as the test DSL's `:test-dsl`) is mapped here.
val v10ArtifactIds = mapOf<String, String>()
fun Project.artifactName(): String = if (this in v10Projects) v10ArtifactIds[name] ?: "wow-$name" else name
// Libraries that build and test like the others but are not published yet. The dataset registry (dataset platform
// design R1b) left with the analytics service (R5d).
val incubatingProjects = v10Projects
val publishProjects =
    subprojects - exampleProjects - codeCoverageReportProject - benchmarksProject - incubatingProjects
val libraryProjects = publishProjects - bomProjects + exampleLibraries + benchmarksProject + incubatingProjects
// What wow-bom constrains: the published libraries only, never an example, the benchmarks or an incubating module,
// whose coordinates are not on Maven Central.
val bomConstrainedProjects = publishProjects - bomProjects
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
    ":routing-spike",
    ":lease-spike",
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
            optIn.add("me.ahoo.wow.api.annotation.InternalWowApi")
            // Test, contract and benchmark code exercises the SPI directly; main code opts in per module.
            if (project in wowSpiProjects || name != "compileKotlin") {
                optIn.add("me.ahoo.wow.api.annotation.WowSpi")
            }
            javaParameters = true
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
// the same modules. V10 modules keep no ABI dump before 10.0.0-beta (D88), so no `checkKotlinAbi` runs here. The full
// build also compiles the JMH sources of :kernel-benchmarks, which the v10 scope leaves out.
tasks.register("v10Check") {
    description = "Compiles, unit-tests and runs detekt over the V10 modules."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(v10Projects.map { it.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) })
    dependsOn(v10Projects.map { it.tasks.named("detekt") })
    findProject(":kernel-benchmarks")?.let { dependsOn(it.tasks.named("compileJmhKotlin")) }
}

// Container-backed (Docker/Testcontainers). The spikes' suites skip themselves unless their flags are set.
tasks.register("v10IntegrationTest") {
    description = "Runs the container-backed integration tests of the V10 modules."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(v10IntegrationTestProjects.map { it.tasks.named(WowTestLayer.INTEGRATION.taskName) })
}

configure(publishProjects) {
    val isBom = bomProjects.contains(this)
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
            val publishName = if (isBom) "mavenBom" else "mavenLibrary"
            val publishComponentName = if (isBom) "javaPlatform" else "java"
            create<MavenPublication>(publishName) {
                from(components[publishComponentName])
                artifactId = project.artifactName()
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

        if (isBom) {
            sign(extensions.getByType(PublishingExtension::class).publications["mavenBom"])
        } else {
            sign(extensions.getByType(PublishingExtension::class).publications["mavenLibrary"])
        }
    }
}

// D90: v10 does not publish the 9.x modules; main does. A 10.0 pre-release from v10 would otherwise publish them as
// 10.x. Their publications are still built (and signed), and their ABI is still checked below.
configure(publishProjects - v10Projects) {
    tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
    tasks.withType<PublishToMavenLocal>().configureEach { enabled = false }
}

// The JVM ABI of every published library is kept in its `api/<module>.api`; `check` runs `checkKotlinAbi`
// against it, so a 9.x patch cannot change a public signature unnoticed. `./gradlew updateKotlinAbi` rewrites
// the dumps; AGENTS.md ("Binary Compatibility") says when that is allowed.
configure(publishProjects - bomProjects) {
    configure<KotlinJvmProjectExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation {
            // Declarations Wow's own modules share but promise nothing about (see its KDoc).
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
