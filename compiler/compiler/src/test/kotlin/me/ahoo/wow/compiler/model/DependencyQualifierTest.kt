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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/** Decision D119: `@Name` on a dependency parameter is its qualifier. */
@OptIn(ExperimentalCompilerApi::class)
class DependencyQualifierTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "bank",
        ModelSymbolProcessorProvider.MODULE_OPTION to "bank-domain",
    )

    private val wallet = SourceFile.kotlin(
        "Wallet.kt",
        """
        package sample.fx

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Name

        @CreateAggregate
        data class Convert(val amount: Int)
        data class Converted(val amount: Int)
        fun interface Rate { fun of(amount: Int): Int }

        @AggregateRoot
        class Wallet(private val state: WalletState) {
            fun onCommand(
                command: Convert,
                @Name("/fx/primary") primary: Rate,
                @Name("backup") backup: Rate,
                plain: Rate,
            ): Converted = Converted(primary.of(command.amount) * 100 + backup.of(command.amount) * 10 + plain.of(0))

            // The same qualifier, and no qualifier, are the dependencies the command function takes.
            fun afterCommand(command: Convert, @Name("backup") again: Rate, unqualified: Rate): List<Converted> =
                emptyList()
        }

        class WalletState(val id: String) {
            var amount = 0
            fun onSourcing(event: Converted) { amount = event.amount }
        }
        """.trimIndent(),
    )

    /** Loads the module's provider as a host would, with two qualified rates and an unqualified one. */
    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample.fx

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.AggregateModel
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Dependencies
        import me.ahoo.wow.kernel.memory.InMemoryEventLog
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.ModelProvider

        fun run(loader: ClassLoader): Int = runBlocking {
            val dependencies = Dependencies.of(mapOf("/fx/primary" to Rate { 1 }, "backup" to Rate { 2 }), Rate { 3 })
            @Suppress("UNCHECKED_CAST")
            val wallet = ModelProvider.load(loader).single().models(dependencies).single() as AggregateModel<WalletState>
            val outcome = Kernel(InMemoryEventLog()).execute(wallet, Command("c1", "r1", wallet.type.ref("t", "w-1"), Convert(5)))
            ((outcome as CommandOutcome.Committed).state as WalletState).amount
        }
        """.trimIndent(),
    )

    private fun generated(compilation: KotlinCompilation, name: String): File =
        compilation.workingDir.walkTopDown().first { it.isFile && it.name == name }

    @Test
    fun `a qualified parameter should be its own dependency, found by its qualifier`() {
        val (compilation, result) = compileModel("generate", options, wallet, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "WalletModel.kt").readText().assert().contains(
            "public fun walletModel(context: String, rateFxPrimary: sample.fx.Rate, rateBackup: sample.fx.Rate, " +
                "rate: sample.fx.Rate, configure:",
            ".afterCommand(command as sample.fx.Convert, rateBackup, rate)",
        )
        generated(compilation, "BankDomainModelProvider.kt").readText().assert().contains(
            "sample.fx.walletModel(context, " +
                "dependencies.get(sample.fx.Rate::class.javaObjectType, \"/fx/primary\"), " +
                "dependencies.get(sample.fx.Rate::class.javaObjectType, \"backup\"), " +
                "dependencies.get(sample.fx.Rate::class.javaObjectType)),",
        )

        val resources = generated(compilation, ModelProviderSource.MODEL_PROVIDER).parentFile.parentFile.parentFile
        val loader = URLClassLoader(arrayOf(resources.toURI().toURL()), result.classLoader)
        val run = result.classLoader.loadClass("sample.fx.ScenarioKt").getMethod("run", ClassLoader::class.java)
        run.invoke(null, loader).assert().isEqualTo(123)
    }

    @Test
    fun `a qualifier should name a dependency parameter only, and not be blank`() {
        val misplaced = SourceFile.kotlin(
            "Misplaced.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.decide.DecideScope

            data class Post(val note: String)
            data class Posted(val note: String)
            fun interface Clock { fun now(): Long }

            @AggregateRoot
            class Journal(val id: String) {
                fun onCommand(@Name("post") command: Post, @Name("scope") scope: DecideScope, @Name(" ") clock: Clock): Posted =
                    Posted(command.note)

                fun onSourcing(event: Posted) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, misplaced)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "onCommand's command parameter is annotated @Name: a qualifier names a dependency parameter",
            "onCommand's parameter scope is annotated @Name: the decide scope takes no qualifier",
            "onCommand's parameter clock has a blank @Name: a qualifier names the dependency",
        )
    }
}
