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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * The package of a context's generated `<Context>Contract` (issue 6513), built with the plugins (Gradle TestKit): the
 * domain module declares it, `wow { contractPackage = "…" }`, and it never moves with the classes. The contexts are
 * [ContextsFixture]'s: `a`, its commands and events in `a.api`, an api, a domain and a service module.
 */
class ContractPackageFunctionalTest {
    private fun build(root: File, contractPackage: String?, vararg modules: String) {
        ContextsFixture.write(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "contexts"
            include("a-api", "a-domain", "a-server"${modules.joinToString("") { ", \"$it\"" }})
            """,
        )
        ContextsFixture.properties(root)
        ContextsFixture.context(
            root,
            ContextsFixture.Context("a", "Alpha", "b", "Beta", consumes = false, contractPackage = contractPackage),
        )
    }

    private fun runner(root: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace")
        .forwardOutput()

    private fun entries(jar: File): List<String> = ZipFile(jar).use { zip -> zip.entries().toList().map { it.name } }

    private fun setPackage(root: File, contractPackage: String) {
        val file = File(root, "a-domain/build.gradle.kts")
        file.writeText(
            file.readText().replace(Regex("""contractPackage = "[^"]*""""), "contractPackage = \"$contractPackage\"")
        )
    }

    @Test
    fun `a domain module without a contract package or with no Java package name should fail, naming what to write`(
        @TempDir root: File,
    ) {
        build(root, contractPackage = null)
        val task = ":a-domain:${WowPlugin.GENERATE_CONTRACT_TASK}"
        runner(root, task).buildAndFail().output.assert().contains(
            "Project ':a-domain' sets no contractPackage. It is the package of the code generated for its context a " +
                "(AContract), which other services import, so once it is set it should not change. Add to its build " +
                "file, with the package its command and event classes share today:",
            "wow { contractPackage = \"a.api\" }",
        )
        File(root, "a-domain/build.gradle.kts").appendText("\nwow { contractPackage = \"a.2api\" }\n")
        runner(root, task).buildAndFail().output.assert().contains(
            "wow { contractPackage = \"a.2api\" } of project ':a-domain' is not a Java package name: its segment " +
                "\"2api\" is not a Java identifier.",
        )
        setPackage(root, "a.class")
        runner(root, task).buildAndFail().output.assert().contains(
            "wow { contractPackage = \"a.class\" } of project ':a-domain' is not a Java package name: its segment " +
                "\"class\" is a Java keyword.",
        )
    }

    @Test
    fun `the contract should keep its package when the context gains an aggregate of another package`(
        @TempDir root: File,
    ) {
        build(root, contractPackage = "a.api")
        val jarTask = ":a-api:${WowPlugin.CONTRACT_JAR_TASK}"
        runner(root, jarTask, ":a-domain:jar", "--configuration-cache").build()
        val jar = File(root, "a-api/build/libs/a-api-contract.jar")
        entries(jar).assert().contains("a/api/AContract.class", ApiRole.CONTRACT_RESOURCE)
        // The package file is the api module's build input alone: neither jar carries it.
        (entries(jar) + entries(File(root, "a-domain/build/libs/a-domain.jar"))).filter {
            it.endsWith(ApiRole.CONTRACT_PACKAGES_FILE)
        }.assert().isEmpty()

        // An aggregate whose classes are in a.extra: the classes' common package is a, the contract's stays a.api.
        gamma(root)
        runner(root, jarTask, "--configuration-cache").build()
        entries(jar).assert().contains("a/api/AContract.class", "a/api/AContract\$Gamma.class")
            .doesNotContain("a/AContract.class")

        // Nothing changed: the configuration cache is reused and every task is up to date.
        val again = runner(root, jarTask, "--configuration-cache").build()
        again.output.assert().contains("Configuration cache entry reused")
        again.task(jarTask)?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)

        // Another package reruns the contract's code, and nothing the package does not reach: the assembled contract
        // names no package and no view, so it stays as it was.
        setPackage(root, "a.contract")
        val moved = runner(root, jarTask, "--configuration-cache").build()
        listOf(
            ":a-domain:${WowPlugin.GENERATE_CONTRACT_TASK}",
            ":a-api:${WowPlugin.GENERATE_CONTRACT_SOURCES_TASK}",
            jarTask,
        ).forEach { moved.task(it)?.outcome.assert().describedAs(it).isEqualTo(TaskOutcome.SUCCESS) }
        listOf(
            ":a-api:compileKotlin",
            ":a-domain:compileKotlin",
            ":a-domain:${WowPlugin.GENERATE_FRAGMENT_TASK}",
            ":a-api:${WowPlugin.ASSEMBLE_CONTRACT_TASK}",
        ).forEach { moved.task(it)?.outcome.assert().describedAs(it).isEqualTo(TaskOutcome.UP_TO_DATE) }
        entries(jar).assert().contains("a/contract/AContract.class").doesNotContain("a/api/AContract.class")
    }

    @Test
    fun `an api module carrying several domain modules' contexts should put each in its own package`(
        @TempDir root: File,
    ) {
        build(root, contractPackage = "a.api", "c-domain")
        // The context c, its classes in a-api's package a.c, its domain module declaring c.contract.
        delta(root)
        val api = File(root, "a-api/build.gradle.kts")
        api.writeText(
            api.readText().replace(
                "wowContract(project(\":a-domain\"))",
                "wowContract(project(\":a-domain\"))\nwowContract(project(\":c-domain\"))"
            )
        )

        runner(root, ":a-api:${WowPlugin.CONTRACT_JAR_TASK}").build()
        entries(File(root, "a-api/build/libs/a-api-contract.jar")).assert()
            .contains("a/api/AContract.class", "c/contract/CContract.class")
    }

    /** The aggregate gamma of context a: its command and event in a-api's package a.extra, its root in a-domain. */
    private fun gamma(root: File) {
        ContextsFixture.write(
            root,
            "a-api/src/main/kotlin/a/extra/Gamma.kt",
            """
            package a.extra

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class OpenGamma(val name: String)
            data class GammaOpened(val name: String)
            """,
        )
        ContextsFixture.write(
            root,
            "a-domain/src/main/kotlin/a/domain/Gamma.kt",
            """
            package a.domain

            import me.ahoo.wow.api.annotation.AggregateRoot
            import a.extra.GammaOpened
            import a.extra.OpenGamma

            class GammaState(val id: String) {
                var name: String = ""
                    private set

                fun onSourcing(event: GammaOpened) { name = event.name }
            }

            @AggregateRoot
            class Gamma(private val state: GammaState) {
                fun onCommand(command: OpenGamma): GammaOpened = GammaOpened(command.name)
            }
            """,
        )
    }

    /** The context c: its command and event in a-api's package a.c, its domain module c-domain of c.contract. */
    private fun delta(root: File) {
        ContextsFixture.write(
            root,
            "a-api/src/main/kotlin/a/c/Delta.kt",
            """
            package a.c

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class OpenDelta(val name: String)
            data class DeltaOpened(val name: String)
            """,
        )
        ContextsFixture.module(
            root,
            "c-domain",
            WowPlugin.ID,
            "api(project(\":a-api\"))",
            "wow { contractPackage = \"c.contract\" }",
        )
        ContextsFixture.write(
            root,
            "c-domain/src/main/kotlin/c/domain/Delta.kt",
            """
            package c.domain

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import a.c.DeltaOpened
            import a.c.OpenDelta

            @BoundedContext(name = "c")
            object DeltaContext

            class DeltaState(val id: String) {
                var name: String = ""
                    private set

                fun onSourcing(event: DeltaOpened) { name = event.name }
            }

            @AggregateRoot
            class Delta(private val state: DeltaState) {
                fun onCommand(command: OpenDelta): DeltaOpened = DeltaOpened(command.name)
            }
            """,
        )
    }
}
