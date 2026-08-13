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
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testretry.TestRetryPlugin
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    alias(libs.plugins.test.retry)
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    jacoco
}

val dependenciesProject = project(":wow-dependencies")
val bomProjects = setOf(
    project(":wow-bom"),
    dependenciesProject,
)

val exampleDomainProject = project(":example-domain")
val exampleLibraries = setOf(
    project(":example-api"), exampleDomainProject,
    project(":example-transfer-api"),
    project(":example-transfer-domain")
)
val exampleProjects =
    exampleLibraries + project(":example-server") + project(":example-transfer-server") + project(":wow-compensation-server")

val testProject = project(":wow-test")
val codeCoverageReportProject = project(":code-coverage-report")
val benchmarksProject = project(":wow-benchmarks")
val publishProjects = subprojects - exampleProjects - codeCoverageReportProject - benchmarksProject
val libraryProjects = publishProjects - bomProjects + exampleLibraries + benchmarksProject
val isInCI = !System.getenv("CI").isNullOrEmpty()
ext.set("libraryProjects", libraryProjects)

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
val localTestTaskProjects = localTestProjects + project(":wow-compensation-server")
val localContractTestProjects = setOf(
    project(":wow-core"),
    project(":wow-opentelemetry"),
    project(":wow-mock"),
)
val integrationTestProjects = setOf(
    project(":wow-bi"),
    project(":wow-mongo"),
    project(":wow-redis"),
    project(":wow-kafka"),
    project(":wow-elasticsearch"),
    project(":wow-it"),
)

val queryApiModules = listOf(
    ":wow-api",
    ":wow-query",
    ":wow-webflux",
    ":wow-spring",
    ":wow-spring-boot-starter",
    ":wow-mongo",
    ":wow-elasticsearch",
    ":wow-cosec",
)
val queryApiExpectedModules = layout.projectDirectory.file("config/query-api/expected-modules.txt")
ext.set("localTestProjects", localTestProjects)
ext.set("localContractTestProjects", localContractTestProjects)
ext.set("integrationTestProjects", integrationTestProjects)

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
            attributes["Implementation-Title"] = project.name
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
    apply<JacocoPlugin>()
    apply<JavaLibraryPlugin>()
    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }
    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
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

val queryApiRuntimeClasspathTaskName = "writeQueryApiRuntimeClasspath"
configure(queryApiModules.map(::project)) {
    val sourceSets = extensions.getByType<SourceSetContainer>()
    val mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    val runtimeDependencyClasspath = providers.provider {
        mainSourceSet.runtimeClasspath.minus(mainSourceSet.output)
    }
    val runtimeDependencyClasspathOrder = runtimeDependencyClasspath.map { classpath ->
        classpath.files.map(File::getAbsolutePath)
    }
    val runtimeClasspathFile = layout.buildDirectory.file("query-api/runtime-classpath.txt")
    tasks.register(queryApiRuntimeClasspathTaskName) {
        inputs.files(runtimeDependencyClasspath)
            .withPropertyName("runtimeDependencyClasspath")
            .withNormalizer(ClasspathNormalizer::class.java)
        inputs.property("runtimeDependencyClasspathOrder", runtimeDependencyClasspathOrder)
        outputs.file(runtimeClasspathFile)
        doLast {
            val classpathEntries = runtimeDependencyClasspath.get().files
            val missingRequiredEntries = classpathEntries.filter { entry -> !entry.exists() }
            check(missingRequiredEntries.isEmpty()) {
                "Runtime classpath contains missing required entries: $missingRequiredEntries"
            }
            val output = runtimeClasspathFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                classpathEntries.joinToString(separator = "\n", postfix = "\n") { it.absolutePath }
            )
        }
    }
}

fun queryApiModuleRuntimeClasspath(module: String): List<String> = project(module)
    .layout
    .buildDirectory
    .file("query-api/runtime-classpath.txt")
    .get()
    .asFile
    .readLines()
    .filter(String::isNotEmpty)

fun queryApiModuleRuntimeDependencies(module: String): FileCollection {
    val sourceSets = project(module).extensions.getByType<SourceSetContainer>()
    val mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    return mainSourceSet.runtimeClasspath.minus(mainSourceSet.output)
}

fun queryApiRuntimeClasspath(): String = queryApiModules
    .map(::queryApiModuleRuntimeClasspath)
    .flatten()
    .joinToString(File.pathSeparator)

val queryApiKotlinCompiler = configurations.detachedConfiguration(
    dependencies.create("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.asProvider().get()}")
)

val queryApiSourceCheck = tasks.register<Exec>("queryApiSourceCheck") {
    description = "Compiles query admission API fixtures as external Java and Kotlin modules."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(":wow-query:jar")
    dependsOn(":wow-query:$queryApiRuntimeClasspathTaskName")
    dependsOn(":wow-mongo:jar")
    dependsOn(":wow-mongo:$queryApiRuntimeClasspathTaskName")
    dependsOn(":wow-elasticsearch:jar")
    dependsOn(":wow-elasticsearch:$queryApiRuntimeClasspathTaskName")
    val wowQueryJar = project(":wow-query").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val wowMongoJar = project(":wow-mongo").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val wowElasticsearchJar = project(":wow-elasticsearch").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtimeDependencies = queryApiModuleRuntimeDependencies(":wow-query")
        .plus(queryApiModuleRuntimeDependencies(":wow-mongo"))
        .plus(queryApiModuleRuntimeDependencies(":wow-elasticsearch"))
        .minus(files(wowQueryJar, wowMongoJar, wowElasticsearchJar))
    inputs.file("scripts/query-api-source-check.sh")
    inputs.file(wowQueryJar)
    inputs.file(wowMongoJar)
    inputs.file(wowElasticsearchJar)
    inputs.file(project(":wow-query").layout.buildDirectory.file("query-api/runtime-classpath.txt"))
    inputs.file(project(":wow-mongo").layout.buildDirectory.file("query-api/runtime-classpath.txt"))
    inputs.file(project(":wow-elasticsearch").layout.buildDirectory.file("query-api/runtime-classpath.txt"))
    inputs.files(runtimeDependencies)
        .withPropertyName("runtimeDependencyClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(queryApiKotlinCompiler)
        .withPropertyName("kotlinCompilerClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    doFirst {
        val wowQueryJar = project(":wow-query").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val wowMongoJar = project(":wow-mongo").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val wowElasticsearchJar = project(":wow-elasticsearch").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val sourceRuntimeClasspath = (
            queryApiModuleRuntimeClasspath(":wow-query") + queryApiModuleRuntimeClasspath(":wow-mongo") +
                queryApiModuleRuntimeClasspath(":wow-elasticsearch")
            ).distinct().filterNot { runtimeEntry ->
                runtimeEntry == wowQueryJar.absolutePath || runtimeEntry == wowMongoJar.absolutePath ||
                    runtimeEntry == wowElasticsearchJar.absolutePath
            }
        commandLine(
            "bash",
            "scripts/query-api-source-check.sh",
            wowQueryJar.absolutePath,
            wowMongoJar.absolutePath,
            wowElasticsearchJar.absolutePath,
            sourceRuntimeClasspath.joinToString(File.pathSeparator),
            queryApiKotlinCompiler.asPath,
        )
    }
}

tasks.register<Exec>("queryApiDump") {
    dependsOn(queryApiModules.map { "$it:jar" })
    dependsOn(queryApiModules.map { "$it:$queryApiRuntimeClasspathTaskName" })
    inputs.file(queryApiExpectedModules)
    doFirst {
        commandLine(
            "bash",
            "scripts/query-api-abi.sh",
            "dump",
            "--expected-modules",
            queryApiExpectedModules.asFile.absolutePath,
            "--runtime-classpath",
            queryApiRuntimeClasspath(),
            "--classpath-separator",
            File.pathSeparator,
        )
    }
}

tasks.register<Exec>("queryApiCheck") {
    dependsOn(queryApiSourceCheck)
    dependsOn(queryApiModules.map { "$it:jar" })
    dependsOn(queryApiModules.map { "$it:$queryApiRuntimeClasspathTaskName" })
    inputs.file(queryApiExpectedModules)
    doFirst {
        commandLine(
            "bash",
            "scripts/query-api-abi.sh",
            "check",
            "--expected-modules",
            queryApiExpectedModules.asFile.absolutePath,
            "--runtime-classpath",
            queryApiRuntimeClasspath(),
            "--classpath-separator",
            File.pathSeparator,
        )
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
}

tasks.register("benchmarkSmoke") {
    description = "Runs the PR-safe JMH smoke benchmark set."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(benchmarksProject.tasks.named("benchmarkSmoke"))
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
