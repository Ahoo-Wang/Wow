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
 * An api module published under an artifactId of its own, not its project's name, as Wow publishes `:ops-api` as
 * `wow-ops-api` (Gradle TestKit): `a`'s build publishes project `a-api` as `acme-a-api`, and holds `z`, which reads
 * `a`'s generated view through `wow.contract(project(":a-api"))`. From the repository, by coordinates, `b`'s domain
 * reads it through `wow.contract("repro:acme-a-api:1.0.0")`, and `c`'s service, which depends on the types alone,
 * derives the contract its merge needs; `d`, a build including `a`'s, asks for those coordinates, which its
 * substitution gives `a-api`. Each asks for the module's feature `contract`, as a consumer by coordinates does:
 * `<group>:<artifactId>-contract`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PublishedArtifactIdFunctionalTest {
    private lateinit var root: File
    private val a: File get() = File(root, "a")
    private val b: File get() = File(root, "b")
    private val c: File get() = File(root, "c")
    private val d: File get() = File(root, "d")
    private val repository: String get() = File(root, "repo").toURI().toString()
    private val published = "\"repro:acme-a-api:1.0.0\""

    private fun settings(dir: File, name: String, vararg contexts: String) {
        val modules = contexts.flatMap { listOf("$it-api", "$it-domain", "$it-server") }
        ContextsFixture.write(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "$name"
            include(${modules.joinToString { "\"$it\"" }})
            """,
        )
        ContextsFixture.properties(dir)
    }

    private val coordinates = """
        group = "repro"
        version = "1.0.0"
    """

    /** `a`'s build: `a-api` published as `acme-a-api`, and `z`, which reads `a`'s view by the project. */
    private fun publisher() {
        settings(a, "a", "a", "z")
        val publishing = """
            $coordinates
            apply(plugin = "maven-publish")
            extensions.configure<PublishingExtension> {
                publications { create<MavenPublication>("api") { artifactId = "acme-a-api"; from(components["java"]) } }
                repositories { maven { url = uri("${'$'}{rootDir}/../repo") } }
            }
        """
        val alpha = ContextsFixture.Context(
            "a",
            "Alpha",
            "z",
            "Zeta",
            consumes = false,
            extra = coordinates,
            apiExtra = publishing,
        )
        ContextsFixture.context(a, alpha)
        val zeta = ContextsFixture.Context("z", "Zeta", "a", "Alpha", views = true, extra = coordinates)
        ContextsFixture.context(a, zeta)
    }

    /** The build of [name], consuming `a` by its published coordinates, from [from] when given. */
    private fun consumer(dir: File, name: String, aggregate: String, views: Boolean, from: String?) {
        settings(dir, name, name)
        val context = ContextsFixture.Context(
            name,
            aggregate,
            "a",
            "Alpha",
            views = views,
            extra = coordinates,
            peerApi = published,
            repository = from,
        )
        ContextsFixture.context(dir, context)
    }

    @BeforeAll
    fun builds(@TempDir directory: File) {
        root = directory
        publisher()
        consumer(b, "b", "Beta", views = true, from = repository)
        consumer(c, "c", "Gamma", views = false, from = repository)
        // A build including a's, which asks for its published coordinates, substituted by its project: Gradle
        // substitutes an included build's projects by their group and name, so another name is substituted by hand.
        consumer(d, "d", "Delta", views = true, from = null)
        File(d, "settings.gradle.kts").appendText(
            """

            includeBuild("../a") {
                dependencySubstitution { substitute(module("repro:acme-a-api")).using(project(":a-api")) }
            }
            """.trimIndent(),
        )
    }

    private fun runner(dir: File, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    @Test
    @Order(1)
    fun `the api module should publish under its artifactId, and its build's own consumer read it by project`() {
        val result = runner(a, ":a-api:publish", ":z-domain:compileKotlin", ":z-server:${WowPlugin.MERGE_TASK}").build()
        result.task(":z-server:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        File(root, "repo/repro/acme-a-api/1.0.0").list()!!.filter { it.endsWith(".jar") }.sorted()
            .assert().containsExactly("acme-a-api-1.0.0-contract.jar", "acme-a-api-1.0.0.jar")
        // The contract variants offer the capability of the published module and of the project.
        File(root, "repo/repro/acme-a-api/1.0.0/acme-a-api-1.0.0.module").readText().assert()
            .contains("\"name\": \"acme-a-api-contract\"", "\"name\": \"a-api-contract\"")
    }

    @Test
    @Order(2)
    fun `a domain should read the published contract by the module's coordinates`() {
        runner(b, ":b-domain:compileKotlin").build().task(":b-domain:compileKotlin")?.outcome
            .assert().isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    @Order(3)
    fun `a service should derive the published contract of the api module it depends on`() {
        runner(c, ":c-server:${WowPlugin.MERGE_TASK}").build().task(":c-server:${WowPlugin.MERGE_TASK}")?.outcome
            .assert().isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    @Order(4)
    fun `a build including the api's should read its contract by the published coordinates, from its project`() {
        val result = runner(d, ":d-domain:compileKotlin", ":d-server:${WowPlugin.MERGE_TASK}").build()
        result.task(":a:a-api:${WowPlugin.CONTRACT_JAR_TASK}")?.outcome.assert().isNotNull()
        result.task(":d-server:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
    }
}
