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

import java.io.File

/**
 * Two contexts' modules for Gradle TestKit builds: each context an api, a domain and a service module, its domain
 * recording the aggregates its peer opens ([Context]). [ContractSplitFunctionalTest] builds two in one build,
 * [CrossRepositoryFunctionalTest] each in a build of its own.
 */
internal object ContextsFixture {
    fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    /**
     * The module [path] of [plugin], depending on [dependencies], resolving from [repository] too when given, its build
     * file ending with [extra].
     */
    fun module(
        root: File,
        path: String,
        plugin: String,
        dependencies: String,
        extra: String = "",
        repository: String? = null,
    ) {
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
                ${FixtureRepositories.central}
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
                ${repository?.let { "maven { url = uri(\"$it\") }" }.orEmpty()}
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
    data class Context(
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
        /** The peer's api as the domain depends on it: its project in one build, its coordinates across builds. */
        val peerApi: String = "project(\":$peer-api\")",
        /** A Maven repository the modules resolve from too: the one another build publishes to. */
        val repository: String? = null,
    )

    fun context(root: File, context: Context) {
        api(root, context)
        domain(root, context)
        if (context.consumes) saga(root, context)
        val domain = "implementation(project(\":${context.name}-domain\"))"
        module(root, "${context.name}-server", WowPlugin.SERVICE_ID, domain, context.extra, context.repository)
    }

    fun api(root: File, context: Context) = with(context) {
        val dependencies = "wowContract(project(\":$name-domain\"))\n$api"
        module(root, "$name-api", WowPlugin.API_ID, dependencies, apiExtra, repository)
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

    fun domain(root: File, context: Context) = with(context) {
        val peerDependency = when {
            !consumes -> ""
            contract -> "implementation(wow.contract($peerApi))"
            else -> "implementation($peerApi)"
        }
        module(root, "$name-domain", WowPlugin.ID, "api(project(\":$name-api\"))\n$peerDependency", extra, repository)
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
    fun saga(root: File, context: Context) = with(context) {
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

    /** The `gradle.properties` of a build at [root]: the Kotlin daemon in-process, and a heap for the build. */
    fun properties(root: File) {
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
    }
}
