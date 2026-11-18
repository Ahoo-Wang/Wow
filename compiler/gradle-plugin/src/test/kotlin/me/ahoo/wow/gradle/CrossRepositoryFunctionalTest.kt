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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Two contexts that consume each other's events, `a` and `b`, each in a build of its own, publishing its api module
 * into one Maven repository (Gradle TestKit); `b-api` uses a type of `a-api`. An api module publishes its contract
 * within itself, its feature `contract`, so publishing it needs its domain compiled, which needs the peer's types: the
 * first release of the cycle publishes `a`'s types alone (`-Pwow.contract.publish=false`), `b` releases in full against
 * them, then `a` in full, and every later release builds against its peer's previous one, as 9.x did.
 *
 * Then what the published contract is to others: a module consuming a service's runtime variant gets its contracts
 * (configuration cache, warnings as failures, isolated projects); a consumer without a Wow plugin, and one reading the
 * POM alone, get the types; a service of a build that includes `a`'s build gets `a`'s contract from its project.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CrossRepositoryFunctionalTest {
    private lateinit var root: File
    private val repository: String get() = File(root, "repo").toURI().toString()
    private val a: File get() = File(root, "a")
    private val b: File get() = File(root, "b")

    /** The build of [context]: its three modules, its api published into [repository], its peer's by coordinates. */
    private fun build(dir: File, context: ContextsFixture.Context) {
        ContextsFixture.write(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "${context.name}"
            include("${context.name}-api", "${context.name}-domain", "${context.name}-server")
            """,
        )
        ContextsFixture.properties(dir)
        ContextsFixture.context(dir, context)
    }

    private val coordinates = """
        group = "repro"
        version = providers.gradleProperty("repro.version").get()
    """

    private val published = """
        $coordinates
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications { create<MavenPublication>("api") { from(components["java"]) } }
            repositories { maven { url = uri("${'$'}{rootDir}/../repo") } }
        }
    """

    private fun peer(name: String) = "\"repro:$name-api:\" + providers.gradleProperty(\"repro.peer\").get()"

    @BeforeAll
    fun builds(@TempDir directory: File) {
        root = directory
        build(
            a,
            ContextsFixture.Context(
                "a",
                "Alpha",
                "b",
                "Beta",
                extra = coordinates,
                apiExtra = published,
                peerApi = peer("b"),
                repository = repository,
            ),
        )
        build(
            b,
            ContextsFixture.Context(
                "b",
                "Beta",
                "a",
                "Alpha",
                api = "api(${peer("a")})",
                amount = ", val amount: a.api.AlphaAmount",
                extra = coordinates,
                apiExtra = published,
                peerApi = peer("a"),
                repository = repository,
            ),
        )
    }

    private fun runner(dir: File, version: String, peer: String, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*arguments, "-Prepro.version=$version", "-Prepro.peer=$peer", "--stacktrace")
            .forwardOutput()

    private fun publishedModule(name: String, version: String): String =
        File(root, "repo/repro/$name/$version/$name-$version.module").readText()

    private fun publishedJars(name: String, version: String): List<String> =
        File(root, "repo/repro/$name/$version").list()!!.filter { it.endsWith(".jar") }.sorted()

    @Test
    @Order(1)
    fun `the first release of a cycle should publish one member's types alone`() {
        runner(a, "1.0.0-types", "none", "-P${ContractRole.PUBLISH_PROPERTY}=false", ":a-api:publish").build()
            .task(":a-domain:compileKotlin").assert().isNull()
        publishedJars("a-api", "1.0.0-types").assert().containsExactly("a-api-1.0.0-types.jar")
        publishedModule("a-api", "1.0.0-types").assert()
            .doesNotContain(ContractRole.CONTRACT_API_ELEMENTS, WowPlugin.CONTRACT_ATTRIBUTE.name)
    }

    @Test
    @Order(2)
    fun `the peer should release in full against those types, its service waiting for their contract`() {
        val result = runner(b, "1.0.0", "1.0.0-types", ":b-api:publish", ":b-domain:check").build()
        result.task(":b-domain:check")?.outcome.assert().isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
        publishedJars("b-api", "1.0.0").assert().containsExactly("b-api-1.0.0-contract.jar", "b-api-1.0.0.jar")
        runner(b, "1.0.0", "1.0.0-types", ":b-server:${WowPlugin.MERGE_TASK}").buildAndFail().output.assert()
            .contains("AlphaOpened, which no peer contract on the class path lists")
    }

    @Test
    @Order(3)
    fun `the first member should then release in full, and each service merge`() {
        runner(a, "1.0.0", "1.0.0", ":a-api:publish", ":a-server:${WowPlugin.MERGE_TASK}").build()
        publishedJars("a-api", "1.0.0").assert().containsExactly("a-api-1.0.0-contract.jar", "a-api-1.0.0.jar")
        publishedModule("a-api", "1.0.0").assert().contains(""""${WowPlugin.CONTRACT_ATTRIBUTE.name}": "contract"""")
        runner(b, "1.0.1", "1.0.0", ":b-server:${WowPlugin.MERGE_TASK}").build()
            .task(":b-server:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
    }

    /** The files a `files` task ([printing]) printed, by name. */
    private fun printed(result: BuildResult): List<String> =
        result.output.lines().single { it.startsWith("FILES=") }.removePrefix("FILES=").split(',')
            .filter { it.isNotBlank() }.map { File(it).name }.sorted()

    private fun printing(configuration: String) = """
        tasks.register("files") {
            val files = configurations.named("$configuration").map { it.files.map { file -> file.path } }
            doLast { println("FILES=" + files.get().joinToString(",")) }
        }
    """

    @Test
    @Order(4)
    fun `a later release should build against its peer's previous one, and whoever consumes the service run it`() {
        // The end-to-end module of `a`: a server's runtime variant, resolved from another project, holds its contracts.
        File(a, "settings.gradle.kts").appendText("\ninclude(\"a-e2e\")\n")
        ContextsFixture.write(
            a,
            "a-e2e/build.gradle.kts",
            """
            plugins { java }
            val server = configurations.dependencyScope("server")
            val serverClasspath = configurations.resolvable("serverClasspath") {
                extendsFrom(server.get())
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                }
            }
            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
                maven { url = uri("$repository") }
            }
            dependencies { "server"(project(":a-server")) }
            ${printing("serverClasspath")}
            """,
        )
        val steady = runner(
            a,
            "1.1.0",
            "1.0.0",
            ":a-api:publish",
            ":a-server:${WowPlugin.MERGE_TASK}",
            ":a-e2e:files",
            "--configuration-cache",
            "--warning-mode=fail",
            "-Dorg.gradle.unsafe.isolated-projects=true",
        ).build()
        steady.task(":a-server:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        printed(steady).filter { it.endsWith("-contract.jar") }.assert()
            .containsExactly("a-api-1.1.0-contract.jar", "b-api-1.0.0-contract.jar")
    }

    @Test
    @Order(5)
    fun `a consumer without a Wow plugin should get the types, and the contract when it asks for the feature`(
        @TempDir consumer: File,
    ) {
        fun consume(declaration: String, repositoryBlock: String): List<String> {
            ContextsFixture.write(consumer, "settings.gradle.kts", """rootProject.name = "consumer"""")
            ContextsFixture.write(
                consumer,
                "build.gradle.kts",
                """
                plugins { `java-library` }
                repositories {
                    mavenCentral()
                    maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
                    $repositoryBlock
                }
                dependencies { $declaration }
                ${printing("runtimeClasspath")}
                """,
            )
            return printed(GradleRunner.create().withProjectDir(consumer).withArguments("files").build())
                .filter { it.startsWith("a-api") || it.startsWith("b-api") }
        }
        val module = """maven { url = uri("$repository") }"""
        // The attribute naming the feature takes part in no selection.
        val types = """implementation("repro:a-api:1.1.0")"""
        consume(types, module).assert().containsExactly("a-api-1.1.0.jar")
        val contract = """implementation("repro:a-api:1.1.0") { capabilities { requireFeature("contract") } }"""
        consume(contract, module).assert().containsExactly("a-api-1.1.0-contract.jar", "a-api-1.1.0.jar")
        // A consumer reading the POM alone gets the types, and nothing to derive.
        val pom = """maven { url = uri("$repository"); metadataSources { mavenPom(); artifact() } }"""
        consume(types, pom).assert().containsExactly("a-api-1.1.0.jar")
    }

    @Test
    @Order(6)
    fun `a service of a build that includes a context's build should take the contract from its project`(
        @TempDir including: File,
    ) {
        // The included build reads no command line property of the including one.
        val properties = File(a, "gradle.properties")
        properties.writeText("repro.version=1.1.0\nrepro.peer=1.0.0\n" + properties.readText())
        ContextsFixture.write(
            including,
            "settings.gradle.kts",
            """
            rootProject.name = "including"
            includeBuild("${a.invariantSeparatorsPath}")
            include("c-server")
            """,
        )
        ContextsFixture.properties(including)
        ContextsFixture.module(
            including,
            "c-server",
            WowPlugin.SERVICE_ID,
            "implementation(\"repro:a-domain:1.1.0\")",
            printing("runtimeClasspath"),
            repository,
        )
        val result = GradleRunner.create().withProjectDir(including).withPluginClasspath()
            .withArguments(":c-server:files", "--stacktrace").forwardOutput().build()
        val contracts = result.output.lines().single { it.startsWith("FILES=") }.removePrefix("FILES=").split(',')
            .filter { it.endsWith("-contract.jar") }.map { File(it) }
        // a's from its project's build directory, not the repository's older publication; b's from the repository.
        contracts.map { it.name }.assert()
            .containsExactlyInAnyOrder("a-api-1.1.0-contract.jar", "b-api-1.0.0-contract.jar")
        contracts.single { it.name.startsWith("a-api") }.canonicalFile.invariantSeparatorsPath.assert()
            .startsWith("${a.canonicalFile.invariantSeparatorsPath}/a-api/build/")
    }
}
