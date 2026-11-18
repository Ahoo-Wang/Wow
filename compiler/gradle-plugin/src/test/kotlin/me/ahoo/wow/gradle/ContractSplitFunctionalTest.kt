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
    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    /** The module [path] of [plugin], depending on [dependencies], its build file ending with [extra]. */
    private fun module(root: File, path: String, plugin: String, dependencies: String, extra: String = "") {
        val compiler = if (plugin == WowPlugin.API_ID) {
            ""
        } else {
            "ksp(files(${classpath("wow-test.fixture.processorClasspath")}))"
        }
        write(
            root,
            "$path/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("$plugin")
            }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                $compiler
                wowTools(files(${classpath("wow-test.fixture.toolsClasspath")}))
                $dependencies
            }

            $extra
            """,
        )
    }

    /**
     * Context [name] (aggregate [aggregate]), whose peer is [peer] (aggregate [peerAggregate]): its api, declaring
     * [api] too, whose open command takes [amount] when given; its domain, which records the peer's aggregate when
     * [consumes], reading its state as its generated view when [views]; and its service.
     */
    private data class Context(
        val name: String,
        val aggregate: String,
        val peer: String,
        val peerAggregate: String,
        val api: String = "",
        val amount: String = "",
        val consumes: Boolean = true,
        val views: Boolean = false,
        /** Whether a domain reading the peer's view asks for the peer's contract (`wow.contract`). */
        val contract: Boolean = views,
        /** What each module's build file ends with: [apiExtra] for the api module. */
        val extra: String = "",
        val apiExtra: String = extra,
    )

    private fun context(root: File, context: Context) {
        api(root, context)
        domain(root, context)
        if (context.consumes) saga(root, context)
        val domain = "implementation(project(\":${context.name}-domain\"))"
        module(root, "${context.name}-server", WowPlugin.SERVICE_ID, domain, context.extra)
    }

    private fun api(root: File, context: Context) = with(context) {
        module(root, "$name-api", WowPlugin.API_ID, "wowContract(project(\":$name-domain\"))\n$api", apiExtra)
        write(
            root,
            "$name-api/src/main/kotlin/$name/api/Messages.kt",
            """
            package $name.api

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Open$aggregate(val name: String$amount)
            data class ${aggregate}Opened(val name: String)

            @CreateAggregate
            data class Record$peerAggregate(val peerId: String)
            data class ${peerAggregate}Recorded(val peerId: String)

            data class ${aggregate}Amount(val value: Long)
            """,
        )
    }

    private fun domain(root: File, context: Context) = with(context) {
        val peerApi = when {
            !consumes -> ""
            contract -> "implementation(wow.contract(project(\":$peer-api\")))"
            else -> "implementation(project(\":$peer-api\"))"
        }
        module(root, "$name-domain", WowPlugin.ID, "api(project(\":$name-api\"))\n$peerApi", extra)
        write(
            root,
            "$name-domain/src/main/kotlin/$name/domain/$aggregate.kt",
            """
            package $name.domain

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import $name.api.${aggregate}Opened
            import $name.api.Open$aggregate
            import $name.api.Record$peerAggregate
            import $name.api.${peerAggregate}Recorded

            @BoundedContext(name = "$name")
            object ${aggregate}Context

            class ${aggregate}State(val id: String) {
                var name: String = ""
                    private set

                fun onSourcing(event: ${aggregate}Opened) { name = event.name }

                fun onSourcing(event: ${peerAggregate}Recorded) { name = event.peerId }
            }

            @AggregateRoot
            class $aggregate(private val state: ${aggregate}State) {
                fun onCommand(command: Open$aggregate): ${aggregate}Opened = ${aggregate}Opened(command.name)

                fun onCommand(command: Record$peerAggregate): ${peerAggregate}Recorded =
                    ${peerAggregate}Recorded(command.peerId)
            }
            """,
        )
    }

    /** The saga of [context]'s domain that records each aggregate its peer opens. */
    private fun saga(root: File, context: Context) = with(context) {
        val state = if (views) "$peer.api.${peerAggregate}StateView" else "Any?"
        write(
            root,
            "$name-domain/src/main/kotlin/$name/domain/${peerAggregate}Saga.kt",
            """
            package $name.domain

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.subscribe.StateTypedEvent
            import $name.api.Record$peerAggregate
            import $peer.api.${peerAggregate}Opened

            @Saga
            class ${peerAggregate}Saga {
                @OnEvent
                fun on${peerAggregate}Opened(
                    event: StateTypedEvent<$state, ${peerAggregate}Opened>
                ): CommandRequest<Record$peerAggregate> =
                    CommandRequest.of(Record$peerAggregate(event.ref.id), event.ref.id)
            }
            """,
        )
    }

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
        write(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "contexts"
            include("a-api", "a-domain", "a-server", "b-api", "b-domain", "b-server")
            """,
        )
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
        context(
            root,
            Context("a", "Alpha", "b", "Beta", views = views, contract = contract, extra = extra, apiExtra = apiExtra),
        )
        context(
            root,
            Context(
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
