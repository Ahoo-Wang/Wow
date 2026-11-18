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
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Two contexts in one build, `a` and `b`, each an api, a domain and a service module (Gradle TestKit): the layouts
 * that were a task cycle while an api module's own variants carried its contract, which comes from compiling its
 * domain (D435). Now an api's variants hold its types, its contract is its feature `contract`, and each layout builds:
 *
 * - each domain consumes the other's events, `b-api` uses a type of `a-api`, and `a-domain` reads `b`'s state as the
 *   generated `BetaStateView` (`wow.contract(project(":b-api"))`);
 * - each domain consumes the other's events, the api modules share no type;
 * - only `a-domain` consumes `b`'s events, and `b-api` uses a type of `a-api`: the 9.x layering of an api that depends
 *   on another context's api.
 *
 * Each service merges its Manifest, which finds the peer's events in the peer's contract.
 */
class ContractSplitFunctionalTest {
    /**
     * The two contexts: `b-api` uses `a-api`'s amount when [shared]; `b-domain` consumes `a`'s events when [mutual];
     * `a-domain` reads `b`'s state as its view when [views].
     */
    private fun build(
        root: File,
        shared: Boolean,
        mutual: Boolean,
        views: Boolean,
        contract: Boolean = views,
        extra: String = "",
        apiExtra: String = extra,
    ) {
        ContextsFixture.write(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "contexts"
            include("a-api", "a-domain", "a-server", "b-api", "b-domain", "b-server")
            """,
        )
        ContextsFixture.properties(root)
        ContextsFixture.context(
            root,
            ContextsFixture.Context(
                "a",
                "Alpha",
                "b",
                "Beta",
                views = views,
                contract = contract,
                extra = extra,
                apiExtra = apiExtra,
            ),
        )
        ContextsFixture.context(
            root,
            ContextsFixture.Context(
                "b",
                "Beta",
                "a",
                "Alpha",
                api = if (shared) "api(project(\":a-api\"))" else "",
                amount = if (shared) ", val amount: a.api.AlphaAmount" else "",
                consumes = mutual,
                extra = extra,
                apiExtra = apiExtra,
            ),
        )
    }

    private fun runner(root: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace")
        .forwardOutput()

    /** Builds both services' Manifests, and returns the contexts each service's subscriptions take events of. */
    private fun merge(root: File): Map<String, List<String>> {
        val result = runner(root, ":a-server:${WowPlugin.MERGE_TASK}", ":b-server:${WowPlugin.MERGE_TASK}").build()
        listOf("a", "b").forEach {
            result.task(":$it-server:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        }
        return listOf("a", "b").associateWith { context ->
            ManifestJson.read(File(root, "$context-server/build/wow/manifest.json")).contexts
                .flatMap { it.subscriptions }.mapNotNull { it.source.context }
        }
    }

    @Test
    fun `two contexts consuming each other's events, one api using the other's types, should build`(
        @TempDir root: File
    ) {
        build(root, shared = true, mutual = true, views = true)
        merge(root).assert().isEqualTo(mapOf("a" to listOf("b"), "b" to listOf("a")))
    }

    @Test
    fun `two contexts consuming each other's events, sharing no type, should build`(@TempDir root: File) {
        build(root, shared = false, mutual = true, views = false)
        merge(root).assert().isEqualTo(mapOf("a" to listOf("b"), "b" to listOf("a")))
    }

    @Test
    fun `a context consuming the events of one whose api uses its types should build`(@TempDir root: File) {
        build(root, shared = true, mutual = false, views = false)
        merge(root).assert().isEqualTo(mapOf("a" to listOf("b"), "b" to emptyList()))
    }

    @Test
    fun `a domain using a peer's generated view without asking for its contract should not compile`(
        @TempDir root: File
    ) {
        build(root, shared = false, mutual = false, views = true, contract = false)
        val failed = runner(root, ":a-domain:compileKotlin").buildAndFail()
        // The peer's types resolve, its api's own variant being them; its generated view is the contract's alone.
        failed.output.assert().contains("BetaStateView").doesNotContain("BetaOpened did not resolve")
    }

    @Test
    fun `a service should see the contracts of api modules whose JVM target is set after the plugin's evaluation`(
        @TempDir root: File
    ) {
        val target = """
            java {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
            kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
        """
        // The api modules set it in an afterEvaluate of their own, which runs after the one the plugin offers the
        // contract in: a view whose JVM version was taken then (the build's default) would not fit the services'
        // request for 21, Gradle would pick the api's own variant, and the merge would find no peer contract.
        val late = "afterEvaluate {\n$target\n}"
        build(root, shared = true, mutual = true, views = false, extra = target, apiExtra = late)
        merge(root).assert().isEqualTo(mapOf("a" to listOf("b"), "b" to listOf("a")))
    }
}
