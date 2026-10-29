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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * The three plugins by role (decision D363 ①), built with Gradle TestKit: the quickstart's single module, which applies
 * `me.ahoo.wow.service` alone; a domain module whose api module does not declare it; the integration test suite; and
 * a domain, its api and its service under Gradle's isolated projects, since no plugin configures another project.
 */
class PluginRolesFunctionalTest {
    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    /** The build file of the module at [path] (the root for an empty one), applying the Wow plugin [id]. */
    private fun module(root: File, path: String, id: String, dependencies: String = "") {
        val compiler = if (id == WowPlugin.API_ID) "" else "ksp(files(${classpath("wow.fixture.processorClasspath")}))"
        write(
            root,
            if (path.isEmpty()) "build.gradle.kts" else "$path/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("$id")
            }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                $compiler
                wowTools(files(${classpath("wow.fixture.toolsClasspath")}))
                $dependencies
            }
            """,
        )
    }

    private fun settings(root: File, name: String, vararg modules: String) {
        write(
            root,
            "settings.gradle.kts",
            "rootProject.name = \"$name\"\n" + modules.joinToString("\n") { "include(\"$it\")" }
        )
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
    }

    /** The bank of the quickstart: its context, commands, events and account, in package [pkg] of [path]. */
    private fun bank(root: File, path: String, pkg: String = "bank") {
        val sources = if (path.isEmpty()) "src/main/kotlin/$pkg" else "$path/src/main/kotlin/$pkg"
        write(
            root,
            "$sources/Account.kt",
            """
            package $pkg

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate

            @BoundedContext(name = "bank")
            object Bank

            @CreateAggregate
            data class OpenAccount(val owner: String)
            data class AccountOpened(val owner: String)

            @AggregateRoot
            class Account(private val state: AccountState) {
                fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)
            }

            class AccountState(val id: String) {
                var owner: String = ""
                    private set

                fun onSourcing(event: AccountOpened) { owner = event.owner }
            }
            """,
        )
    }

    private fun runner(root: File, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    @Test
    fun `a single module should be its domain and its service with the service plugin alone`(@TempDir root: File) {
        settings(root, "bank")
        module(root, "", WowPlugin.SERVICE_ID)
        // One fact, one place: the module's contexts are hosted by bank-service, its contract and Manifest alike.
        File(root, "build.gradle.kts").appendText("\nwow { service = \"bank-service\" }\n")
        bank(root, "")
        val result = runner(
            root,
            // The gate needs a baseline: record it, then check against it.
            WowPlugin.UPDATE_BASELINE_TASK,
            "check",
            WowPlugin.MERGE_TASK,
            WowPlugin.OPENAPI_TASK,
            WowPlugin.GENERATE_CONTRACT_TASK,
            "jar",
            "--configuration-cache",
        ).build()
        result.task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        val manifest = ManifestJson.read(File(root, "build/wow/manifest.json"))
        manifest.service.assert().isEqualTo(ServiceInfo("bank-service", "unspecified"))
        manifest.contexts.single().aggregates.single().name.assert().isEqualTo("account")
        File(root, "build/wow/openapi.json").isFile.assert().isTrue()
        ZipFile(File(root, "build/libs/bank.jar")).use { jar ->
            jar.getEntry("${Manifest.FRAGMENT_DIRECTORY}/bank.json").assert().isNotNull()
        }
        // No api module of its own: the contract of its context, for an api module to carry once it splits one out.
        ManifestJson.read(File(root, "build/wow/contract.json")).let { contract ->
            contract.contexts.map { it.name }.assert().containsExactly("bank")
            contract.service.assert().isEqualTo(ServiceInfo("bank-service"))
        }
    }

    @Test
    fun `a domain module whose classes are in an api module that does not declare it should name the line`(
        @TempDir root: File,
    ) {
        settings(root, "bank", "bank-api", "bank-domain")
        // The api module applies the api plugin but declares no domain module.
        module(root, "bank-api", WowPlugin.API_ID)
        write(
            root,
            "bank-api/src/main/kotlin/bankapi/Messages.kt",
            """
            package bankapi

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class OpenAccount(val owner: String)
            data class AccountOpened(val owner: String)
            """,
        )
        module(root, "bank-domain", WowPlugin.ID, dependencies = """api(project(":bank-api"))""")
        write(
            root,
            "bank-domain/src/main/kotlin/bank/Account.kt",
            """
            package bank

            import bankapi.AccountOpened
            import bankapi.OpenAccount
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "bank")
            object Bank

            @AggregateRoot
            class Account(private val state: AccountState) {
                fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)
            }

            class AccountState(val id: String) {
                var owner: String = ""
                    private set

                fun onSourcing(event: AccountOpened) { owner = event.owner }
            }
            """,
        )
        runner(root, ":bank-domain:${WowPlugin.GENERATE_CONTRACT_TASK}").buildAndFail().output.assert().contains(
            "The contract of project ':bank-domain' reaches no api module",
            "context bank has its classes bankapi.AccountOpened, bankapi.OpenAccount in project ':bank-api'",
            "add dependencies { wowContract(project(\":bank-domain\")) }",
        )
    }

    @Test
    fun `the integration test suite should run apart from check`(@TempDir root: File) {
        settings(root, "bank")
        module(root, "", WowPlugin.ID)
        bank(root, "")
        write(
            root,
            "src/integrationTest/kotlin/bank/AccountIT.kt",
            """
            package bank

            import org.junit.jupiter.api.Test

            class AccountIT {
                @Test
                fun opens() {
                    check(AccountState("a").owner == "")
                }
            }
            """,
        )
        val check = runner(root, "check").build()
        check.task(":${WowPlugin.INTEGRATION_TEST}").assert().isNull()
        val integration = runner(root, WowPlugin.INTEGRATION_TEST).build()
        integration.task(":${WowPlugin.INTEGRATION_TEST}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        File(root, "build/test-results/integrationTest/TEST-bank.AccountIT.xml").readText().assert()
            .contains("tests=\"1\"", "failures=\"0\"")
    }

    @Test
    fun `a domain, its api and its service should build under isolated projects`(@TempDir root: File) {
        settings(root, "bank", "bank-api", "bank-domain", "bank-server")
        module(root, "bank-api", WowPlugin.API_ID, dependencies = """wowContract(project(":bank-domain"))""")
        write(
            root,
            "bank-api/src/main/kotlin/bankapi/Messages.kt",
            """
            package bankapi

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class OpenAccount(val owner: String)
            data class AccountOpened(val owner: String)
            """,
        )
        module(root, "bank-domain", WowPlugin.ID, dependencies = """api(project(":bank-api"))""")
        write(
            root,
            "bank-domain/src/main/kotlin/bank/Account.kt",
            """
            package bank

            import bankapi.AccountOpened
            import bankapi.OpenAccount
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "bank")
            object Bank

            @AggregateRoot
            class Account(private val state: AccountState) {
                fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)
            }

            class AccountState(val id: String) {
                var owner: String = ""
                    private set

                fun onSourcing(event: AccountOpened) { owner = event.owner }
            }
            """,
        )
        module(root, "bank-server", WowPlugin.SERVICE_ID, dependencies = """implementation(project(":bank-domain"))""")
        runner(
            root,
            ":bank-server:${WowPlugin.MERGE_TASK}",
            ":bank-api:jar",
            "-Dorg.gradle.unsafe.isolated-projects=true",
        ).build()
        ManifestJson.read(File(root, "bank-server/build/wow/manifest.json")).contexts.map { it.name }.assert()
            .containsExactly("bank")
        ZipFile(File(root, "bank-api/build/libs/bank-api.jar")).use { jar ->
            jar.getEntry("bankapi/BankContract.class").assert().isNotNull()
            jar.getEntry("META-INF/wow/contract.json").assert().isNotNull()
        }
    }
}
