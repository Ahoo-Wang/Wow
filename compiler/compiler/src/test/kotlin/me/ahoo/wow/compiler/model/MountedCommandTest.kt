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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Decision D182: a `@MountedCommand` of the module is mounted under the aggregate it names, in the generated model and
 * in the Manifest fragment, which lists it as mounted with its routes and its `transformsTo`.
 */
@OptIn(ExperimentalCompilerApi::class)
class MountedCommandTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private val model = SourceFile.kotlin(
        "Model.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.MountedCommand
        import me.ahoo.wow.api.annotation.Name

        @CreateAggregate
        data class Register(val username: String)
        data class Registered(val username: String)

        @MountedCommand(aggregate = "customer", transformsTo = ["shop.customer.Register"])
        data class ImportCustomer(val username: String, val legacyId: String)

        @Name("ProviderCallback")
        @MountedCommand(aggregate = "customer")
        data class Callback(val user: String)

        @AggregateRoot
        class Customer(val id: String) {
            fun onCommand(command: Register): Registered = Registered(command.username)
            fun onSourcing(event: Registered) = Unit
        }
        """.trimIndent(),
    )

    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        package sample

        fun mounted(): String = customerModel("shop").mountedCommandTypes.joinToString { it.name + "=" + it.type.simpleName }
        """.trimIndent(),
    )

    @Test
    fun `a mounted command is mounted in the model and listed in the fragment`() {
        val (compilation, result) = compileModel("generate", options, model, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("mounted").invoke(null).assert()
            .isEqualTo("shop.customer.ImportCustomer=ImportCustomer, shop.customer.ProviderCallback=Callback")
        val file = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val aggregate = ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))
            .contexts.single().aggregates.single()
        aggregate.commands.map { it.name }.assert().containsExactly("ImportCustomer", "ProviderCallback", "Register")
        aggregate.commands.first().assert().isEqualTo(
            CommandManifest(
                name = "ImportCustomer",
                schema = "#/\$defs/sample.ImportCustomer",
                creation = Creation.NONE,
                routes = listOf(
                    RouteManifest("POST", "/shop/customer/{id}/import-customer"),
                    RouteManifest("POST", "/shop/customer/import-customer"),
                ),
                mounted = true,
                transformsTo = listOf("shop.customer.Register"),
            ),
        )
        aggregate.commands[1].transformsTo.assert().isNull()
        aggregate.commands[2].mounted.assert().isFalse()
    }

    @Test
    fun `a mounted command nowhere, taken by a function or misnamed should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.MountedCommand
            import me.ahoo.wow.api.annotation.Name

            @CreateAggregate
            data class Open(val id: String)
            data class Opened(val id: String)

            @MountedCommand(aggregate = "account", transformsTo = ["Open"])
            data class Taken(val id: String)

            @Name("Open")
            @MountedCommand(aggregate = "account")
            data class SameName(val id: String)

            @MountedCommand(aggregate = "ledger")
            data class Nowhere(val id: String)

            @MountedCommand(aggregate = " ")
            data class Blank(val id: String)

            @AggregateRoot
            class Account(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onCommand(command: Taken): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("mounted command Taken is taken by a command function: a mounted command has no @OnCommand")
            .contains(
                "mounted command Taken transforms to \"Open\": @MountedCommand(transformsTo) names each command as " +
                    "context.aggregate.Command",
            )
            .contains("mounted command Open has the name of a command of the aggregate")
            .contains("mounted command sample.Nowhere is mounted under ledger, which is not an aggregate of this module")
            .contains("mounted command Blank names no aggregate")
    }
}
